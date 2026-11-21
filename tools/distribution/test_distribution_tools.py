import json
import os
import tempfile
import unittest
from pathlib import Path

from tools.distribution.backup_releases import format_checksums_file, parse_semver_key
from tools.distribution.migrate_releases import (
    resolve_release_notes,
    validate_sanitized_notes,
)

REPO_ROOT = Path(__file__).resolve().parents[2]
NOTES_PATH = REPO_ROOT / "tools" / "distribution" / "sanitized_historical_notes.json"


class DistributionToolsTest(unittest.TestCase):
    def test_sanitized_historical_notes_clean_and_complete(self):
        notes = json.loads(NOTES_PATH.read_text(encoding="utf-8"))
        self.assertGreaterEqual(len(notes), 60)
        for tag in ("v1.0.0", "v1.0.50", "v1.0.53", "v1.0.54", "v1.0.55", "v1.0.59", "v1.0.60", "v1.0.61"):
            self.assertIn(tag, notes)
        for tag, body in notes.items():
            issues = validate_sanitized_notes(body, tag_name=tag)
            self.assertEqual(issues, [], f"Validation issues for {tag}: {issues}")
            self.assertNotIn("ViewModel", body)
            self.assertNotIn("Full Changelog", body)

    def test_validate_rejects_leaked_pr_or_source_repo(self):
        bad = "Fixed bug in https://github.com/Adetisola/Laterbox-Android/pull/12"
        issues = validate_sanitized_notes(bad, tag_name="v1.0.99")
        self.assertTrue(len(issues) >= 1)

        bad_compare = "**Full Changelog**: https://github.com/Adetisola/Laterbox/compare/v1.0.60...v1.0.61"
        self.assertTrue(len(validate_sanitized_notes(bad_compare, tag_name="v1.0.61")) >= 1)

    def test_resolve_notes_prefers_markdown_override(self):
        with tempfile.TemporaryDirectory() as tmp:
            override = Path(tmp) / "v1.0.61.md"
            override.write_text("## Custom\n- Override note", encoding="utf-8")
            resolved = resolve_release_notes("v1.0.61", {"v1.0.61": "old"}, notes_dir=tmp)
            self.assertIn("Override note", resolved)

    def test_semver_sort_and_checksums(self):
        tags = ["v1.0.9", "v1.0.10", "v1.0.2", "v1.0.61"]
        ordered = sorted(tags, key=parse_semver_key)
        self.assertEqual(ordered, ["v1.0.2", "v1.0.9", "v1.0.10", "v1.0.61"])

        out = format_checksums_file([("abc123", "Laterbox-Universal.apk"), ("def456", "Laterbox-ARM64.apk")])
        self.assertEqual(
            out,
            "def456  Laterbox-ARM64.apk\nabc123  Laterbox-Universal.apk\n",
        )


if __name__ == "__main__":
    unittest.main()
