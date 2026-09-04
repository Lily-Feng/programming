import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Production engineering: the parts of a service that only matter when it is under load,
 * partially broken, or being restarted.
 *
 * <p>Everything here is deterministic — the clock is injected, so timeouts, backoff, the
 * circuit breaker, and the rate limiter are tested by advancing time rather than sleeping.
 * That is the same reason production code should take a {@code Clock}: behaviour you cannot
 * advance is behaviour you cannot test.
 *
 * <p>Requires Java 17 or later. Run with: {@code java ProductionEngineering.java}
 */
public final class ProductionEngineering {

    public static void main(String[] args) throws Exception {
        configurationAndSecrets();
        structuredLoggingWithCorrelation();
        metrics();
        timeoutsRetriesAndBackoff();
        circuitBreakerOpensAndRecovers();
        bulkheadIsolatesADependency();
        rateLimiterShedsLoad();
        healthChecksSeparateLivenessFromReadiness();
        gracefulShutdown();
        incidentChecklist();
    }

    // --- a clock you can advance ------------------------------------------------

    /** Test clock: production passes a real one; nothing below reads the wall clock directly. */
    static final class TestClock {
        private final AtomicLong millis = new AtomicLong(0);

        long now() { return millis.get(); }
        void advance(Duration amount) { millis.addAndGet(amount.toMillis()); }
    }

    // --- configuration and secrets -------------------------------------------------

    /**
     * Configuration is read once at startup and validated there, so a typo fails the
     * deploy instead of the first request that needs it.
     */
    record Config(String serviceName, Duration requestTimeout, int poolSize, String databasePassword) {

        static Config from(Map<String, String> environment) {
            List<String> problems = new ArrayList<>();
            String name = environment.getOrDefault("SERVICE_NAME", "items-api");
            Duration timeout = Duration.ofMillis(readInt(environment, "REQUEST_TIMEOUT_MS", 2_000, problems));
            int pool = readInt(environment, "POOL_SIZE", 10, problems);
            String password = environment.get("DB_PASSWORD"); // never a default, never in the repo

            if (pool < 1) problems.add("POOL_SIZE must be >= 1");
            if (password == null || password.isBlank()) problems.add("DB_PASSWORD is required");
            if (!problems.isEmpty()) throw new IllegalStateException("bad configuration: " + problems);

            return new Config(name, timeout, pool, password);
        }

        private static int readInt(Map<String, String> environment, String key, int fallback,
                                   List<String> problems) {
            String raw = environment.get(key);
            if (raw == null) return fallback;
            try {
                return Integer.parseInt(raw.strip());
            } catch (NumberFormatException notANumber) {
                problems.add(key + " must be an integer, was '" + raw + "'");
                return fallback;
            }
        }

        /** Secrets must not reach logs, crash reports, or metrics labels. */
        @Override public String toString() {
            return "Config[serviceName=%s, requestTimeout=%s, poolSize=%d, databasePassword=***]"
                    .formatted(serviceName, requestTimeout, poolSize);
        }
    }

    private static void configurationAndSecrets() {
        Config config = Config.from(Map.of("POOL_SIZE", "20", "DB_PASSWORD", "hunter2"));
        check("defaults apply where the environment is silent",
                config.requestTimeout().equals(Duration.ofSeconds(2)));
        check("the environment overrides the default", config.poolSize() == 20);
        check("toString never prints the secret",
                !config.toString().contains("hunter2") && config.toString().contains("***"));

        check("a missing required secret fails at startup",
                failsWith(() -> Config.from(Map.of("POOL_SIZE", "20")), "DB_PASSWORD is required"));
        check("an unparseable value fails at startup, not on first use",
                failsWith(() -> Config.from(Map.of("POOL_SIZE", "many", "DB_PASSWORD", "x")),
                        "POOL_SIZE must be an integer"));
    }

    // --- structured logging with a correlation id ---------------------------------------

    /**
     * One event per line, machine-parseable, with the request id attached — so a single
     * failing request can be followed across services instead of grepped for by hand.
     */
    static final class StructuredLogger {
        static final ThreadLocal<String> CORRELATION_ID = new ThreadLocal<>();
        final List<String> emitted = new ArrayList<>(); // stands in for stdout

