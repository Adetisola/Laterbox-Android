import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from tools.release.publish_distribution_release import verify_and_collect_artifacts, write_release_manifest


class PublishDistributionReleaseTest(unittest.TestCase):
    def test_verify_and_collect_artifacts_generates_checksums(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for apk in ("Laterbox-ARM32.apk", "Laterbox-ARM64.apk", "Laterbox-Universal.apk"):
                (root / apk).write_bytes(b"PK\x03\x04dummy-" + apk.encode())

            patch_bytes = b"BSDIFF40dummy-patch"
            patch_path = root / "Laterbox-ARM64-1.0.60-to-1.0.61.patch"
            patch_path.write_bytes(patch_bytes)
            patch_sha = hashlib.sha256(patch_bytes).hexdigest()

            manifest = {
                "schemaVersion": 1,
                "targetVersion": "1.0.61",
                "targetVersionCode": 61,
                "patches": [
                    {
                        "architecture": "arm64-v8a",
                        "patchFilename": patch_path.name,
                        "patchSha256": patch_sha,
                    }
                ],
            }
            (root / "delta-manifest.json").write_text(json.dumps(manifest), encoding="utf-8")

            result = verify_and_collect_artifacts(str(root))
            names = {Path(p).name for p in result["files"]}
            self.assertEqual(
                names,
                {
                    "Laterbox-ARM32.apk",
                    "Laterbox-ARM64.apk",
                    "Laterbox-Universal.apk",
                    "Laterbox-ARM64-1.0.60-to-1.0.61.patch",
                    "delta-manifest.json",
                    "checksums.sha256",
                },
            )
            checksum_text = Path(result["checksums_path"]).read_text(encoding="utf-8")
            self.assertIn(patch_sha, checksum_text)
            self.assertIn("Laterbox-Universal.apk", checksum_text)

    def test_write_release_manifest_included_in_collected_artifacts_and_checksums(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for apk in ("Laterbox-ARM32.apk", "Laterbox-ARM64.apk", "Laterbox-Universal.apk"):
                (root / apk).write_bytes(b"PK\x03\x04dummy-" + apk.encode())
            (root / "delta-manifest.json").write_text(
                json.dumps({"schemaVersion": 1, "targetVersion": "1.0.67", "patches": []}),
                encoding="utf-8",
            )
            rm_path = write_release_manifest(
                str(root),
                tag_name="v1.0.67",
                version_name="1.0.67",
                version_code=205,
            )
            result = verify_and_collect_artifacts(str(root))
            names = {Path(p).name for p in result["files"]}
            self.assertIn("release-manifest.json", names)
            parsed = json.loads(Path(rm_path).read_text(encoding="utf-8"))
            self.assertEqual(parsed["versionCode"], 205)
            self.assertEqual(parsed["version"], "1.0.67")
            checksum_text = Path(result["checksums_path"]).read_text(encoding="utf-8")
            self.assertIn("release-manifest.json", checksum_text)

    def test_fails_when_required_apk_missing(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "Laterbox-Universal.apk").write_bytes(b"apk")
            (root / "delta-manifest.json").write_text('{"targetVersion":"1.0.61","patches":[]}', encoding="utf-8")
            with self.assertRaises(RuntimeError):
                verify_and_collect_artifacts(str(root))


if __name__ == "__main__":
    unittest.main()
