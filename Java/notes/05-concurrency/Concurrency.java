import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Concurrency.
 *
 * <p>Every section states the correctness property it is protecting and then proves it
 * with a bounded, repeatable exercise:
 * <ul>
 *   <li>atomicity — a lost-update race, then three correct fixes</li>
 *   <li>visibility — a happens-before edge published through a volatile write</li>
 *   <li>executors — bounded queues, rejection, cancellation, and clean shutdown</li>
 *   <li>composition — {@code CompletableFuture} results and failures</li>
 *   <li>deadlock — why a global lock order removes it</li>
 *   <li>virtual threads — cheap blocking, with a semaphore guarding a scarce resource</li>
 * </ul>
 *
 * <p>Requires Java 21 (virtual threads). Run with: {@code java Concurrency.java}
 */
public final class Concurrency {

    private static final int THREADS = 8;
    private static final int INCREMENTS_PER_THREAD = 10_000;

    public static void main(String[] args) throws Exception {
        lostUpdateAndItsFixes();
        visibilityAndHappensBefore();
        boundedPoolRejectionAndShutdown();
        cancellation();
        completableFutureComposition();
        lockOrderingAvoidsDeadlock();
        virtualThreadsWithBoundedAccess();
    }

    // --- atomicity: ++ is read-modify-write ------------------------------------

    private static class Counters {
        int plain;                               // unsafe
        int guarded;                             // safe under the lock below
        final AtomicInteger atomic = new AtomicInteger();
        final LongAdder adder = new LongAdder(); // scales better under heavy contention
        final ReentrantLock lock = new ReentrantLock();
    }

    private static void lostUpdateAndItsFixes() throws InterruptedException {
        Counters counters = new Counters();
        runConcurrently(THREADS, () -> {
            for (int i = 0; i < INCREMENTS_PER_THREAD; i++) {
                counters.plain++;                        // lost updates
                counters.lock.lock();
                try {
                    counters.guarded++;                  // mutual exclusion
                } finally {
                    counters.lock.unlock();              // always in a finally
                }
                counters.atomic.incrementAndGet();       // one CAS
                counters.adder.increment();              // striped cells
            }
        });

        int expected = THREADS * INCREMENTS_PER_THREAD;
        check("an explicit lock preserves every update", counters.guarded == expected);
        check("an atomic makes the read-modify-write indivisible", counters.atomic.get() == expected);
        check("LongAdder sums its cells at the end", counters.adder.sum() == expected);
        // Not asserted as a failure: a race is allowed to produce the right answer by luck.
        System.out.println("note  unsynchronized counter reached " + counters.plain
                + " of " + expected + " (a race, not a guarantee)");
    }

    // --- visibility: publishing safely -------------------------------------------

    private static int payload;            // plain field, published through the volatile below
    private static volatile boolean ready; // the volatile write/read is the happens-before edge

    private static void visibilityAndHappensBefore() throws InterruptedException {
        payload = 0;
        ready = false;

        Thread writer = new Thread(() -> {
            payload = 42;   // 1: plain write
            ready = true;   // 2: volatile write publishes everything before it
        });
        Thread reader = new Thread(() -> {
            while (!ready) { Thread.onSpinWait(); } // 3: volatile read
            if (payload != 42) throw new AssertionError("torn publication"); // 4: guaranteed to see 42
        });

        reader.start();
        writer.start();
        writer.join();
        reader.join();

        check("a volatile write/read pair orders the plain write before the read", payload == 42);
        check("join() also establishes happens-before", !reader.isAlive());
    }

    // --- executors: bounded work, rejection, shutdown --------------------------------

