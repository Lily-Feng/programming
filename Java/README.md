# Java learning

Topics are represented by runnable code, not by prose. Every file compiles and runs on its
own with the single-file launcher, states the Java version it needs, and asserts the claims
it makes — a claim that cannot be checked does not belong in one.

```sh
java Java/notes/01-language-and-oop/LanguageAndOop.java
```

```text
Java/
├── notes/              # One file per topic; each runs its own checks
├── exercises/          # Problem, constraints, solution, tests, tradeoffs
├── projects/           # Larger work that combines several topics
└── interview-prep/     # Retrieval practice
```

## Notes

| Category | File | Needs |
| --- | --- | --- |
| `01-language-and-oop` | `LanguageAndOop.java` — values and references, initialization order, dispatch versus hiding, covariant returns, equality and defensive copying | 11 |
| `02-modern-java` | `ModernJava.java` — `var`, switch expressions, text blocks, records, sealed types, record patterns, collection factories | 21 |
| `03-standard-library-and-io` | `StandardLibraryAndIo.java` — text and regex, `java.time`, `Optional`, `Path`/`Files` with explicit charsets, `HttpClient` requests | 11 |
| `04-collections-generics-and-streams` | `CollectionsGenericsAndStreams.java` — implementation tradeoffs, hashing and ordering hazards, PECS, erasure, collectors, parallelism | 17 |
| `05-concurrency` | `Concurrency.java` — lost updates, happens-before, bounded pools and rejection, cancellation, `CompletableFuture`, lock ordering, virtual threads | 21 |
| `06-jvm-and-performance` | `JvmAndPerformance.java` — lazy class initialization, the string pool, allocation and GC, stacks, why ad-hoc timing lies, a diagnostics checklist | 17 |
| `07-testing-and-build` | `TestingAndBuild.java` — a minimal test framework built to show what JUnit does, tested production code, Maven and Gradle wiring | 17 |
| `08-backend` | `BackendService.java` — a real HTTP service over the JDK server: validation, error shape, pagination, idempotency, optimistic locking, transaction rollback | 17 |
| `09-production` | `ProductionEngineering.java` — config and secrets, structured logging, metrics, retries with jitter, circuit breaker, bulkhead, rate limiter, health checks, graceful shutdown | 17 |

## Everything else

| Path | What it is |
| --- | --- |
| `exercises/LruCacheExercise.java` | A worked exercise and the template for the rest: problem, constraints, edge cases, solution, tests, tradeoffs |
| `projects/ProjectBacklog.java` | The project backlog as data — topics, deliverables, and failure modes — ranked by what is already covered |
| `interview-prep/InterviewDrills.java` | Questions printed without answers; `--answers` reveals, `--topic 05` filters |
| `interview-prep/java-interview-prep.html` | The same material as a page for reading |

Group new exercises under the same numbered categories as `notes/`. A weak interview answer
becomes a runnable example here, not a longer note.
