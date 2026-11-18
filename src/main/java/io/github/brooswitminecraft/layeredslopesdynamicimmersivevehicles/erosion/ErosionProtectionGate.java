package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

/**
 * Hard, code-level gate on whether this mod's erosion consumer is permitted to run at all --
 * per the ticket's "Protection is a release gate, not a TODO silently skipped" requirement.
 *
 * <p>Defaults to NOT asserted (fails closed). The erosion consumer, once implemented, MUST
 * check {@link #isAsserted()} before performing any real world mutation and must not run at
 * all while it is {@code false}. This class does not itself implement propagation/neighbor/
 * cave-in protection -- grading's {@code SmoothingAuthorizationEvent} (merged on
 * layered-slope-grading) covers source/destination authorization for a single move only, and
 * explicitly does not cover what happens to neighboring blocks once an authorized move is
 * applied. This gate exists so the consumer can honestly refuse to run until that remaining
 * coverage has actually been implemented and tested, rather than silently shipping without it
 * or vaguely claiming "universal claim-mod coverage" that was never verified.
 *
 * <p>{@link #assertCoverage} requires the caller to name EXACTLY what was tested, so a future
 * reader (or review) can tell a real, specific claim from a rubber stamp.
 */
public final class ErosionProtectionGate {
    private volatile boolean asserted = false;
    private volatile String assertedCoverageDescription = null;

    /**
     * Opens the gate. {@code coverageDescription} must name exactly which protection
     * integration was implemented and tested (e.g. "SmoothingAuthorizationEvent
     * source/destination authorization, verified against claim-mod X; neighbor/cave-in
     * propagation NOT covered") -- never a vague "protection is handled" claim.
     */
    public void assertCoverage(String coverageDescription) {
        if (coverageDescription == null || coverageDescription.isBlank()) {
            throw new IllegalArgumentException(
                    "coverageDescription must name exactly what protection integration is tested, not be blank");
        }
        this.assertedCoverageDescription = coverageDescription;
        this.asserted = true;
    }

    /** Closes the gate again, e.g. if the asserted coverage is later found to be inadequate. */
    public void revoke() {
        this.asserted = false;
        this.assertedCoverageDescription = null;
    }

    /** @return {@code true} only after {@link #assertCoverage} has been called and not since revoked. */
    public boolean isAsserted() {
        return asserted;
    }

    /** The exact coverage description passed to {@link #assertCoverage}, or {@code null} if not asserted. */
    public String assertedCoverageDescription() {
        return assertedCoverageDescription;
    }
}
