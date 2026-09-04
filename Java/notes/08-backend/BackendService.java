import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Backend Java, without a framework or a database driver.
 *
 * <p>The JDK ships an HTTP server ({@code jdk.httpserver}) and an HTTP client, so a whole
 * request path can be exercised in one file: validation, error responses, pagination,
 * idempotent writes, optimistic concurrency, and a transaction boundary around a unit of
 * work. Spring Boot and Jakarta EE add declarative wiring on top of exactly these
 * concerns — they do not replace them.
 *
 * <p>The store is in-memory, but it is written the way a repository over JDBC should be:
 * the transaction begins and ends in the service layer, the repository never commits, and
 * a failure mid-way rolls the whole unit of work back. {@link #persistenceNotes()} maps
 * each piece onto its JDBC/JPA equivalent.
 *
 * <p>Binds to the loopback interface on an ephemeral port and shuts down before exiting.
 *
 * <p>Requires Java 17 or later. Run with: {@code java BackendService.java}
 */
public final class BackendService {

    public static void main(String[] args) throws Exception {
        ItemStore store = new ItemStore();
        HttpServer server = start(new ItemApi(new ItemService(store)));
        URI base = URI.create("http://" + server.getAddress().getHostString()
                + ":" + server.getAddress().getPort());
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

        try {
            validationRejectsBadInput(client, base);
            createsAndReadsBack(client, base);
            writesAreIdempotent(client, base);
            paginationIsBoundedAndStable(client, base);
            unknownRoutesAndMethodsAreExplicit(client, base);
            transactionsRollBackAsAUnit(store);
        } finally {
            server.stop(0);
            // The request pool holds non-daemon threads: without this the JVM never exits.
            ((java.util.concurrent.ExecutorService) server.getExecutor()).shutdown();
        }
        persistenceNotes();
    }

    // ======================= domain and persistence =======================

    record Item(long id, String name, int quantity, long version) { }

    /** Thrown for anything the caller can fix; the API maps it to 400. */
    static final class ValidationException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ValidationException(String message) { super(message); }
    }

    /** Thrown when the row changed under us; the API maps it to 409. */
    static final class ConflictException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ConflictException(String message) { super(message); }
    }

    /**
     * Stands in for a JDBC repository. Reads and writes go through {@link #inTransaction},
     * which is the only place that commits — the repository methods never do.
     */
    static final class ItemStore {
        private final Map<Long, Item> rows = new LinkedHashMap<>();
        private final AtomicLong sequence = new AtomicLong();

        synchronized <T> T inTransaction(Function<ItemStore, T> unitOfWork) {
            Map<Long, Item> snapshot = new LinkedHashMap<>(rows); // the "before" image
            long sequenceMark = sequence.get();
            try {
                T result = unitOfWork.apply(this);
                return result;                                    // commit: keep the mutations
            } catch (RuntimeException failure) {
                rows.clear();                                     // rollback: restore the snapshot
                rows.putAll(snapshot);
                sequence.set(sequenceMark);
                throw failure;                                    // never swallow: the caller decides
            }
        }

        Item insert(String name, int quantity) {
            long id = sequence.incrementAndGet();
            Item item = new Item(id, name, quantity, 1);
            rows.put(id, item);
            return item;
        }

        /** Optimistic locking: the update only applies to the version the caller read. */
        Item update(long id, int quantity, long expectedVersion) {
            Item current = find(id).orElseThrow(() -> new ValidationException("no item " + id));
            if (current.version() != expectedVersion) {
                throw new ConflictException("item " + id + " changed since version " + expectedVersion);
            }
            Item updated = new Item(id, current.name(), quantity, current.version() + 1);
            rows.put(id, updated);
            return updated;
        }

        Optional<Item> find(long id) { return Optional.ofNullable(rows.get(id)); }

        /** Keyset pagination: stable under concurrent inserts, unlike OFFSET. */
        List<Item> page(long afterId, int limit) {
            List<Item> page = new ArrayList<>();
            for (Item item : rows.values()) {
                if (item.id() > afterId && page.size() < limit) page.add(item);
            }
            return page;
        }

        int size() { return rows.size(); }
    }

    // ======================= service layer =======================

    static final class ItemService {
        private static final int MAX_PAGE = 50;

        private final ItemStore store;
        /** Idempotency keys, as a real service would keep them in Redis or a unique index. */
        private final Map<String, Item> processedKeys = new ConcurrentHashMap<>();

        ItemService(ItemStore store) { this.store = store; }

        Item create(String idempotencyKey, String name, Integer quantity) {
            validate(name, quantity);
            Item replayed = idempotencyKey == null ? null : processedKeys.get(idempotencyKey);
            if (replayed != null) return replayed;   // a retried POST must not create a second row

            Item created = store.inTransaction(tx -> tx.insert(name.strip(), quantity));
            if (idempotencyKey != null) processedKeys.put(idempotencyKey, created);
            return created;
        }

        List<Item> list(long afterId, int limit) {
            if (limit < 1) throw new ValidationException("limit must be >= 1");
            return store.page(afterId, Math.min(limit, MAX_PAGE)); // never let a client ask for everything
        }

        private static void validate(String name, Integer quantity) {
            if (name == null || name.isBlank()) throw new ValidationException("name is required");
            if (name.length() > 64) throw new ValidationException("name must be <= 64 characters");
            if (quantity == null) throw new ValidationException("quantity is required");
            if (quantity < 0) throw new ValidationException("quantity must be >= 0");
        }
    }

    // ======================= HTTP layer =======================

    /** Maps HTTP semantics onto the service: status codes, content type, error shape. */
    record ItemApi(ItemService service) {

        void handle(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                if (!path.equals("/items")) {
                    respond(exchange, 404, error("not_found", "no route for " + path));
                    return;
                }
                switch (exchange.getRequestMethod()) {
                    case "GET" -> respond(exchange, 200, listItems(exchange));
                    case "POST" -> createItem(exchange);
                    default -> {
                        exchange.getResponseHeaders().add("Allow", "GET, POST");
                        respond(exchange, 405, error("method_not_allowed", exchange.getRequestMethod()));
                    }
                }
            } catch (ValidationException invalid) {
                respond(exchange, 400, error("invalid_request", invalid.getMessage()));
            } catch (ConflictException conflict) {
                respond(exchange, 409, error("conflict", conflict.getMessage()));
            } catch (RuntimeException unexpected) {
                // Never leak internals to the client; the detail belongs in the log.
                System.err.println("unhandled: " + unexpected);
                respond(exchange, 500, error("internal_error", "request failed"));
            } finally {
                exchange.close(); // the response body must be closed or the client hangs
            }
        }

        private String listItems(HttpExchange exchange) {
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            long afterId = parseLong(query.getOrDefault("after_id", "0"), "after_id");
            int limit = (int) parseLong(query.getOrDefault("limit", "20"), "limit");

            List<Item> page = service.list(afterId, limit);
            StringBuilder json = new StringBuilder("{\"items\":[");
            for (int i = 0; i < page.size(); i++) {
                if (i > 0) json.append(',');
                json.append(toJson(page.get(i)));
            }
            json.append("],\"next_after_id\":")
                    .append(page.isEmpty() ? "null" : page.get(page.size() - 1).id())
                    .append('}');
            return json.toString();
        }

        private void createItem(HttpExchange exchange) throws IOException {
            Map<String, String> body = parseFlatJson(readBody(exchange));
            String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
            Integer quantity = body.containsKey("quantity")
                    ? (int) parseLong(body.get("quantity"), "quantity")
                    : null;

            Item created = service.create(key, body.get("name"), quantity);
            exchange.getResponseHeaders().add("Location", "/items/" + created.id());
            respond(exchange, 201, toJson(created)); // 201 + Location, not 200 + a body only
        }

        private static long parseLong(String raw, String field) {
            try {
                return Long.parseLong(raw.strip());
            } catch (NumberFormatException notANumber) {
                throw new ValidationException(field + " must be an integer");
            }
        }

        private static String toJson(Item item) {
            return "{\"id\":%d,\"name\":\"%s\",\"quantity\":%d,\"version\":%d}"
                    .formatted(item.id(), item.name().replace("\"", "\\\""),
                            item.quantity(), item.version());
        }

        private static String error(String code, String message) {
            return "{\"error\":\"%s\",\"message\":\"%s\"}".formatted(code, message);
        }

        private static void respond(HttpExchange exchange, int status, String json) {
            try {
                byte[] payload = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(status, payload.length);
                exchange.getResponseBody().write(payload);
            } catch (IOException broken) {
                System.err.println("could not write response: " + broken);
            }
        }
    }

    private static HttpServer start(ItemApi api) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", api::handle);
        server.setExecutor(Executors.newFixedThreadPool(4)); // bounded, like any real request pool
        server.start();
        return server;
    }

    // ======================= exercising the API =======================

    private static void validationRejectsBadInput(HttpClient client, URI base) throws Exception {
        HttpResponse<String> blankName = post(client, base, null, "{\"name\":\"  \",\"quantity\":1}");
        check("a blank name is a 400, not a 500", blankName.statusCode() == 400);
        check("the error body names the problem", blankName.body().contains("name is required"));

        HttpResponse<String> negative = post(client, base, null, "{\"name\":\"bolt\",\"quantity\":-1}");
        check("a negative quantity is rejected", negative.statusCode() == 400);

        HttpResponse<String> missing = post(client, base, null, "{\"name\":\"bolt\"}");
        check("a missing field is rejected, not defaulted", missing.statusCode() == 400);
    }

    private static void createsAndReadsBack(HttpClient client, URI base) throws Exception {
        HttpResponse<String> created = post(client, base, null, "{\"name\":\"widget\",\"quantity\":3}");
        check("a successful create returns 201", created.statusCode() == 201);
        check("201 carries a Location header",
                created.headers().firstValue("Location").orElse("").startsWith("/items/"));
        check("the response is JSON",
                created.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        check("the created row is echoed back", created.body().contains("\"name\":\"widget\""));

        HttpResponse<String> listed = get(client, base, "/items?limit=10");
        check("the row is readable afterwards", listed.body().contains("\"name\":\"widget\""));
    }

    private static void writesAreIdempotent(HttpClient client, URI base) throws Exception {
        String key = "order-42";
        String body = "{\"name\":\"gasket\",\"quantity\":2}";

        HttpResponse<String> first = post(client, base, key, body);
        HttpResponse<String> retry = post(client, base, key, body); // the client timed out and retried

        check("both responses succeed", first.statusCode() == 201 && retry.statusCode() == 201);
        check("the retry returns the same row instead of creating a second one",
                first.body().equals(retry.body()));

        HttpResponse<String> listed = get(client, base, "/items?limit=50");
        check("only one gasket exists", countOccurrences(listed.body(), "gasket") == 1);
    }

    private static void paginationIsBoundedAndStable(HttpClient client, URI base) throws Exception {
        for (int i = 0; i < 5; i++) {
            post(client, base, null, "{\"name\":\"page-item-%d\",\"quantity\":1}".formatted(i));
        }

        HttpResponse<String> firstPage = get(client, base, "/items?limit=2");
        check("the page honours the limit", countOccurrences(firstPage.body(), "\"id\":") == 2);

        long cursor = Long.parseLong(firstPage.body()
                .replaceAll(".*\"next_after_id\":(\\d+)}.*", "$1"));
        HttpResponse<String> secondPage = get(client, base, "/items?limit=2&after_id=" + cursor);
        check("the cursor advances without repeating rows",
                !secondPage.body().contains("\"id\":" + cursor + ","));

        HttpResponse<String> huge = get(client, base, "/items?limit=100000");
        check("an oversized limit is capped, not honoured",
                countOccurrences(huge.body(), "\"id\":") <= 50);

        HttpResponse<String> bad = get(client, base, "/items?limit=abc");
        check("an unparseable parameter is a 400", bad.statusCode() == 400);
    }

    private static void unknownRoutesAndMethodsAreExplicit(HttpClient client, URI base) throws Exception {
        HttpResponse<String> missing = get(client, base, "/nope");
        check("an unknown route is a 404 with a body", missing.statusCode() == 404
                && missing.body().contains("not_found"));

        HttpResponse<String> deleted = client.send(
                HttpRequest.newBuilder(base.resolve("/items")).DELETE()
                        .timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.ofString());
        check("an unsupported method is a 405", deleted.statusCode() == 405);
        check("405 advertises what is allowed",
                deleted.headers().firstValue("Allow").orElse("").contains("GET"));
    }

    private static void transactionsRollBackAsAUnit(ItemStore store) {
        int before = store.size();

        try {
            store.inTransaction(tx -> {
                tx.insert("first-half", 1);
                tx.insert("second-half", 1);
                throw new IllegalStateException("downstream call failed after the writes");
            });
            check("the failing unit of work propagated", false);
        } catch (IllegalStateException expected) {
            check("the failing unit of work propagated", true);
        }
        check("neither insert survived the rollback", store.size() == before);

        Item committed = store.inTransaction(tx -> tx.insert("committed", 1));
        check("a successful unit of work commits", store.find(committed.id()).isPresent());

        Item updated = store.inTransaction(tx -> tx.update(committed.id(), 9, committed.version()));
        check("an update on the expected version applies", updated.quantity() == 9);

        try {
            store.inTransaction(tx -> tx.update(committed.id(), 10, committed.version())); // stale
            check("a stale version is a conflict, not a silent overwrite", false);
        } catch (ConflictException expected) {
            check("a stale version is a conflict, not a silent overwrite", true);
        }
        check("the conflicting write did not apply",
                store.find(committed.id()).orElseThrow().quantity() == 9);
    }

    // ======================= how this maps onto JDBC, JPA, and a framework =======================

    private static void persistenceNotes() {
        System.out.println("""

                note  the same code over a real database
                        inTransaction        connection.setAutoCommit(false) … commit() / rollback()
                                             or @Transactional, whose boundary is the service method
                        insert/update        PreparedStatement with bind parameters — never string
                                             concatenation, which is how SQL injection gets in
                        version column       optimistic locking: UPDATE … WHERE id = ? AND version = ?
                                             and treat 0 updated rows as the 409 above
                        keyset pagination    WHERE id > ? ORDER BY id LIMIT ? — stable and index-friendly,
                                             unlike OFFSET, which drifts and degrades on deep pages
                        idempotency key      a unique index on the key column; a duplicate insert
                                             becomes a constraint violation you translate to a replay

                note  what a connection pool actually bounds
                        pool size is a concurrency limit on the database, not a throughput dial;
                        size it near the database's core count, and set a connection timeout so an
                        exhausted pool fails fast instead of queueing every request thread

                note  JPA/Hibernate specifics worth knowing before using it
                        the persistence context is a first-level cache and an identity map:
                          the same row read twice in one transaction is the same object
                        lazy associations need the session open, or you get LazyInitializationException
                        N+1 selects: one query for the parents, then one per child collection —
                          fix with a join fetch or an entity graph, and assert the query count in a test
                        a dirty entity is flushed at commit even without an explicit save() call

                note  where a framework takes over
                        Spring Boot / Jakarta EE supply routing, validation (jakarta.validation),
                        JSON binding (Jackson), transactions, connection pooling, and health endpoints.
                        The failure modes above stay exactly the same — only the wiring is declarative.""");
    }

    // ======================= small helpers =======================

    private static HttpResponse<String> post(HttpClient client, URI base, String idempotencyKey, String body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve("/items"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(HttpClient client, URI base, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream body = exchange.getRequestBody()) {
            return new String(body.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Enough JSON for one flat object; a real service uses Jackson and a typed record. */
    private static Map<String, String> parseFlatJson(String json) {
        Map<String, String> fields = new LinkedHashMap<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"(\\w+)\"\\s*:\\s*(?:\"([^\"]*)\"|(-?\\d+))")
                .matcher(json);
        while (matcher.find()) {
            fields.put(matcher.group(1), matcher.group(2) != null ? matcher.group(2) : matcher.group(3));
        }
        return fields;
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> parameters = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) return parameters;
        for (String pair : rawQuery.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                parameters.put(java.net.URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
            }
        }
        return parameters;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int index = haystack.indexOf(needle); index >= 0; index = haystack.indexOf(needle, index + 1)) {
            count++;
        }
        return count;
    }

    private static void check(String claim, boolean holds) {
        if (!holds) throw new AssertionError("failed: " + claim);
        System.out.println("ok  " + claim);
    }
}
