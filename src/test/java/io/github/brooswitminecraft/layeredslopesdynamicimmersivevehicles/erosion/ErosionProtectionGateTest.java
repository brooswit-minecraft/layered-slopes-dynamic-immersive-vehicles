package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion.ErosionProtectionGate.MutationRequest;
import io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion.ErosionProtectionGate.ProtectionAdapter;

class ErosionProtectionGateTest {

    private static final MutationRequest REQUEST =
            new MutationRequest("minecraft:overworld", 1, 2, 3, 4, 2, 3, true);
    private static final MutationRequest REQUEST_UNKNOWN_PROPAGATION =
            new MutationRequest("minecraft:overworld", 1, 2, 3, 4, 2, 3, false);

    private static ProtectionAdapter adapter(boolean sourceDestination, boolean propagation, boolean authorizeResult, String description) {
        return new ProtectionAdapter() {
            @Override
            public boolean supportsSourceDestinationAuthorization() {
                return sourceDestination;
            }

            @Override
            public boolean supportsNeighborCaveInPropagation() {
                return propagation;
            }

            @Override
            public String describe() {
                return description;
            }

            @Override
            public boolean authorize(MutationRequest request) {
                return authorizeResult;
            }
        };
    }

    @Test
    void defaultsToNotReadyAndDenyingEveryAuthorizeCall() {
        ErosionProtectionGate gate = new ErosionProtectionGate();
        assertFalse(gate.isReady());
        assertFalse(gate.authorize(REQUEST));
        assertNull(gate.evidenceDescription());
    }

    @Test
    void anAdapterThatOnlyClaimsSourceDestinationSupportIsNeitherReadyNorAuthorizing() {
        // Regression (PR #3 reviews 54845/54853/54855): a source/destination-only adapter must
        // deny, no matter how the claim is worded -- checked via the adapter's OWN boolean
        // methods, not a trusted description string.
        ErosionProtectionGate gate = new ErosionProtectionGate();
        gate.assertCoverage(adapter(true, false, true, "SmoothingAuthorizationEvent, grading PR #2 -- propagation not implemented"));
        assertFalse(gate.isReady());
        assertFalse(gate.authorize(REQUEST));
    }

    @Test
    void readinessAloneDoesNotAuthorizeAnything() {
        // Regression (review 54855): isReady()==true is a READINESS signal, never itself
        // permission to mutate. A fully-ready adapter whose own authorize() denies a specific
        // request must still deny that request.
        ErosionProtectionGate gate = new ErosionProtectionGate();
        gate.assertCoverage(adapter(true, true, false, "ready, but this specific attempt is denied"));
        assertTrue(gate.isReady());
        assertFalse(gate.authorize(REQUEST));
    }

    @Test
    void anAdapterAdvertisingCapabilityButDenyingUnknownPropagationScopeIsDenied() {
        // The exact scenario review 54855 named: "missing/unknown propagation scope" must deny
        // even from an otherwise-ready, capable adapter.
        ProtectionAdapter denyUnknownPropagation = new ProtectionAdapter() {
            @Override
            public boolean supportsSourceDestinationAuthorization() {
                return true;
            }

            @Override
            public boolean supportsNeighborCaveInPropagation() {
                return true;
            }

            @Override
            public String describe() {
                return "real adapter that denies when propagation scope is unknown";
            }

            @Override
            public boolean authorize(MutationRequest request) {
                return request.propagationScopeKnown();
            }
        };
        ErosionProtectionGate gate = new ErosionProtectionGate();
        gate.assertCoverage(denyUnknownPropagation);
        assertTrue(gate.isReady());
        assertTrue(gate.authorize(REQUEST), "sanity: known propagation scope is authorized");
        assertFalse(gate.authorize(REQUEST_UNKNOWN_PROPAGATION), "unknown propagation scope must be denied");
    }

    @Test
    void onlyAReadyAdapterThatAlsoAuthorizesTheSpecificRequestGrantsIt() {
        ErosionProtectionGate gate = new ErosionProtectionGate();
        gate.assertCoverage(adapter(true, true, true, "real adapter, verified against X, authorizes this request"));
        assertTrue(gate.isReady());
        assertTrue(gate.authorize(REQUEST));
    }

