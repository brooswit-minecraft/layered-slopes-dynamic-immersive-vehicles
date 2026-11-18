package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * Entry point for the dedicated Layered Slopes / Dynamic Immersive Vehicles (DIV) traction-loss
 * erosion compatibility mod (MINECRAFT-510). Owns erosion policy, configuration, tuning,
 * RNG/dedup, and all integration/compat adapters; neither DIV nor Layered Slopes gains a
 * dependency on the other, and this mod has no dependency on Dynamic Terrain or Dynamic
 * Vehicles.
 *
 * <p>This slice (slice 2 of the ticket's bounded sequence) only establishes the real NeoForge
 * mod scaffold and pinned layered-slopes/layered-slope-grading dependencies -- no erosion
 * consumer is wired up yet. That depends on DIV's own contact-telemetry seam, which is itself
 * deferred pending MINECRAFT-545's active PR on DIV's PhysicsBridge.java.
 */
@Mod(LayeredSlopesDynamicImmersiveVehiclesMod.MODID)
public class LayeredSlopesDynamicImmersiveVehiclesMod {
    public static final String MODID = "layeredslopesdynamicimmersivevehicles";
    public static final Logger LOGGER = LogUtils.getLogger();

    public LayeredSlopesDynamicImmersiveVehiclesMod(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("Layered Slopes Dynamic Immersive Vehicles loaded (erosion consumer not yet wired up)");
    }
}
