#!/usr/bin/env python3
"""
Laterbox Delta Patch Generator.

Generates BSDIFF40 differential patches from a rolling window of previous stable
Laterbox APK releases directly to a target release, validates that reconstructed
APKs match the target APKs byte-for-byte, enforces size threshold policies
(default: patch must be < 70% of full APK size), and produces one combined
delta-manifest.json for GitHub Releases.
"""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
import sys
import tempfile
import time
from typing import Any, Dict, List, Optional, Tuple
import zipfile

try:
    import bsdiff4
except ImportError:
    print("Error: 'bsdiff4' module is required. Install with: pip install bsdiff4", file=sys.stderr)
    sys.exit(1)

# Default size threshold: patch size must be less than 70% of full APK size
DEFAULT_THRESHOLD_RATIO = 0.70

# Default number of previous published stable releases to generate direct patches from
DEFAULT_ROLLING_WINDOW = 5

# Architecture variants configuration: (label, filename, architecture_id, human_label)
VARIANTS = [
    ("ARM32", "Laterbox-ARM32.apk", "armeabi-v7a", "ARM32 (armeabi-v7a)"),
    ("ARM64", "Laterbox-ARM64.apk", "arm64-v8a", "ARM64 (arm64-v8a)"),
    ("Universal", "Laterbox-Universal.apk", "universal", "Universal"),
]

_VARIANT_BY_ARCH = {arch_id: (label, apk_name, arch_id, human) for label, apk_name, arch_id, human in VARIANTS}


def clean_version(raw: str) -> str:
    """Normalizes a version or tag string by trimming whitespace and leading 'v'."""
    cleaned = (raw or "").strip()
    if cleaned.startswith(("v", "V")):
        cleaned = cleaned[1:].strip()
    return cleaned


def sha256_file(filepath: str) -> str:
    """Computes SHA-256 digest of a file in 64 KB streaming chunks."""
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest().lower()


def is_valid_apk_file(filepath: str, verify_zip: bool = False) -> Tuple[bool, str]:
    """Checks that an APK file exists, is non-empty, and optionally passes ZIP/manifest checks."""
    if not filepath or not os.path.isfile(filepath):
        return False, "file not found"
    try:
        size = os.path.getsize(filepath)
    except OSError as exc:
        return False, f"unable to stat file ({exc})"
    if size <= 0:
        return False, "file is empty (0 bytes)"
    if verify_zip:
        try:
            with zipfile.ZipFile(filepath, "r") as zf:
                if "AndroidManifest.xml" not in zf.namelist():
                    return False, "archive missing AndroidManifest.xml"
        except (zipfile.BadZipFile, OSError) as exc:
            return False, f"invalid APK archive ({exc})"
    return True, "ok"


def _parse_published_timestamp(raw_ts: Optional[str]) -> Optional[float]:
    if not raw_ts or not isinstance(raw_ts, str):
        return None
    normalized = raw_ts.strip()
    if not normalized:
        return None
    if normalized.endswith("Z"):
        normalized = normalized[:-1] + "+00:00"
    try:
        dt = datetime.fromisoformat(normalized)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return dt.timestamp()
    except ValueError:
        return None


