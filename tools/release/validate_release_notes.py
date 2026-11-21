#!/usr/bin/env python3
"""
Validates sanitized user-facing release notes before publishing to the public
Laterbox distribution repository.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from typing import List

FORBIDDEN_PATTERNS = [
    (re.compile(r"github\.com/Adetisola/Laterbox-Android", re.IGNORECASE), "private Android source repo URL"),
    (re.compile(r"github\.com/Adetisola/Laterbox-MovieBox-API", re.IGNORECASE), "private backend repo URL"),
    (re.compile(r"https?://github\.com/[^/\s]+/[^/\s]+/(pull|commit|compare)/", re.IGNORECASE), "internal PR/commit/compare link"),
    (re.compile(r"Full Changelog\s*:", re.IGNORECASE), "raw GitHub Full Changelog footer"),
    (re.compile(r"\*\*What's Changed\*\*", re.IGNORECASE), "raw GitHub auto-generated header"),
    (re.compile(r"\b[0-9a-f]{40}\b"), "full 40-char git commit SHA"),
    (re.compile(r"\b[A-Za-z0-9_]+(?:ViewModel|Repository|Activity|Fragment|Dao|Entity|Worker)\b"), "internal Android class name"),
    (re.compile(r"\b[A-Za-z0-9_./-]+\.(?:kt|java|py|kts|yml|yaml)\b"), "internal source filename"),
]


def validate_release_notes_text(text: str) -> List[str]:
    errors: List[str] = []
    stripped = (text or "").strip()
    if not stripped:
        return ["Release notes file is empty."]
    if len(stripped) < 15:
        errors.append("Release notes are too short to be meaningful user-facing notes.")
    for pattern, label in FORBIDDEN_PATTERNS:
        match = pattern.search(stripped)
        if match:
            errors.append(f"Disallowed {label}: '{match.group(0)}'")
    return errors


def validate_release_notes_file(path: str) -> List[str]:
    if not path or not os.path.isfile(path):
        return [f"Missing release notes file: {path}"]
    with open(path, "r", encoding="utf-8") as f:
        return validate_release_notes_text(f.read())


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate Laterbox public release notes")
    parser.add_argument("--file", required=True, help="Path to sanitized release notes markdown file")
    args = parser.parse_args()

    errors = validate_release_notes_file(args.file)
    if errors:
        for err in errors:
            print(f"::error::{err}", file=sys.stderr)
        return 1
    print(f"Release notes validated cleanly: {args.file}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
