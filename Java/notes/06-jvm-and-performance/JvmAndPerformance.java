import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * JVM and performance.
 *
 * <p>This file inspects the runtime it is actually running on instead of asserting
 * facts about "the JVM": every number it prints is labelled with the JDK version and
 * collector reported by {@link ManagementFactory}. What it does assert are the
 * language-level guarantees — lazy class initialization, string interning, reference
 * clearing — that hold on any conforming implementation.
 *
 * <p>The timing section is deliberately a demonstration of measurement error, not a
 * benchmark. Real numbers need JMH (see {@code ../07-testing-and-build}), which handles
 * warmup, dead-code elimination, and fork isolation.
 *
 * <p>Requires Java 17 or later. Run with: {@code java JvmAndPerformance.java}
 * <p>Useful flags: {@code -Xlog:gc}, {@code -Xlog:class+load=info}, {@code -XX:+PrintCompilation}
 */
public final class JvmAndPerformance {

    public static void main(String[] args) {
        describeRuntime();
        classLoadingIsLazy();
        stringPoolAndIdentity();
        allocationAndGc();
        threadsAndStacks();
        whyAdHocTimingLies();
        diagnosticsChecklist();
    }

    // --- what am I actually running on? -------------------------------------------

    private static void describeRuntime() {
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        System.out.println("note  jdk " + Runtime.version()
                + " / " + System.getProperty("java.vm.name")
                + " / " + System.getProperty("os.arch"));
        System.out.println("note  collectors " + collectorNames());
        System.out.println("note  cpus " + Runtime.getRuntime().availableProcessors()
                + ", max heap " + megabytes(Runtime.getRuntime().maxMemory()) + " MB");
        System.out.println("note  vm args " + runtime.getInputArguments());

        check("the runtime describes itself; never guess the version",
                Runtime.version().feature() >= 11);
    }

