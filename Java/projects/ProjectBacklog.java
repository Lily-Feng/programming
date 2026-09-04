import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The project backlog, as code rather than a checklist.
 *
 * <p>A project is worth doing here when it forces several note categories to work together
 * and produces something reviewable. This file states each candidate as data — the topics it
 * exercises, what it must ship, and how it can fail — and picks the next one from the topics
 * already studied.
 *
 * <p>Run it to see the ranked backlog:
 * <pre>{@code
 * java ProjectBacklog.java                       # rank by topics covered so far
 * java ProjectBacklog.java 01 02 04 05           # rank as if these categories are solid
 * }</pre>
 *
 * <p>Requires Java 17 or later.
 */
public final class ProjectBacklog {

    /** The note categories under {@code ../notes/}. */
    enum Topic {
        LANGUAGE_AND_OOP("01"),
        MODERN_JAVA("02"),
        STANDARD_LIBRARY_AND_IO("03"),
        COLLECTIONS_GENERICS_STREAMS("04"),
        CONCURRENCY("05"),
        JVM_AND_PERFORMANCE("06"),
        TESTING_AND_BUILD("07"),
        BACKEND("08"),
        PRODUCTION("09");

        final String number;

        Topic(String number) { this.number = number; }

        static Topic ofNumber(String number) {
            for (Topic topic : values()) {
                if (topic.number.equals(number)) return topic;
            }
            throw new IllegalArgumentException("no note category " + number);
        }
    }

    /**
     * @param deliverables what must exist before the project counts as done
     * @param failureModes the interesting part: what the project is supposed to teach when it breaks
     */
    record Project(String name,
                   String summary,
                   Set<Topic> topics,
                   List<String> deliverables,
                   List<String> failureModes) {

        /** How much of this project rests on topics that are not solid yet. */
        int unmetPrerequisites(Set<Topic> studied) {
            int unmet = 0;
            for (Topic topic : topics) {
                if (!studied.contains(topic)) unmet++;
            }
            return unmet;
        }

        boolean readyToStart(Set<Topic> studied) { return unmetPrerequisites(studied) == 0; }
    }

