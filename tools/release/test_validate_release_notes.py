import tempfile
import unittest
from pathlib import Path

from tools.release.validate_release_notes import (
    validate_release_notes_file,
    validate_release_notes_text,
)


class ValidateReleaseNotesTest(unittest.TestCase):
    def test_accepts_clean_user_facing_notes(self):
        notes = """## What's New
- Improved offline playback stability and media session controls.
- Fixed fullscreen trailer rendering issues.
"""
        self.assertEqual(validate_release_notes_text(notes), [])

    def test_rejects_internal_leaks(self):
        cases = [
            "Updated GitHubUpdateChecker.kt to fix race condition.",
            "Fixed crash in InboxViewModel during refresh.",
            "**Full Changelog**: https://github.com/Adetisola/Laterbox/compare/v1.0.60...v1.0.61",
            "See https://github.com/Adetisola/Laterbox-Android/pull/42 for details.",
            "Commit 0123456789abcdef0123456789abcdef01234567 fixed this.",
        ]
        for bad in cases:
            self.assertTrue(len(validate_release_notes_text(bad)) >= 1, f"Expected failure for: {bad}")

    def test_missing_file_returns_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            missing = str(Path(tmp) / "v9.9.9.md")
            self.assertTrue(len(validate_release_notes_file(missing)) >= 1)


if __name__ == "__main__":
    unittest.main()