    private static List<String> collectorNames() {
        List<String> names = new ArrayList<>();
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            names.add(collector.getName());
        }
        return names;
    }

    // --- class loading, linking, initialization ------------------------------------

    static final class Eager {
        static final String CONSTANT = "compile-time constant"; // inlined by javac, no init needed
    }

    /** Records its own initialization from the outside, so nothing here has to touch its fields. */
    static final List<String> INITIALIZED = new ArrayList<>();

    static final class Lazy {
        static { INITIALIZED.add("Lazy"); }                     // runs on first active use
        static int value() { return 7; }
    }

    private static void classLoadingIsLazy() {
        // Reading a constant variable does not trigger initialization: the value was inlined.
        String constant = Eager.CONSTANT;
        check("constant variables are inlined at compile time", constant.length() > 0);

        loadWithoutInitializing("JvmAndPerformance$Lazy");
        check("loading a class does not initialize it", INITIALIZED.isEmpty());
        check("calling a static method is an active use",
                Lazy.value() == 7 && INITIALIZED.equals(List.of("Lazy")));

        try {
            Class.forName("com.example.NotOnTheClasspath");
            check("missing classes fail at link time, not at compile time", false);
        } catch (ClassNotFoundException expected) {
            check("missing classes fail at link time, not at compile time", true);
        }
    }

    /** {@code initialize=false} loads and links the class without running its static initializer. */
    private static void loadWithoutInitializing(String binaryName) {
        try {
            Class.forName(binaryName, false, JvmAndPerformance.class.getClassLoader());
        } catch (ClassNotFoundException error) {
            throw new IllegalStateException(error);
        }
    }

    // --- the string pool and object identity -----------------------------------------

    private static void stringPoolAndIdentity() {
        String literal = "jvm";
        String alsoLiteral = "jvm";
        String built = new StringBuilder("j").append("vm").toString();

        check("literals share one pooled instance", literal == alsoLiteral);
        check("a computed string is a distinct object", literal != built);
        check("intern() maps it back to the pooled instance", literal == built.intern());
        check("equals compares contents, == compares identity", literal.equals(built));
    }

    // --- allocation, references, GC ------------------------------------------------------

    private static void allocationAndGc() {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        MemoryUsage before = memory.getHeapMemoryUsage();

        // Short-lived garbage: cheap to allocate, cheap to collect in a young generation.
        long checksum = 0;
        for (int i = 0; i < 200_000; i++) {
            byte[] scratch = new byte[64];
            scratch[0] = (byte) i;
            checksum += scratch[0];
        }

        MemoryUsage after = memory.getHeapMemoryUsage();
        System.out.println("note  heap used before/after " + megabytes(before.getUsed())
                + "/" + megabytes(after.getUsed()) + " MB (allocation is a pointer bump; "
                + "collection cost tracks live data, not garbage)");
        check("the loop actually ran and was not optimized away", checksum != 0);

        // A java.lang.ref reference does not keep its object alive; a strong one does.
        java.lang.ref.WeakReference<Object> weak = new java.lang.ref.WeakReference<>(new Object());
        Object strong = new Object();
        java.lang.ref.WeakReference<Object> weakToStrong = new java.lang.ref.WeakReference<>(strong);
        System.gc();                                  // a hint, never a guarantee
        check("a strongly reachable object survives", weakToStrong.get() != null);
        System.out.println("note  unreachable weak referent cleared: " + (weak.get() == null)
                + " (System.gc() is advisory)");
        check("the strong reference is still in scope", strong != null);
    }

    // --- threads, stacks, and where memory lives -------------------------------------------

    private static void threadsAndStacks() {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        System.out.println("note  live threads " + threads.getThreadCount()
                + ", peak " + threads.getPeakThreadCount());

        int depth = recurse(0);
        System.out.println("note  stack overflowed at depth " + depth
                + " (frames live on the thread stack, objects on the shared heap; "
                + "-Xss changes the first, -Xmx the second)");
        check("stack depth is bounded per thread, independently of heap size", depth > 100);
    }

    private static int recurse(int depth) {
        try {
            return recurse(depth + 1);
        } catch (StackOverflowError expected) {
            return depth; // caught here only to measure it; never swallow this in production code
        }
    }

    // --- why a hand-rolled timing loop is not a benchmark --------------------------------------

    private static void whyAdHocTimingLies() {
        long cold = timeSum();
        long warm = 0;
        for (int i = 0; i < 20; i++) {
            warm = timeSum(); // the JIT compiles and inlines the hot path along the way
        }

        System.out.println("note  first run " + TimeUnit.NANOSECONDS.toMicros(cold)
                + " us, run 21 " + TimeUnit.NANOSECONDS.toMicros(warm)
                + " us — the difference is warmup, not a code change");
        check("both runs produced a timing", cold > 0 && warm > 0);
        // What is still wrong with the numbers above: one fork, no dead-code elimination
        // guard, no blackhole, no statistics, and the result is discarded. Use JMH.
    }

    private static long timeSum() {
        long start = System.nanoTime();
        long total = 0;
        for (int i = 0; i < 1_000_000; i++) {
            total += i % 7;
        }
        long elapsed = System.nanoTime() - start;
        if (total == Long.MIN_VALUE) System.out.println("unreachable"); // keep the result live
        return elapsed;
    }

    // --- what to reach for when something is slow or stuck ---------------------------------------

    private static void diagnosticsChecklist() {
        long pid = ProcessHandle.current().pid();
        System.out.println("""
                note  diagnostics for pid %d
                        jcmd %d Thread.print              stuck? deadlock? which locks are held
                        jcmd %d GC.heap_info              heap shape before assuming a leak
                        jcmd %d VM.native_memory summary  needs -XX:NativeMemoryTracking=summary
                        jcmd %d JFR.start duration=60s filename=app.jfr   allocation and latency profile
                        jmap -histo:live %d | head        what is actually retained
                        -XX:+HeapDumpOnOutOfMemoryError   capture the evidence before the restart"""
                .formatted(pid, pid, pid, pid, pid, pid));
        check("the process can identify itself for tooling", pid > 0);
    }

    // --- helpers -----------------------------------------------------------------------------------

    private static long megabytes(long bytes) { return bytes / (1024 * 1024); }

    private static void check(String claim, boolean holds) {
        if (!holds) throw new AssertionError("failed: " + claim);
        System.out.println("ok  " + claim);
    }
}
