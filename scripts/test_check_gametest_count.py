import unittest

from check_gametest_count import RuntimeEvidenceError, verify_runtime_log

BATCH_HEADER = "Running test batch 'defaultBatch:0' (3 tests)...\n"


class CheckGameTestCountTest(unittest.TestCase):
    def test_unset_expected_count_fails_closed_even_on_an_otherwise_valid_log(self):
        # EXPECTED_REQUIRED_TEST_COUNT is UNSET until this mod has a real GameTest -- this must
        # refuse to pass ANY log, including one that would otherwise look like a clean success,
        # rather than silently accepting a 0-test or borrowed-count run as evidence of nothing.
        log = BATCH_HEADER + "=== 3 GAME TESTS COMPLETE IN 1.5s ===\nAll 3 required tests passed :)"
        with self.assertRaises(RuntimeEvidenceError):
            verify_runtime_log(log)

    def test_zero_tests_fails_closed(self):
        with self.assertRaises(RuntimeEvidenceError):
            verify_runtime_log("=== 0 GAME TESTS COMPLETE IN 0.1s ===\nAll 0 required tests passed :)")

    def test_startup_failure_fails_closed(self):
        with self.assertRaises(RuntimeEvidenceError):
            verify_runtime_log("Failed to start the minecraft server")

    def test_reported_required_failure_fails_closed(self):
        with self.assertRaises(RuntimeEvidenceError):
            verify_runtime_log(BATCH_HEADER
                                + "someregressiontest failed at 1, 2, 3! expected X, got Y\n"
                                + "1 required tests failed :(")

    def test_mismatched_completed_and_passed_counts_fails_closed(self):
        with self.assertRaises(RuntimeEvidenceError):
            verify_runtime_log(BATCH_HEADER + "=== 3 GAME TESTS COMPLETE IN 1.5s ===\nAll 2 required tests passed :)")

    def test_missing_summary_fails_closed(self):
        with self.assertRaises(RuntimeEvidenceError):
            verify_runtime_log("nothing useful here")


if __name__ == "__main__":
    unittest.main()
