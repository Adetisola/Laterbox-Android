import unittest

from tools.release.generate_release_notes import (
    build_deterministic_notes,
    sanitize_commit_subject,
)
from tools.release.validate_release_notes import validate_release_notes_text


class GenerateReleaseNotesTest(unittest.TestCase):
    def test_filters_internal_commits_and_sanitizes_user_commits(self):
        self.assertIsNone(sanitize_commit_subject("chore(release): bump version to 1.0.62"))
        self.assertIsNone(sanitize_commit_subject("test(update): add unit tests for GitHubUpdateChecker.kt"))
        self.assertIsNone(sanitize_commit_subject("ci: fix actions/checkout@v4"))

        parsed = sanitize_commit_subject("feat(movies): add offline subtitle search in DownloadsScreen.kt")
        self.assertIsNotNone(parsed)
        cat, msg = parsed
        self.assertEqual(cat, "features")
        self.assertNotIn(".kt", msg)
        self.assertTrue(msg.endswith("."))

    def test_build_deterministic_notes_produces_valid_bullet_markdown(self):
        subjects = [
            "chore(release): bump version to 1.0.62",
            "feat(reader): add serif font size slider for Article Reader",
            "perf(player): speed up offline video startup",
            "fix(media): resolve YouTube caption download stall on slow networks",
            "test(inbox): verify InboxViewModel debounce",
        ]
        md = build_deterministic_notes(subjects, "1.0.62")
        self.assertIn("## New Features", md)
        self.assertIn("## What's Improved", md)
        self.assertIn("## Bug Fixes", md)
        self.assertIn("- Add serif font size slider for Article Reader.", md)
        self.assertEqual(validate_release_notes_text(md), [])

    def test_empty_subjects_falls_back_cleanly(self):
        md = build_deterministic_notes(["chore: bump version"], "1.0.62")
        self.assertIn("1.0.62", md)
        self.assertEqual(validate_release_notes_text(md), [])


if __name__ == "__main__":
    unittest.main()
