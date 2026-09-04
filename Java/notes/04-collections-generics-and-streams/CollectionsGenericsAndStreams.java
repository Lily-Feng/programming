import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Collections, generics, and streams.
 *
 * <p>Covers implementation tradeoffs, hashing and ordering hazards, generic bounds
 * and PECS, erasure and heap pollution, and stream laziness, collectors, and parallelism.
 *
 * <p>Requires Java 17 or later (records). Run with: {@code java CollectionsGenericsAndStreams.java}
 */
public final class CollectionsGenericsAndStreams {

    public static void main(String[] args) {
        implementationTradeoffs();
        hashingAndOrdering();
        mutableKeyHazard();
        genericsBoundsAndPecs();
        erasureAndHeapPollution();
        streamLazinessAndCollectors();
        parallelismCaveats();
    }

    // --- picking an implementation ---------------------------------------------

    private static void implementationTradeoffs() {
        // ArrayList: O(1) random access, O(n) middle insert. LinkedList is rarely the answer.
        List<Integer> list = new ArrayList<>(List.of(1, 2, 3));
        list.add(1, 99);
        check("ArrayList indexes directly and shifts on insert",
                list.get(1) == 99 && list.get(3) == 3);

        // HashMap: unordered. LinkedHashMap: insertion (or access) order. TreeMap: sorted by key.
        check("HashMap makes no ordering promise",
                new HashMap<>(Map.of("b", 2, "a", 1)).containsKey("a"));
        check("LinkedHashMap preserves insertion order",
                List.copyOf(insertionOrdered().keySet()).equals(List.of("z", "a", "m")));
        check("TreeMap keeps keys in comparator order",
                List.copyOf(new TreeMap<>(Map.of("z", 1, "a", 2, "m", 3)).keySet())
                        .equals(List.of("a", "m", "z")));

        // Deque replaces both Stack and LinkedList-as-queue.
        Deque<String> stack = new ArrayDeque<>();
        stack.push("first");
        stack.push("second");
        check("ArrayDeque is the modern stack and queue", stack.pop().equals("second"));

        NavigableSet<Integer> sorted = new TreeSet<>(List.of(10, 20, 30));
        check("navigable sets answer range questions",
                sorted.ceiling(15) == 20 && sorted.headSet(30).size() == 2);

        check("concurrent maps trade a global lock for per-bin work",
                new ConcurrentHashMap<>(Map.of("k", 1)).get("k") == 1);
    }

    private static Map<String, Integer> insertionOrdered() {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("z", 1);
        map.put("a", 2);
        map.put("m", 3);
        return map;
    }

    // --- equality, hashing, ordering ---------------------------------------------

    static final class Version implements Comparable<Version> {
        private final int major;
        private final int minor;

        Version(int major, int minor) { this.major = major; this.minor = minor; }

        @Override public boolean equals(Object other) {
            return other instanceof Version
                    && major == ((Version) other).major
                    && minor == ((Version) other).minor;
        }

        @Override public int hashCode() { return Objects.hash(major, minor); }

        @Override public int compareTo(Version other) {
            return Comparator.comparingInt((Version v) -> v.major)
                    .thenComparingInt(v -> v.minor)
                    .compare(this, other);
        }

        @Override public String toString() { return major + "." + minor; }
    }

    private static void hashingAndOrdering() {
        Set<Version> versions = new HashSet<>(List.of(new Version(1, 0), new Version(1, 0)));
        check("consistent equals/hashCode collapses duplicates", versions.size() == 1);

        List<Version> ordered = new ArrayList<>(List.of(new Version(2, 1), new Version(1, 9)));
        ordered.sort(Comparator.naturalOrder());
        check("natural order follows compareTo", ordered.get(0).equals(new Version(1, 9)));

        // Comparators must be consistent with equals when used in a sorted set.
        Set<Version> byMajorOnly = new TreeSet<>(Comparator.comparingInt((Version v) -> v.major));
        byMajorOnly.add(new Version(1, 0));
        byMajorOnly.add(new Version(1, 9));
        check("a TreeSet dedupes by the comparator, not by equals", byMajorOnly.size() == 1);

        check("nulls need an explicit policy in comparators",
                Comparator.nullsFirst(Comparator.<String>naturalOrder()).compare(null, "a") < 0);
    }

    private static void mutableKeyHazard() {
        List<String> key = new ArrayList<>(List.of("stable"));
        Set<List<String>> set = new HashSet<>();
        set.add(key);

        key.add("mutated"); // the hash changed after insertion

        check("a mutated key is stranded in its old bucket", !set.contains(key));
        check("the element is still in the set, just unreachable by lookup", set.size() == 1);
    }

    // --- generics: bounds, wildcards, PECS ------------------------------------------

    /** Producer extends: read T values out of the source. */
    private static double sumOf(List<? extends Number> producer) {
        double total = 0;
        for (Number number : producer) {
            total += number.doubleValue();
        }
        return total;
    }

    /** Consumer super: write T values into the sink. */
    private static void fillWithSquares(List<? super Integer> consumer, int count) {
        for (int i = 1; i <= count; i++) {
            consumer.add(i * i);
        }
    }

    /** A bounded type parameter, not a wildcard: the caller needs the exact type back. */
    private static <T extends Comparable<T>> T maxOf(List<T> values) {
        if (values.isEmpty()) throw new IllegalArgumentException("empty");
        T best = values.get(0);
        for (T value : values) {
            if (value.compareTo(best) > 0) best = value;
        }
        return best;
    }

