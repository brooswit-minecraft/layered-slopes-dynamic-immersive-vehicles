package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

/**
 * Service-provider seam (MINECRAFT-510, direction comment 55238/M55554611) between this mod's
 * always-compiled main sourceSet and its DIV (Dynamic Immersive Vehicles) wheel-contact consumer,
 * which can only ever be compiled/present when a local DIV jar is actually on the classpath
 * (DIV ships no stable, sha512-pinnable release yet -- see build.gradle's {@code divJar}/{@code
 * -PdivLocalJar} handling). An ordinary build never has an implementation of this interface, and
 * {@code ServiceLoader} gracefully returns an empty iterator in that case -- so the always-
 * compiled mod entry point can safely attempt to load and register one without ever risking a
 * {@code NoClassDefFoundError}, and without this interface (or the entry point) ever needing a
 * compile-time reference to any DIV type.
 *
 * <p>The real implementation lives in {@code src/divIntegration/java}, only added to the
 * compiled sourceSet when {@code divJar != null} (the same opt-in gate the existing paired-
 * runtime CI jobs already use), and is discovered via a {@code META-INF/services} provider-
 * configuration file that likewise only exists in that same opt-in build.
 */
public interface ErosionWheelContactIntegration {
    /** Registers whatever real-world listeners this integration needs. Called exactly once. */
    void register();
}
