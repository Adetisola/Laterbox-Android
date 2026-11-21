#!/usr/bin/env python3
"""
Automatically generates polished, sanitized, user-facing release notes in
bullet-point Markdown from git commits since the previous release tag.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
from typing import Dict, List, Optional, Tuple

from tools.release.validate_release_notes import validate_release_notes_text

_CONVENTIONAL_RE = re.compile(
    r"^(?P<kind>[a-zA-Z]+)(?:\((?P<scope>[^)]*)\))?(?:!)?:\s*(?P<msg>.+)$"
)
_TRAILING_TAG_RE = re.compile(r"\s*\(v?\d+\.\d+\.\d+[^)]*\)\s*$")
_LEADING_MILESTONE_RE = re.compile(r"^(?:Milestone\s+\d+|M\d+)\s*:\s*", re.IGNORECASE)
_INTERNAL_PREFIXES = ("chore", "ci", "build", "test", "tests", "style", "docs")
_INTERNAL_SCOPES = {"ci", "test", "tests", "release", "distribution", "deps", "lint"}
_INTERNAL_IDENTIFIERS_RE = re.compile(
    r"\b[A-Za-z0-9_]+(?:ViewModel|Repository|Activity|Fragment|Dao|Entity|Worker)\b"
    r"|\b[A-Za-z0-9_./-]+\.(?:kt|java|py|kts|yml|yaml)\b"
    r"|\b[0-9a-f]{7,40}\b"
    r"|https?://\S+"
    r"|#\d+"
)


def parse_semver_key(tag_name: str) -> Tuple[int, ...]:
    clean = (tag_name or "").strip().lstrip("vV")
    parts: List[int] = []
    for part in clean.split("."):
        digits = "".join(ch for ch in part if ch.isdigit())
        parts.append(int(digits) if digits else 0)
    return tuple(parts)


def find_previous_tag(repo_dir: str, current_tag: str) -> Optional[str]:
    try:
        raw = subprocess.check_output(
            ["git", "-C", repo_dir, "tag", "--list", "v*"],
            text=True,
            stderr=subprocess.DEVNULL,
        )
    except subprocess.CalledProcessError:
        return None

    tags = [t.strip() for t in raw.splitlines() if t.strip() and t.strip() != current_tag]
    if not tags:
        return None
    tags.sort(key=parse_semver_key)
    current_key = parse_semver_key(current_tag)
    earlier = [t for t in tags if parse_semver_key(t) < current_key]
    return earlier[-1] if earlier else tags[-1]


def get_commit_subjects(repo_dir: str, prev_tag: Optional[str], current_ref: str = "HEAD") -> List[str]:
    rev_range = f"{prev_tag}..{current_ref}" if prev_tag else current_ref
    try:
        raw = subprocess.check_output(
            ["git", "-C", repo_dir, "log", rev_range, "--no-merges", "--pretty=format:%s", "--max-count=40"],
            text=True,
            stderr=subprocess.DEVNULL,
        )
        return [line.strip() for line in raw.splitlines() if line.strip()]
    except subprocess.CalledProcessError:
        return []


def sanitize_commit_subject(subject: str) -> Optional[Tuple[str, str]]:
    """
    Returns (category, clean_sentence) or None if the commit is internal-only.
    Categories: 'features', 'improvements', 'fixes'.
    """
    text = (subject or "").strip()
    if not text:
        return None

    lower = text.lower()
    if lower.startswith("bump version") or "bump version to" in lower or "consolidated release" in lower:
        return None

    category = "improvements"
    match = _CONVENTIONAL_RE.match(text)
    if match:
        kind = match.group("kind").lower()
        scope = (match.group("scope") or "").strip().lower()
        body = match.group("msg").strip()
        if kind in _INTERNAL_PREFIXES or scope in _INTERNAL_SCOPES:
            return None
        if kind == "feat":
            category = "features"
        elif kind == "fix":
            category = "fixes"
        else:
            category = "improvements"
    else:
        body = text
        if lower.startswith(("fix ", "fixes ", "fixed ")):
            category = "fixes"
        elif lower.startswith(("add ", "added ", "implement ", "implemented ", "introduce ")):
            category = "features"

    body = _LEADING_MILESTONE_RE.sub("", body)
    body = _TRAILING_TAG_RE.sub("", body)
    body = _INTERNAL_IDENTIFIERS_RE.sub("", body)
    body = re.sub(r"\s{2,}", " ", body).strip(" ,;:-")
    if len(body) < 10:
        return None

    body = body[0].upper() + body[1:]
    if not body.endswith("."):
        body += "."
    return category, body


def build_deterministic_notes(subjects: List[str], version: str) -> str:
    grouped: Dict[str, List[str]] = {
        "features": [],
        "improvements": [],
        "fixes": [],
    }
    seen = set()

    for subj in subjects:
        parsed = sanitize_commit_subject(subj)
        if not parsed:
            continue
        cat, clean = parsed
        key = clean.lower()
        if key in seen:
            continue
        seen.add(key)
        grouped[cat].append(clean)

    sections: List[str] = []
    if grouped["features"]:
        sections.append("## New Features\n" + "\n".join(f"- {item}" for item in grouped["features"][:8]))
    if grouped["improvements"]:
        sections.append("## What's Improved\n" + "\n".join(f"- {item}" for item in grouped["improvements"][:8]))
    if grouped["fixes"]:
        sections.append("## Bug Fixes\n" + "\n".join(f"- {item}" for item in grouped["fixes"][:8]))

    if not sections:
        return (
            f"## What's Improved\n"
            f"- General stability, performance, and reliability improvements in Laterbox v{version}."
        )
    return "\n\n".join(sections)


def polish_with_gemini(draft_markdown: str, version: str, api_key: str) -> Optional[str]:
    if not api_key:
        return None
    prompt = (
        "Rewrite these Android app release notes for Laterbox v"
        + version
        + " into clean, concise, user-facing Markdown bullet points.\n"
        "Rules:\n"
        "- Use only headings from: ## New Features, ## What's Improved, ## Bug Fixes.\n"
        "- Write clear, friendly sentences for end users.\n"
        "- NEVER include class names, filenames (.kt, .py, .yml), commit hashes, PR links, or technical jargon.\n"
        "- Do not invent features not present in the input.\n\n"
        "Input:\n"
        + draft_markdown
    )
    payload = json.dumps(
        {
            "contents": [{"parts": [{"text": prompt}]}],
            "generationConfig": {"temperature": 0.2, "maxOutputTokens": 600},
        }
    ).encode("utf-8")
    url = f"https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent?key={api_key}"
    req = urllib.request.Request(
        url,
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        candidates = data.get("candidates") or []
        parts = ((candidates[0].get("content") or {}).get("parts") or []) if candidates else []
        text = str(parts[0].get("text") or "").strip() if parts else ""
        if text and validate_release_notes_text(text) == []:
            return text
    except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, ValueError, KeyError, IndexError):
        return None
    return None


def generate_release_notes(
    repo_dir: str,
    tag_name: str,
    *,
    gemini_api_key: str = "",
) -> str:
    clean_ver = tag_name.strip().lstrip("vV")
    prev_tag = find_previous_tag(repo_dir, tag_name)
    subjects = get_commit_subjects(repo_dir, prev_tag, "HEAD")
    draft = build_deterministic_notes(subjects, clean_ver)

    if gemini_api_key:
        polished = polish_with_gemini(draft, clean_ver, gemini_api_key)
        if polished:
            return polished

    return draft


def main() -> int:
    parser = argparse.ArgumentParser(description="Auto-generate sanitized Laterbox release notes")
    parser.add_argument("--repo-dir", default=".", help="Path to Android git repository")
    parser.add_argument("--tag", required=True, help="Target release tag (e.g. v1.0.62)")
    parser.add_argument("--output", required=True, help="Output Markdown file path")
    args = parser.parse_args()

    api_key = os.environ.get("GEMINI_API_KEY", "").strip()
    notes = generate_release_notes(args.repo_dir, args.tag, gemini_api_key=api_key)
    errors = validate_release_notes_text(notes)
    if errors:
        for err in errors:
            print(f"::error::{err}", file=sys.stderr)
        return 1

    with open(args.output, "w", encoding="utf-8") as f:
        f.write(notes.strip() + "\n")
    print(f"Generated sanitized release notes for {args.tag} -> {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
