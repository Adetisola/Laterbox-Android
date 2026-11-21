#!/usr/bin/env python3
"""
Back up all GitHub Releases, metadata, and release assets from a Laterbox
source repository to a local directory with SHA-256 and APK verification.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
import zipfile
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Tuple


def parse_semver_key(tag_name: str) -> Tuple[int, ...]:
    clean = (tag_name or "").strip().lstrip("vV")
    parts: List[int] = []
    for part in clean.split("."):
        digits = "".join(ch for ch in part if ch.isdigit())
        parts.append(int(digits) if digits else 0)
    return tuple(parts)


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest().lower()


def verify_apk_archive(path: str) -> Tuple[bool, str]:
    try:
        with zipfile.ZipFile(path, "r") as zf:
            if "AndroidManifest.xml" not in zf.namelist():
                return False, "missing AndroidManifest.xml"
        return True, "ok"
    except (zipfile.BadZipFile, OSError) as exc:
        return False, str(exc)


def fetch_releases_via_gh(repo: str) -> List[Dict[str, Any]]:
    raw = subprocess.check_output(
        ["gh", "api", "--paginate", "--slurp", f"repos/{repo}/releases?per_page=100"],
        text=True,
    )
    pages = json.loads(raw)
    releases: List[Dict[str, Any]] = []
    for page in pages:
        if isinstance(page, list):
            releases.extend(r for r in page if isinstance(r, dict))
        elif isinstance(page, dict):
            releases.append(page)
    return releases


def download_release_assets(repo: str, tag_name: str, dest_dir: str) -> None:
    os.makedirs(dest_dir, exist_ok=True)
    subprocess.check_call(
        ["gh", "release", "download", tag_name, "-R", repo, "--dir", dest_dir, "--clobber"]
    )


def format_checksums_file(entries: List[Tuple[str, str]]) -> str:
    lines = [f"{digest}  {name}" for digest, name in sorted(entries, key=lambda x: x[1])]
    return "\n".join(lines) + ("\n" if lines else "")


def backup_repository_releases(
    source_repo: str,
    backup_dir: str,
    *,
    tags_filter: Optional[List[str]] = None,
    skip_download: bool = False,
) -> Dict[str, Any]:
    os.makedirs(backup_dir, exist_ok=True)
    releases = fetch_releases_via_gh(source_repo)
    releases.sort(
        key=lambda r: (
            str(r.get("published_at") or r.get("created_at") or ""),
            parse_semver_key(str(r.get("tag_name") or "")),
        )
    )

    if tags_filter:
        wanted = set(tags_filter)
        releases = [r for r in releases if r.get("tag_name") in wanted]

    with open(os.path.join(backup_dir, "releases_raw.json"), "w", encoding="utf-8") as f:
        json.dump(releases, f, indent=2)

    inventory: Dict[str, Any] = {
        "source_repo": source_repo,
        "backed_up_at": datetime.now(timezone.utc).isoformat(),
        "release_count": len(releases),
        "releases": [],
        "warnings": [],
    }

    for rel in releases:
        tag = str(rel.get("tag_name") or "").strip()
        if not tag:
            continue
        rel_dir = os.path.join(backup_dir, tag)
        assets_dir = os.path.join(rel_dir, "assets")
        os.makedirs(assets_dir, exist_ok=True)

        with open(os.path.join(rel_dir, "release.json"), "w", encoding="utf-8") as f:
            json.dump(rel, f, indent=2)

        raw_assets = rel.get("assets") or []
        if raw_assets and not skip_download:
            download_release_assets(source_repo, tag, assets_dir)

        verified_assets: List[Dict[str, Any]] = []
        checksum_entries: List[Tuple[str, str]] = []

        for asset in raw_assets:
            name = str(asset.get("name") or "").strip()
            expected_size = int(asset.get("size") or 0)
            expected_digest = str(asset.get("digest") or "").removeprefix("sha256:").strip().lower()
            local_path = os.path.join(assets_dir, name)

            exists = os.path.isfile(local_path)
            actual_size = os.path.getsize(local_path) if exists else 0
            actual_sha256 = sha256_file(local_path) if exists else ""

            if not exists:
                inventory["warnings"].append(f"{tag}/{name}: missing after download")
            elif actual_size != expected_size:
                inventory["warnings"].append(
                    f"{tag}/{name}: size mismatch ({actual_size} != {expected_size})"
                )
            elif expected_digest and actual_sha256 != expected_digest:
                inventory["warnings"].append(
                    f"{tag}/{name}: sha256 mismatch ({actual_sha256} != {expected_digest})"
                )

            if exists and name.lower().endswith(".apk"):
                ok, reason = verify_apk_archive(local_path)
                if not ok:
                    inventory["warnings"].append(f"{tag}/{name}: invalid APK ({reason})")

            if exists and (name.lower().endswith(".apk") or name.lower().endswith(".patch")):
                checksum_entries.append((actual_sha256, name))

            verified_assets.append(
                {
                    "name": name,
                    "size": expected_size,
                    "actual_size": actual_size,
                    "sha256": actual_sha256 or expected_digest,
                    "github_digest": expected_digest or None,
                    "browser_download_url": asset.get("browser_download_url"),
                    "exists_locally": exists,
                }
            )

        if checksum_entries:
            checksums_path = os.path.join(assets_dir, "checksums.sha256")
            with open(checksums_path, "w", encoding="utf-8") as f:
                f.write(format_checksums_file(checksum_entries))

        inventory["releases"].append(
            {
                "tag_name": tag,
                "name": rel.get("name"),
                "published_at": rel.get("published_at"),
                "draft": bool(rel.get("draft", False)),
                "prerelease": bool(rel.get("prerelease", False)),
                "asset_count": len(verified_assets),
                "assets": verified_assets,
            }
        )
        print(f"Backed up {tag}: {len(verified_assets)} asset(s) verified")

    inv_path = os.path.join(backup_dir, "backup-inventory.json")
    with open(inv_path, "w", encoding="utf-8") as f:
        json.dump(inventory, f, indent=2)

    print(f"\nBackup complete: {len(inventory['releases'])} releases -> {backup_dir}")
    if inventory["warnings"]:
        print(f"Warnings ({len(inventory['warnings'])}):", file=sys.stderr)
        for w in inventory["warnings"]:
            print(f"  - {w}", file=sys.stderr)
    return inventory


def main() -> int:
    parser = argparse.ArgumentParser(description="Back up Laterbox GitHub releases and assets")
    parser.add_argument("--source-repo", default="Adetisola/Laterbox", help="Source GitHub repo")
    parser.add_argument("--backup-dir", required=True, help="Local directory for backup output")
    parser.add_argument("--tags", nargs="*", default=None, help="Optional subset of tags to back up")
    parser.add_argument("--skip-download", action="store_true", help="Skip asset download (use existing files)")
    args = parser.parse_args()

    inv = backup_repository_releases(
        source_repo=args.source_repo,
        backup_dir=args.backup_dir,
        tags_filter=args.tags,
        skip_download=args.skip_download,
    )
    return 1 if inv["warnings"] else 0


if __name__ == "__main__":
    sys.exit(main())