        void log(String level, String event, Map<String, Object> fields) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("ts", Instant.EPOCH.toString());  // a real logger uses the injected clock
            line.put("level", level);
            line.put("event", event);
            line.put("correlation_id", Optional.ofNullable(CORRELATION_ID.get()).orElse("-"));
            line.putAll(fields);
            emitted.add(line.toString());
        }
    }

    private static void structuredLoggingWithCorrelation() {
        StructuredLogger log = new StructuredLogger();
        String incoming = UUID.randomUUID().toString(); // from the X-Request-Id header, or generated

        StructuredLogger.CORRELATION_ID.set(incoming);
        try {
            log.log("INFO", "request.started", Map.of("route", "/items", "method", "GET"));
            log.log("WARN", "dependency.slow", Map.of("dependency", "inventory", "latency_ms", 950));
        } finally {
            StructuredLogger.CORRELATION_ID.remove(); // pooled threads outlive the request
        }
        log.log("INFO", "request.finished", Map.of("status", 200));

        check("every in-request line carries the same correlation id",
                log.emitted.get(0).contains(incoming) && log.emitted.get(1).contains(incoming));
        check("the id does not leak to the next task on the same thread",
                log.emitted.get(2).contains("correlation_id=-"));
        check("events are named, not free text", log.emitted.get(0).contains("event=request.started"));
    }

    // --- metrics: counters, and latency as a distribution ---------------------------------

    /** Averages hide outages. Keep the distribution and read the tail. */
    static final class Latencies {
        private final List<Long> samples = new ArrayList<>();

        void record(long millis) { samples.add(millis); }

        double mean() { return samples.stream().mapToLong(Long::longValue).average().orElse(0); }

        long percentile(int percentile) {
            List<Long> sorted = new ArrayList<>(samples);
            sorted.sort(Long::compare);
            int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
            return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
        }
    }

    private static void metrics() {
        AtomicLong requests = new AtomicLong();
        AtomicLong failures = new AtomicLong();
        Latencies latencies = new Latencies();

        for (int i = 1; i <= 100; i++) {
            requests.incrementAndGet();
            latencies.record(i <= 99 ? 10 : 5_000); // 99 fast, one very slow
            if (i == 100) failures.incrementAndGet();
        }

        check("the mean hides the outage", latencies.mean() < 100);
        check("the tail does not", latencies.percentile(100) == 5_000);
        check("an error ratio is the alertable signal, not a raw count",
                failures.get() / (double) requests.get() == 0.01);
        // Alert on symptoms users feel — error ratio and tail latency — not on CPU.
    }

    // --- timeouts, retries, backoff, jitter -------------------------------------------------

    /** Retries only help for transient failures, and only with a budget and jitter. */
    static final class Retrier {
        private final int maxAttempts;
        private final long baseDelayMillis;
        private final long maxDelayMillis;
        private final TestClock clock;
        final List<Long> waits = new ArrayList<>();

        Retrier(int maxAttempts, long baseDelayMillis, long maxDelayMillis, TestClock clock) {
            this.maxAttempts = maxAttempts;
            this.baseDelayMillis = baseDelayMillis;
            this.maxDelayMillis = maxDelayMillis;
            this.clock = clock;
        }

        <T> T call(Supplier<T> work) {
            RuntimeException last = null;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    return work.get();
                } catch (RuntimeException transientFailure) {
                    last = transientFailure;
                    if (attempt == maxAttempts) break;
                    long ceiling = Math.min(baseDelayMillis << (attempt - 1), maxDelayMillis);
                    long wait = ThreadLocalRandom.current().nextLong(ceiling + 1); // full jitter
                    waits.add(wait);
                    clock.advance(Duration.ofMillis(wait)); // production sleeps here
                }
            }
            throw new IllegalStateException("exhausted " + maxAttempts + " attempts", last);
        }
    }

    private static void timeoutsRetriesAndBackoff() {
        TestClock clock = new TestClock();
        Retrier retrier = new Retrier(5, 100, 800, clock);
        AtomicInteger calls = new AtomicInteger();

        String result = retrier.call(() -> {
            if (calls.incrementAndGet() < 4) throw new IllegalStateException("connection reset");
            return "ok";
        });

        check("a transient failure is retried", result.equals("ok") && calls.get() == 4);
        check("each wait stays under its exponential ceiling",
                retrier.waits.get(0) <= 100 && retrier.waits.get(1) <= 200 && retrier.waits.get(2) <= 400);
        check("the clock advanced by exactly the waits taken",
                clock.now() == retrier.waits.stream().mapToLong(Long::longValue).sum());

        TestClock exhausted = new TestClock();
        check("a permanent failure stops at the budget",
                failsWith(() -> new Retrier(3, 10, 100, exhausted)
                        .call(() -> { throw new IllegalStateException("down"); }), "exhausted 3 attempts"));
        // Retrying without a budget turns one slow dependency into a self-inflicted DDoS.
    }

    // --- circuit breaker ----------------------------------------------------------------------

    /** Stops hammering a dependency that is already failing, and probes before trusting it again. */
    static final class CircuitBreaker {
        enum State { CLOSED, OPEN, HALF_OPEN }

        private final int failureThreshold;
        private final Duration openFor;
        private final TestClock clock;
        private State state = State.CLOSED;
        private int consecutiveFailures;
        private long openedAt;

        CircuitBreaker(int failureThreshold, Duration openFor, TestClock clock) {
            this.failureThreshold = failureThreshold;
            this.openFor = openFor;
            this.clock = clock;
        }

        State state() { return state; }

        <T> T call(Supplier<T> work) {
            if (state == State.OPEN) {
                if (clock.now() - openedAt < openFor.toMillis()) {
                    throw new IllegalStateException("circuit open: failing fast");
                }
                state = State.HALF_OPEN; // let exactly one probe through
            }
            try {
                T value = work.get();
                state = State.CLOSED;
                consecutiveFailures = 0;
                return value;
            } catch (RuntimeException failure) {
                if (state == State.HALF_OPEN || ++consecutiveFailures >= failureThreshold) {
                    state = State.OPEN;
                    openedAt = clock.now();
                }
                throw failure;
            }
        }
    }

    private static void circuitBreakerOpensAndRecovers() {
        TestClock clock = new TestClock();
        CircuitBreaker breaker = new CircuitBreaker(3, Duration.ofSeconds(30), clock);
        AtomicInteger dependencyCalls = new AtomicInteger();
        Supplier<String> broken = () -> {
            dependencyCalls.incrementAndGet();
            throw new IllegalStateException("dependency down");
        };

        for (int i = 0; i < 3; i++) {
            failsWith(() -> breaker.call(broken), "dependency down");
        }
        check("the breaker opens after the threshold", breaker.state() == CircuitBreaker.State.OPEN);

        int callsBefore = dependencyCalls.get();
        check("an open circuit fails fast", failsWith(() -> breaker.call(broken), "circuit open"));
        check("and does not touch the dependency", dependencyCalls.get() == callsBefore);

        clock.advance(Duration.ofSeconds(31));
        check("after the cool-down one probe is allowed through",
                breaker.call(() -> "recovered").equals("recovered"));
        check("a successful probe closes the circuit", breaker.state() == CircuitBreaker.State.CLOSED);
    }

    // --- bulkhead: one sick dependency must not consume every thread ------------------------------

    private static void bulkheadIsolatesADependency() throws Exception {
        Semaphore slowDependency = new Semaphore(2); // its own budget, not the whole request pool
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(8);

        ExecutorService requests = Executors.newFixedThreadPool(8);
        try {
            for (int i = 0; i < 8; i++) {
                requests.execute(() -> {
                    if (!slowDependency.tryAcquire()) { // never block indefinitely on a sick dependency
                        rejected.incrementAndGet();
                        attempted.countDown();
                        return;
                    }
                    try {
                        peak.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
                        attempted.countDown();
                        hold.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        concurrent.decrementAndGet();
                        slowDependency.release();
                    }
                });
            }
            attempted.await(5, TimeUnit.SECONDS);

            check("the dependency never sees more than its budget", peak.get() <= 2);
            check("the overflow is shed instead of queueing forever", rejected.get() == 6);
        } finally {
            hold.countDown();
            requests.shutdown();
            check("the request pool drains", requests.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    // --- rate limiting: decide what to drop before the JVM decides for you --------------------------

    /** Token bucket: a steady refill rate with a burst allowance. */
    static final class TokenBucket {
        private final long capacity;
        private final long refillPerSecond;
        private final TestClock clock;
        private double tokens;
        private long lastRefillMillis;

        TokenBucket(long capacity, long refillPerSecond, TestClock clock) {
            this.capacity = capacity;
            this.refillPerSecond = refillPerSecond;
            this.clock = clock;
            this.tokens = capacity;
            this.lastRefillMillis = clock.now();
        }

        boolean tryAcquire() {
            long now = clock.now();
            tokens = Math.min(capacity, tokens + (now - lastRefillMillis) / 1000.0 * refillPerSecond);
            lastRefillMillis = now;
            if (tokens < 1) return false;
            tokens -= 1;
            return true;
        }
    }

    private static void rateLimiterShedsLoad() {
        TestClock clock = new TestClock();
        TokenBucket bucket = new TokenBucket(5, 10, clock);

        int allowed = 0;
        for (int i = 0; i < 20; i++) {
            if (bucket.tryAcquire()) allowed++;
        }
        check("a burst is allowed up to the bucket size", allowed == 5);
        check("the rest is rejected immediately, with 429 and Retry-After", !bucket.tryAcquire());

        clock.advance(Duration.ofSeconds(1));
        int afterRefill = 0;
        for (int i = 0; i < 20; i++) {
            if (bucket.tryAcquire()) afterRefill++;
        }
        check("tokens refill at the configured rate", afterRefill == 5); // capped by capacity
    }

    // --- health checks: liveness and readiness are different questions --------------------------------

    record Health(boolean healthy, String detail) { }

    static final class HealthRegistry {
        private final Map<String, Supplier<Health>> readiness = new LinkedHashMap<>();

        void register(String name, Supplier<Health> check) { readiness.put(name, check); }

        /** Liveness: is this process still able to make progress? Restarting fixes it. */
        boolean live() { return true; }

        /** Readiness: should this instance receive traffic right now? Restarting does not fix it. */
        Map<String, Health> ready() {
            Map<String, Health> results = new LinkedHashMap<>();
            readiness.forEach((name, check) -> {
                try {
                    results.put(name, check.get());
                } catch (RuntimeException failure) {
                    results.put(name, new Health(false, failure.toString()));
                }
            });
            return results;
        }
    }

    private static void healthChecksSeparateLivenessFromReadiness() {
        HealthRegistry health = new HealthRegistry();
        AtomicInteger databaseUp = new AtomicInteger(1);
        health.register("database", () -> databaseUp.get() == 1
                ? new Health(true, "pool 3/10 in use")
                : new Health(false, "pool exhausted"));
        health.register("cache", () -> { throw new IllegalStateException("timeout"); });

        Map<String, Health> first = health.ready();
        check("a dependency that throws is unhealthy, not a crashed probe",
                !first.get("cache").healthy() && first.get("cache").detail().contains("timeout"));
        check("a working dependency reports why it is healthy", first.get("database").healthy());

        databaseUp.set(0);
        check("readiness flips without killing the process", !health.ready().get("database").healthy());
        check("liveness stays true: a restart would not fix a dependency outage", health.live());
    }

    // --- graceful shutdown ---------------------------------------------------------------------------

    private static void gracefulShutdown() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        List<String> completed = java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch inFlight = new CountDownLatch(2);

        for (int i = 0; i < 2; i++) {
            int id = i;
            workers.execute(() -> {
                inFlight.countDown();
                try {
                    Thread.sleep(50);          // an in-flight request
                    completed.add("request-" + id);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        inFlight.await();

        // The order that matters: stop accepting, drain what is in flight, then force.
        workers.shutdown();                                        // 1. no new work
        boolean drained = workers.awaitTermination(5, TimeUnit.SECONDS); // 2. finish in-flight
        if (!drained) workers.shutdownNow();                       // 3. interrupt the stragglers

        check("in-flight work finishes before exit", drained && completed.size() == 2);
        check("new work is refused during shutdown", workers.isShutdown());

        Runtime.getRuntime().addShutdownHook(new Thread(() ->
                System.out.println("note  shutdown hook: flush metrics, close pools, deregister from LB")));
        // In Kubernetes: fail readiness first, sleep past the endpoint-propagation delay, then
        // drain — otherwise the load balancer keeps sending requests to a closing process.
    }

    // --- incident diagnosis --------------------------------------------------------------------------

    private static void incidentChecklist() {
        System.out.println("""

                note  first five minutes, in order
                        1. what changed  deploys, config, feature flags, traffic shape, dependency status
                        2. blast radius  error ratio and p99 by route and by instance, not an average
                        3. saturation    threads, connection pool, queue depth, heap after GC, CPU throttling
                        4. one instance  jcmd <pid> Thread.print, jcmd <pid> GC.heap_info, JFR for 60s
                        5. mitigate      roll back, shed load, or open the breaker before root-causing

                note  reading the evidence
                        rising p99 with flat CPU        queueing: a pool or a lock, not compute
                        heap full after every full GC   a leak; take a heap dump before restarting
                        threads BLOCKED on one monitor  lock contention; the stack names the lock
                        WAITING on a pool               the dependency is slow, the pool is the symptom
                        CPU pinned in GC threads        allocation rate or an undersized heap
                        container OOMKilled, heap fine  native/metaspace/thread stacks, not the heap

                note  keep the evidence
                        -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/var/log
                        -Xlog:gc*:file=/var/log/gc.log:time,uptime:filecount=5,filesize=20m
                        JFR always-on with a profile settings file, dumped on exit""");
    }

    // --- helpers ---------------------------------------------------------------------------------------

    private static boolean failsWith(Runnable body, String expectedFragment) {
        try {
            body.run();
            return false;
        } catch (RuntimeException failure) {
            return failure.getMessage() != null && failure.getMessage().contains(expectedFragment);
        }
    }

    private static void check(String claim, boolean holds) {
        if (!holds) throw new AssertionError("failed: " + claim);
        System.out.println("ok  " + claim);
    }
}
