import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Exercise: a fixed-capacity LRU cache.
 *
 * <p>This file is also the template for every exercise in this directory. Group exercises
 * under the same numbered categories used in {@code ../notes/} (this one belongs to
 * {@code 04-collections-generics-and-streams}), and keep all five parts:
 *
 * <ol>
 *   <li><b>Problem</b> — stated below, in the terms the caller cares about</li>
 *   <li><b>Constraints and edge cases</b> — listed before any code is written</li>
 *   <li><b>Solution</b> — {@link LruCache}, written to the stated complexity</li>
 *   <li><b>Tests</b> — {@link #tests()}, including every listed edge case</li>
 *   <li><b>Tradeoffs</b> — {@link #tradeoffs()}, including the alternative not taken</li>
 * </ol>
 *
 * <h2>Problem</h2>
 * Implement a cache holding at most {@code capacity} entries. {@code get} returns the value
 * for a key or reports absence; {@code put} inserts or updates. When the cache is full, the
 * next insertion evicts the least recently used entry. Both reads and writes count as use.
 *
 * <h2>Constraints</h2>
 * <ul>
 *   <li>{@code get} and {@code put} must be O(1) — no scanning to find the victim</li>
 *   <li>{@code capacity >= 1}; a smaller capacity is a programming error, not a runtime state</li>
 *   <li>Keys and values are non-null; absence is reported, never encoded as {@code null}</li>
 *   <li>Single-threaded; the concurrent version is a different exercise (see the tradeoffs)</li>
 * </ul>
 *
 * <h2>Edge cases worth a test</h2>
 * <ul>
 *   <li>capacity 1: every insertion evicts</li>
 *   <li>updating an existing key must not grow the cache, but does refresh recency</li>
 *   <li>a {@code get} on the oldest entry must save it from the next eviction</li>
 *   <li>a miss must not change recency order</li>
 *   <li>re-inserting an evicted key is a plain insertion, not a resurrection</li>
 * </ul>
 *
 * <p>Requires Java 17 or later. Run with: {@code java LruCacheExercise.java}
 */
public final class LruCacheExercise {

    public static void main(String[] args) {
        int failures = tests();
        tradeoffs();
        if (failures > 0) throw new AssertionError(failures + " failing case(s)");
    }

    // ======================= solution =======================

    /**
     * Hash map for O(1) lookup, intrusive doubly linked list for O(1) recency updates.
     * The list is written by hand rather than reusing {@link LinkedHashMap} so the
     * mechanism is visible; see {@link #tradeoffs()} for when to just use the JDK.
     */
    static final class LruCache<K, V> {

        private static final class Node<K, V> {
            final K key;
            V value;
            Node<K, V> previous;
            Node<K, V> next;

            Node(K key, V value) { this.key = key; this.value = value; }
        }

        private final int capacity;
        private final Map<K, Node<K, V>> index;
        private final Node<K, V> head = new Node<>(null, null); // sentinels remove every null check
        private final Node<K, V> tail = new Node<>(null, null); // head <-> most recent … least recent <-> tail
        private int evictions;

        LruCache(int capacity) {
            if (capacity < 1) throw new IllegalArgumentException("capacity must be >= 1");
            this.capacity = capacity;
            this.index = new HashMap<>(capacity * 2);
            head.next = tail;
            tail.previous = head;
        }

        boolean containsKey(K key) { return index.containsKey(Objects.requireNonNull(key, "key")); }

        /** @throws NoSuchElementException if absent — absence is a fact, not a null. */
        V get(K key) {
            Node<K, V> node = index.get(Objects.requireNonNull(key, "key"));
            if (node == null) throw new NoSuchElementException("no entry for " + key);
            moveToFront(node);
            return node.value;
        }

        void put(K key, V value) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");

            Node<K, V> existing = index.get(key);
            if (existing != null) {          // update: refresh recency, do not grow
                existing.value = value;
                moveToFront(existing);
                return;
            }
            if (index.size() == capacity) {  // evict before inserting, never after
                Node<K, V> victim = tail.previous;
                unlink(victim);
                index.remove(victim.key);
                evictions++;
            }
            Node<K, V> node = new Node<>(key, value);
            index.put(key, node);
            linkAfterHead(node);
        }

        int size() { return index.size(); }

        int evictions() { return evictions; }

        /** Most recent first — for tests and for reasoning, not for callers to depend on. */
        List<K> keysByRecency() {
            List<K> keys = new ArrayList<>(index.size());
            for (Node<K, V> node = head.next; node != tail; node = node.next) {
                keys.add(node.key);
            }
            return keys;
        }

        private void moveToFront(Node<K, V> node) {
            unlink(node);
            linkAfterHead(node);
        }

        private void unlink(Node<K, V> node) {
            node.previous.next = node.next;
            node.next.previous = node.previous;
        }

        private void linkAfterHead(Node<K, V> node) {
            node.previous = head;
            node.next = head.next;
            head.next.previous = node;
            head.next = node;
        }
    }

    // ======================= tests =======================

    private static int tests() {
        int failures = 0;
        failures += expect("a hit returns the stored value", () -> {
            LruCache<String, Integer> cache = new LruCache<>(2);
            cache.put("a", 1);
            return cache.get("a") == 1;
        });
        failures += expect("a miss is reported, not returned as null", () -> {
            LruCache<String, Integer> cache = new LruCache<>(2);
            try {
                cache.get("absent");
                return false;
            } catch (NoSuchElementException expected) {
                return true;
            }
        });
        failures += expect("capacity 1 evicts on every new key", () -> {
            LruCache<String, Integer> cache = new LruCache<>(1);
            cache.put("a", 1);
            cache.put("b", 2);
            return cache.size() == 1 && !cache.containsKey("a") && cache.get("b") == 2;
        });
        failures += expect("the least recently used entry is the victim", () -> {
            LruCache<String, Integer> cache = new LruCache<>(2);
            cache.put("a", 1);
            cache.put("b", 2);
            cache.put("c", 3);                       // evicts "a"
            return !cache.containsKey("a") && cache.keysByRecency().equals(List.of("c", "b"));
        });
        failures += expect("a read refreshes recency and saves the entry", () -> {
            LruCache<String, Integer> cache = new LruCache<>(2);
            cache.put("a", 1);
            cache.put("b", 2);
            cache.get("a");                          // "b" is now the oldest
            cache.put("c", 3);
            return cache.containsKey("a") && !cache.containsKey("b");
        });
        failures += expect("updating an existing key does not grow the cache", () -> {
            LruCache<String, Integer> cache = new LruCache<>(2);
            cache.put("a", 1);
            cache.put("b", 2);
            cache.put("a", 10);                      // update, not insert
            return cache.size() == 2 && cache.evictions() == 0 && cache.get("a") == 10;
        });
        failures += expect("an update also refreshes recency", () -> {
            LruCache<String, Integer> cache = new LruCache<>(2);
            cache.put("a", 1);
            cache.put("b", 2);
            cache.put("a", 10);
            cache.put("c", 3);                       // evicts "b", not "a"
            return cache.containsKey("a") && !cache.containsKey("b");
        });
        failures += expect("a miss leaves recency untouched", () -> {
            LruCache<String, Integer> cache = new LruCache<>(2);
            cache.put("a", 1);
            cache.put("b", 2);
            try {
                cache.get("absent");
            } catch (NoSuchElementException ignored) {
                // a miss is not a use
            }
            return cache.keysByRecency().equals(List.of("b", "a"));
        });
        failures += expect("re-inserting an evicted key is a plain insertion", () -> {
            LruCache<String, Integer> cache = new LruCache<>(1);
            cache.put("a", 1);
            cache.put("b", 2);
            cache.put("a", 3);
            return cache.get("a") == 3 && cache.size() == 1 && cache.evictions() == 2;
        });
        failures += expect("invalid capacity fails at construction", () -> {
            try {
                new LruCache<String, String>(0);
                return false;
            } catch (IllegalArgumentException expected) {
                return true;
            }
        });
        failures += expect("null keys and values are rejected", () -> {
            LruCache<String, String> cache = new LruCache<>(2);
            try {
                cache.put(null, "v");
                return false;
            } catch (NullPointerException expected) {
                return rejects(() -> cache.put("k", null));
            }
        });
        failures += expect("a scan of many keys keeps the size at the capacity", () -> {
            LruCache<Integer, Integer> cache = new LruCache<>(64);
            for (int i = 0; i < 10_000; i++) {
                cache.put(i, i * i);
            }
            return cache.size() == 64 && cache.evictions() == 10_000 - 64
                    && cache.get(9_999) == 9_999 * 9_999;
        });

        System.out.println(failures == 0 ? "all cases passed" : failures + " case(s) failed");
        return failures;
    }

    private static boolean rejects(Runnable body) {
        try {
            body.run();
            return false;
        } catch (NullPointerException expected) {
            return true;
        }
    }

    private static int expect(String description, java.util.function.BooleanSupplier body) {
        boolean passed;
        try {
            passed = body.getAsBoolean();
        } catch (RuntimeException error) {
            System.out.println("FAIL " + description + " — threw " + error);
            return 1;
        }
        System.out.println((passed ? "PASS " : "FAIL ") + description);
        return passed ? 0 : 1;
    }

    // ======================= tradeoffs =======================

    private static void tradeoffs() {
        System.out.println("""

                note  what this solution costs and what it buys
                        time      get/put O(1): one hash lookup plus a constant number of pointer writes
                        space     O(capacity): a map entry and a node with two links per entry
                        why a hand-written list  an array or ArrayDeque would make eviction O(n)
                                  because finding and removing the victim means a scan
                        why sentinels  head/tail nodes remove every null check from link and unlink,
                                  which is where hand-written linked lists usually go wrong

                note  the alternative not taken
                        LinkedHashMap(capacity, 0.75f, true) with removeEldestEntry does all of this
                        in five lines, and is what production code should use. Writing the list out
                        is the exercise; shipping it is not.

                note  what changes under concurrency
                        recency updates make every read a write, so a plain lock serialises reads
                        Caffeine records reads in buffers and applies them in batches instead
                        a ConcurrentHashMap plus a striped or approximate policy (CLOCK, TinyLFU)
                        trades exact LRU order for scalability — usually the right trade

                note  the follow-up questions this exercise sets up
                        add a TTL: eviction then has two reasons, and expiry must be checked on read
                        add a size-based bound (bytes, not entries)
                        add hit-ratio metrics, then measure whether LRU beats plain FIFO on real keys""");
    }
}