    @Test
    void theGateIsReCheckedLiveNotCachedFromTheMomentOfAssertion() {
        boolean[] propagationSupported = {true};
        ProtectionAdapter mutableAdapter = new ProtectionAdapter() {
            @Override
            public boolean supportsSourceDestinationAuthorization() {
                return true;
            }

            @Override
            public boolean supportsNeighborCaveInPropagation() {
                return propagationSupported[0];
            }

            @Override
            public String describe() {
                return "mutable test adapter";
            }

            @Override
            public boolean authorize(MutationRequest request) {
                return true;
            }
        };
        ErosionProtectionGate gate = new ErosionProtectionGate();
        gate.assertCoverage(mutableAdapter);
        assertTrue(gate.authorize(REQUEST));

        propagationSupported[0] = false;
        assertFalse(gate.isReady(), "a live capability regression must close readiness immediately");
        assertFalse(gate.authorize(REQUEST), "and must deny every subsequent authorize() call too");
    }

    @Test
    void missingDimensionIsDeniedBeforeEverReachingAnAlwaysGrantingAdapter() {
        // Regression (review 54861/54863, PR comment 6968499181): the gate itself must fail
        // closed on mandatory context it cannot even identify -- an adapter that unconditionally
        // grants everything must never be able to launder a request the gate can't identify.
        int[] adapterInvocations = {0};
        ProtectionAdapter alwaysGrants = countingAdapter(adapterInvocations);
        ErosionProtectionGate gate = new ErosionProtectionGate();
        gate.assertCoverage(alwaysGrants);
        MutationRequest missingDimension = new MutationRequest(null, 1, 2, 3, 4, 2, 3, true);
        assertFalse(gate.authorize(missingDimension));
        assertTrue(adapterInvocations[0] == 0, "the gate must deny missing-dimension requests without ever delegating to the adapter");
    }

    @Test
    void unknownPropagationScopeIsDeniedBeforeEverReachingAnAlwaysGrantingAdapter() {
        // Same regression as above, for the other mandatory-context field named in review
        // 54861/54863: propagationScopeKnown == false must deny on its own, independent of
        // whatever an always-granting adapter would have said.
        int[] adapterInvocations = {0};
        ProtectionAdapter alwaysGrants = countingAdapter(adapterInvocations);
        ErosionProtectionGate gate = new ErosionProtectionGate();
        gate.assertCoverage(alwaysGrants);
        assertFalse(gate.authorize(REQUEST_UNKNOWN_PROPAGATION));
        assertTrue(adapterInvocations[0] == 0, "the gate must deny unknown-propagation-scope requests without ever delegating to the adapter");
    }

    private static ProtectionAdapter countingAdapter(int[] invocationCounter) {
        return new ProtectionAdapter() {
            @Override
            public boolean supportsSourceDestinationAuthorization() {
                return true;
            }

            @Override
            public boolean supportsNeighborCaveInPropagation() {
                return true;
            }

            @Override
            public String describe() {
                return "always-granting adapter used only to prove the gate denies without delegating";
            }

            @Override
            public boolean authorize(MutationRequest request) {
                invocationCounter[0]++;
                return true;
            }
        };
    }

    @Test
    void nullAdapterIsRejected() {
        ErosionProtectionGate gate = new ErosionProtectionGate();
        assertThrows(IllegalArgumentException.class, () -> gate.assertCoverage(null));
    }

    @Test
    void nullRequestIsRejected() {
        ErosionProtectionGate gate = new ErosionProtectionGate();
        gate.assertCoverage(adapter(true, true, true, "ready"));
        assertThrows(IllegalArgumentException.class, () -> gate.authorize(null));
    }

    @Test
    void blankDescribeIsRejected() {
        ErosionProtectionGate gate = new ErosionProtectionGate();
        assertThrows(IllegalArgumentException.class, () -> gate.assertCoverage(adapter(true, true, true, null)));
        assertThrows(IllegalArgumentException.class, () -> gate.assertCoverage(adapter(true, true, true, "")));
        assertThrows(IllegalArgumentException.class, () -> gate.assertCoverage(adapter(true, true, true, "   ")));
        assertFalse(gate.isReady());
    }

    @Test
    void revokeAllClosesTheGateAgain() {
        ErosionProtectionGate gate = new ErosionProtectionGate();
        gate.assertCoverage(adapter(true, true, true, "fully covered"));
        assertTrue(gate.isReady());
        assertTrue(gate.authorize(REQUEST));

        gate.revokeAll();
        assertFalse(gate.isReady());
        assertFalse(gate.authorize(REQUEST));
        assertNull(gate.evidenceDescription());
    }
}
