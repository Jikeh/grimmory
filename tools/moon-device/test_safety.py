"""Offline checks of the test-book guard (no phone or Drive needed): `uv run python -m unittest`."""

import unittest
from unittest import mock

import moon_device as md

REAL_BOOK = "Some Real Book.epub"
ARGS = {
    "open": [REAL_BOOK], "restart": [REAL_BOOK], "read-to": [REAL_BOOK, "Chapter 1"],
    "select": [REAL_BOOK, "1", "2", "3", "4"], "add-highlight": [REAL_BOOK, "1", "2", "3", "4", "n"],
    "edit-note": [REAL_BOOK, "1", "2", "n"], "delete-highlight": [REAL_BOOK, "1", "2", "trash"],
    "add-bookmark": [REAL_BOOK], "sync-up": [REAL_BOOK], "sync-down": [REAL_BOOK, "forced"],
    "answer-prompt": [REAL_BOOK, "ok"], "fetch": [REAL_BOOK],
}


class TestBookGuard(unittest.TestCase):
    def test_every_book_command_is_covered(self):
        self.assertEqual(set(ARGS), md.BOOK_COMMANDS)

    def test_refuses_books_not_on_the_allowlist_before_connecting(self):
        with mock.patch.object(md, "Phone", side_effect=AssertionError("must not connect")), \
                mock.patch.object(md, "drive_session", side_effect=AssertionError("must not reach Drive")):
            for command, args in ARGS.items():
                with self.subTest(command=command):
                    result = md.run(command, args)
                    self.assertFalse(result["ok"])
                    self.assertIn("NotATestBook", result["error"])

    def test_allowlisted_names_must_match_exactly(self):
        md.require_test_book("TEST - Pride  Prejudice.epub")
        for near_miss in ("TEST - Pride Prejudice.epub", "test - pride  prejudice.epub", "", "TEST - "):
            with self.subTest(name=near_miss), self.assertRaises(md.NotATestBook):
                md.require_test_book(near_miss)

    def test_every_allowlisted_book_is_marked_as_a_test_book(self):
        for book in md.test_books():
            self.assertTrue(book.startswith("TEST - "), book)


if __name__ == "__main__":
    unittest.main()
