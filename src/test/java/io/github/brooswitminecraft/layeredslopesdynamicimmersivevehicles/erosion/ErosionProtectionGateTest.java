package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion.ErosionProtectionGate.CoverageArea;

class ErosionProtectionGateTest {

    @Test
    void defaultsToNotAssertedFailingClosed() {
        ErosionProtectionGate gate = new ErosionProtectionGate();
        assertFalse(gate.isAsserted());
        for (CoverageArea area : CoverageArea.values()) {
            assertFalse(gate.isAreaCovered(area));
            assertNull(gate.evidenceFor(area));
        }
    }

    @Test
    void sourceDestinationOnlyCoverageDoesNotOpenTheGate() {
        // Regression (PR #3 review 54845): this is EXACTLY the insufficient coverage the gate
        // must reject -- grading's merged SmoothingAuthorizationEvent covers source/destination
        // authorization only, and that alone must never be able to turn erosion on.
        ErosionProtectionGate gate = new ErosionProtectionGate();
        gate.assertCoverage(CoverageArea.SOURCE_DESTINATION_AUTHORIZATION,
                "SmoothingAuthorizationEvent, verified against the cancellation/stale-plan regressions on grading PR #2");
        assertFalse(gate.isAsserted());
        assertTrue(gate.isAreaCovered(CoverageArea.SOURCE_DESTINATION_AUTHORIZATION));
        assertFalse(gate.isAreaCovered(CoverageArea.NEIGHBOR_CAVE_IN_PROPAGATION));
    }

    @Test
    void unsupportedOrIncompleteProtectionCannotAuthorizeErosion() {
        // Same regression, stated the way the review framed it: an unsupported/incomplete
        // propagation area must leave the gate closed no matter what text accompanies it.
        ErosionProtectionGate gate = new ErosionProtectionGate();
        gate.assertCoverage(CoverageArea.SOURCE_DESTINATION_AUTHORIZATION, "fully covered and tested");
        assertFalse(gate.isAsserted(), "a single covered area must not authorize erosion while any other remains open");
    }

    @Test
    void onlyAssertingEveryAreaOpensTheGate() {
        ErosionProtectionGate gate = new ErosionProtectionGate();
        gate.assertCoverage(CoverageArea.SOURCE_DESTINATION_AUTHORIZATION, "SmoothingAuthorizationEvent, grading PR #2");
        assertFalse(gate.isAsserted());

        gate.assertCoverage(CoverageArea.NEIGHBOR_CAVE_IN_PROPAGATION, "real propagation adapter, verified against X");
        assertTrue(gate.isAsserted());
        assertEquals("SmoothingAuthorizationEvent, grading PR #2",
                gate.evidenceFor(CoverageArea.SOURCE_DESTINATION_AUTHORIZATION));
        assertEquals("real propagation adapter, verified against X",
                gate.evidenceFor(CoverageArea.NEIGHBOR_CAVE_IN_PROPAGATION));
    }

    @Test
    void blankOrNullEvidenceIsRejected() {
        ErosionProtectionGate gate = new ErosionProtectionGate();
        assertThrows(IllegalArgumentException.class,
                () -> gate.assertCoverage(CoverageArea.SOURCE_DESTINATION_AUTHORIZATION, null));
        assertThrows(IllegalArgumentException.class,
                () -> gate.assertCoverage(CoverageArea.SOURCE_DESTINATION_AUTHORIZATION, ""));
        assertThrows(IllegalArgumentException.class,
                () -> gate.assertCoverage(CoverageArea.SOURCE_DESTINATION_AUTHORIZATION, "   "));
        assertFalse(gate.isAreaCovered(CoverageArea.SOURCE_DESTINATION_AUTHORIZATION));
    }

    @Test
    void nullAreaIsRejected() {
        ErosionProtectionGate gate = new ErosionProtectionGate();
        assertThrows(IllegalArgumentException.class, () -> gate.assertCoverage(null, "evidence"));
    }

    @Test
    void revokingOneAreaClosesTheGateAgain() {
        ErosionProtectionGate gate = new ErosionProtectionGate();
        gate.assertCoverage(CoverageArea.SOURCE_DESTINATION_AUTHORIZATION, "a");
        gate.assertCoverage(CoverageArea.NEIGHBOR_CAVE_IN_PROPAGATION, "b");
        assertTrue(gate.isAsserted());

        gate.revoke(CoverageArea.NEIGHBOR_CAVE_IN_PROPAGATION);
        assertFalse(gate.isAsserted());
        assertTrue(gate.isAreaCovered(CoverageArea.SOURCE_DESTINATION_AUTHORIZATION));
        assertFalse(gate.isAreaCovered(CoverageArea.NEIGHBOR_CAVE_IN_PROPAGATION));
    }

    @Test
    void revokeAllClosesTheGateCompletely() {
        ErosionProtectionGate gate = new ErosionProtectionGate();
        gate.assertCoverage(CoverageArea.SOURCE_DESTINATION_AUTHORIZATION, "a");
        gate.assertCoverage(CoverageArea.NEIGHBOR_CAVE_IN_PROPAGATION, "b");
        assertTrue(gate.isAsserted());

        gate.revokeAll();
        assertFalse(gate.isAsserted());
        for (CoverageArea area : CoverageArea.values()) {
            assertFalse(gate.isAreaCovered(area));
        }
    }
}
