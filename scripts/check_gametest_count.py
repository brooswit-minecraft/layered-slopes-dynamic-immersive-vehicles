#!/usr/bin/env python3
"""Fail closed on a GameTest server run that silently did nothing.

Gradle reports BUILD SUCCESSFUL even when the GameTest server fails to start
or ran zero tests, so a green `./gradlew runGameTestServer` exit code alone
proves nothing. This checks the run's own log for unambiguous evidence: the
server must not have failed to start, there must be no reported
required-test failures, and the completed/passed counts must both exactly
equal the number of acceptance tests this mod declares (MINECRAFT-510).

Adapted from layered-slopes' own guard (MINECRAFT-499), which confirmed this
log format against a real CI run: passing tests are only ever counted, never
individually named, so only the completion/pass summary counts -- not a
per-test-name substring check -- can work here.

NOT YET WIRED INTO ci.yml: this mod (MINECRAFT-510) has zero GameTests as of
this slice -- `./gradlew runGameTestServer` here currently fails to start
with "No test functions were given!" (confirmed live). EXPECTED_REQUIRED_TEST_COUNT
is the UNSET sentinel below, not a borrowed number or a 0 that a genuine
0-test log could satisfy: main() refuses to run at all while it is UNSET,
the same fail-closed-on-an-unfilled-placeholder shape build.gradle's
download tasks use for an unfilled "UNRELEASED" dependency pin. Wire this
into ci.yml, and set EXPECTED_REQUIRED_TEST_COUNT to the real count, once
the first real GameTest for this mod actually exists -- never copy a
sibling mod's count or test-name list into this file again.
"""
import re
import sys
from pathlib import Path


class RuntimeEvidenceError(ValueError):
    """The GameTest log cannot prove a complete, non-trivial run."""


UNSET = "UNSET"

# No GameTest exists for this mod yet, so there is no real count to check against -- UNSET is a
# sentinel, not a count, and main() refuses to run while it is UNSET (see below). Update to the
# real count -- never a borrowed one -- when the first GameTest is added, and wire this script
# into ci.yml at the same time.
EXPECTED_REQUIRED_TEST_COUNT = UNSET


def verify_runtime_log(log_text):
    if EXPECTED_REQUIRED_TEST_COUNT == UNSET:
        raise RuntimeEvidenceError(
            "EXPECTED_REQUIRED_TEST_COUNT is still UNSET -- this mod has no GameTests yet; "
            "set it to the real count once the first one is added, don't borrow one")
    if re.search(r"Failed to start the minecraft server", log_text, re.I):
        raise RuntimeEvidenceError("GameTest server startup failed")
    if re.search(r"\b\d+\s+required tests failed\b", log_text):
        raise RuntimeEvidenceError("required GameTest failure evidence")
    completed = list(re.finditer(r"=+\s*(\d+)\s+GAME TESTS COMPLETE\s+IN\s+", log_text))
    passed = list(re.finditer(r"All\s+(\d+)\s+required tests passed", log_text))
    if len(completed) != 1 or len(passed) != 1:
        raise RuntimeEvidenceError("missing or ambiguous completion/pass summary")
    if completed[0].end() > passed[0].start() or completed[0].group(1) != passed[0].group(1):
        raise RuntimeEvidenceError("completion/pass summaries are not one consistent terminal pair")
    count = int(completed[0].group(1))
    if count != EXPECTED_REQUIRED_TEST_COUNT:
        raise RuntimeEvidenceError(
            f"expected exactly {EXPECTED_REQUIRED_TEST_COUNT} required GameTests to complete and pass, got {count}")
    return count


def main(argv):
    if len(argv) != 2:
        raise SystemExit(f"usage: {argv[0]} <gametest.log>")
    try:
        passed = verify_runtime_log(Path(argv[1]).read_text())
    except (OSError, RuntimeEvidenceError) as error:
        raise SystemExit(f"GameTest count guard failed: {error}") from error
    print(f"GameTest count guard passed: {passed} required cases")


if __name__ == "__main__":
    main(sys.argv)
