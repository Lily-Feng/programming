#!/usr/bin/env python3
"""Validate privacy boundaries and public learning progress."""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path, PurePosixPath


ROOT = Path(__file__).resolve().parents[1]
PRIVATE_DIRECTORIES = (".learning", ".private")
PRIVATE_FILES = ("PLAN.private.md", "learning-plan.md")
AREA_DIRECTORIES = {
    "fundamentals": "notes",
    "exercises": "exercises",
    "project": "projects",
}


def git_files() -> list[str]:
    result = subprocess.run(
        ["git", "ls-files"],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    )
    return [line for line in result.stdout.splitlines() if line]


def is_private(path: str) -> bool:
    pure = PurePosixPath(path)
    return (
        bool(pure.parts and pure.parts[0] in PRIVATE_DIRECTORIES)
        or pure.name in PRIVATE_FILES
        or pure.name == ".env"
        or pure.name.startswith(".env.") and pure.name != ".env.example"
    )


def main() -> int:
    try:
        files = git_files()
        exposed = [path for path in files if is_private(path)]
        if exposed:
            print("private files must not be tracked:", file=sys.stderr)
            for path in exposed:
                print(f"  {path}", file=sys.stderr)
            return 1

        result = subprocess.run(
            [sys.executable, "scripts/update_progress.py", "--check"],
            cwd=ROOT,
        )
        if result.returncode:
            return result.returncode

        with (ROOT / "learning-progress.json").open(encoding="utf-8") as handle:
            progress = json.load(handle)["topics"]
        for topic, areas in progress.items():
            for area, status in areas.items():
                if status != "complete":
                    continue
                prefix = f"{topic}/{AREA_DIRECTORIES[area]}/"
                evidence = [
                    path
                    for path in files
                    if path.startswith(prefix)
                    and not path.endswith(("/README.md", "/.gitkeep"))
                ]
                if not evidence:
                    print(
                        f"{topic}/{area} is complete but has no public evidence "
                        f"under {prefix}",
                        file=sys.stderr,
                    )
                    return 1
        print("learning repository checks passed")
        return 0
    except (OSError, json.JSONDecodeError, subprocess.CalledProcessError) as error:
        print(f"repository check error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
