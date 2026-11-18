package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Real NeoForge server-config wiring for {@link ErosionConfig}/{@link ErosionConfig.Overrides}
 * (direction, comment 54943: "preserve... immutable validated config snapshot/atomic rejected
 * reload behavior"). {@link #OVERRIDES} is the single live snapshot {@link ErosionAttempt}
 * (once DIV's telemetry seam is wired -- blocked on MINECRAFT-545) will read
 * {@code erosionEnabled()} and the tuning fields from.
 *
 * <p>{@code erosionEnabled} defaults {@code false}, same convention and wording as Dynamic
 * Terrain's own {@code erosionEnabled}/{@code vehicleErosion} switches ("Terrain-altering systems
 * default to OFF so existing worlds are safe") -- this mod owns its OWN switch, with no
 * dependency on Dynamic Terrain's. Each tuning field is independently bounded by NeoForge's own
 * {@code ModConfigSpec} (matching {@link ErosionConfig#isValid()}'s per-field domains), but
 * {@code ModConfigSpec} cannot express the accepted contract's RELATIONAL constraints (e.g.
 * {@code slipThresholdMs < referenceSlipMs}, {@code minLoadFactor <= maxLoadFactor}) -- a config
 * file could pass every individual field's bounds and still describe an invalid whole tuple.
 * {@link #onLoad} and {@link #onReload} re-derive a full candidate from the live spec values on
 * every load/reload and route it through {@link ErosionConfig.Overrides#reload}, so an
 * individually-bounded-but-relationally-invalid file is still atomically rejected as a whole --
 * exactly the guarantee {@code ErosionConfigTest}'s case 28 already proves in isolation, now
 * wired to a real file-driven trigger.
 */
public final class ErosionModConfig {
    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.BooleanValue EROSION_ENABLED;
    private static final ModConfigSpec.DoubleValue SLIP_THRESHOLD_MS;
    private static final ModConfigSpec.DoubleValue BASE_EROSION_PER_TICK;
    private static final ModConfigSpec.DoubleValue REFERENCE_SLIP_MS;
    private static final ModConfigSpec.DoubleValue REFERENCE_LOAD_KG;
    private static final ModConfigSpec.DoubleValue MAX_SLIP_FACTOR;
    private static final ModConfigSpec.DoubleValue MIN_LOAD_FACTOR;
    private static final ModConfigSpec.DoubleValue MAX_LOAD_FACTOR;

    /** The single live snapshot the production path reads from; starts at {@link ErosionConfig#DEFAULT}. */
    public static final ErosionConfig.Overrides OVERRIDES = new ErosionConfig.Overrides(ErosionConfig.DEFAULT);

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        EROSION_ENABLED = builder
                .comment("Let ErosionAttempt mutate the world at all. When false, every attempt is denied "
                        + "before any dedup/RNG/world work. Existing worlds are safe by default.")
                .define("erosionEnabled", false);
        SLIP_THRESHOLD_MS = builder
                .comment("Minimum wheel slip speed (m/s) before any erosion chance is nonzero. Must stay "
                        + "below referenceSlipMs.")
                .defineInRange("slipThresholdMs", ErosionConfig.DEFAULT.slipThresholdMs(), 0.0, 1000.0);
        BASE_EROSION_PER_TICK = builder
                .comment("Base per-tick erosion-success probability at the reference slip/load point.")
                .defineInRange("baseErosionPerTick", ErosionConfig.DEFAULT.baseErosionPerTick(), 0.0001, 1000.0);
        REFERENCE_SLIP_MS = builder
                .comment("Wheel slip speed (m/s) the base probability is calibrated at.")
                .defineInRange("referenceSlipMs", ErosionConfig.DEFAULT.referenceSlipMs(), 0.0001, 1000.0);
        REFERENCE_LOAD_KG = builder
                .comment("Wheel load (kg) the base probability is calibrated at.")
                .defineInRange("referenceLoadKg", ErosionConfig.DEFAULT.referenceLoadKg(), 0.0001, 100000.0);
        MAX_SLIP_FACTOR = builder
                .comment("Upper clamp on the slip-speed probability multiplier. Must stay >= 1.0.")
                .defineInRange("maxSlipFactor", ErosionConfig.DEFAULT.maxSlipFactor(), 1.0, 100.0);
        MIN_LOAD_FACTOR = builder
                .comment("Lower clamp on the wheel-load probability multiplier. Must stay in (0, 1] and "
                        + "<= maxLoadFactor.")
                .defineInRange("minLoadFactor", ErosionConfig.DEFAULT.minLoadFactor(), 0.0001, 1.0);
        MAX_LOAD_FACTOR = builder
                .comment("Upper clamp on the wheel-load probability multiplier. Must stay >= 1.0 and "
                        + ">= minLoadFactor.")
                .defineInRange("maxLoadFactor", ErosionConfig.DEFAULT.maxLoadFactor(), 1.0, 1000.0);
        SPEC = builder.build();
    }

    private ErosionModConfig() {
    }

    public static void onLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == SPEC) {
            reloadFromSpec();
        }
    }

    public static void onReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == SPEC) {
            reloadFromSpec();
        }
    }

    /**
     * Package-only so a test can exercise the exact same real-candidate-construction logic
     * without needing a full NeoForge config-load event.
     */
    static void reloadFromSpec() {
        ErosionConfig candidate = new ErosionConfig(
                SLIP_THRESHOLD_MS.get(), BASE_EROSION_PER_TICK.get(), REFERENCE_SLIP_MS.get(),
                REFERENCE_LOAD_KG.get(), MAX_SLIP_FACTOR.get(), MIN_LOAD_FACTOR.get(),
                MAX_LOAD_FACTOR.get(), EROSION_ENABLED.get());
        OVERRIDES.reload(candidate);
    }
}