def select_rolling_base_releases(
    releases: List[Any],
    current_tag: str = "",
    current_version: str = "",
    window: int = DEFAULT_ROLLING_WINDOW,
) -> List[Dict[str, Any]]:
    """
    Filters GitHub release records to published stable releases (excluding drafts,
    prereleases, and the release currently being built), sorts newest first by actual
    publication timestamp (`published_at`), and returns up to `window` releases.
    """
    if window <= 0 or not releases:
        return []

    # Support flattened or slurped paginated arrays from `gh api --paginate --slurp`
    flat_releases: List[Dict[str, Any]] = []
    for item in releases:
        if isinstance(item, list):
            flat_releases.extend(r for r in item if isinstance(r, dict))
        elif isinstance(item, dict):
            flat_releases.append(item)

    clean_current_tag = (current_tag or "").strip()
    clean_current_ver = clean_version(current_version or current_tag)
    eligible: List[Tuple[float, str, Dict[str, Any]]] = []

    for rel in flat_releases:
        if rel.get("draft") or rel.get("prerelease"):
            continue

        tag_name = str(rel.get("tag_name") or rel.get("tagName") or "").strip()
        if not tag_name:
            continue

        rel_clean_ver = clean_version(tag_name)
        if clean_current_tag and tag_name == clean_current_tag:
            continue
        if clean_current_ver and rel_clean_ver == clean_current_ver:
            continue

        published_raw = rel.get("published_at") or rel.get("publishedAt")
        published_epoch = _parse_published_timestamp(published_raw)
        if published_epoch is None:
            # Skip unpublished releases lacking a valid publication timestamp
            continue

        eligible.append(
            (
                published_epoch,
                tag_name,
                {
                    "tag_name": tag_name,
                    "version": rel_clean_ver,
                    "published_at": str(published_raw),
                },
            )
        )

    eligible.sort(key=lambda item: (item[0], item[1]), reverse=True)
    return [item[2] for item in eligible[:window]]


def _parse_base_release_spec(spec: str) -> Dict[str, Any]:
    raw = (spec or "").strip()
    if "=" in raw:
        meta, base_dir = raw.split("=", 1)
        if ":" in meta:
            ver, code_raw = meta.split(":", 1)
            try:
                vcode = int(code_raw.strip())
            except ValueError:
                vcode = 0
        else:
            ver = meta
            vcode = 0
        return {"dir": base_dir.strip(), "version": clean_version(ver), "version_code": vcode}

    return {
        "dir": raw,
        "version": clean_version(os.path.basename(os.path.normpath(raw))),
        "version_code": 0,
    }


def _unique_patch_filename(
    label: str,
    base_version: str,
    target_version: str,
    base_sha: str,
    used_filenames: set,
) -> str:
    clean_base = clean_version(base_version) or f"base-{base_sha[:8]}"
    clean_base = clean_base.replace("/", "_").replace("\\", "_").replace(" ", "_")
    candidate = f"Laterbox-{label}-{clean_base}-to-{target_version}.patch"
    if candidate not in used_filenames:
        return candidate
    suffix_candidate = f"Laterbox-{label}-{clean_base}-{base_sha[:8]}-to-{target_version}.patch"
    if suffix_candidate not in used_filenames:
        return suffix_candidate
    counter = 2
    while True:
        numbered = f"Laterbox-{label}-{clean_base}-{base_sha[:8]}-{counter}-to-{target_version}.patch"
        if numbered not in used_filenames:
            return numbered
        counter += 1


def _load_existing_valid_patches(
    existing_manifest_path: Optional[str],
    output_dir: str,
    clean_target_ver: str,
    target_info_by_arch: Dict[str, Dict[str, Any]],
) -> List[Dict[str, Any]]:
    if not existing_manifest_path or not os.path.isfile(existing_manifest_path):
        return []
    try:
        with open(existing_manifest_path, "r", encoding="utf-8") as f:
            existing = json.load(f)
    except Exception as exc:
        print(f"Warning: Could not read existing manifest '{existing_manifest_path}': {exc}", file=sys.stderr)
        return []

    if not isinstance(existing, dict) or clean_version(str(existing.get("targetVersion") or "")) != clean_target_ver:
        return []

    kept: List[Dict[str, Any]] = []
    for entry in existing.get("patches") or []:
        if not isinstance(entry, dict):
            continue
        arch = str(entry.get("architecture") or "").strip().lower()
        target_info = target_info_by_arch.get(arch)
        if not target_info:
            continue
        if str(entry.get("targetApkSha256") or "").strip().lower() != target_info["sha256"]:
            continue
        patch_filename = str(entry.get("patchFilename") or "").strip()
        patch_sha = str(entry.get("patchSha256") or "").strip().lower()
        if not patch_filename or not patch_sha:
            continue
        patch_path = os.path.join(output_dir, patch_filename)
        if not os.path.isfile(patch_path):
            continue
        if sha256_file(patch_path) != patch_sha:
            print(f"Warning: Existing patch '{patch_filename}' SHA-256 mismatch; ignoring.", file=sys.stderr)
            continue
        kept.append(entry)
    return kept


