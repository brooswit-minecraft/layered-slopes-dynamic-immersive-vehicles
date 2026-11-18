package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

import java.util.EnumSet;
import java.util.Set;

/**
 * Hard, code-level gate on whether this mod's erosion consumer is permitted to run at all --
 * per the ticket's "Protection is a release gate, not a TODO silently skipped" requirement.
 *
 * <p>Models the set of concrete {@link CoverageArea}s that must ALL be covered before the gate
 * opens -- not a single free-text claim. A review finding (PR #3, comment 54845) on an earlier
 * version of this class correctly rejected that design: accepting any non-blank description let
 * the gate's own test open it with "source/destination only, no propagation" -- exactly the
 * insufficient coverage the gate exists to keep closed. A description is documentation, not
 * verification, and must never be able to turn erosion on by itself. {@link #isAsserted()} is
 * now {@code true} only once {@link #assertCoverage} has been called for EVERY value of {@link
 * CoverageArea}; asserting a strict subset (e.g. only {@link
 * CoverageArea#SOURCE_DESTINATION_AUTHORIZATION}) leaves the gate closed.
 *
 * <p>{@link #assertCoverage} still requires the caller to name specific evidence per area (so a
 * future reader or review can tell a real, specific claim from a rubber stamp), but that text is
 * record-keeping alongside the enum, never itself sufficient to open the gate.
 */
public final class ErosionProtectionGate {

    /**
     * One concrete protection concern this mod's erosion consumer must cover before it may run.
     * Grading's merged {@code SmoothingAuthorizationEvent} covers {@link
     * #SOURCE_DESTINATION_AUTHORIZATION} only; {@link #NEIGHBOR_CAVE_IN_PROPAGATION} (what
     * happens to neighboring blocks once an authorized move is applied) remains unimplemented
     * as of this slice.
     */
    public enum CoverageArea {
        SOURCE_DESTINATION_AUTHORIZATION,
        NEIGHBOR_CAVE_IN_PROPAGATION
    }

    private final Set<CoverageArea> covered = EnumSet.noneOf(CoverageArea.class);
    private final java.util.Map<CoverageArea, String> evidenceByArea = new java.util.EnumMap<>(CoverageArea.class);

    /**
     * Records that {@code area} is covered, with specific evidence of what was implemented and
     * tested (e.g. "SmoothingAuthorizationEvent, verified against claim-mod X's listener"). Does
     * NOT by itself open the gate unless this is the last remaining area -- {@link #isAsserted()}
     * requires every {@link CoverageArea} to have been asserted.
     */
    public synchronized void assertCoverage(CoverageArea area, String evidenceDescription) {
        if (area == null) {
            throw new IllegalArgumentException("area must not be null");
        }
        if (evidenceDescription == null || evidenceDescription.isBlank()) {
            throw new IllegalArgumentException(
                    "evidenceDescription must name exactly what was implemented and tested for " + area + ", not be blank");
        }
        covered.add(area);
        evidenceByArea.put(area, evidenceDescription);
    }

    /** Revokes coverage for one area, e.g. if it is later found to be inadequate. */
    public synchronized void revoke(CoverageArea area) {
        covered.remove(area);
        evidenceByArea.remove(area);
    }

    /** Revokes all coverage, fully closing the gate. */
    public synchronized void revokeAll() {
        covered.clear();
        evidenceByArea.clear();
    }

    /** @return {@code true} only once EVERY {@link CoverageArea} has been asserted. */
    public synchronized boolean isAsserted() {
        return covered.containsAll(EnumSet.allOf(CoverageArea.class));
    }

    /** @return {@code true} if {@code area} specifically has been asserted (regardless of the others). */
    public synchronized boolean isAreaCovered(CoverageArea area) {
        return covered.contains(area);
    }

    /** The evidence description recorded for {@code area}, or {@code null} if not covered. */
    public synchronized String evidenceFor(CoverageArea area) {
        return evidenceByArea.get(area);
    }
}
