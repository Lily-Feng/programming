import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Retrieval practice as a runnable drill.
 *
 * <p>The companion page {@code java-interview-prep.html} is for reading; this file is for
 * answering. It prints questions only, so the answer has to come out of memory first —
 * recognising an answer is not the same as being able to produce one.
 *
 * <pre>{@code
 * java InterviewDrills.java                 # every question, shuffled, answers hidden
 * java InterviewDrills.java --topic 05      # one note category
 * java InterviewDrills.java --answers       # reveal, after answering aloud
 * java InterviewDrills.java --seed 7        # a repeatable order, to re-test the weak ones
 * }</pre>
 *
 * <p>Two rules that keep this honest: say the answer out loud (or write it) before revealing,
 * and turn every weak answer into a runnable example under {@code ../notes/} or
 * {@code ../exercises/} rather than re-reading the answer here.
 *
 * <p>Requires Java 17 or later.
 */
public final class InterviewDrills {

    /**
     * @param topic the note category number under {@code ../notes/}
     * @param followUp where the question usually goes next — that is where interviews are decided
     */
    record Drill(String topic, String question, String answer, String followUp) { }

    static final List<Drill> DRILLS = List.of(
            new Drill("01",
                    "Is Java pass-by-value or pass-by-reference?",
                    "Always pass-by-value. For a reference type the value copied is the reference, "
                            + "so the callee can mutate the same object but cannot make the caller's "
                            + "variable point somewhere else.",
                    "Then why does a setter called inside a method change what the caller sees?"),
            new Drill("01",
                    "What breaks if equals is overridden and hashCode is not?",
                    "Equal objects can land in different buckets, so a HashMap or HashSet loses them: "
                            + "contains returns false for a value that is in the collection. The contract "
                            + "is one-directional — equal objects must share a hash code; equal hash codes "
                            + "say nothing.",
                    "What happens if a key is mutated after being inserted into a HashSet?"),
            new Drill("01",
                    "Why is calling an overridable method from a constructor dangerous?",
                    "The override runs before the subclass's fields and instance initialisers have run, "
                            + "so it sees defaults (null, 0, false) rather than the constructed state.",
                    "How would you get the same extension point safely?"),
            new Drill("02",
                    "What does a record actually give you, and what does it cost?",
                    "A transparent carrier for its components: final fields, a canonical constructor, "
                            + "accessors, and value-based equals, hashCode, and toString. The cost is that "
                            + "the components are the API — shallow immutability only, so a mutable "
                            + "component must still be copied defensively in a compact constructor.",
                    "How do you validate or normalise a component?"),
            new Drill("02",
                    "What do sealed types buy over a plain interface?",
                    "The permitted set is known at compile time, so a switch over it can be checked for "
                            + "exhaustiveness without a default branch. Adding a case then becomes a "
                            + "compile error at every site that must handle it, instead of a runtime surprise.",
                    "When is an abstract method on the interface the better answer than a switch?"),
            new Drill("03",
                    "Why does a string that round-trips through a file sometimes come back wrong?",
                    "Because a charset was left implicit somewhere. Encode and decode must agree; the "
                            + "platform default differs between machines. Name the charset on every read, "
                            + "write, getBytes, and new String.",
                    "Which JDK version changed the default charset for these APIs, and to what?"),
            new Drill("03",
                    "When is Files.lines wrong and Files.readAllLines right?",
                    "Files.lines is lazy and holds an open file handle, so it must be closed in a "
                            + "try-with-resources; it is the right choice for large files. readAllLines "
                            + "reads everything into memory and needs no closing, which is fine only when "
                            + "the file is known to be small.",
                    "What happens to the handle if the stream is filtered and never terminated?"),
            new Drill("04",
                    "When is ArrayList the wrong list, really?",
                    "Rarely. Random access and iteration are cache-friendly; growth is amortised O(1). "
                            + "LinkedList only wins for frequent insertion or removal through an iterator "
                            + "in the middle, and loses on every other access pattern. For a queue or a "
                            + "stack, use ArrayDeque.",
                    "What does 'amortised' hide about a single add call?"),
            new Drill("04",
                    "PECS — what does it mean and why does it exist?",
                    "Producer extends, consumer super. Generics are invariant, so List<String> is not a "
                            + "List<Object>; the wildcards restore the flexibility safely. From a "
                            + "'? extends T' you can read T but not write; into a '? super T' you can write "
                            + "T but read only Object.",
                    "Why can you not add to a List<? extends Number>?"),
            new Drill("04",
                    "When does a parallel stream actually help?",
                    "When the work per element is significant, the source splits evenly (an array or "
                            + "ArrayList, not a LinkedList or an IO-bound iterator), the operation is "
                            + "associative and stateless, and the result is collected rather than "
                            + "accumulated into shared mutable state. Otherwise it costs more than it saves.",
                    "Which thread pool does it use by default, and why does that matter in a server?"),
            new Drill("05",
                    "What does volatile guarantee, and what does it not?",
                    "It guarantees visibility and ordering: a read sees the latest write, and everything "
                            + "written before the volatile write is visible after the volatile read. It "
                            + "does not give atomicity — count++ is still a lost update.",
                    "What would you use instead for a counter, and what for a flag?"),
            new Drill("05",
                    "How do you cancel work that is already running?",
                    "Cooperatively. Future.cancel(true) interrupts the thread; the task must check "
                            + "Thread.interrupted() or block in an interruptible call, and on catching "
                            + "InterruptedException must either propagate it or restore the flag.",
                    "What breaks if a library swallows InterruptedException?"),
            new Drill("05",
                    "What do virtual threads change, and what do they not?",
                    "They make blocking cheap, so a thread-per-request model scales to very high "
                            + "concurrency; blocking unmounts the virtual thread instead of parking a "
                            + "platform thread. They do not raise the limit of any scarce downstream "
                            + "resource — that still needs a semaphore or a pool — and pooling them is "
                            + "pointless.",
                    "What still pins a virtual thread to its carrier?"),
            new Drill("06",
                    "A service is slow but the CPU is idle. Where do you look?",
                    "Queueing, not compute. Thread dump for BLOCKED and WAITING states, connection pool "
                            + "and executor queue depth, downstream latency, and GC pauses. Rising p99 "
                            + "with flat CPU is almost always waiting on a lock, a pool, or a dependency.",
                    "Which single command gives you the most in the first minute?"),
            new Drill("06",
                    "Why is a hand-written timing loop not a benchmark?",
                    "No warmup, so it measures the interpreter and the JIT compiling; dead-code "
                            + "elimination can delete the work; one fork hides run-to-run variance; and a "
                            + "single number hides the distribution. JMH exists to handle all four.",
                    "What is a blackhole for?"),
            new Drill("06",
                    "OutOfMemoryError — what are the first three questions?",
                    "Which OOME (Java heap space, GC overhead limit, Metaspace, direct buffer, unable to "
                            + "create native thread — they have different causes); is there a heap dump "
                            + "(-XX:+HeapDumpOnOutOfMemoryError); and does the heap stay full after a full "
                            + "GC, which distinguishes a leak from an undersized heap.",
                    "The container was OOMKilled but the heap looks fine. Now what?"),
            new Drill("07",
                    "What makes a test valuable rather than just present?",
                    "It fails for exactly one reason, that reason is a behaviour a user cares about, and "
                            + "the failure message says what was expected. Coverage measures which lines "
                            + "ran, not whether a change would be caught — mutation testing measures that.",
                    "Which of your tests would still pass if the method body were deleted?"),
            new Drill("07",
                    "When is mocking the wrong tool?",
                    "When the double encodes an assumption about a collaborator you do not control — then "
                            + "the test passes while production fails. Prefer real objects for value types, "
                            + "hand-written fakes for small interfaces, and Testcontainers for "
                            + "infrastructure.",
                    "What is the cost of a test suite where every collaborator is mocked?"),
            new Drill("08",
                    "A client retries a POST it never got a response to. What must the server do?",
                    "Make the write idempotent: an idempotency key from the client, stored with a unique "
                            + "constraint, so the replay returns the original result instead of creating a "
                            + "second row. At-least-once delivery is the norm; exactly-once is a property "
                            + "of the handler, not the network.",
                    "Where does the key live, and when can it expire?"),
            new Drill("08",
                    "Why is OFFSET pagination a problem, and what replaces it?",
                    "The database still walks the skipped rows, so deep pages get slower, and concurrent "
                            + "inserts shift the window so rows are repeated or missed. Keyset pagination "
                            + "— WHERE id > :cursor ORDER BY id LIMIT :n — is stable and uses the index.",
                    "How does that change when the sort key is not unique?"),
            new Drill("08",
                    "What is an N+1 query and how do you catch it before production?",
                    "One query loads the parents and then one query fires per parent for a lazy "
                            + "association. Fix it with a join fetch or an entity graph, and prevent the "
                            + "regression by asserting the query count in a test rather than by reading the "
                            + "code.",
                    "Why does making everything eager not fix it?"),
            new Drill("09",
                    "Retries made an incident worse. Why?",
                    "Retries multiply load on a dependency that is already failing. Without a budget, "
                            + "jitter, and a circuit breaker, every layer retrying its layer below turns a "
                            + "slowdown into a self-inflicted flood — and a retry is only ever valid for a "
                            + "failure that is transient and an operation that is idempotent.",
                    "Why full jitter rather than fixed exponential backoff?"),
            new Drill("09",
                    "What is the difference between liveness and readiness?",
                    "Liveness asks whether the process should be restarted; readiness asks whether it "
                            + "should receive traffic now. Wiring a dependency check into liveness turns a "
                            + "database blip into a restart loop across every instance.",
                    "What order should shutdown take so no request is dropped?"),
            new Drill("09",
                    "What do you alert on?",
                    "Symptoms users feel: error ratio and tail latency per route, plus saturation of the "
                            + "resources that cause them (pool, queue, heap). CPU and memory alone are "
                            + "causes, not symptoms, and page for things a human must act on now.",
                    "Why is an average latency alert close to useless?"));