def generate_delta_patches(
    prev_dir: Optional[str] = None,
    target_dir: str = "",
    output_dir: str = "",
    target_version: str = "",
    target_version_code: int = 1,
    prev_version: str = "",
    prev_version_code: int = 0,
    threshold_ratio: float = DEFAULT_THRESHOLD_RATIO,
    base_releases: Optional[List[Dict[str, Any]]] = None,
    existing_manifest_path: Optional[str] = None,
    verify_zip: bool = False,
) -> dict:
    """
    Generates and verifies direct delta patches from one or more base release directories
    to the target APKs across all available architectures, accumulating all eligible entries
    into one combined delta-manifest.json.
    """
    os.makedirs(output_dir, exist_ok=True)
    clean_target_ver = clean_version(target_version) or target_version.strip()

    manifest: Dict[str, Any] = {
        "schemaVersion": 1,
        "targetVersion": clean_target_ver,
        "targetVersionCode": target_version_code,
        "generatedAt": int(time.time() * 1000),
        "patches": [],
    }

    target_info_by_arch: Dict[str, Dict[str, Any]] = {}
    for label, apk_filename, arch_id, human_label in VARIANTS:
        target_apk = os.path.join(target_dir, apk_filename)
        ok, reason = is_valid_apk_file(target_apk, verify_zip=verify_zip)
        if not ok:
            print(f"[{label}] Target APK unavailable/invalid at '{target_apk}' ({reason}). Skipping target variant.")
            continue
        target_info_by_arch[arch_id] = {
            "label": label,
            "apk_filename": apk_filename,
            "arch_id": arch_id,
            "human_label": human_label,
            "path": target_apk,
            "sha256": sha256_file(target_apk),
            "size": os.path.getsize(target_apk),
        }

    existing_kept = _load_existing_valid_patches(
        existing_manifest_path=existing_manifest_path,
        output_dir=output_dir,
        clean_target_ver=clean_target_ver,
        target_info_by_arch=target_info_by_arch,
    )
    manifest["patches"].extend(existing_kept)

    seen_keys = {
        (str(p.get("architecture") or "").lower(), str(p.get("baseApkSha256") or "").lower())
        for p in existing_kept
    }
    used_filenames = {str(p.get("patchFilename") or "") for p in existing_kept}

    bases_to_process: List[Dict[str, Any]] = []
    if base_releases is not None:
        bases_to_process = list(base_releases)
    elif prev_dir:
        bases_to_process = [
            {"dir": prev_dir, "version": prev_version, "version_code": prev_version_code}
        ]

    if not bases_to_process:
        print("Notice: No previous base release directories supplied. Writing manifest with existing/empty patches.")
    elif not target_info_by_arch:
        print("Notice: No valid target APKs found. Writing manifest without new patches.")
    else:
        print(
            f"=== Generating Rolling Delta Patches for Laterbox {clean_target_ver} "
            f"({len(bases_to_process)} base release(s), threshold: {threshold_ratio * 100:.0f}%) ==="
        )

        for base_spec in bases_to_process:
            base_dir = str(base_spec.get("dir") or "")
            raw_base_ver = str(base_spec.get("version") or "")
            base_ver = clean_version(raw_base_ver)
            base_vcode = int(base_spec.get("version_code") or 0)

            if not base_dir or not os.path.isdir(base_dir):
                print(
                    f"Notice: Base release directory '{base_dir}' (version={base_ver or 'unknown'}) "
                    "is missing or not a directory. Skipping cleanly."
                )
                continue

            print(f"\n--- Processing base release {base_ver or base_dir} ---")

            for label, apk_filename, arch_id, human_label in VARIANTS:
                target_info = target_info_by_arch.get(arch_id)
                if not target_info:
                    continue

                prev_apk = os.path.join(base_dir, apk_filename)
                ok, reason = is_valid_apk_file(prev_apk, verify_zip=verify_zip)
                if not ok:
                    print(f"[{label} <- {base_ver or 'prev'}] Base APK skipped at '{prev_apk}' ({reason}).")
                    continue

                base_sha = sha256_file(prev_apk)
                target_sha = target_info["sha256"]
                target_size = target_info["size"]
                prev_size = os.path.getsize(prev_apk)

                if (arch_id.lower(), base_sha) in seen_keys:
                    print(
                        f"[{label} <- {base_ver or 'prev'}] Entry already present for base SHA-256 {base_sha[:12]}...; preserving existing entry."
                    )
                    continue

                if base_sha == target_sha:
                    print(f"[{label} <- {base_ver or 'prev'}] Base and Target APKs are identical (SHA-256: {base_sha}). No patch needed.")
                    continue

                patch_filename = _unique_patch_filename(
                    label=label,
                    base_version=base_ver,
                    target_version=clean_target_ver,
                    base_sha=base_sha,
                    used_filenames=used_filenames,
                )
                patch_path = os.path.join(output_dir, patch_filename)

                print(
                    f"[{label} <- {base_ver or 'prev'}] Generating patch: "
                    f"{prev_size / (1024 * 1024):.2f} MB -> {target_size / (1024 * 1024):.2f} MB..."
                )
                t0 = time.time()
                try:
                    bsdiff4.file_diff(prev_apk, target_info["path"], patch_path)
                except Exception as exc:
                    print(f"[{label} <- {base_ver or 'prev'}] Error generating patch: {exc}", file=sys.stderr)
                    if os.path.exists(patch_path):
                        os.remove(patch_path)
                    continue

                diff_time = time.time() - t0
                patch_size = os.path.getsize(patch_path)
                patch_ratio = patch_size / target_size if target_size > 0 else 1.0
                savings = (1.0 - patch_ratio) * 100

                print(
                    f"[{label} <- {base_ver or 'prev'}] Patch generated in {diff_time:.2f}s: "
                    f"{patch_size / (1024 * 1024):.2f} MB ({patch_ratio * 100:.1f}% of full APK size, {savings:.1f}% savings)"
                )

                # Threshold check
                if patch_ratio >= threshold_ratio:
                    print(
                        f"[{label} <- {base_ver or 'prev'}] Patch size ({patch_size} bytes) exceeds threshold "
                        f"({threshold_ratio * 100:.0f}% of {target_size} bytes). Discarding patch."
                    )
                    os.remove(patch_path)
                    continue

                # Verification step: reconstruct and verify byte-for-byte SHA-256 match
                print(f"[{label} <- {base_ver or 'prev'}] Verifying patch reconstruction...")
                with tempfile.NamedTemporaryFile(suffix=".apk", delete=False) as recon_tmp:
                    recon_path = recon_tmp.name

                verified = False
                try:
                    t1 = time.time()
                    bsdiff4.file_patch(prev_apk, recon_path, patch_path)
                    recon_time = time.time() - t1
                    recon_sha = sha256_file(recon_path)

                    if recon_sha != target_sha:
                        print(
                            f"[{label} <- {base_ver or 'prev'}] CRITICAL: Reconstructed APK SHA-256 ({recon_sha}) "
                            f"does not match target APK SHA-256 ({target_sha})!",
                            file=sys.stderr,
                        )
                        os.remove(patch_path)
                    else:
                        print(
                            f"[{label} <- {base_ver or 'prev'}] Verified byte-for-byte match in {recon_time:.2f}s! "
                            f"(SHA-256: {recon_sha})"
                        )
                        verified = True
                except Exception as exc:
                    print(
                        f"[{label} <- {base_ver or 'prev'}] Error verifying patch reconstruction: {exc}",
                        file=sys.stderr,
                    )
                    if os.path.exists(patch_path):
                        os.remove(patch_path)
                finally:
                    if os.path.exists(recon_path):
                        os.remove(recon_path)

                if not verified:
                    continue

                patch_sha = sha256_file(patch_path)
                patch_entry = {
                    "architecture": arch_id,
                    "baseArchitecture": arch_id,
                    "architectureLabel": human_label,
                    "baseVersion": base_ver or "previous",
                    "baseVersionCode": base_vcode,
                    "baseApkSha256": base_sha,
                    "targetApkFilename": apk_filename,
                    "targetApkSha256": target_sha,
                    "targetApkSize": target_size,
                    "patchFilename": patch_filename,
                    "patchSha256": patch_sha,
                    "patchSize": patch_size,
                    "patchAlgorithm": "BSDIFF40",
                }
                manifest["patches"].append(patch_entry)
                seen_keys.add((arch_id.lower(), base_sha))
                used_filenames.add(patch_filename)
                print(f"[{label} <- {base_ver or 'prev'}] Added patch to manifest: {patch_filename} ({patch_size} bytes)")

    manifest_path = os.path.join(output_dir, "delta-manifest.json")
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)

    print(f"\nSuccessfully wrote manifest with {len(manifest['patches'])} eligible patch(es) to {manifest_path}")
    return manifest


