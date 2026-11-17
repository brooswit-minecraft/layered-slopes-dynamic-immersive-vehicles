package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Cases 22-28 from the MINECRAFT-555 v4 accepted acceptance matrix (comment 54618): boundary
 * domains and the atomic-reject/retain-prior-values reload contract.
 */
class ErosionConfigTest {

    private static ErosionConfig withSlipThreshold(double v) {
        return new ErosionConfig(v, 0.01, 10.0, 300.0, 3.0, 0.25, 4.0);
    }

    @Test
    void case22_slipThresholdZero_isAccepted() {
        assertTrue(withSlipThreshold(0.0).isValid());
    }

    @Test
    void case23_slipThresholdEqualToReferenceSlip_isRejected() {
        // Strict-less-than relational constraint: equal values violate it.
        assertFalse(withSlipThreshold(10.0).isValid());
    }

    @Test
    void case24_baseErosionPerTickZero_isRejected() {
        ErosionConfig c = new ErosionConfig(0.15, 0.0, 10.0, 300.0, 3.0, 0.25, 4.0);
        assertFalse(c.isValid());
    }

    @Test
    void case25_maxSlipFactorOne_isAccepted() {
        ErosionConfig c = new ErosionConfig(0.15, 0.01, 10.0, 300.0, 1.0, 0.25, 4.0);
        assertTrue(c.isValid());
    }

    @Test
    void case26_maxSlipFactorBelowOne_isRejected() {
        ErosionConfig c = new ErosionConfig(0.15, 0.01, 10.0, 300.0, 0.99, 0.25, 4.0);
        assertFalse(c.isValid());
    }

    @Test
    void case27_minLoadFactorAboveOne_isRejected() {
        ErosionConfig c = new ErosionConfig(0.15, 0.01, 10.0, 300.0, 3.0, 1.5, 4.0);
        assertFalse(c.isValid());
    }

    @Test
    void case28_atomicReject_retainsAllSevenPriorValuesIncludingValidOnes() {
        ErosionConfig.Overrides overrides = new ErosionConfig.Overrides();
        ErosionConfig prior = overrides.current();

        // BASE_EROSION_PER_TICK=0.0 is invalid (case 24); the other 6 fields below are each
        // individually valid changes. The whole candidate must still be rejected and every
        // field -- including the 6 valid ones -- must retain its prior value.
        ErosionConfig candidate = new ErosionConfig(
                0.20,   // slipThresholdMs (individually valid change)
                0.0,    // baseErosionPerTick (INVALID)
                20.0,   // referenceSlipMs (individually valid change)
                500.0,  // referenceLoadKg (individually valid change)
                2.0,    // maxSlipFactor (individually valid change)
                0.5,    // minLoadFactor (individually valid change)
                3.0     // maxLoadFactor (individually valid change)
        );

        boolean applied = overrides.reload(candidate);

        assertFalse(applied);
        ErosionConfig after = overrides.current();
        assertEquals(prior.slipThresholdMs(), after.slipThresholdMs());
        assertEquals(prior.baseErosionPerTick(), after.baseErosionPerTick());
        assertEquals(prior.referenceSlipMs(), after.referenceSlipMs());
        assertEquals(prior.referenceLoadKg(), after.referenceLoadKg());
        assertEquals(prior.maxSlipFactor(), after.maxSlipFactor());
        assertEquals(prior.minLoadFactor(), after.minLoadFactor());
        assertEquals(prior.maxLoadFactor(), after.maxLoadFactor());
    }

    @Test
    void validReload_actuallyApplies() {
        ErosionConfig.Overrides overrides = new ErosionConfig.Overrides();
        ErosionConfig candidate = new ErosionConfig(0.20, 0.02, 12.0, 350.0, 2.5, 0.3, 3.5);

        assertTrue(overrides.reload(candidate));
        assertEquals(0.20, overrides.current().slipThresholdMs());
        assertEquals(0.02, overrides.current().baseErosionPerTick());
    }

    @Test
    void nonFiniteValuesAreRejected() {
        assertFalse(new ErosionConfig(Double.NaN, 0.01, 10.0, 300.0, 3.0, 0.25, 4.0).isValid());
        assertFalse(new ErosionConfig(0.15, Double.POSITIVE_INFINITY, 10.0, 300.0, 3.0, 0.25, 4.0).isValid());
    }

    @Test
    void defaultConfigIsItselfValid() {
        assertTrue(ErosionConfig.DEFAULT.isValid());
    }
}
