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

    // Direction, comment 54943: the default-OFF erosionEnabled switch rides in the same
    // immutable tuple and atomic-reject reload as the 7 accepted-contract fields above.

    @Test
    void defaultConfigHasErosionDisabled() {
        assertFalse(ErosionConfig.DEFAULT.erosionEnabled());
    }

    @Test
    void theLegacySevenArgConstructorDefaultsErosionEnabledToTrue() {
        // Every call site written before this switch existed assumed erosion is live; the
        // 7-arg constructor must keep that assumption true without those call sites changing.
        ErosionConfig c = new ErosionConfig(0.15, 0.01, 10.0, 300.0, 3.0, 0.25, 4.0);
        assertTrue(c.erosionEnabled());
    }

    @Test
    void erosionEnabledNeverCausesIsValidToRejectOnItsOwn() {
        ErosionConfig enabled = new ErosionConfig(0.15, 0.01, 10.0, 300.0, 3.0, 0.25, 4.0, true);
        ErosionConfig disabled = new ErosionConfig(0.15, 0.01, 10.0, 300.0, 3.0, 0.25, 4.0, false);
        assertTrue(enabled.isValid());
        assertTrue(disabled.isValid());
    }

    @Test
    void atomicReject_alsoPreservesThePriorErosionEnabledValue() {
        ErosionConfig.Overrides overrides = new ErosionConfig.Overrides(
                new ErosionConfig(0.15, 0.01, 10.0, 300.0, 3.0, 0.25, 4.0, true));
        // Individually-valid tuning fields, but erosionEnabled flipped to false and
        // baseErosionPerTick=0.0 is invalid (case 24) -- the whole candidate, including the
        // erosionEnabled flip, must be rejected and the prior true value retained.
        ErosionConfig candidate = new ErosionConfig(0.20, 0.0, 20.0, 500.0, 2.0, 0.5, 3.0, false);

        assertFalse(overrides.reload(candidate));
        assertTrue(overrides.current().erosionEnabled());
    }

    @Test
    void validReload_actuallyAppliesANewErosionEnabledValue() {
        ErosionConfig.Overrides overrides = new ErosionConfig.Overrides();
        assertFalse(overrides.current().erosionEnabled());

        ErosionConfig candidate = new ErosionConfig(0.15, 0.01, 10.0, 300.0, 3.0, 0.25, 4.0, true);
        assertTrue(overrides.reload(candidate));
        assertTrue(overrides.current().erosionEnabled());
    }
}
