import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Testing and build tooling.
 *
 * <p>The JUnit 5 jars are not on the classpath of a single-file run, so this file builds
 * the smallest thing that behaves like a test framework — reflective discovery, a lifecycle
 * hook, parameterized cases, and a failure report — and uses it to test a small production
 * class. Reading it is the point: it shows what {@code @Test}, {@code @BeforeEach}, and
 * {@code @ParameterizedTest} actually do for you.
 *
 * <p>{@link #buildConfiguration()} prints the Maven and Gradle wiring for the real thing,
 * and {@link #testingPractices()} records the rules the tests below follow.
 *
 * <p>Requires Java 17 or later. Run with: {@code java TestingAndBuild.java}
 */
public final class TestingAndBuild {

    public static void main(String[] args) {
        int failures = new TinyRunner(RetryPolicyTest.class).run();
        testingPractices();
        buildConfiguration();
        if (failures > 0) {
            throw new AssertionError(failures + " test(s) failed");
        }
    }

    // ======================= production code under test =======================

    /** Exponential backoff with a cap and a total-attempt budget. */
    public record RetryPolicy(int maxAttempts, long baseDelayMillis, long maxDelayMillis) {

        public RetryPolicy {
            if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
            if (baseDelayMillis < 0) throw new IllegalArgumentException("baseDelayMillis must be >= 0");
            if (maxDelayMillis < baseDelayMillis) {
                throw new IllegalArgumentException("maxDelayMillis must be >= baseDelayMillis");
            }
        }

        /** Delay before attempt {@code n} (1-based). Attempt 1 is immediate. */
        public long delayForAttempt(int attempt) {
            if (attempt < 1 || attempt > maxAttempts) {
                throw new IllegalArgumentException("attempt out of range: " + attempt);
            }
            if (attempt == 1) return 0;
            long exponential = baseDelayMillis << Math.min(attempt - 2, 32); // saturating shift
            return Math.min(exponential < 0 ? maxDelayMillis : exponential, maxDelayMillis);
        }

        /**
         * Runs {@code work}, retrying on failure. The sleeper is injected so tests never
         * sleep: that is the difference between a fast unit test and a flaky one.
         */
        public <T> T execute(Supplier<T> work, Sleeper sleeper) {
            RuntimeException last = null;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                sleeper.sleep(delayForAttempt(attempt));
                try {
                    return work.get();
                } catch (RuntimeException failure) {
                    last = failure;
                }
            }
            throw new IllegalStateException("all " + maxAttempts + " attempts failed", last);
        }
    }

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis);
    }

    /**
     * A hand-written test double. Prefer this to a mocking framework when the interface is
     * this small: it records what happened and stays readable in the failure message.
     */
    static final class RecordingSleeper implements Sleeper {
        final List<Long> slept = new ArrayList<>();

        @Override public void sleep(long millis) { slept.add(millis); }
    }

    // ======================= the tests =======================

    /** Behaviour-focused tests: one reason to fail each, and no sleeping. */
    public static final class RetryPolicyTest {

        private RetryPolicy policy;          // rebuilt per test by setUp: no shared mutable state

        public void setUp() {                // the @BeforeEach equivalent
            policy = new RetryPolicy(4, 100, 500);
        }

        public void testFirstAttemptIsImmediate() {
            assertEquals(0L, policy.delayForAttempt(1), "attempt 1 should not wait");
        }

        public void testBackoffDoublesUntilTheCap() {
            assertEquals(100L, policy.delayForAttempt(2), "first backoff is the base delay");
            assertEquals(200L, policy.delayForAttempt(3), "backoff doubles");
            assertEquals(400L, policy.delayForAttempt(4), "backoff doubles again");
        }

        public void testDelayIsCapped() {
            RetryPolicy capped = new RetryPolicy(10, 100, 300);
            assertEquals(300L, capped.delayForAttempt(9), "delay never exceeds maxDelayMillis");
        }

        /** The @ParameterizedTest equivalent: cases as data, each reported separately. */
        public void testRejectsInvalidAttempts() {
            for (int attempt : new int[] {0, -1, 5, Integer.MAX_VALUE}) {
                assertThrows(IllegalArgumentException.class,
                        () -> policy.delayForAttempt(attempt),
                        "attempt " + attempt + " is outside 1.." + policy.maxAttempts());
            }
        }

        public void testInvalidConfigurationFailsFast() {
            assertThrows(IllegalArgumentException.class,
                    () -> new RetryPolicy(0, 10, 10), "maxAttempts must be at least 1");
            assertThrows(IllegalArgumentException.class,
                    () -> new RetryPolicy(3, 100, 10), "the cap must not be below the base delay");
        }

        public void testSucceedsWithoutRetryWhenWorkSucceeds() {
            RecordingSleeper sleeper = new RecordingSleeper();
            String result = policy.execute(() -> "ok", sleeper);

            assertEquals("ok", result, "the first successful result is returned");
            assertEquals(List.of(0L), sleeper.slept, "no backoff after a success");
        }

        public void testRetriesThenSucceeds() {
            RecordingSleeper sleeper = new RecordingSleeper();
            int[] calls = {0};

            String result = policy.execute(() -> {
                if (++calls[0] < 3) throw new IllegalStateException("transient");
                return "ok";
            }, sleeper);

            assertEquals("ok", result, "the third attempt succeeds");
            assertEquals(3, calls[0], "no attempts beyond the first success");
            assertEquals(List.of(0L, 100L, 200L), sleeper.slept, "backoff grows between attempts");
        }

        public void testGivesUpAfterMaxAttemptsAndKeepsTheCause() {
            RecordingSleeper sleeper = new RecordingSleeper();
            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> policy.execute(() -> { throw new IllegalStateException("always down"); }, sleeper),
                    "exhausting the budget is a failure, not a silent null");

            assertEquals(4, sleeper.slept.size(), "exactly maxAttempts attempts were made");
            assertEquals("always down",
                    Optional.ofNullable(thrown.getCause()).map(Throwable::getMessage).orElse(null),
                    "the last failure is kept as the cause");
        }
    }

    // ======================= the minimal framework =======================

    /** Discovers {@code test*} methods reflectively, runs {@code setUp} before each, reports failures. */
    static final class TinyRunner {
        private final Class<?> testClass;

        TinyRunner(Class<?> testClass) { this.testClass = testClass; }

        int run() {
            List<Method> tests = Arrays.stream(testClass.getDeclaredMethods())
                    .filter(method -> method.getName().startsWith("test"))
                    .filter(method -> method.getParameterCount() == 0)
                    .sorted(Comparator.comparing(Method::getName)) // deterministic order for readable output
                    .toList();

            int failures = 0;
            for (Method test : tests) {
                try {
                    Object instance = testClass.getDeclaredConstructor().newInstance(); // isolation per test
                    testClass.getDeclaredMethod("setUp").invoke(instance);
                    test.invoke(instance);
                    System.out.println("PASS " + test.getName());
                } catch (InvocationTargetException error) {
                    failures++;
                    System.out.println("FAIL " + test.getName() + ": " + error.getCause());
                } catch (ReflectiveOperationException error) {
                    throw new IllegalStateException("could not run " + test.getName(), error);
                }
            }
            System.out.println(tests.size() + " test(s), " + failures + " failure(s)");
            return failures;
        }
    }

    static void assertEquals(Object expected, Object actual, String because) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(because + " — expected <" + expected + "> but was <" + actual + ">");
        }
    }

    static <T extends Throwable> T assertThrows(Class<T> expected, Runnable body, String because) {
        try {
            body.run();
        } catch (Throwable thrown) {
            if (expected.isInstance(thrown)) return expected.cast(thrown);
            throw new AssertionError(because + " — expected " + expected.getSimpleName()
                    + " but got " + thrown, thrown);
        }
        throw new AssertionError(because + " — expected " + expected.getSimpleName() + ", nothing was thrown");
    }

    // ======================= practices and build wiring =======================

    private static void testingPractices() {
        System.out.println("""

                note  what the tests above are doing on purpose
                        one behaviour per test, named after the behaviour, not the method
                        arrange / act / assert, with the assertion message stating the expectation
                        the clock and the sleeper are injected, so nothing is timing-dependent
                        a hand-written double instead of a mocking framework for a one-method interface
                        edge cases as data (0, -1, past the budget, MAX_VALUE)
                        the pyramid: these are unit tests; integration tests use Testcontainers
                        against real Postgres/Kafka, and stay out of the fast feedback loop""");
    }

    private static void buildConfiguration() {
        System.out.println("""

                note  the real framework — Maven (src/test/java, surefire runs *Test)
                        <dependency>
                          <groupId>org.junit.jupiter</groupId>
                          <artifactId>junit-jupiter</artifactId>
                          <version>5.11.3</version>
                          <scope>test</scope>
                        </dependency>
                        mvn -q verify          compile, test, package
                        mvn dependency:tree    why a version was chosen (nearest-wins)

                note  the real framework — Gradle
                        testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
                        tasks.test { useJUnitPlatform() }
                        ./gradlew test --tests '*RetryPolicy*'
                        ./gradlew dependencies --configuration testRuntimeClasspath

                note  the rest of the quality gate
                        jacoco          coverage as a smoke alarm, never as a target
                        pitest          mutation testing: do the tests actually detect a change
                        spotbugs/errorprone + spotless   analysis and formatting in CI, not in review
                        jmh             microbenchmarks (separate module, forked JVMs)
                        dependency locking and a reproducible toolchain pin the build inputs

                note  the JUnit 5 shape of RetryPolicyTest
                        @BeforeEach void setUp() { policy = new RetryPolicy(4, 100, 500); }
                        @Test void firstAttemptIsImmediate() { assertEquals(0, policy.delayForAttempt(1)); }
                        @ParameterizedTest @ValueSource(ints = {0, -1, 5})
                        void rejectsInvalidAttempts(int attempt) {
                          assertThrows(IllegalArgumentException.class, () -> policy.delayForAttempt(attempt));
                        }""");
    }
}
