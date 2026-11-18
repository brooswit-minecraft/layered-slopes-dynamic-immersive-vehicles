package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

/**
 * Hard, code-level gate on whether this mod's erosion consumer is permitted to run at all --
 * per the ticket's "Protection is a release gate, not a TODO silently skipped" requirement.
 *
 * <p>Three review rounds (PR #3, comments 54845/54853/54854/54855) progressively found the
 * design still conflated READINESS (does a capable adapter exist at all) with AUTHORIZATION
 * (is THIS SPECIFIC attempted mutation actually permitted). {@link #isReady()} is readiness
 * ONLY and must never itself be read as permission to mutate anything -- a fully capable
 * adapter can still, correctly, deny an individual {@link #authorize} call (e.g. because that
 * specific attempt's propagation scope is unknown to it). The erosion consumer MUST call {@link
 * #authorize} for every attempted mutation and must not proceed unless it returns {@code true};
 * checking {@link #isReady()} alone is never sufficient and is provided only for diagnostics.
 *
 * <p><b>Not currently used by {@code ErosionAttempt}.</b> Two different {@code ProtectionAdapter}
 * implementations built against this class (review 54855/54861/54878/54890/54899/54901) were
 * each independently found to claim a capability they could not actually verify. The root cause:
 * {@link MutationRequest} is deliberately Minecraft-free (so this class stays pure-unit-testable,
 * see {@code ErosionProtectionGateTest}), which cannot host a real per-attempt check that needs
 * a live {@code ServerLevel}/{@code BlockPos} -- {@code ErosionAttempt} instead enforces
 * {@code ClaimProtection} and {@code DynamicTerrainCaveInRisk} directly, as mandatory,
 * unconditional steps. This class remains correct on its own terms and may host a genuinely
 * Minecraft-free policy in the future; it is simply not the vehicle for those two checks.
 */
public final class ErosionProtectionGate {

    /** One specific attempted mutation, described abstractly so this class stays free of any Minecraft reference. */
    public record MutationRequest(Object dimension, int sourceX, int sourceY, int sourceZ,
                                   int destinationX, int destinationY, int destinationZ,
                                   boolean propagationScopeKnown) {}

    /**
     * A real, checkable protection integration. {@link #supportsSourceDestinationAuthorization()}
     * and {@link #supportsNeighborCaveInPropagation()} are READINESS capability declarations
     * only -- grading's merged {@code SmoothingAuthorizationEvent} would back the former; no
     * implementation of the latter exists yet, and no production adapter shipped in this slice
     * returns {@code true} for it. {@link #authorize} is the actual per-attempt decision: even
     * a fully capable adapter may deny a specific request (e.g. {@code
     * propagationScopeKnown=false}), and nothing here claims an adapter unconditionally
     * authorizing every request is itself evidence of real protection.
     */
    public interface ProtectionAdapter {
        boolean supportsSourceDestinationAuthorization();

        boolean supportsNeighborCaveInPropagation();

        /** Free-text record-keeping only -- never itself consulted by {@link #isReady()} or {@link #authorize}. */
        String describe();

        /** The per-attempt decision for exactly this {@code request}. */
        boolean authorize(MutationRequest request);
    }

    private volatile ProtectionAdapter adapter;

    /** Installs the adapter whose methods will be consulted by {@link #isReady()} and {@link #authorize}. */
    public void assertCoverage(ProtectionAdapter adapter) {
        if (adapter == null) {
            throw new IllegalArgumentException("adapter must not be null");
        }
        if (adapter.describe() == null || adapter.describe().isBlank()) {
            throw new IllegalArgumentException("adapter.describe() must name exactly what was implemented and tested, not be blank");
        }
        this.adapter = adapter;
    }

    /** Removes the installed adapter. {@link #isReady()} becomes {@code false} and {@link #authorize} denies everything. */
    public void revokeAll() {
        this.adapter = null;
    }

    /**
     * READINESS ONLY -- {@code true} if an adapter is installed AND it reports support for
     * every required area right now. Does NOT mean any particular mutation is authorized; the
     * consumer must still call {@link #authorize} for each actual attempt. Re-checked live on
     * every call, never cached from the moment {@link #assertCoverage} was called.
     */
    public boolean isReady() {
        ProtectionAdapter current = adapter;
        return current != null
                && current.supportsSourceDestinationAuthorization()
                && current.supportsNeighborCaveInPropagation();
    }

    /**
     * The actual per-attempt authorization decision for {@code request}. Denies on its own,
     * before ever consulting the adapter, if the request's mandatory context is missing or
     * unknown ({@code dimension == null} or {@code propagationScopeKnown == false}) -- an
     * adapter that unconditionally grants every request must not be able to launder a request
     * this gate itself cannot even identify. Otherwise {@code false} if no adapter is installed,
     * if the installed adapter is not fully {@link #isReady() ready}, or if the ready adapter's
     * own {@link ProtectionAdapter#authorize} denies this specific request -- any one of those
     * is sufficient to deny; only all conditions together (identifiable context, installed,
     * ready, and the adapter's own per-request grant) authorize a mutation.
     */
    public boolean authorize(MutationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        // Fail closed on missing/unknown mandatory context ourselves, before ever reaching the
        // adapter -- an adapter that unconditionally grants must not be able to launder a
        // request this gate itself cannot even identify (review 54861/54863, PR comment
        // 6968499181).
        if (request.dimension() == null || !request.propagationScopeKnown()) {
            return false;
        }
        ProtectionAdapter current = adapter;
        if (current == null) {
            return false;
        }
        if (!current.supportsSourceDestinationAuthorization() || !current.supportsNeighborCaveInPropagation()) {
            return false;
        }
        return current.authorize(request);
    }

    /** The currently installed adapter's record-keeping description, or {@code null} if none is installed. */
    public String evidenceDescription() {
        ProtectionAdapter current = adapter;
        return current == null ? null : current.describe();
    }
}
