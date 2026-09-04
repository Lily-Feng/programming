import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Standard library and I/O.
 *
 * <p>Covers text and regular expressions, {@code java.time}, {@code Optional},
 * {@code Path}/{@code Files} with explicit charsets, resource ownership, and the
 * request side of {@code HttpClient}. Nothing here touches the network or leaves
 * files behind: the file section works inside a temporary directory it deletes.
 *
 * <p>Requires Java 11 or later. Run with: {@code java StandardLibraryAndIo.java}
 */
public final class StandardLibraryAndIo {

    public static void main(String[] args) throws IOException {
        textAndRegex();
        dateTime();
        optionalAndEnums();
        filesAndCharsets();
        httpRequests();
    }

    // --- text, StringBuilder, formatting, regex --------------------------------

    private static void textAndRegex() {
        check("strings are immutable; concatenation makes a new object",
                "ab".concat("c").equals("abc"));

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            builder.append(i).append(',');
        }
        builder.setLength(builder.length() - 1); // drop the trailing separator
        check("StringBuilder mutates one buffer in a loop", builder.toString().equals("0,1,2"));

        check("format is locale-sensitive; pass the locale you mean",
                String.format(java.util.Locale.US, "%.2f", 1234.5).equals("1234.50"));

        // Compile a pattern once when it is reused; Pattern is immutable and thread-safe.
        Pattern logLine = Pattern.compile("^(?<level>WARN|ERROR)\\s+(?<message>.+)$");
        Matcher matcher = logLine.matcher("ERROR disk full");
        check("named groups document the intent", matcher.matches()
                && matcher.group("level").equals("ERROR")
                && matcher.group("message").equals("disk full"));

        check("split drops trailing empties unless a negative limit is given",
                "a,b,,".split(",").length == 2 && "a,b,,".split(",", -1).length == 4);

        String multiline = "one\ntwo\n";
        check("lines() splits without a trailing empty element", multiline.lines().count() == 2);
        String emSpace = "\u2003";     // Unicode whitespace, but above U+0020
        check("strip removes Unicode whitespace that trim leaves behind",
                (emSpace + "x" + emSpace).strip().equals("x")
                        && !(emSpace + "x" + emSpace).trim().equals("x"));
        check("neither removes a non-breaking space: it is not whitespace",
                "\u00A0x".strip().equals("\u00A0x"));
    }

    // --- java.time --------------------------------------------------------------

    private static void dateTime() {
        LocalDate release = LocalDate.of(2023, 9, 19);       // no time zone, no instant
        check("date arithmetic is calendar-aware",
                release.plusMonths(5).equals(LocalDate.of(2024, 2, 19)));
        check("February clamps rather than overflowing",
                LocalDate.of(2024, 1, 31).plusMonths(1).equals(LocalDate.of(2024, 2, 29)));

        // A wall-clock time is not an instant until a zone is applied.
        LocalDateTime wallClock = LocalDateTime.of(2024, 3, 10, 1, 30);
        ZonedDateTime newYork = wallClock.atZone(ZoneId.of("America/New_York"));
        ZonedDateTime utc = newYork.withZoneSameInstant(ZoneId.of("UTC"));
        check("the same instant has different wall clocks per zone",
                newYork.toInstant().equals(utc.toInstant())
                        && newYork.getHour() != utc.getHour());

        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        Duration uptime = Duration.between(start, start.plus(90, ChronoUnit.MINUTES));
        check("Duration measures elapsed time on a timeline", uptime.toMinutes() == 90);

        check("formatters are explicit about the pattern and locale",
                release.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")).equals("2023-09-19"));
    }

    // --- Optional and enums ------------------------------------------------------

    enum Environment {
        DEV(false), PROD(true);

        private final boolean requiresApproval;

        Environment(boolean requiresApproval) { this.requiresApproval = requiresApproval; }

        boolean requiresApproval() { return requiresApproval; }
    }

    private static Optional<String> lookup(List<String> values, String prefix) {
        return values.stream().filter(value -> value.startsWith(prefix)).findFirst();
    }

    private static void optionalAndEnums() {
        List<String> values = List.of("db.url=jdbc:h2:mem", "db.user=app");

        check("Optional models a possibly absent result, not an error",
                lookup(values, "db.user").map(String::length).orElse(0) == 11);
        check("orElseGet defers the fallback computation",
                lookup(values, "missing").orElseGet(() -> "default").equals("default"));
        check("enums carry behavior, not just names", Environment.PROD.requiresApproval());
        check("valueOf is exact and throws on unknown names", rejectsUnknownEnum());
    }

    private static boolean rejectsUnknownEnum() {
        try {
            Environment.valueOf("STAGING");
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    // --- Path, Files, charsets, resource ownership --------------------------------

    private static void filesAndCharsets() throws IOException {
        Path workspace = Files.createTempDirectory("stdlib-io-");
        try {
            Path report = workspace.resolve("report.csv");
            Files.writeString(report, "id,name\n1,café\n", StandardCharsets.UTF_8);

            check("the charset round-trips only when both sides agree",
                    Files.readString(report, StandardCharsets.UTF_8).contains("café"));
            check("reading the same bytes as ISO-8859-1 mangles them",
                    !Files.readString(report, StandardCharsets.ISO_8859_1).contains("café"));

            // try-with-resources closes the stream even if the body throws. Files.lines
            // holds an open file handle, so it must be closed; Files.readAllLines does not.
            List<String> rows = new ArrayList<>();
            try (Stream<String> lines = Files.lines(report, StandardCharsets.UTF_8)) {
                lines.skip(1).forEach(rows::add);
            }
            check("streams over files are resources", rows.size() == 1 && rows.get(0).equals("1,café"));

            check("resolve joins paths without string concatenation",
                    workspace.resolve("a").resolve("b").endsWith(Path.of("a", "b")));
            check("normalize removes redundant navigation",
                    Path.of("/tmp/a/../b").normalize().equals(Path.of("/tmp/b")));
        } finally {
            deleteRecursively(workspace); // owned here, so cleaned up here
        }
        check("the temporary workspace is gone", !Files.exists(workspace));
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException error) {
                    throw new UncheckedIOException(error); // never swallow cleanup failures
                }
            });
        }
    }

    // --- HttpClient: build a request without sending it -----------------------------

    private static void httpRequests() {
        URI uri = URI.create("https://example.invalid/api/items?page=2");
        check("URI parses structure instead of guessing at substrings",
                uri.getHost().equals("example.invalid")
                        && uri.getPath().equals("/api/items")
                        && uri.getQuery().equals("page=2"));

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(2))             // always bound a network call
                .header("Accept", "application/json")
                .GET()
                .build();

        check("a request carries its own timeout",
                request.timeout().orElseThrow().equals(Duration.ofSeconds(2)));
        check("headers are stored as a multimap",
                request.headers().firstValue("Accept").orElseThrow().equals("application/json"));

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))       // separate from the request timeout
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        check("the client owns connection-level policy",
                client.connectTimeout().orElseThrow().toSeconds() == 2);
        // No send() call here: an example should not depend on the network to pass.
    }

    // --- tiny assertion helper (assertions are off by default) ------------------------

    private static void check(String claim, boolean holds) {
        if (!holds) throw new AssertionError("failed: " + claim);
        System.out.println("ok  " + claim);
    }
}
