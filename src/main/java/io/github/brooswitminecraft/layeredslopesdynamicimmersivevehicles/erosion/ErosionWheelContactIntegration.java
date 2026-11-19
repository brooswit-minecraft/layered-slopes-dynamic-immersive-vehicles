package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

/**
 * Service-provider seam (MINECRAFT-510, direction comment 55238/M55554611) between this mod's
 * always-compiled main sourceSet and its DIV (Dynamic Immersive Vehicles) wheel-contact consumer,
 * which can only ever be compiled/present when a local DIV jar is actually on the classpath
 * (DIV ships no stable, sha512-pinnable release yet -- see build.gradle's {@code divJar}/{@code
 * -PdivLocalJar} handling). This interface itself never needs a compile-time reference to any
 * DIV type.
 *
 * <p>The real implementation lives in {@code src/divIntegration/java}, only added to the
 * compiled sourceSet when {@code divJar != null} (passed automatically by ci.yml's own pinned,
 * reproducible DIV build -- direction comment 55251 -- not merely an opt-in flag a contributor
 * must remember), and is discovered via a {@code META-INF/services} provider-configuration file
 * that likewise only exists in that same build.
 *
 * <p><b>This mod's own shipped jar therefore ALWAYS bundles that provider metadata now</b>
 * (review finding, direction comment 55286) -- unlike the earlier "absent when DIV absent"
 * design, {@code ServiceLoader} does NOT return an empty iterator on an end user's server that
 * genuinely lacks DIV, since the provider class name is baked into this mod's own jar
 * regardless. Resolving/linking that provider's real DIV-typed method signatures (inside its own
 * {@code register()}) throws {@code LinkageError}/{@code ServiceConfigurationError} if DIV
 * itself is not actually on the runtime classpath -- the entry point ({@code
 * LayeredSlopesDynamicImmersiveVehiclesMod}) catches that per-provider and skips it rather than
 * crashing mod construction over a different mod's optional dependency being missing. See
 * {@code missing-div-isolation-smoke} in {@code pinned-jar-smoke-acceptance.yml} for the real,
 * end-to-end proof of this.
 */
public interface ErosionWheelContactIntegration {
    /** Registers whatever real-world listeners this integration needs. Called exactly once. */
    void register();
}