def main():
    parser = argparse.ArgumentParser(description="Generate and verify Laterbox delta APK patches")
    parser.add_argument(
        "--select-bases",
        action="store_true",
        help="Select eligible rolling-window base releases from --releases-json and print JSON to stdout",
    )
    parser.add_argument("--releases-json", default="", help="Path to GitHub releases JSON array file (for --select-bases)")
    parser.add_argument("--current-tag", default="", help="Tag name currently being built (excluded in --select-bases)")
    parser.add_argument("--window", type=int, default=DEFAULT_ROLLING_WINDOW, help="Rolling window size (default: 5)")

    parser.add_argument("--prev-dir", default="", help="Directory containing a single previous release's APKs")
    parser.add_argument(
        "--base-release",
        action="append",
        default=[],
        help="Base release spec VERSION=DIR or VERSION:VERSION_CODE=DIR (repeatable)",
    )
    parser.add_argument("--target-dir", default="", help="Directory containing target release APKs")
    parser.add_argument("--output-dir", default="", help="Directory where patches and manifest will be written")
    parser.add_argument("--target-version", default="", help="Target release version name (e.g. 1.0.55)")
    parser.add_argument("--target-version-code", type=int, default=1, help="Target release version code")
    parser.add_argument("--prev-version", default="", help="Previous release version name (for --prev-dir)")
    parser.add_argument("--prev-version-code", type=int, default=0, help="Previous release version code (for --prev-dir)")
    parser.add_argument("--existing-manifest", default="", help="Optional existing delta-manifest.json to merge with")
    parser.add_argument("--verify-zip", action="store_true", help="Verify APK files are valid ZIPs with AndroidManifest.xml")
    parser.add_argument("--threshold-ratio", type=float, default=DEFAULT_THRESHOLD_RATIO, help="Patch size threshold ratio (default: 0.70)")

    args = parser.parse_args()

    if args.select_bases:
        if not args.releases_json or not os.path.isfile(args.releases_json):
            print("[]")
            return
        with open(args.releases_json, "r", encoding="utf-8") as f:
            raw_releases = json.load(f)
        selected = select_rolling_base_releases(
            releases=raw_releases if isinstance(raw_releases, list) else [],
            current_tag=args.current_tag,
            current_version=args.target_version,
            window=args.window,
        )
        print(json.dumps(selected))
        return

    if not args.target_dir or not args.output_dir or not args.target_version:
        parser.error("--target-dir, --output-dir, and --target-version are required unless --select-bases is set")

    parsed_bases = [_parse_base_release_spec(spec) for spec in (args.base_release or [])]
    if args.prev_dir:
        parsed_bases.append(
            {
                "dir": args.prev_dir,
                "version": args.prev_version,
                "version_code": args.prev_version_code,
            }
        )

    generate_delta_patches(
        target_dir=args.target_dir,
        output_dir=args.output_dir,
        target_version=args.target_version,
        target_version_code=args.target_version_code,
        threshold_ratio=args.threshold_ratio,
        base_releases=parsed_bases,
        existing_manifest_path=args.existing_manifest or None,
        verify_zip=args.verify_zip,
    )


if __name__ == "__main__":
    main()