    private static void genericsBoundsAndPecs() {
        check("a producer accepts any subtype list", sumOf(List.of(1, 2.5, 3L)) == 6.5);

        List<Number> sink = new ArrayList<>();
        fillWithSquares(sink, 3);
        check("a consumer accepts any supertype list", sink.equals(List.of(1, 4, 9)));

        check("bounded type parameters keep the caller's type",
                maxOf(List.of(new Version(1, 2), new Version(1, 10))).toString().equals("1.10"));
        // List<Object> is not a supertype of List<String>: generics are invariant.
        check("invariance is what makes the wildcards necessary",
                List.of("a").getClass() == List.of(1).getClass());
    }

    // --- erasure and heap pollution ---------------------------------------------------

    @SafeVarargs
    private static <T> List<T> listOfAll(T... values) { // @SafeVarargs: this body never stores into the array
        List<T> copy = new ArrayList<>(values.length);  // and never leaks the array itself
        for (T value : values) {
            copy.add(value);
        }
        return List.copyOf(copy);
    }

    private static void erasureAndHeapPollution() {
        List<String> strings = new ArrayList<>();
        List<Integer> integers = new ArrayList<>();
        check("type arguments are erased at runtime", strings.getClass() == integers.getClass());

        // Arrays are covariant and reified; generics are invariant and erased. Mixing them fails.
        Object[] array = new String[1];
        try {
            array[0] = 42;
            check("array covariance fails at runtime", false);
        } catch (ArrayStoreException expected) {
            check("array covariance fails at runtime", true);
        }

        check("prefer generic collections over generic arrays",
                listOfAll("a", "b").size() == 2);
    }

    // --- streams: laziness, collectors, short-circuiting ---------------------------------

    record Employee(String name, String team, int salary) { }

    private static final List<Employee> STAFF = List.of(
            new Employee("ada", "platform", 180),
            new Employee("linus", "platform", 170),
            new Employee("grace", "data", 190),
            new Employee("alan", "data", 150));

    private static void streamLazinessAndCollectors() {
        List<String> touched = new ArrayList<>();
        java.util.Optional<Employee> first = STAFF.stream()
                .peek(employee -> touched.add(employee.name()))  // peek is for observing, not for effects
                .filter(employee -> employee.salary() > 160)
                .findFirst();                                    // short-circuits

        check("nothing runs until a terminal operation", first.isPresent());
        check("elements are pulled one at a time, not staged per operation", touched.size() == 1);

        Map<String, List<String>> namesByTeam = STAFF.stream()
                .collect(Collectors.groupingBy(Employee::team,
                        Collectors.mapping(Employee::name, Collectors.toList())));
        check("groupingBy with a downstream collector avoids a second pass",
                namesByTeam.get("data").equals(List.of("grace", "alan")));

        Map<String, Integer> payrollByTeam = STAFF.stream()
                .collect(Collectors.groupingBy(Employee::team,
                        Collectors.summingInt(Employee::salary)));
        check("summing collectors keep the primitive path", payrollByTeam.get("platform") == 350);

        Map<Boolean, Long> senior = STAFF.stream()
                .collect(Collectors.partitioningBy(employee -> employee.salary() >= 180,
                        Collectors.counting()));
        check("partitioningBy always has both keys", senior.get(true) == 2L && senior.get(false) == 2L);

        check("toMap needs a merge function when keys can collide",
                STAFF.stream()
                        .collect(Collectors.toMap(Employee::team, Employee::salary, Integer::sum))
                        .get("data") == 340);

        check("streams are single-use", isConsumed(STAFF.stream()));

        check("primitive streams avoid boxing in numeric pipelines",
                IntStream.rangeClosed(1, 100).sum() == 5050);

        Function<Employee, String> label = employee -> employee.name() + "@" + employee.team();
        check("map then join is clearer than manual string building",
                STAFF.stream().limit(2).map(label).collect(Collectors.joining(", "))
                        .equals("ada@platform, linus@platform"));
    }

    private static boolean isConsumed(Stream<Employee> stream) {
        stream.count();
        try {
            stream.count();
            return false;
        } catch (IllegalStateException expected) {
            return true;
        }
    }

    // --- parallel streams: only when the work justifies the split -----------------------

    private static void parallelismCaveats() {
        int sequential = IntStream.rangeClosed(1, 1_000).sum();
        int parallel = IntStream.rangeClosed(1, 1_000).parallel().sum();
        check("an associative reduction is safe in parallel", sequential == parallel);

        // Ordered collection into a list keeps encounter order; shared mutable state does not.
        List<Integer> collected = IntStream.rangeClosed(1, 100).parallel().boxed()
                .collect(Collectors.toList());
        check("collect preserves encounter order even in parallel",
                collected.get(0) == 1 && collected.get(99) == 100);
        // Never do: forEach(list::add) on an ArrayList in a parallel stream. Use forEachOrdered
        // or a collector, both of which define what happens across threads.
    }

    // --- tiny assertion helper (assertions are off by default) --------------------------

    private static void check(String claim, boolean holds) {
        if (!holds) throw new AssertionError("failed: " + claim);
        System.out.println("ok  " + claim);
    }
}
