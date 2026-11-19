package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion.ErosionClientSyncLiveProbe;
import io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion.ErosionClientSyncProbe;
import io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion.ErosionClientSyncTestCommand;
import io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion.ErosionModConfig;
import io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion.ErosionWheelContactIntegration;

import java.util.Iterator;
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
 * direct reference -- see that interface's own doc for the full seam, and why the provider
 * catch below is required once this mod's own build pins and bundles DIV at build time
 * (direction comment 55251) while DIV itself stays an optional runtime dependency that may
 * still be genuinely absent from any given server.
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
        // Review finding (MINECRAFT-510, direction comment 55251/55286): this mod's own shipped
        // jar now ALWAYS bundles src/divIntegration and its META-INF/services provider file
        // (pinned-build packaging, direction comment 55251), so ServiceLoader finds the real
        // DivWheelContactConsumer provider regardless of whether DIV is actually present on a
        // given server's classpath -- the old "empty iterator when absent" guarantee only held
        // while that metadata was itself conditionally omitted. If DIV is genuinely absent,
        // linking that provider's own real DIV-typed method signatures (inside register(), when
        // its NeoForge::EVENT_BUS.addListener(DivWheelContactConsumer::onWheelGroundContact)
        // method references get resolved) throws LinkageError/ServiceConfigurationError -- this
        // loop must catch that per-provider and skip it, not let it crash this mod's own
        // construction over a different mod's optional dependency being missing.
        int registeredWheelContactIntegrations = 0;
        Iterator<ErosionWheelContactIntegration> wheelContactIntegrations =
                ServiceLoader.load(ErosionWheelContactIntegration.class, getClass().getClassLoader()).iterator();
        while (wheelContactIntegrations.hasNext()) {
            try {
                wheelContactIntegrations.next().register();
                registeredWheelContactIntegrations++;
            } catch (ServiceConfigurationError | LinkageError e) {
                LOGGER.warn("Skipping a wheel-contact integration provider whose own required "
                        + "dependency is not actually present on this server (this mod's jar "
                        + "always ships the provider metadata; the dependency it needs, e.g. "
                        + "DIV, remains a genuinely optional install): " + e);
            }
        }
        LOGGER.info("Layered Slopes Dynamic Immersive Vehicles loaded (wheel-contact integrations registered: "
                + registeredWheelContactIntegrations + ")");
    }
}
