#!/usr/bin/env python3
"""
Migrate backed-up Laterbox releases into a clean, artifact-only public
distribution GitHub repository without transferring source code or commit history.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import subprocess
import sys
import time
from typing import Any, Dict, List, Optional, Tuple

FORBIDDEN_SOURCE_FILES = {
    "app/build.gradle.kts",
    "build.gradle.kts",
    "settings.gradle.kts",
    "gradlew",
}

FORBIDDEN_NOTE_PATTERNS = [
    re.compile(r"github\.com/Adetisola/Laterbox-Android", re.IGNORECASE),
    re.compile(r"github\.com/Adetisola/Laterbox-MovieBox-API", re.IGNORECASE),
    re.compile(r"https?://github\.com/[^/\s]+/[^/\s]+/(pull|commit|compare)/", re.IGNORECASE),
    re.compile(r"Full Changelog\s*:", re.IGNORECASE),
    re.compile(r"\*\*What's Changed\*\*", re.IGNORECASE),
    re.compile(r"\b[0-9a-f]{40}\b"),
]

DISTRIBUTION_README = """# Laterbox Releases

Official public release distribution repository for **Laterbox** for Android.

This repository hosts only signed release APKs, in-app differential update patches, release manifests, SHA-256 checksums, and user-facing release notes.

