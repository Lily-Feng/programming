# Programming Review and Learning

This repository is a workspace for reviewing core concepts, practicing problems, and building small projects across five languages and technologies:

- SQL
- Python
- Java
- Go
- Rust

## Learning goals

- Refresh language fundamentals and standard-library knowledge.
- Practice data structures, algorithms, and problem-solving patterns.
- Record useful examples, notes, and common pitfalls.
- Build small projects that turn concepts into working code.
- Compare how similar ideas are expressed across languages.

## Planned structure

```text
.
├── sql/       # Queries, schemas, exercises, and database concepts
├── python/    # Language review, exercises, and projects
├── java/      # Language review, exercises, and projects
├── go/        # Language review, exercises, and projects
└── rust/      # Language review, exercises, and projects
```

Each topic can grow independently while following a simple pattern:

```text
<topic>/
├── notes/
├── exercises/
└── projects/
```

## Progress

<!-- learning-progress:start -->
| Topic | Fundamentals | Exercises | Project |
| --- | --- | --- | --- |
| SQL | Not started | Not started | Not started |
| Python | Not started | Not started | Not started |
| Java | Not started | Not started | Not started |
| Go | Not started | Not started | Not started |
| Rust | Not started | Not started | Not started |
<!-- learning-progress:end -->

The machine-readable source for this table is `learning-progress.json`.

## Agentic workflow

The detailed learning plan lives locally at `.learning/plan.md`. The entire `.learning/` directory is ignored and protected by repository checks so personal goals, dates, and reflections are never committed. Public agents should follow `AGENTS.md`.

Enable the versioned Git hooks once after cloning:

```sh
git config core.hooksPath .githooks
```

To update progress as part of a commit, add an exact `Learning-Progress` trailer. For example:

```sh
git commit -m "Add Python fundamentals examples" \
  -m "Learning-Progress: python fundamentals complete"
```

Valid topics are `sql`, `python`, `java`, `go`, and `rust`. Valid areas are `fundamentals`, `exercises`, and `project`; valid states are `not_started`, `in_progress`, `review`, and `complete`.

The hooks update both progress files automatically. A `complete` update is rejected unless the commit includes a shareable example in the corresponding `notes/`, `exercises/`, or `projects/` directory. Run the same checks manually with:

```sh
python3 scripts/check_learning_repo.py
```

For recurring reminders, use the task prompt in `automation/learning-reminder.md` with a scheduled agent that can access this local checkout.

## Working conventions

- Keep examples small and runnable.
- Include a short explanation with each non-obvious solution.
- Add tests when an exercise has meaningful edge cases.
- Prefer clear, idiomatic code over clever code.
- Update the progress table as topics are completed.
