package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

/**
 * Hard, code-level gate on whether this mod's erosion consumer is permitted to run at all --
 * per the ticket's "Protection is a release gate, not a TODO silently skipped" requirement.
 *
 * <p>Two review rounds (PR #3, comments 54845/54853) each found the previous design still let
 * a free-text claim, or a pair of them, open the gate with no actual executable capability
 * behind it. Fixed properly this time: {@link #assertCoverage} takes a real {@link
 * ProtectionAdapter} object, and {@link #isAsserted()} calls that adapter's OWN capability
 * methods every time rather than trusting a one-shot description string -- an adapter that
 * reports it does not support neighbor/cave-in propagation keeps the gate closed no matter what
 * text accompanies it, and there is no way to "assert" past that short of actually implementing
 * an adapter whose own methods return {@code true}.
 */
public final class ErosionProtectionGate {

    /**
     * A real, checkable claim of what protection this mod's erosion consumer has integrated and
     * tested. Grading's merged {@code SmoothingAuthorizationEvent} supports {@link
     * #supportsSourceDestinationAuthorization()} only; no implementation of {@link
     * #supportsNeighborCaveInPropagation()} exists yet (what happens to neighboring blocks once
     * an authorized move is applied) -- there is deliberately no adapter shipped in this slice
     * that returns {@code true} for it.
     */
    public interface ProtectionAdapter {
        boolean supportsSourceDestinationAuthorization();

        boolean supportsNeighborCaveInPropagation();

        /** Free-text record-keeping only -- never itself consulted by {@link #isAsserted()}. */
        String describe();
    }

    private volatile ProtectionAdapter adapter;

    /** Installs the adapter whose own capability methods will be consulted by {@link #isAsserted()}. */
    public void assertCoverage(ProtectionAdapter adapter) {
        if (adapter == null) {
            throw new IllegalArgumentException("adapter must not be null");
        }
        if (adapter.describe() == null || adapter.describe().isBlank()) {
            throw new IllegalArgumentException("adapter.describe() must name exactly what was implemented and tested, not be blank");
        }
        this.adapter = adapter;
    }

    /** Removes the installed adapter, fully closing the gate. */
    public void revokeAll() {
        this.adapter = null;
    }

    /**
     * @return {@code true} only if an adapter is installed AND that adapter's own methods
     *     report support for EVERY required area right now -- re-checked on every call, never
     *     cached from the moment {@link #assertCoverage} was called.
     */
    public boolean isAsserted() {
        ProtectionAdapter current = adapter;
        return current != null
                && current.supportsSourceDestinationAuthorization()
                && current.supportsNeighborCaveInPropagation();
    }

    /** The currently installed adapter's record-keeping description, or {@code null} if none is installed. */
    public String evidenceDescription() {
        ProtectionAdapter current = adapter;
        return current == null ? null : current.describe();
    }
}