- **Latest Downloads & Changelog**: See [Releases](../../releases)
- **Checksums**: Each release includes a `checksums.sha256` file and asset SHA-256 digests for verification.
"""


def parse_semver_key(tag_name: str) -> Tuple[int, ...]:
    clean = (tag_name or "").strip().lstrip("vV")
    parts: List[int] = []
    for part in clean.split("."):
        digits = "".join(ch for ch in part if ch.isdigit())
        parts.append(int(digits) if digits else 0)
    return tuple(parts)


def validate_sanitized_notes(notes: str, tag_name: str = "") -> List[str]:
    issues: List[str] = []
    if not notes or not notes.strip():
        issues.append(f"{tag_name}: release notes are empty")
        return issues
    for pat in FORBIDDEN_NOTE_PATTERNS:
        if pat.search(notes):
            issues.append(f"{tag_name}: release notes matched forbidden pattern {pat.pattern}")
    return issues


def resolve_release_notes(
    tag_name: str,
    historical_notes: Dict[str, str],
    notes_dir: Optional[str] = None,
) -> str:
    if notes_dir:
        candidate = os.path.join(notes_dir, f"{tag_name}.md")
        if os.path.isfile(candidate):
            with open(candidate, "r", encoding="utf-8") as f:
                return f.read().strip()
    if tag_name in historical_notes:
        return str(historical_notes[tag_name]).strip()
    clean_ver = tag_name.lstrip("vV")
    return f"## Laterbox v{clean_ver}\n- Maintenance and reliability release."


def gh_api_json(path: str, method: str = "GET", fields: Optional[Dict[str, Any]] = None) -> Any:
    cmd = ["gh", "api", path]
    if method != "GET":
        cmd.extend(["-X", method])
    if fields:
        for k, v in fields.items():
            if isinstance(v, bool):
                cmd.extend(["-F", f"{k}={'true' if v else 'false'}"])
            else:
                cmd.extend(["-f", f"{k}={v}"])
    raw = subprocess.check_output(cmd, text=True)
    return json.loads(raw) if raw.strip() else None


def verify_target_repo_is_artifact_only(target_repo: str) -> str:
    """
    Safety guard: refuses to touch a repository containing Android source files,
    and ensures an artifact-only commit exists on `main`, returning its SHA.
    """
    try:
        tree_items = gh_api_json(f"repos/{target_repo}/contents/")
    except subprocess.CalledProcessError:
        tree_items = []

    if isinstance(tree_items, list):
        names = {str(item.get("name") or "") for item in tree_items if isinstance(item, dict)}
        for forbidden in ("app", "gradle", "gradlew", "build.gradle.kts", "settings.gradle.kts"):
            if forbidden in names:
                raise RuntimeError(
                    f"Refusing to migrate into '{target_repo}': repository contains source path '{forbidden}'!"
                )

    readme_b64 = base64.b64encode(DISTRIBUTION_README.encode("utf-8")).decode("ascii")
    existing_sha: Optional[str] = None
    existing_content = ""
    try:
        readme_obj = gh_api_json(f"repos/{target_repo}/contents/README.md")
        if isinstance(readme_obj, dict):
            existing_sha = readme_obj.get("sha")
            raw_b64 = str(readme_obj.get("content") or "")
            if raw_b64:
                existing_content = base64.b64decode(raw_b64).decode("utf-8", errors="replace")
    except subprocess.CalledProcessError:
        pass

    if existing_content.strip() != DISTRIBUTION_README.strip():
        payload: Dict[str, Any] = {
            "message": "Initialize artifact-only distribution repository README",
            "content": readme_b64,
            "branch": "main",
        }
        if existing_sha:
            payload["sha"] = existing_sha
        gh_api_json(f"repos/{target_repo}/contents/README.md", method="PUT", fields=payload)

    ref = gh_api_json(f"repos/{target_repo}/git/ref/heads/main")
    commit_sha = str((ref or {}).get("object", {}).get("sha") or "")
    if not commit_sha:
        raise RuntimeError(f"Could not resolve main branch HEAD SHA in {target_repo}")
    return commit_sha


def ensure_tag_points_to_commit(target_repo: str, tag_name: str, commit_sha: str) -> None:
    try:
        existing = gh_api_json(f"repos/{target_repo}/git/ref/tags/{tag_name}")
        existing_sha = str((existing or {}).get("object", {}).get("sha") or "")
        if existing_sha == commit_sha:
            return
        if existing_sha:
            raise RuntimeError(
                f"Tag {tag_name} in {target_repo} already points to {existing_sha}, expected {commit_sha}"
            )
    except subprocess.CalledProcessError:
        pass

    gh_api_json(
        f"repos/{target_repo}/git/refs",
        method="POST",
        fields={"ref": f"refs/tags/{tag_name}", "sha": commit_sha},
    )


def get_existing_release(target_repo: str, tag_name: str) -> Optional[Dict[str, Any]]:
    try:
        raw = subprocess.check_output(
            [
                "gh",
                "release",
                "view",
                tag_name,
                "-R",
                target_repo,
                "--json",
                "tagName,name,body,assets,isDraft,isPrerelease",
            ],
            text=True,
            stderr=subprocess.DEVNULL,
        )
        return json.loads(raw)
    except subprocess.CalledProcessError:
        return None


def migrate_releases(
    backup_dir: str,
    target_repo: str,
    notes_json_path: str,
    *,
    notes_dir: Optional[str] = None,
    tags_filter: Optional[List[str]] = None,
    dry_run: bool = False,
    cleanup_assets: bool = False,
) -> Dict[str, Any]:
    with open(os.path.join(backup_dir, "backup-inventory.json"), "r", encoding="utf-8") as f:
        inventory = json.load(f)

    with open(notes_json_path, "r", encoding="utf-8") as f:
        historical_notes = json.load(f)

    releases = list(inventory.get("releases") or [])
    releases.sort(
        key=lambda r: (
            str(r.get("published_at") or ""),
            parse_semver_key(str(r.get("tag_name") or "")),
        )
    )

    if tags_filter:
        wanted = set(tags_filter)
        releases = [r for r in releases if r.get("tag_name") in wanted]

    # Pre-flight validate all release notes before making any remote writes
    validation_errors: List[str] = []
    notes_by_tag: Dict[str, str] = {}
    for rel in releases:
        tag = str(rel.get("tag_name") or "")
        body = resolve_release_notes(tag, historical_notes, notes_dir=notes_dir)
        validation_errors.extend(validate_sanitized_notes(body, tag_name=tag))
        notes_by_tag[tag] = body

    if validation_errors:
        raise RuntimeError("Release note validation failed:\n" + "\n".join(validation_errors))

    summary: Dict[str, Any] = {
        "target_repo": target_repo,
        "dry_run": dry_run,
        "migrated": [],
        "skipped": [],
        "errors": [],
    }

    if dry_run:
        for rel in releases:
            tag = str(rel["tag_name"])
            assets_dir = os.path.join(backup_dir, tag, "assets")
            files = sorted(
                os.path.join(assets_dir, fn)
                for fn in os.listdir(assets_dir)
                if os.path.isfile(os.path.join(assets_dir, fn))
            )
            summary["migrated"].append({"tag": tag, "files": len(files)})
            print(f"[DRY-RUN] Would migrate {tag} ({len(files)} file(s)) to {target_repo}")
        return summary

    artifact_commit_sha = verify_target_repo_is_artifact_only(target_repo)
    print(f"Verified artifact-only main commit in {target_repo}: {artifact_commit_sha}")

    for rel in releases:
        tag = str(rel["tag_name"])
        if rel.get("draft"):
            summary["skipped"].append({"tag": tag, "reason": "draft"})
            continue

        assets_dir = os.path.join(backup_dir, tag, "assets")
        upload_files = sorted(
            os.path.join(assets_dir, fn)
            for fn in os.listdir(assets_dir)
            if os.path.isfile(os.path.join(assets_dir, fn))
        )

        ensure_tag_points_to_commit(target_repo, tag, artifact_commit_sha)
        existing = get_existing_release(target_repo, tag)

        notes_file = os.path.join(backup_dir, tag, "sanitized_notes.md")
        with open(notes_file, "w", encoding="utf-8") as nf:
            nf.write(notes_by_tag[tag] + "\n")

        title = str(rel.get("name") or f"Laterbox {tag}")
        if existing is None:
            cmd = [
                "gh",
                "release",
                "create",
                tag,
                "-R",
                target_repo,
                "--title",
                title,
                "--notes-file",
                notes_file,
            ]
            if rel.get("prerelease"):
                cmd.append("--prerelease")
            cmd.extend(upload_files)
            subprocess.check_call(cmd)
        else:
            subprocess.check_call(
                [
                    "gh",
                    "release",
                    "edit",
                    tag,
                    "-R",
                    target_repo,
                    "--title",
                    title,
                    "--notes-file",
                    notes_file,
                ]
            )
            if upload_files:
                subprocess.check_call(
                    ["gh", "release", "upload", tag, "-R", target_repo, "--clobber"] + upload_files
                )

        # Verify uploaded assets match expected names, sizes, and digests
        verified_view = get_existing_release(target_repo, tag)
        if not verified_view:
            raise RuntimeError(f"Release {tag} missing after upload in {target_repo}")
        uploaded_by_name = {a["name"]: a for a in verified_view.get("assets") or []}
        for local_path in upload_files:
            fname = os.path.basename(local_path)
            if fname not in uploaded_by_name:
                raise RuntimeError(f"{tag}: uploaded release missing asset {fname}")
            if uploaded_by_name[fname]["size"] != os.path.getsize(local_path):
                raise RuntimeError(
                    f"{tag}/{fname}: uploaded size {uploaded_by_name[fname]['size']} != {os.path.getsize(local_path)}"
                )

        summary["migrated"].append({"tag": tag, "assets": len(upload_files)})
        print(f"Migrated & verified {tag} -> {target_repo} ({len(upload_files)} asset(s))")
        if cleanup_assets:
            for local_path in upload_files:
                if os.path.isfile(local_path):
                    os.remove(local_path)
        time.sleep(1.1)

    return summary


def main() -> int:
    parser = argparse.ArgumentParser(description="Migrate Laterbox releases to artifact-only repo")
    parser.add_argument("--backup-dir", required=True, help="Directory created by backup_releases.py")
    parser.add_argument("--target-repo", required=True, help="Target distribution GitHub repo (owner/repo)")
    parser.add_argument(
        "--notes-json",
        default=os.path.join(os.path.dirname(__file__), "sanitized_historical_notes.json"),
        help="Path to sanitized historical release notes JSON",
    )
    parser.add_argument("--notes-dir", default="", help="Optional directory containing vX.Y.Z.md overrides")
    parser.add_argument("--tags", nargs="*", default=None, help="Optional subset of tags to migrate")
    parser.add_argument("--dry-run", action="store_true", help="Validate without modifying GitHub")
    parser.add_argument("--cleanup-assets", action="store_true", help="Remove local asset files after verified upload")
    args = parser.parse_args()

    result = migrate_releases(
        backup_dir=args.backup_dir,
        target_repo=args.target_repo,
        notes_json_path=args.notes_json,
        notes_dir=args.notes_dir or None,
        tags_filter=args.tags,
        dry_run=args.dry_run,
        cleanup_assets=args.cleanup_assets,
    )
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
