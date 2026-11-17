package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

/**
 * Immutable erosion-policy config snapshot, per the MINECRAFT-555 v4 prep packet (comment 54618,
 * approved 54622/54623). {@link #validate()} checks all 7 fields plus the relational constraints
 * as one composed tuple (never field-by-field): {@link Overrides#reload} discards the ENTIRE
 * candidate and keeps every prior value unchanged if any single check fails (atomic reject,
 * case 28). Values are not individually mutable after construction.
 */
public final class ErosionConfig {

    public static final ErosionConfig DEFAULT = new ErosionConfig(
            0.15,   // slipThresholdMs
            0.01,   // baseErosionPerTick
            10.0,   // referenceSlipMs
            300.0,  // referenceLoadKg
            3.0,    // maxSlipFactor
            0.25,   // minLoadFactor
            4.0     // maxLoadFactor
    );

    private final double slipThresholdMs;
    private final double baseErosionPerTick;
    private final double referenceSlipMs;
    private final double referenceLoadKg;
    private final double maxSlipFactor;
    private final double minLoadFactor;
    private final double maxLoadFactor;

    public ErosionConfig(double slipThresholdMs, double baseErosionPerTick, double referenceSlipMs,
                          double referenceLoadKg, double maxSlipFactor, double minLoadFactor,
                          double maxLoadFactor) {
        this.slipThresholdMs = slipThresholdMs;
        this.baseErosionPerTick = baseErosionPerTick;
        this.referenceSlipMs = referenceSlipMs;
        this.referenceLoadKg = referenceLoadKg;
        this.maxSlipFactor = maxSlipFactor;
        this.minLoadFactor = minLoadFactor;
        this.maxLoadFactor = maxLoadFactor;
    }

    public double slipThresholdMs() {
        return slipThresholdMs;
    }

    public double baseErosionPerTick() {
        return baseErosionPerTick;
    }

    public double referenceSlipMs() {
        return referenceSlipMs;
    }

    public double referenceLoadKg() {
        return referenceLoadKg;
    }

    public double maxSlipFactor() {
        return maxSlipFactor;
    }

    public double minLoadFactor() {
        return minLoadFactor;
    }

    public double maxLoadFactor() {
        return maxLoadFactor;
    }

    /**
     * True if every finite-domain and relational constraint from the accepted contract holds.
     * Never partially applied by a caller -- {@link Overrides#reload} treats this as one atomic
     * gate over the whole candidate tuple.
     */
    public boolean isValid() {
        if (!(isFinite(slipThresholdMs) && slipThresholdMs >= 0.0 && slipThresholdMs < referenceSlipMs)) {
            return false;
        }
        if (!(isFinite(baseErosionPerTick) && baseErosionPerTick > 0.0 && baseErosionPerTick <= 1000.0)) {
            return false;
        }
        if (!(isFinite(referenceSlipMs) && referenceSlipMs > 0.0 && referenceSlipMs <= 1000.0)) {
            return false;
        }
        if (!(isFinite(referenceLoadKg) && referenceLoadKg > 0.0 && referenceLoadKg <= 100000.0)) {
            return false;
        }
        if (!(isFinite(maxSlipFactor) && maxSlipFactor >= 1.0 && maxSlipFactor <= 100.0)) {
            return false;
        }
        if (!(isFinite(minLoadFactor) && minLoadFactor > 0.0 && minLoadFactor <= 1.0)) {
            return false;
        }
        if (!(isFinite(maxLoadFactor) && maxLoadFactor >= 1.0 && maxLoadFactor <= 1000.0)) {
            return false;
        }
        return minLoadFactor <= maxLoadFactor;
    }

    private static boolean isFinite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    /**
     * Holds the live, hot-reloadable config snapshot. {@link #reload} is the atomic-rejected
     * composed-tuple gate from the accepted contract: an invalid candidate is discarded whole and
     * the prior snapshot is retained unchanged (case 28), never applied field-by-field.
     */
    public static final class Overrides {
        private volatile ErosionConfig current;

        public Overrides() {
            this(ErosionConfig.DEFAULT);
        }

        public Overrides(ErosionConfig initial) {
            if (!initial.isValid()) {
                throw new IllegalArgumentException("initial ErosionConfig must itself be valid");
            }
            this.current = initial;
        }

        public ErosionConfig current() {
            return current;
        }

        /**
         * @return true if {@code candidate} was valid and is now live; false if it was rejected
         *         and {@link #current()} is unchanged.
         */
        public boolean reload(ErosionConfig candidate) {
            if (!candidate.isValid()) {
                return false;
            }
            this.current = candidate;
            return true;
        }
    }
}
