# Agent instructions

## Purpose

Help the owner review SQL, Python, Java, Go, and Rust while keeping the public repository useful to other learners.

## Privacy boundary

- `.learning/` is private local context. Agents may read `.learning/plan.md` when it exists, but must never stage, commit, quote, summarize into public files, paste into issues, or otherwise publish its contents.
- Never weaken or remove the private-plan rules in `.gitignore` or `scripts/check_learning_repo.py`.
- Treat `.private/`, `PLAN.private.md`, `learning-plan.md`, `.env`, and `.env.*` as private too.
- Public files may contain generic topic names and progress states, but not private deadlines, reflections, constraints, or personal notes.

## Learning workflow

1. Read this file and, when available, the private `.learning/plan.md`.
2. Work on one small outcome from the plan.
3. Put shareable notes, exercises, tests, and projects under the matching topic directory.
4. Run the narrowest relevant formatter, test, or query validation.
5. Do not declare an area complete without the owner's explicit confirmation.
6. When the owner confirms completion, use the `Learning-Progress` commit trailer documented in `README.md`. The hooks update public progress only when relevant staged evidence exists.

## Quality bar for public examples

- Keep examples focused, runnable, and idiomatic.
- Explain the concept, prerequisites, and how to run the example.
- Include meaningful edge cases or tests where applicable.
- Do not commit generated output, credentials, personal data, or copied solutions without attribution and permission.
- Prefer a small polished example over a large unfinished one.

## Verification

Run before proposing a commit:

```sh
python3 scripts/check_learning_repo.py
```
