package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion.ErosionClientSyncLiveProbe;
import io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion.ErosionClientSyncProbe;
import io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion.ErosionClientSyncTestCommand;
import io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion.ErosionModConfig;
import io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion.ErosionWheelContactIntegration;

import java.util.ServiceLoader;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Entry point for the dedicated Layered Slopes / Dynamic Immersive Vehicles (DIV) traction-loss
 * erosion compatibility mod (MINECRAFT-510). Owns erosion policy, configuration, tuning,
 * RNG/dedup, and all integration/compat adapters; neither DIV nor Layered Slopes gains a
 * dependency on the other, and this mod has no dependency on Dynamic Terrain or Dynamic
 * Vehicles.
 *
 * <p>Registers {@link ErosionModConfig#SPEC} as a real {@code ModConfig.Type.SERVER} config
 * (direction, comment 54943's default-OFF switch) and listens for both its initial load and any
 * later reload, routing each into {@link ErosionModConfig#OVERRIDES}' atomic-rejected reload.
 *
 * <p>The real DIV wheel-contact consumer (MINECRAFT-510, direction comment 55238/M55554611) is
 * loaded via {@link ServiceLoader} against {@link ErosionWheelContactIntegration} rather than a
 * direct reference -- it can only ever be compiled/present when a local DIV jar is on the
 * classpath (DIV ships no stable release yet), and this class must stay loadable/compilable on
 * every ordinary build regardless. {@code ServiceLoader} returns an empty iterator, not an
 * error, when no provider is present -- see that interface's own doc for the full seam.
 */
@Mod(LayeredSlopesDynamicImmersiveVehiclesMod.MODID)
public class LayeredSlopesDynamicImmersiveVehiclesMod {
    public static final String MODID = "layeredslopesdynamicimmersivevehicles";
    public static final Logger LOGGER = LogUtils.getLogger();

    public LayeredSlopesDynamicImmersiveVehiclesMod(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, ErosionModConfig.SPEC);
        modEventBus.addListener(ErosionModConfig::onLoad);
        modEventBus.addListener(ErosionModConfig::onReload);
        if (ErosionClientSyncTestCommand.isEnabled()) {
            NeoForge.EVENT_BUS.addListener(ErosionClientSyncTestCommand::register);
        }
        // Guarded by Dist so this class is never even loaded on a real dedicated-server
        // distribution, which physically lacks the client-only classes it references.
        if (FMLEnvironment.dist == Dist.CLIENT && ErosionClientSyncProbe.isEnabled()) {
            NeoForge.EVENT_BUS.addListener(ErosionClientSyncProbe::onClientTick);
        }
        if (FMLEnvironment.dist == Dist.CLIENT && ErosionClientSyncLiveProbe.isEnabled()) {
            NeoForge.EVENT_BUS.addListener(ErosionClientSyncLiveProbe::onClientTick);
        }
        // Empty on every ordinary build (no DIV on the classpath, no provider-configuration
        // file present) -- never a NoClassDefFoundError risk. See
        // ErosionWheelContactIntegration's own doc.
        int registeredWheelContactIntegrations = 0;
        for (var integration : ServiceLoader.load(ErosionWheelContactIntegration.class, getClass().getClassLoader())) {
            integration.register();
            registeredWheelContactIntegrations++;
        }
        LOGGER.info("Layered Slopes Dynamic Immersive Vehicles loaded (wheel-contact integrations registered: "
                + registeredWheelContactIntegrations + ")");
    }
}
