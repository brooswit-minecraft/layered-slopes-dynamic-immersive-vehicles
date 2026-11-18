package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ErosionProtectionGateTest {

    @Test
    void defaultsToNotAssertedFailingClosed() {
        ErosionProtectionGate gate = new ErosionProtectionGate();
        assertFalse(gate.isAsserted());
        assertNull(gate.assertedCoverageDescription());
    }

    @Test
    void assertingCoverageOpensTheGateAndRecordsTheExactDescription() {
        ErosionProtectionGate gate = new ErosionProtectionGate();
        gate.assertCoverage("SmoothingAuthorizationEvent source/destination only, no propagation");
        assertTrue(gate.isAsserted());
        assertEquals("SmoothingAuthorizationEvent source/destination only, no propagation",
                gate.assertedCoverageDescription());
    }

    @Test
    void blankOrNullDescriptionIsRejected() {
        ErosionProtectionGate gate = new ErosionProtectionGate();
        assertThrows(IllegalArgumentException.class, () -> gate.assertCoverage(null));
        assertThrows(IllegalArgumentException.class, () -> gate.assertCoverage(""));
        assertThrows(IllegalArgumentException.class, () -> gate.assertCoverage("   "));
        // None of those rejected attempts may have opened the gate.
        assertFalse(gate.isAsserted());
    }

    @Test
    void revokeClosesTheGateAgain() {
        ErosionProtectionGate gate = new ErosionProtectionGate();
        gate.assertCoverage("some coverage");
        assertTrue(gate.isAsserted());

        gate.revoke();
        assertFalse(gate.isAsserted());
        assertNull(gate.assertedCoverageDescription());
    }
}
