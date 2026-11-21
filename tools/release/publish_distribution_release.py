#!/usr/bin/env python3
"""
Verifies build artifacts, generates checksums.sha256, ensures an artifact-only
tag on the public distribution repository, publishes the GitHub Release, and
verifies uploaded assets and public download URLs.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
import urllib.request
from typing import Any, Dict, List, Optional


REQUIRED_APKS = [
    "Laterbox-ARM32.apk",
    "Laterbox-ARM64.apk",
    "Laterbox-Universal.apk",
]


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest().lower()


def verify_and_collect_artifacts(artifacts_dir: str) -> Dict[str, Any]:
    if not os.path.isdir(artifacts_dir):
        raise RuntimeError(f"Artifacts directory not found: {artifacts_dir}")

    for apk in REQUIRED_APKS:
        apk_path = os.path.join(artifacts_dir, apk)
        if not os.path.isfile(apk_path) or os.path.getsize(apk_path) <= 0:
            raise RuntimeError(f"Missing or empty required APK: {apk_path}")

    manifest_path = os.path.join(artifacts_dir, "delta-manifest.json")
    if not os.path.isfile(manifest_path):
        raise RuntimeError(f"Missing required manifest: {manifest_path}")

    with open(manifest_path, "r", encoding="utf-8") as f:
        manifest = json.load(f)
    if not isinstance(manifest, dict) or not manifest.get("targetVersion"):
        raise RuntimeError("delta-manifest.json is missing required targetVersion")

    files_to_upload: List[str] = [
        os.path.join(artifacts_dir, apk) for apk in REQUIRED_APKS
    ]
    checksum_rows: List[str] = []

    for apk in REQUIRED_APKS:
        apk_path = os.path.join(artifacts_dir, apk)
        checksum_rows.append(f"{sha256_file(apk_path)}  {apk}")

    seen_patches = set()
    for patch in manifest.get("patches") or []:
        fname = str(patch.get("patchFilename") or "").strip()
        expected_sha = str(patch.get("patchSha256") or "").strip().lower()
        if not fname:
            continue
        patch_path = os.path.join(artifacts_dir, fname)
        if not os.path.isfile(patch_path):
            raise RuntimeError(f"Manifest references missing patch file: {patch_path}")
        actual_sha = sha256_file(patch_path)
        if expected_sha and actual_sha != expected_sha:
            raise RuntimeError(
                f"Patch SHA-256 mismatch for {fname}: {actual_sha} != {expected_sha}"
            )
        if fname not in seen_patches:
            seen_patches.add(fname)
            files_to_upload.append(patch_path)
            checksum_rows.append(f"{actual_sha}  {fname}")

    files_to_upload.append(manifest_path)
    checksums_path = os.path.join(artifacts_dir, "checksums.sha256")
    with open(checksums_path, "w", encoding="utf-8") as f:
        f.write("\n".join(sorted(checksum_rows, key=lambda r: r.split("  ", 1)[1])) + "\n")
    files_to_upload.append(checksums_path)

    return {
        "files": files_to_upload,
        "checksums_path": checksums_path,
        "manifest": manifest,
    }


def gh_api_json(path: str, method: str = "GET", fields: Optional[Dict[str, str]] = None) -> Any:
    cmd = ["gh", "api", path]
    if method != "GET":
        cmd.extend(["-X", method])
    if fields:
        for k, v in fields.items():
            cmd.extend(["-f", f"{k}={v}"])
    raw = subprocess.check_output(cmd, text=True)
    return json.loads(raw) if raw.strip() else None


def ensure_distribution_repo_artifact_tag(distribution_repo: str, tag_name: str) -> str:
    contents = gh_api_json(f"repos/{distribution_repo}/contents/")
    if isinstance(contents, list):
        names = {str(item.get("name") or "") for item in contents if isinstance(item, dict)}
        for forbidden in ("app", "gradle", "gradlew", "build.gradle.kts", "settings.gradle.kts"):
            if forbidden in names:
                raise RuntimeError(
                    f"Refusing to publish to '{distribution_repo}': repository contains source path '{forbidden}'"
                )

    ref = gh_api_json(f"repos/{distribution_repo}/git/ref/heads/main")
    commit_sha = str((ref or {}).get("object", {}).get("sha") or "")
    if not commit_sha:
        raise RuntimeError(f"Could not resolve main branch HEAD in {distribution_repo}")

    try:
        existing_tag = gh_api_json(f"repos/{distribution_repo}/git/ref/tags/{tag_name}")
        existing_sha = str((existing_tag or {}).get("object", {}).get("sha") or "")
        if existing_sha and existing_sha != commit_sha:
            raise RuntimeError(
                f"Tag {tag_name} in {distribution_repo} points to {existing_sha}, expected {commit_sha}"
            )
    except subprocess.CalledProcessError:
        gh_api_json(
            f"repos/{distribution_repo}/git/refs",
            method="POST",
            fields={"ref": f"refs/tags/{tag_name}", "sha": commit_sha},
        )
    return commit_sha


def verify_public_download_url(url: str) -> None:
    req = urllib.request.Request(url, method="HEAD", headers={"User-Agent": "Laterbox-Release-Verifier"})
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            if resp.status not in (200, 301, 302, 303, 307, 308):
                raise RuntimeError(f"Download probe returned HTTP {resp.status} for {url}")
    except urllib.error.HTTPError as exc:
        if exc.code not in (200, 301, 302, 303, 307, 308):
            raise RuntimeError(f"Download probe failed HTTP {exc.code} for {url}") from exc


def publish_release(
    distribution_repo: str,
    tag_name: str,
    title: str,
    notes_file: str,
    artifacts_dir: str,
    dry_run: bool = False,
) -> Dict[str, Any]:
    if not os.path.isfile(notes_file):
        raise RuntimeError(f"Missing sanitized release notes file: {notes_file}")

    collected = verify_and_collect_artifacts(artifacts_dir)
    upload_files: List[str] = collected["files"]

    if dry_run:
        print(f"[DRY-RUN] Verified {len(upload_files)} artifact(s) for {tag_name} -> {distribution_repo}")
        return {"dry_run": True, "tag": tag_name, "files": [os.path.basename(f) for f in upload_files]}

    ensure_distribution_repo_artifact_tag(distribution_repo, tag_name)

    existing_release = None
    try:
        raw = subprocess.check_output(
            ["gh", "release", "view", tag_name, "-R", distribution_repo, "--json", "tagName,assets"],
            text=True,
            stderr=subprocess.DEVNULL,
        )
        existing_release = json.loads(raw)
    except subprocess.CalledProcessError:
        existing_release = None

    if existing_release is None:
        subprocess.check_call(
            [
                "gh",
                "release",
                "create",
                tag_name,
                "-R",
                distribution_repo,
                "--title",
                title,
                "--notes-file",
                notes_file,
            ]
            + upload_files
        )
    else:
        subprocess.check_call(
            [
                "gh",
                "release",
                "edit",
                tag_name,
                "-R",
                distribution_repo,
                "--title",
                title,
                "--notes-file",
                notes_file,
            ]
        )
        subprocess.check_call(
            ["gh", "release", "upload", tag_name, "-R", distribution_repo, "--clobber"] + upload_files
        )

    verified = json.loads(
        subprocess.check_output(
            ["gh", "release", "view", tag_name, "-R", distribution_repo, "--json", "tagName,url,assets"],
            text=True,
        )
    )
    by_name = {a["name"]: a for a in verified.get("assets") or []}
    for path in upload_files:
        name = os.path.basename(path)
        if name not in by_name:
            raise RuntimeError(f"Uploaded release missing asset: {name}")
        if by_name[name]["size"] != os.path.getsize(path):
            raise RuntimeError(f"Asset size mismatch for {name}")
        expected_digest = sha256_file(path)
        remote_digest = str(by_name[name].get("digest") or "").removeprefix("sha256:").lower()
        if remote_digest and remote_digest != expected_digest:
            raise RuntimeError(f"Asset digest mismatch for {name}: {remote_digest} != {expected_digest}")

    universal_url = f"https://github.com/{distribution_repo}/releases/download/{tag_name}/Laterbox-Universal.apk"
    verify_public_download_url(universal_url)
    print(f"Published and verified {tag_name} in {distribution_repo} ({len(upload_files)} assets)")
    return {"dry_run": False, "tag": tag_name, "url": verified.get("url"), "files": len(upload_files)}


def main() -> int:
    parser = argparse.ArgumentParser(description="Publish Laterbox release artifacts to public distribution repo")
    parser.add_argument("--distribution-repo", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--title", required=True)
    parser.add_argument("--notes-file", required=True)
    parser.add_argument("--artifacts-dir", required=True)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    result = publish_release(
        distribution_repo=args.distribution_repo,
        tag_name=args.tag,
        title=args.title,
        notes_file=args.notes_file,
        artifacts_dir=args.artifacts_dir,
        dry_run=args.dry_run,
    )
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