    public static void main(String[] args) {
        boolean revealAnswers = false;
        String topicFilter = null;
        Long seed = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--answers" -> revealAnswers = true;
                case "--topic" -> topicFilter = requireValue(args, ++i, "--topic");
                case "--seed" -> seed = Long.parseLong(requireValue(args, ++i, "--seed"));
                default -> throw new IllegalArgumentException("unknown option: " + args[i]);
            }
        }

        List<Drill> selected = new ArrayList<>();
        for (Drill drill : DRILLS) {
            if (topicFilter == null || drill.topic().equals(topicFilter)) selected.add(drill);
        }
        if (selected.isEmpty()) throw new IllegalArgumentException("no drills for topic " + topicFilter);
        Collections.shuffle(selected, seed == null ? new Random() : new Random(seed));

        System.out.println(selected.size() + " drill(s)"
                + (topicFilter == null ? "" : " for category " + topicFilter)
                + (revealAnswers ? ", answers shown" : ", answer aloud before revealing")
                + (seed == null ? "" : ", seed " + seed));

        for (int i = 0; i < selected.size(); i++) {
            Drill drill = selected.get(i);
            System.out.printf("%n%2d. [%s] %s%n", i + 1, drill.topic(), drill.question());
            if (revealAnswers) {
                System.out.println("    " + wrap(drill.answer()));
                System.out.println("    follow-up: " + drill.followUp());
            }
        }

        if (!revealAnswers) {
            System.out.println("""

                    re-run with --answers to check yourself. For anything you could not answer
                    without hesitating, write the runnable example instead of re-reading the answer.""");
        }
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) throw new IllegalArgumentException(option + " needs a value");
        return args[index];
    }

    /** Wraps at 88 columns so an answer stays readable in a terminal. */
    private static String wrap(String text) {
        StringBuilder wrapped = new StringBuilder();
        int lineLength = 0;
        for (String word : text.split(" ")) {
            if (lineLength + word.length() > 84) {
                wrapped.append("\n    ");
                lineLength = 0;
            }
            wrapped.append(word).append(' ');
            lineLength += word.length() + 1;
        }
        return wrapped.toString().stripTrailing();
    }
}