    static final List<Project> BACKLOG = List.of(
            new Project(
                    "csv-pipeline",
                    "A command-line processor that streams a large delimited file, aggregates it, "
                            + "and writes a report — without loading the file into memory.",
                    EnumSet.of(Topic.STANDARD_LIBRARY_AND_IO, Topic.COLLECTIONS_GENERICS_STREAMS,
                            Topic.TESTING_AND_BUILD),
                    List.of("streaming read with an explicit charset and a closed resource",
                            "collector-based aggregation, no manual mutable accumulation",
                            "golden-file tests plus a malformed-input test",
                            "a documented memory ceiling, verified on a file larger than the heap"),
                    List.of("a quoted delimiter inside a field",
                            "a truncated final line",
                            "a BOM, and a file that is not the charset it claims to be")),
            new Project(
                    "job-runner",
                    "A bounded concurrent job runner: submit work, apply backpressure, cancel, "
                            + "and shut down without losing or duplicating a job.",
                    EnumSet.of(Topic.CONCURRENCY, Topic.COLLECTIONS_GENERICS_STREAMS,
                            Topic.TESTING_AND_BUILD, Topic.PRODUCTION),
                    List.of("a bounded queue with an explicit rejection policy",
                            "cancellation that actually interrupts, and restores the flag",
                            "shutdown ordering: stop accepting, drain, then force",
                            "a repeatable test that fails if the invariant breaks"),
                    List.of("a task that ignores interruption",
                            "a queue that grows until the heap is gone",
                            "a shutdown that drops in-flight work")),
            new Project(
                    "items-service",
                    "A small HTTP service with persistence, validation, idempotent writes, "
                            + "pagination, metrics, and a health endpoint.",
                    EnumSet.of(Topic.BACKEND, Topic.STANDARD_LIBRARY_AND_IO, Topic.TESTING_AND_BUILD,
                            Topic.PRODUCTION, Topic.MODERN_JAVA),
                    List.of("transaction boundaries stated in the service layer",
                            "an error response shape used by every endpoint",
                            "keyset pagination and a bounded page size",
                            "integration tests against a real database via Testcontainers",
                            "structured logs with a correlation id, and a readiness check"),
                    List.of("a retried POST creating two rows",
                            "a stale write silently overwriting a newer one",
                            "the connection pool exhausted by one slow query")),
            new Project(
                    "jvm-lab",
                    "A diagnostics lab that produces one controlled failure at a time — a leak, a "
                            + "deadlock, an allocation storm — and then investigates it from the evidence.",
                    EnumSet.of(Topic.JVM_AND_PERFORMANCE, Topic.CONCURRENCY, Topic.PRODUCTION),
                    List.of("a reproducible trigger per failure, behind a flag",
                            "the heap dump, thread dump, and JFR recording each one produces",
                            "a written diagnosis naming the JDK version and collector",
                            "the fix, and the measurement that shows it worked"),
                    List.of("a cache with no bound, retaining everything",
                            "two locks taken in opposite orders",
                            "an allocation rate that keeps the collector busy but the heap small")),
            new Project(
                    "wire-protocol",
                    "A small binary protocol client and server: framing, timeouts, backpressure, "
                            + "and a fuzz test that throws malformed frames at the parser.",
                    EnumSet.of(Topic.STANDARD_LIBRARY_AND_IO, Topic.CONCURRENCY, Topic.LANGUAGE_AND_OOP,
                            Topic.TESTING_AND_BUILD),
                    List.of("a framing format written down before any code",
                            "a parser that never trusts a length prefix",
                            "read and write timeouts on every socket",
                            "a fuzz test over truncated, oversized, and reordered frames"),
                    List.of("a length prefix that promises more bytes than exist",
                            "a slow reader that stalls the writer",
                            "a partial write treated as a complete one")));

    public static void main(String[] args) {
        Set<Topic> studied = args.length == 0 ? defaultStudied() : parse(args);
        System.out.println("studied categories: " + studied);

        List<Project> ranked = new ArrayList<>(BACKLOG);
        ranked.sort(Comparator
                .comparingInt((Project project) -> project.unmetPrerequisites(studied))
                .thenComparing(Project::name));

        for (Project project : ranked) {
            System.out.println();
            System.out.printf("%-14s %s%n", project.name(),
                    project.readyToStart(studied)
                            ? "ready"
                            : project.unmetPrerequisites(studied) + " prerequisite(s) missing: "
                                    + missing(project, studied));
            System.out.println("  " + project.summary());
            System.out.println("  ships:");
            project.deliverables().forEach(item -> System.out.println("    - " + item));
            System.out.println("  must handle:");
            project.failureModes().forEach(item -> System.out.println("    - " + item));
        }

        System.out.println();
        System.out.println("""
                note  a project is done when someone else could run it from the README, the tests
                      pass from a clean clone, the architectural decisions are written down, and the
                      known limitations are stated. Half-finished projects teach the wrong lesson.""");
    }

    /** Topics with a runnable example checked in under {@code ../notes/}. */
    private static Set<Topic> defaultStudied() {
        return EnumSet.allOf(Topic.class);
    }

    private static Set<Topic> parse(String[] numbers) {
        Set<Topic> topics = EnumSet.noneOf(Topic.class);
        for (String number : numbers) {
            topics.add(Topic.ofNumber(number.strip()));
        }
        return topics;
    }

    private static List<String> missing(Project project, Set<Topic> studied) {
        List<String> names = new ArrayList<>();
        for (Topic topic : project.topics()) {
            if (!studied.contains(topic)) names.add(topic.number);
        }
        names.sort(Comparator.naturalOrder());
        return names;
    }
}
