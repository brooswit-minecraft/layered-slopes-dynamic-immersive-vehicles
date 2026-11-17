package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

/**
 * Deterministic slip/load/resistance-to-probability mapping accepted for MINECRAFT-510 in the
 * MINECRAFT-555 prep packet (comments 54607/54611/54614/54618, approved 54622/54623). Pure math,
 * no Minecraft/NeoForge classes, no RNG: the caller draws the actual roll against the returned
 * probability and owns dedup/lifecycle bookkeeping.
 *
 * <p>Longitudinal wheelspin/lockup is a decided, owned scope limitation, not a deferral: DIV has
 * no wheel-rotation-vs-ground-speed slip model today (54611 Gap 1), so {@code slipSpeedMs} here
 * is the true lateral contact slip {@code abs(sideSpeed)}, never a fabricated longitudinal
 * signal.
 */
public final class ErosionMath {

    private ErosionMath() {
    }

    /** {@code effectiveSlip = max(0, slipSpeed - threshold)}. */
    public static double effectiveSlip(double slipSpeedMs, double slipThresholdMs) {
        return Math.max(0.0, slipSpeedMs - slipThresholdMs);
    }

    /** {@code slipFactor = min(maxSlipFactor, effectiveSlip / referenceSlipMs)}. */
    public static double slipFactor(double effectiveSlipMs, double referenceSlipMs, double maxSlipFactor) {
        return Math.min(maxSlipFactor, effectiveSlipMs / referenceSlipMs);
    }

    /** {@code loadFactor = clamp(wheelLoadKg / referenceLoadKg, minLoadFactor, maxLoadFactor)}. */
    public static double loadFactor(double wheelLoadKg, double referenceLoadKg, double minLoadFactor, double maxLoadFactor) {
        double raw = wheelLoadKg / referenceLoadKg;
        return Math.max(minLoadFactor, Math.min(maxLoadFactor, raw));
    }

    /** {@code amount = baseErosionPerTick * slipFactor * loadFactor}. */
    public static double amount(double baseErosionPerTick, double slipFactor, double loadFactor) {
        return baseErosionPerTick * slipFactor * loadFactor;
    }

    /**
     * {@code compress(v, ceiling) = v < 0 ? 1.0 : min(1, log1p(v) / log1p(ceiling))}.
     * A negative value (e.g. bedrock's unbreakable hardness) always compresses to the maximum.
     */
    public static double compress(double v, double ceiling) {
        if (v < 0) {
            return 1.0;
        }
        return Math.min(1.0, Math.log1p(v) / Math.log1p(ceiling));
    }

    /**
     * {@code resistance = 0.5*compress(hardness, 50.0) + 0.5*compress(blastResistance, 1200.0)}.
     * Reads only universal vanilla BlockState.getDestroySpeed/getExplosionResistance values;
     * this is DIV's own independent implementation, not a call into any Dynamic Terrain class.
     */
    public static double resistance(double hardness, double blastResistance) {
        return 0.5 * compress(hardness, 50.0) + 0.5 * compress(blastResistance, 1200.0);
    }

    /** {@code probability = clamp(0, 1, amount * (1 - resistance)^4)}. */
    public static double probability(double amount, double resistance) {
        double raw = amount * Math.pow(1.0 - resistance, 4);
        return Math.max(0.0, Math.min(1.0, raw));
    }

    /**
     * Full pipeline per the accepted contract. Returns 0.0 (SKIPPED, no RNG draw expected from
     * the caller) when {@code slipSpeedMs <= config.slipThresholdMs()} without evaluating the
     * rest of the formula, matching DT TireSlip.report's amount&lt;=0 short-circuit shape.
     */
    public static double successChance(double slipSpeedMs, double wheelLoadKg, double hardness,
                                        double blastResistance, ErosionConfig config) {
        if (slipSpeedMs <= config.slipThresholdMs()) {
            return 0.0;
        }
        double effSlip = effectiveSlip(slipSpeedMs, config.slipThresholdMs());
        double slip = slipFactor(effSlip, config.referenceSlipMs(), config.maxSlipFactor());
        double load = loadFactor(wheelLoadKg, config.referenceLoadKg(), config.minLoadFactor(), config.maxLoadFactor());
        double amt = amount(config.baseErosionPerTick(), slip, load);
        double res = resistance(hardness, blastResistance);
        return probability(amt, res);
    }
}
