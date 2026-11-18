import unittest

from check_gametest_count import RuntimeEvidenceError, UNSET, verify_runtime_log

BATCH_HEADER = "Running test batch 'defaultBatch:0' (3 tests)...\n"


class CheckGameTestCountTest(unittest.TestCase):
    def test_default_expected_count_is_wired_to_the_real_current_gametest_count(self):
        # No explicit `expected` passed -- uses the module's real production default, which must
        # be the actual current GameTest count for this mod (5: StandaloneBootGameTests' 1 plus
        # ErosionAttemptGameTests' 4), not the UNSET sentinel and not a stale/borrowed number.
        log = ("Running test batch 'defaultBatch:0' (5 tests)...\n"
               "=== 5 GAME TESTS COMPLETE IN 1.5s ===\nAll 5 required tests passed :)")
        self.assertEqual(verify_runtime_log(log), 5)

    def test_unset_sentinel_still_fails_closed_when_explicitly_selected(self):
        # The UNSET-handling guard itself must still work correctly when explicitly selected
        # (e.g. by a future reset to UNSET if the count is ever removed again), even though
        # UNSET is no longer the active production default now that a real GameTest exists.
        log = BATCH_HEADER + "=== 3 GAME TESTS COMPLETE IN 1.5s ===\nAll 3 required tests passed :)"
        with self.assertRaises(RuntimeEvidenceError):
            verify_runtime_log(log, expected=UNSET)

    # Every test below passes an explicit positive `expected` to actually exercise the
    # log-parsing logic -- relying on the module default here would let every one of these
    # short-circuit on the UNSET check above and pass for the wrong reason, proving nothing
    # about the parser (PR #2 review 54822).

    def test_exact_expected_count_passes(self):
        log = BATCH_HEADER + "=== 3 GAME TESTS COMPLETE IN 1.5s ===\nAll 3 required tests passed :)"
        self.assertEqual(verify_runtime_log(log, expected=3), 3)

    def test_zero_tests_fails_closed(self):
        with self.assertRaises(RuntimeEvidenceError):
            verify_runtime_log("=== 0 GAME TESTS COMPLETE IN 0.1s ===\nAll 0 required tests passed :)", expected=3)

    def test_fewer_than_expected_fails_closed(self):
        with self.assertRaises(RuntimeEvidenceError):
            verify_runtime_log("=== 2 GAME TESTS COMPLETE IN 1.0s ===\nAll 2 required tests passed :)", expected=3)

    def test_startup_failure_fails_closed(self):
        with self.assertRaises(RuntimeEvidenceError):
            verify_runtime_log("Failed to start the minecraft server", expected=3)

    def test_reported_required_failure_fails_closed(self):
        with self.assertRaises(RuntimeEvidenceError):
            verify_runtime_log(BATCH_HEADER
                                + "someregressiontest failed at 1, 2, 3! expected X, got Y\n"
                                + "1 required tests failed :(", expected=3)

    def test_mismatched_completed_and_passed_counts_fails_closed(self):
        with self.assertRaises(RuntimeEvidenceError):
            verify_runtime_log(BATCH_HEADER + "=== 3 GAME TESTS COMPLETE IN 1.5s ===\nAll 2 required tests passed :)",
                                expected=3)

    def test_missing_summary_fails_closed(self):
        with self.assertRaises(RuntimeEvidenceError):
            verify_runtime_log("nothing useful here", expected=3)


if __name__ == "__main__":
    unittest.main()
