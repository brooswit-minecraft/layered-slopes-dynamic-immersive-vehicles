package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion.ErosionProtectionGate.ProtectionAdapter;

class ErosionProtectionGateTest {

    private static ProtectionAdapter adapter(boolean sourceDestination, boolean propagation, String description) {
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
        };
    }

    @Test
    void defaultsToNotAssertedFailingClosed() {
        ErosionProtectionGate gate = new ErosionProtectionGate();
        assertFalse(gate.isAsserted());
        assertNull(gate.evidenceDescription());
    }

    @Test
    void anAdapterThatOnlyClaimsSourceDestinationSupportKeepsTheGateClosed() {
        // Regression (PR #3 reviews 54845/54853): a source/destination-only adapter must deny,
        // no matter how the claim is worded -- this checks the adapter's OWN boolean method,
        // not a trusted description string.
        ErosionProtectionGate gate = new ErosionProtectionGate();
        gate.assertCoverage(adapter(true, false, "SmoothingAuthorizationEvent, grading PR #2 -- propagation not implemented"));
        assertFalse(gate.isAsserted());
    }

    @Test
    void anAdapterThatClaimsBothAreasInTextButActuallyReportsOnlyOneStillDeniesInPractice() {
        // Same regression, worded the way 54853 framed it: even if the description text names
        // both areas, the gate only trusts the adapter's own boolean methods -- if
        // supportsNeighborCaveInPropagation() reports false, no description can override that.
        ErosionProtectionGate gate = new ErosionProtectionGate();
        gate.assertCoverage(adapter(true, false,
                "claims SOURCE_DESTINATION_AUTHORIZATION and NEIGHBOR_CAVE_IN_PROPAGATION are both covered"));
        assertFalse(gate.isAsserted());
    }

    @Test
    void anAdapterSupportingNeitherAreaKeepsTheGateClosed() {
        ErosionProtectionGate gate = new ErosionProtectionGate();
        gate.assertCoverage(adapter(false, false, "no real integration yet"));
        assertFalse(gate.isAsserted());
    }

    @Test
    void onlyAnAdapterSupportingBothAreasOpensTheGate() {
        ErosionProtectionGate gate = new ErosionProtectionGate();
        gate.assertCoverage(adapter(true, true, "real source/destination AND propagation adapter, verified against X"));
        assertTrue(gate.isAsserted());
    }

    @Test
    void theGateIsReCheckedLiveNotCachedFromTheMomentOfAssertion() {
        // isAsserted() consults the adapter's methods every call -- a mutable test adapter whose
        // reported support changes must be reflected immediately, proving nothing was cached.
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
        };
        ErosionProtectionGate gate = new ErosionProtectionGate();
        gate.assertCoverage(mutableAdapter);
        assertTrue(gate.isAsserted());

        propagationSupported[0] = false;
        assertFalse(gate.isAsserted(), "a live capability regression must close the gate immediately, not stay cached open");
    }

    @Test
    void nullAdapterIsRejected() {
        ErosionProtectionGate gate = new ErosionProtectionGate();
        assertThrows(IllegalArgumentException.class, () -> gate.assertCoverage(null));
    }

    @Test
    void blankDescribeIsRejected() {
        ErosionProtectionGate gate = new ErosionProtectionGate();
        assertThrows(IllegalArgumentException.class, () -> gate.assertCoverage(adapter(true, true, null)));
        assertThrows(IllegalArgumentException.class, () -> gate.assertCoverage(adapter(true, true, "")));
        assertThrows(IllegalArgumentException.class, () -> gate.assertCoverage(adapter(true, true, "   ")));
        assertFalse(gate.isAsserted());
    }

    @Test
    void revokeAllClosesTheGateAgain() {
        ErosionProtectionGate gate = new ErosionProtectionGate();
        gate.assertCoverage(adapter(true, true, "fully covered"));
        assertTrue(gate.isAsserted());

        gate.revokeAll();
        assertFalse(gate.isAsserted());
        assertNull(gate.evidenceDescription());
    }
}
