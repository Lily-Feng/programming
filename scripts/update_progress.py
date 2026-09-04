#!/usr/bin/env python3
"""Update the public learning progress table from explicit commit metadata."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PROGRESS_PATH = ROOT / "learning-progress.json"
README_PATH = ROOT / "README.md"
START_MARKER = "<!-- learning-progress:start -->"
END_MARKER = "<!-- learning-progress:end -->"
TOPICS = ("sql", "python", "java", "go", "rust")
AREAS = ("fundamentals", "exercises", "project")
STATUSES = {
    "not_started": "Not started",
    "in_progress": "In progress",
    "review": "In review",
    "complete": "Complete",
}
EVIDENCE_DIRECTORIES = {
    "fundamentals": "notes",
    "exercises": "exercises",
    "project": "projects",
}
TOPIC_DIRECTORIES = {"java": "Java"}
TRAILER_PATTERN = re.compile(
    r"^Learning-Progress:\s*"
    r"(sql|python|java|go|rust)\s+"
    r"(fundamentals|exercises|project)\s+"
    r"(not_started|in_progress|review|complete)\s*$",
    re.IGNORECASE,
)


def load_progress() -> dict:
    with PROGRESS_PATH.open(encoding="utf-8") as handle:
        data = json.load(handle)

    if data.get("version") != 1:
        raise ValueError("learning-progress.json must use schema version 1")
    if tuple(data.get("topics", {}).keys()) != TOPICS:
        raise ValueError(f"topics must appear in this order: {', '.join(TOPICS)}")

    for topic in TOPICS:
        values = data["topics"][topic]
        if tuple(values.keys()) != AREAS:
            raise ValueError(
                f"{topic} areas must appear in this order: {', '.join(AREAS)}"
            )
        for area, status in values.items():
            if status not in STATUSES:
                raise ValueError(f"invalid status for {topic}/{area}: {status}")
    return data


def render_table(data: dict) -> str:
    lines = [
        START_MARKER,
        "| Topic | Fundamentals | Exercises | Project |",
        "| --- | --- | --- | --- |",
    ]
    for topic in TOPICS:
        values = data["topics"][topic]
        lines.append(
            "| "
            + " | ".join(
                [topic.upper() if topic == "sql" else topic.title()]
                + [STATUSES[values[area]] for area in AREAS]
            )
            + " |"
        )
    lines.append(END_MARKER)
    return "\n".join(lines)


def readme_with_table(readme: str, table: str) -> str:
    if readme.count(START_MARKER) != 1 or readme.count(END_MARKER) != 1:
        raise ValueError("README progress markers are missing or duplicated")
    before, remainder = readme.split(START_MARKER, 1)
    _, after = remainder.split(END_MARKER, 1)
    return before + table + after


def tracked_files() -> list[str]:
    result = subprocess.run(
        ["git", "ls-files"],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    )
    return [line for line in result.stdout.splitlines() if line]


def require_evidence(topic: str, area: str) -> None:
    topic_directory = TOPIC_DIRECTORIES.get(topic, topic)
    expected_prefix = f"{topic_directory}/{EVIDENCE_DIRECTORIES[area]}/"
    evidence = [
        path
        for path in tracked_files()
        if path.startswith(expected_prefix)
        and not path.endswith(("/README.md", "/.gitkeep"))
    ]
    if not evidence:
        raise ValueError(
            f"cannot mark {topic}/{area} complete: stage at least one shareable "
            f"example under {expected_prefix}"
        )


def updates_from_message(path: Path) -> list[tuple[str, str, str]]:
    updates = []
    for line in path.read_text(encoding="utf-8").splitlines():
        match = TRAILER_PATTERN.fullmatch(line)
        if match:
            updates.append(tuple(value.lower() for value in match.groups()))
    return updates


def write_progress(data: dict) -> None:
    PROGRESS_PATH.write_text(
        json.dumps(data, indent=2) + "\n",
        encoding="utf-8",
    )
    readme = README_PATH.read_text(encoding="utf-8")
    README_PATH.write_text(
        readme_with_table(readme, render_table(data)),
        encoding="utf-8",
    )


def check_progress(data: dict) -> None:
    readme = README_PATH.read_text(encoding="utf-8")
    expected = readme_with_table(readme, render_table(data))
    if readme != expected:
        raise ValueError(
            "README progress is stale; run python3 scripts/update_progress.py --sync"
        )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--sync", action="store_true")
    mode.add_argument("--from-commit-message", type=Path, metavar="PATH")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        data = load_progress()
        if args.check:
            check_progress(data)
            return 0
        if args.sync:
            write_progress(data)
            return 0

        updates = updates_from_message(args.from_commit_message)
        for topic, area, status in updates:
            if status == "complete":
                require_evidence(topic, area)
            data["topics"][topic][area] = status
        if updates:
            write_progress(data)
            subprocess.run(
                ["git", "add", str(PROGRESS_PATH), str(README_PATH)],
                cwd=ROOT,
                check=True,
            )
        return 0
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"learning progress error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
