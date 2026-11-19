#!/usr/bin/env python3
"""
Unit tests for Laterbox Delta Patch Generator (tools/delta/generate_delta.py).
Tests architecture matching, threshold enforcement, manifest structure, and reconstruction.
"""

import json
import os
import shutil
import tempfile
import unittest
import zipfile

import bsdiff4

from generate_delta import (
    DEFAULT_ROLLING_WINDOW,
    generate_delta_patches,
    select_rolling_base_releases,
    sha256_file,
)


def _create_fake_apk_zip(path: str, payload: bytes) -> None:
    """Writes a minimal valid APK ZIP archive containing AndroidManifest.xml and compressible payload."""
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_STORED) as zf:
        zf.writestr("AndroidManifest.xml", b"manifest-placeholder")
        zf.writestr("classes.dex", payload)


class TestGenerateDelta(unittest.TestCase):

    def setUp(self):
        self.test_dir = tempfile.mkdtemp()
        self.prev_dir = os.path.join(self.test_dir, "prev")
        self.target_dir = os.path.join(self.test_dir, "target")
        self.output_dir = os.path.join(self.test_dir, "output")
        os.makedirs(self.prev_dir, exist_ok=True)
        os.makedirs(self.target_dir, exist_ok=True)
        os.makedirs(self.output_dir, exist_ok=True)

    def tearDown(self):
        shutil.rmtree(self.test_dir, ignore_errors=True)

    def test_missing_prev_dir_gracefully_skipped(self):
        non_existent = os.path.join(self.test_dir, "non_existent")
        manifest = generate_delta_patches(
            prev_dir=non_existent,
            target_dir=self.target_dir,
            output_dir=self.output_dir,
            target_version="1.0.35",
            target_version_code=135,
        )
        self.assertEqual(len(manifest["patches"]), 0)

    def test_architecture_matching_and_reconstruction(self):
        base_content = b"Laterbox Base APK Content ARM32" * 1000
        target_content = b"Laterbox Base APK Content ARM32 with Updates!" * 1000

        with open(os.path.join(self.prev_dir, "Laterbox-ARM32.apk"), "wb") as f:
            f.write(base_content)
        with open(os.path.join(self.target_dir, "Laterbox-ARM32.apk"), "wb") as f:
            f.write(target_content)

        manifest = generate_delta_patches(
            prev_dir=self.prev_dir,
            target_dir=self.target_dir,
            output_dir=self.output_dir,
            target_version="1.0.35",
            target_version_code=135,
            prev_version="1.0.34",
            prev_version_code=134,
            threshold_ratio=0.70,
        )

        self.assertEqual(len(manifest["patches"]), 1)
        patch = manifest["patches"][0]
        self.assertEqual(patch["architecture"], "armeabi-v7a")
        self.assertEqual(patch["baseArchitecture"], "armeabi-v7a")
        self.assertEqual(patch["targetApkFilename"], "Laterbox-ARM32.apk")
        self.assertEqual(patch["baseApkSha256"], sha256_file(os.path.join(self.prev_dir, "Laterbox-ARM32.apk")))
        self.assertEqual(patch["targetApkSha256"], sha256_file(os.path.join(self.target_dir, "Laterbox-ARM32.apk")))
        self.assertTrue(os.path.exists(os.path.join(self.output_dir, patch["patchFilename"])))
        self.assertTrue(os.path.exists(os.path.join(self.output_dir, "delta-manifest.json")))

    def test_threshold_enforcement_discards_large_patch(self):
        base_content = os.urandom(50000)
        target_content = os.urandom(50000)

        with open(os.path.join(self.prev_dir, "Laterbox-ARM64.apk"), "wb") as f:
            f.write(base_content)
        with open(os.path.join(self.target_dir, "Laterbox-ARM64.apk"), "wb") as f:
            f.write(target_content)

        manifest = generate_delta_patches(
            prev_dir=self.prev_dir,
            target_dir=self.target_dir,
            output_dir=self.output_dir,
            target_version="1.0.35",
            target_version_code=135,
            threshold_ratio=0.10,
        )

        self.assertEqual(len(manifest["patches"]), 0)

    def test_rolling_window_multiple_bases_accumulates_manifest_and_reconstructs_byte_for_byte(self):
        common_tail = b"COMMON_RESOURCE_BLOCK_" * 3000
        for variant_apk in ("Laterbox-ARM32.apk", "Laterbox-ARM64.apk", "Laterbox-Universal.apk"):
            with open(os.path.join(self.target_dir, variant_apk), "wb") as f:
                f.write(f"TARGET-1.0.55-{variant_apk}-".encode("utf-8") * 50 + common_tail)

        base_releases = []
        for idx, ver in enumerate(("1.0.54", "1.0.53", "1.0.52", "1.0.51", "1.0.50")):
            bdir = os.path.join(self.test_dir, f"v{ver}")
            os.makedirs(bdir, exist_ok=True)
            for variant_apk in ("Laterbox-ARM32.apk", "Laterbox-ARM64.apk", "Laterbox-Universal.apk"):
                with open(os.path.join(bdir, variant_apk), "wb") as f:
                    f.write(f"BASE-{ver}-{idx}-{variant_apk}-".encode("utf-8") * 50 + common_tail)
            base_releases.append({"dir": bdir, "version": ver, "version_code": 166 - (idx * 2)})

        manifest = generate_delta_patches(
            target_dir=self.target_dir,
            output_dir=self.output_dir,
            target_version="1.0.55",
            target_version_code=168,
            base_releases=base_releases,
            threshold_ratio=0.70,
        )

        self.assertEqual(manifest["schemaVersion"], 1)
        self.assertEqual(manifest["targetVersion"], "1.0.55")
        self.assertEqual(manifest["targetVersionCode"], 168)
        self.assertEqual(len(manifest["patches"]), 15)

        # Verify patch filenames are all unique and present on disk
        filenames = [p["patchFilename"] for p in manifest["patches"]]
        self.assertEqual(len(filenames), len(set(filenames)))

        # Verify every single patch reconstructs byte-for-byte and has Android-required fields
        required_keys = {
            "architecture",
            "architectureLabel",
            "baseVersion",
            "baseVersionCode",
            "baseApkSha256",
            "targetApkFilename",
            "targetApkSha256",
            "targetApkSize",
            "patchFilename",
            "patchSha256",
            "patchSize",
            "patchAlgorithm",
        }
        for p in manifest["patches"]:
            self.assertTrue(required_keys.issubset(set(p.keys())))
            self.assertGreater(p["patchSize"], 0)
            self.assertEqual(p["patchAlgorithm"], "BSDIFF40")
            base_apk = os.path.join(self.test_dir, f"v{p['baseVersion']}", p["targetApkFilename"])
            patch_path = os.path.join(self.output_dir, p["patchFilename"])
            recon_path = os.path.join(self.test_dir, f"recon_{p['patchFilename']}.apk")
            bsdiff4.file_patch(base_apk, recon_path, patch_path)
            self.assertEqual(sha256_file(recon_path), p["targetApkSha256"])

        # Verify manifest file on disk matches returned dict
        with open(os.path.join(self.output_dir, "delta-manifest.json"), "r", encoding="utf-8") as f:
            on_disk = json.load(f)
        self.assertEqual(len(on_disk["patches"]), 15)

    def test_invalid_or_unavailable_base_apks_skipped_safely_and_filenames_unique(self):
        common = b"SHARED_APK_DATA_" * 2500
        _create_fake_apk_zip(os.path.join(self.target_dir, "Laterbox-ARM64.apk"), b"TARGET-" + common)
        _create_fake_apk_zip(os.path.join(self.target_dir, "Laterbox-ARM32.apk"), b"TARGET32-" + common)

        valid_dir = os.path.join(self.test_dir, "valid_53")
        corrupt_dir = os.path.join(self.test_dir, "corrupt_52")
        same_ver_other_dir = os.path.join(self.test_dir, "other_53")
        missing_dir = os.path.join(self.test_dir, "missing_51")
        os.makedirs(valid_dir, exist_ok=True)
        os.makedirs(corrupt_dir, exist_ok=True)
        os.makedirs(same_ver_other_dir, exist_ok=True)

        _create_fake_apk_zip(os.path.join(valid_dir, "Laterbox-ARM64.apk"), b"BASE53-" + common)
        _create_fake_apk_zip(os.path.join(same_ver_other_dir, "Laterbox-ARM64.apk"), b"ALT53-" + common)
        # Write corrupt non-zip file and 0-byte file in corrupt_dir
        with open(os.path.join(corrupt_dir, "Laterbox-ARM64.apk"), "wb") as f:
            f.write(b"not-a-valid-zip-apk")
        with open(os.path.join(corrupt_dir, "Laterbox-ARM32.apk"), "wb") as f:
            f.write(b"")

        manifest = generate_delta_patches(
            target_dir=self.target_dir,
            output_dir=self.output_dir,
            target_version="1.0.55",
            target_version_code=168,
            base_releases=[
                {"dir": valid_dir, "version": "1.0.53", "version_code": 164},
                {"dir": corrupt_dir, "version": "1.0.52", "version_code": 162},
                {"dir": same_ver_other_dir, "version": "1.0.53", "version_code": 164},
                {"dir": missing_dir, "version": "1.0.51", "version_code": 160},
            ],
            verify_zip=True,
        )

        # Corrupt and missing base releases skipped cleanly; both distinct 1.0.53 hashes keep unique filenames
        self.assertEqual(len(manifest["patches"]), 2)
        names = [p["patchFilename"] for p in manifest["patches"]]
        self.assertEqual(len(set(names)), 2)

    def test_existing_manifest_accumulation_preserves_prior_entries(self):
        common = b"ACCUMULATE_DATA_" * 2000
        with open(os.path.join(self.target_dir, "Laterbox-ARM64.apk"), "wb") as f:
            f.write(b"V55-" + common)

        dir_54 = os.path.join(self.test_dir, "v54")
        dir_53 = os.path.join(self.test_dir, "v53")
        os.makedirs(dir_54, exist_ok=True)
        os.makedirs(dir_53, exist_ok=True)
        with open(os.path.join(dir_54, "Laterbox-ARM64.apk"), "wb") as f:
            f.write(b"V54-" + common)
        with open(os.path.join(dir_53, "Laterbox-ARM64.apk"), "wb") as f:
            f.write(b"V53-" + common)

        # First pass generates only 1.0.54 -> 1.0.55
        generate_delta_patches(
            target_dir=self.target_dir,
            output_dir=self.output_dir,
            target_version="1.0.55",
            target_version_code=168,
            base_releases=[{"dir": dir_54, "version": "1.0.54", "version_code": 166}],
        )
        manifest_path = os.path.join(self.output_dir, "delta-manifest.json")

        # Second pass merges existing manifest + new 1.0.53 base without losing 1.0.54
        merged = generate_delta_patches(
            target_dir=self.target_dir,
            output_dir=self.output_dir,
            target_version="1.0.55",
            target_version_code=168,
            base_releases=[
                {"dir": dir_54, "version": "1.0.54", "version_code": 166},
                {"dir": dir_53, "version": "1.0.53", "version_code": 164},
            ],
            existing_manifest_path=manifest_path,
        )

        self.assertEqual([p["baseVersion"] for p in merged["patches"]], ["1.0.54", "1.0.53"])

    def test_select_rolling_base_releases_filters_and_sorts_by_published_at(self):
        releases = [
            {"tag_name": "v1.0.55", "draft": False, "prerelease": False, "published_at": "2026-11-18T23:45:54Z"},
            {"tag_name": "v1.0.56-rc1", "draft": False, "prerelease": True, "published_at": "2026-11-18T23:50:00Z"},
            {"tag_name": "v1.0.54-draft", "draft": True, "prerelease": False, "published_at": "2026-11-18T20:00:00Z"},
            {"tag_name": "v1.0.50", "draft": False, "prerelease": False, "published_at": "2026-11-17T04:02:53Z"},
            {"tag_name": "v1.0.52", "draft": False, "prerelease": False, "published_at": "2026-11-17T13:42:07Z"},
            {"tag_name": "v1.0.54", "draft": False, "prerelease": False, "published_at": "2026-11-18T18:17:28Z"},
            {"tag_name": "v1.0.49", "draft": False, "prerelease": False, "published_at": "2026-11-17T02:28:27Z"},
            {"tag_name": "v1.0.51", "draft": False, "prerelease": False, "published_at": "2026-11-17T11:09:39Z"},
            {"tag_name": "v1.0.53", "draft": False, "prerelease": False, "published_at": "2026-11-17T20:13:05Z"},
        ]

        selected = select_rolling_base_releases(
            releases=releases,
            current_tag="v1.0.55",
            current_version="1.0.55",
            window=DEFAULT_ROLLING_WINDOW,
        )

        self.assertEqual(
            [r["tag_name"] for r in selected],
            ["v1.0.54", "v1.0.53", "v1.0.52", "v1.0.51", "v1.0.50"],
        )


if __name__ == "__main__":
    unittest.main()
