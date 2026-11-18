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

StandaloneBootGameTests.theGameTestHarnessBootsForThisMod (a trivial helper.succeed() proving the
harness boots) plus ErosionAttemptGameTests' 18 cases: a real move applying and conserving
material with no claim mod/Dynamic Terrain installed (the "missing/unsupported integration"
success case), a protected source, a protected destination, and a detected neighbor cave-in risk
each denying (via injected orchestration-seam fakes), a REAL BlockEvent.BreakEvent listener
cancelling at source and at destination each denying via the actual production path, a direct
check that the real DynamicTerrainCaveInRisk reports no risk when Dynamic Terrain is genuinely
absent, a REAL BlockEvent.BreakEvent listener that swaps the source to a DIFFERENT but still
smoothable state without cancelling (review 54910 -- a boolean-only canSmooth recheck cannot
catch this; the production path must detect the exact state change and refuse to apply on top of
it), a REAL listener that swaps the support block for a different-but-sturdy one proving that
harmless case still applies rather than being overcorrected into a denial (review 54915), a
direct check that an unknown config lookup fails closed to the caller's fallback, per-tick
dedup, a denied attempt and a failed roll each still consuming the dedup slot, no-valid-direction,
an off-thread call denied before touching anything, an unloaded source denied without
force-loading it, a deterministic instrumented-seam test proving an unloaded destination is
skipped before Smoothing/mutation ever runs for it, and a disabled-by-default erosionEnabled=false
attempt denying before touching the off-thread check/chunk/dedup/RNG/world at all without
consuming the dedup slot (direction, comment 54943) -- plus ErosionModConfigGameTests' 2 cases
proving the REAL registered NeoForge config actually boots with erosionEnabled=false and that
editing its real on-disk file toggles it live, without a server restart, with the production path
transitioning DISABLED -> APPLIED accordingly. These replaced an earlier design
(ErosionProtectionGate/ProtectionAdapter-routed checks) found three separate times to overclaim
a capability it did not verify (review 54855/54861/54878/54890/54899/54901) -- ClaimProtection
and DynamicTerrainCaveInRisk are real, direct, unconditional checks instead.
EXPECTED_REQUIRED_TEST_COUNT is that real count, 21 -- update it, never borrow a sibling mod's
count or test-name list, as more real GameTests are added.
"""
import re
import sys
from pathlib import Path


class RuntimeEvidenceError(ValueError):
    """The GameTest log cannot prove a complete, non-trivial run."""


UNSET = "UNSET"

# StandaloneBootGameTests (1) + ErosionAttemptGameTests (18) + ErosionModConfigGameTests (2).
EXPECTED_REQUIRED_TEST_COUNT = 21


def verify_runtime_log(log_text, expected=EXPECTED_REQUIRED_TEST_COUNT):
    """`expected` defaults to the module-level placeholder so real CI usage (main(), below)
    always fails closed while it is UNSET. Tests exercising the actual log-parsing logic
    (startup failure, mismatched counts, etc.) must pass an explicit positive `expected` --
    otherwise every one of them would short-circuit on the UNSET check below and never reach
    that logic at all, proving nothing about it."""
    if expected == UNSET:
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
    if count != expected:
        raise RuntimeEvidenceError(
            f"expected exactly {expected} required GameTests to complete and pass, got {count}")
    return count


def main(argv):
    if len(argv) not in (2, 3):
        raise SystemExit(f"usage: {argv[0]} <gametest.log> [expected_count]")
    # expected_count is optional and defaults to this mod's own real GameTest count above --
    # only the opt-in dtPairedRuntime workflow passes an explicit override, since that build adds
    # one extra GameTest (DynamicTerrainPairedGameTests) that does not exist in the default
    # build/test at all.
    kwargs = {"expected": int(argv[2])} if len(argv) == 3 else {}
    try:
        passed = verify_runtime_log(Path(argv[1]).read_text(), **kwargs)
    except (OSError, RuntimeEvidenceError) as error:
        raise SystemExit(f"GameTest count guard failed: {error}") from error
    print(f"GameTest count guard passed: {passed} required cases")


if __name__ == "__main__":
    main(sys.argv)