    private static void boundedPoolRejectionAndShutdown() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1, 1,                                   // one worker
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),            // bounded: backpressure instead of OOM
                new ThreadPoolExecutor.AbortPolicy());  // fail loudly rather than silently dropping

        try {
            pool.execute(() -> await(release)); // occupies the worker
            pool.execute(() -> { });            // fills the queue

            boolean rejected = false;
            try {
                pool.execute(() -> { });        // nowhere left to put it
            } catch (RejectedExecutionException expected) {
                rejected = true;
            }
            check("a bounded queue rejects instead of growing without limit", rejected);
        } finally {
            release.countDown();
            pool.shutdown();                                     // stop accepting new work
            boolean drained = pool.awaitTermination(5, TimeUnit.SECONDS);
            if (!drained) pool.shutdownNow();                    // then interrupt what is left
            check("shutdown drains submitted work before terminating", drained);
        }
    }

    // --- cancellation: interruption is cooperative --------------------------------------

    private static void cancellation() throws InterruptedException {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        try {
            Future<String> task = executor.submit(() -> {
                started.countDown();
                try {
                    Thread.sleep(Duration.ofSeconds(30).toMillis());
                    return "finished";
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); // restore the flag for the caller
                    throw interrupted;
                }
            });

            started.await();
            check("cancel(true) interrupts a blocked task", task.cancel(true));
            check("a cancelled future reports it", task.isCancelled() && task.isDone());
        } finally {
            executor.shutdownNow();
            check("the pool terminates once the task unblocks",
                    executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    // --- CompletableFuture: composition and failure ---------------------------------------

    private static void completableFutureComposition() throws Exception {
        ExecutorService io = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Integer> price = CompletableFuture.supplyAsync(() -> 100, io);
            CompletableFuture<Integer> taxRate = CompletableFuture.supplyAsync(() -> 8, io);

            CompletableFuture<Integer> total = price
                    .thenCombine(taxRate, (base, rate) -> base + base * rate / 100)
                    .thenApply(amount -> amount + 5); // shipping

            check("independent stages combine without blocking either one",
                    total.get(5, TimeUnit.SECONDS) == 113);

            CompletableFuture<String> recovered = CompletableFuture
                    .<String>supplyAsync(() -> { throw new IllegalStateException("upstream down"); }, io)
                    .exceptionally(error -> "fallback:" + error.getCause().getMessage());
            check("exceptionally turns a failure into a value",
                    recovered.get(5, TimeUnit.SECONDS).equals("fallback:upstream down"));

            CompletableFuture<String> failing = CompletableFuture
                    .supplyAsync(() -> { throw new IllegalStateException("still down"); }, io);
            try {
                failing.get(5, TimeUnit.SECONDS);
                check("get wraps the cause in ExecutionException", false);
            } catch (ExecutionException expected) {
                check("get wraps the cause in ExecutionException",
                        expected.getCause() instanceof IllegalStateException);
            }
        } finally {
            io.shutdown();
        }
    }

    // --- deadlock: order the locks, or do not hold two ---------------------------------------

    private record Account(String id, ReentrantLock lock, AtomicInteger balance) { }

    /** Locks are always taken in id order, so no cycle can form. */
    private static void transfer(Account from, Account to, int amount) {
        Account first = from.id().compareTo(to.id()) < 0 ? from : to;
        Account second = first == from ? to : from;
        first.lock().lock();
        try {
            second.lock().lock();
            try {
                from.balance().addAndGet(-amount);
                to.balance().addAndGet(amount);
            } finally {
                second.lock().unlock();
            }
        } finally {
            first.lock().unlock();
        }
    }

    private static void lockOrderingAvoidsDeadlock() throws InterruptedException {
        Account a = new Account("a", new ReentrantLock(), new AtomicInteger(1_000));
        Account b = new Account("b", new ReentrantLock(), new AtomicInteger(1_000));

        runConcurrently(4, () -> {
            for (int i = 0; i < 5_000; i++) {
                if (ThreadLocalRandom.current().nextBoolean()) {
                    transfer(a, b, 1); // opposite directions would deadlock without the ordering
                } else {
                    transfer(b, a, 1);
                }
            }
        });

        check("no transfer is lost and none deadlocks",
                a.balance().get() + b.balance().get() == 2_000);
    }

    // --- virtual threads: many blocking tasks, few scarce permits ---------------------------

    private static void virtualThreadsWithBoundedAccess() throws Exception {
        int tasks = 1_000;
        int permits = 4;                                   // the real limit: a downstream pool
        Semaphore downstream = new Semaphore(permits);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        Map<String, Boolean> seenVirtual = new ConcurrentHashMap<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < tasks; i++) {
                int value = i;
                results.add(executor.submit(() -> {
                    seenVirtual.put("virtual", Thread.currentThread().isVirtual());
                    downstream.acquire();                  // the pool is unbounded; the resource is not
                    try {
                        peak.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
                        Thread.sleep(1);                   // blocking unmounts a virtual thread
                        return value;
                    } finally {
                        concurrent.decrementAndGet();
                        downstream.release();
                    }
                }));
            }

            long sum = 0;
            for (Future<Integer> result : results) {
                sum += result.get(30, TimeUnit.SECONDS);
            }
            check("every task completed", sum == (long) tasks * (tasks - 1) / 2);
        } // close() waits for all tasks: the executor is itself the scope

        check("tasks ran on virtual threads", Boolean.TRUE.equals(seenVirtual.get("virtual")));
        check("the semaphore, not the thread count, caps downstream load", peak.get() <= permits);
    }

    // --- helpers ---------------------------------------------------------------------------

    private static void runConcurrently(int threads, Runnable body) throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread worker = new Thread(() -> {
                await(start);        // maximize overlap
                body.run();
            });
            workers.add(worker);
            worker.start();
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting", interrupted);
        }
    }

    private static void check(String claim, boolean holds) {
        if (!holds) throw new AssertionError("failed: " + claim);
        System.out.println("ok  " + claim);
    }
}
