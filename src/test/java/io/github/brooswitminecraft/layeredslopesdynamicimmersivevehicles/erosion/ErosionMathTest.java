package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Cases 17-21 from the MINECRAFT-555 v4 accepted acceptance matrix (comment 54618), plus the
 * resistance/clamp guards it depends on. Default config throughout (ErosionConfig.DEFAULT).
 */
class ErosionMathTest {

    private final ErosionConfig cfg = ErosionConfig.DEFAULT;

    @Test
    void noSlipGuardSkipsBeforeThreshold() {
        // slipSpeed <= SLIP_THRESHOLD_MS (0.15) short-circuits to 0.0 without evaluating the rest
        // of the formula -- zero RNG draw expected from the caller.
        assertEquals(0.0, ErosionMath.successChance(0.15, cfg.referenceLoadKg(), 1.0, 1.0, cfg));
        assertEquals(0.0, ErosionMath.successChance(0.0, cfg.referenceLoadKg(), 1.0, 1.0, cfg));
    }

    @Test
    void case17_lowJustAboveThreshold_isAlgebraicallyExact() {
        // slipSpeed = 0.16 -> effectiveSlip=0.01, slipFactor=0.001, loadFactor=1.0 (load at
        // reference), amount=0.00001, resistance=0 (hardness<0 branch avoided: use 0 hardness
        // with 0 blastResistance) -> probability=0.00001 exactly.
        double chance = ErosionMath.successChance(0.16, cfg.referenceLoadKg(), 0.0, 0.0, cfg);
        assertEquals(0.00001, chance, 1e-12);
    }

    @Test
    void case18_atReferencePoint_isExactlyBase() {
        // slipSpeed = threshold + referenceSlip = 10.15 -> effectiveSlip=10.0 exactly,
        // slipFactor=1.0, loadFactor=1.0 (load at reference) -> amount=BASE=0.01,
        // resistance=0 -> probability=0.01 exactly.
        double slipSpeed = cfg.slipThresholdMs() + cfg.referenceSlipMs();
        double chance = ErosionMath.successChance(slipSpeed, cfg.referenceLoadKg(), 0.0, 0.0, cfg);
        assertEquals(0.01, chance, 1e-12);
    }

    @Test
    void case19_highSlip_isAmountClampedByMaxSlipFactorNotProbability() {
        // Far above threshold + 3*referenceSlip -> slipFactor saturates at MAX_SLIP_FACTOR=3.0,
        // amount=BASE*3*loadFactor(=1 at reference load)=0.03, well under 1.0 -- the probability
        // clamp(0,1) is NOT what bounds this case; the amount-side MAX_SLIP_FACTOR clamp is.
        double farAboveSlip = cfg.slipThresholdMs() + cfg.referenceSlipMs() * 50;
        double chance = ErosionMath.successChance(farAboveSlip, cfg.referenceLoadKg(), 0.0, 0.0, cfg);
        assertEquals(cfg.baseErosionPerTick() * cfg.maxSlipFactor(), chance, 1e-12);
        assertTrue(chance < 1.0);
    }

    @Test
    void underDefaultConfig_probabilityNeverReachesTheClampCeiling() {
        // max possible default probability is BASE*MAX_SLIP_FACTOR*MAX_LOAD_FACTOR = 0.12 at
        // resistance=0 -- the clamp(0,1) in ErosionMath.probability is a defensive bound against
        // future/operator-configured constants, not something the default path exercises.
        double extremeSlip = cfg.slipThresholdMs() + cfg.referenceSlipMs() * 1000;
        double extremeLoad = cfg.referenceLoadKg() * 1000;
        double chance = ErosionMath.successChance(extremeSlip, extremeLoad, 0.0, 0.0, cfg);
        assertEquals(0.12, chance, 1e-9);
        assertTrue(chance < 1.0);
    }

    @Test
    void unbreakableBlocksNeverErode() {
        // Bedrock-like: unbreakable (negative hardness) AND blast-proof far past the ceiling --
        // both terms of the 0.5/0.5 average must hit 1.0 for resistance=1.0 -> probability=0.
        assertEquals(1.0, ErosionMath.resistance(-1, 1_000_000));
        double chance = ErosionMath.successChance(1000.0, cfg.referenceLoadKg(), -1, 1_000_000, cfg);
        assertEquals(0.0, chance);
    }

    @Test
    void softerBlocksErodeMoreReadilyThanHarderOnes() {
        double soft = ErosionMath.resistance(0.5, 2.5);
        double hard = ErosionMath.resistance(50.0, 1200.0);
        assertTrue(soft < hard);
        double softChance = ErosionMath.successChance(1000.0, cfg.referenceLoadKg(), 0.5, 2.5, cfg);
        double hardChance = ErosionMath.successChance(1000.0, cfg.referenceLoadKg(), 50.0, 1200.0, cfg);
        assertTrue(softChance > hardChance);
    }

    @Test
    void probabilityIsNonDecreasingInWheelLoadWithinClampedRange() {
        double low = ErosionMath.successChance(1000.0, cfg.minLoadFactor() * cfg.referenceLoadKg(), 0.0, 0.0, cfg);
        double mid = ErosionMath.successChance(1000.0, cfg.referenceLoadKg(), 0.0, 0.0, cfg);
        double high = ErosionMath.successChance(1000.0, cfg.maxLoadFactor() * cfg.referenceLoadKg(), 0.0, 0.0, cfg);
        assertTrue(low < mid);
        assertTrue(mid < high);
        // Clamped flat outside [minLoadFactor, maxLoadFactor] * referenceLoadKg.
        double belowFloor = ErosionMath.successChance(1000.0, cfg.minLoadFactor() * cfg.referenceLoadKg() / 2, 0.0, 0.0, cfg);
        assertEquals(low, belowFloor, 1e-12);
    }
}
