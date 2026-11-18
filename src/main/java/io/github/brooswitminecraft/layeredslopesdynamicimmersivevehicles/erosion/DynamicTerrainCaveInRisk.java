package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfigs;

/**
 * A real check for the one concretely-named external propagation risk this ticket's reviews
 * identified: Dynamic Terrain's cave-ins (review 54876/54899/54904/54915).
 *
 * <p>Verified directly against Dynamic Terrain's own source (not assumed): its cave-in handler
 * ({@code CaveIns.onNeighborNotify}) returns immediately, doing nothing at all, whenever its own
 * {@code DynamicTerrainConfig.CAVE_INS_ENABLED} config value is {@code false} -- and that key
 * ({@code "caveInsEnabled"}, registered under {@code ModConfig.Type.SERVER}) defaults to
 * {@code false}. So "Dynamic Terrain is loaded" and "Dynamic Terrain's cave-ins are actually
 * active" are different, independently-verifiable facts; treating the first as proof of the
 * second (the earlier, withdrawn version of this class) was more conservative than necessary.
 *
 * <p>This reads DT's actual loaded config value through NeoForge's own public, generic,
 * supported API ({@code ModConfigs.getModConfigs(modId)} plus the standard
 * {@code night-config} {@code getOrElse} reader) -- never DT's Java classes, and never DT's own
 * {@code ModConfigSpec.BooleanValue} field, matching this mod's "no dependency on Dynamic
 * Terrain" architecture. Any absence, read failure, or unexpected value type fails closed
 * (treated as enabled/risky), per review 54915's "unknown/unreadable/version-mismatched config
 * remains denied."
 */
final class DynamicTerrainCaveInRisk {
    private static final String DYNAMIC_TERRAIN_MOD_ID = "dynamicterrain";
    private static final String CAVE_INS_ENABLED_KEY = "caveInsEnabled";

    private DynamicTerrainCaveInRisk() {
    }

    /**
     * @return {@code true} if Dynamic Terrain is loaded AND either its real
     *     {@code caveInsEnabled} config value could not be confirmed {@code false}, or it is
     *     genuinely {@code true}. {@code false} only when Dynamic Terrain is absent, or is
     *     present with that value confirmed {@code false}.
     */
    static boolean isPresent(ServerLevel level, BlockPos source, BlockPos destination) {
        if (!ModList.get().isLoaded(DYNAMIC_TERRAIN_MOD_ID)) {
            return false;
        }
        return readBooleanConfig(DYNAMIC_TERRAIN_MOD_ID, ModConfig.Type.SERVER, CAVE_INS_ENABLED_KEY, true);
    }

    /**
     * Package-only, modid-generic so the fail-closed "no such config" path is directly testable
     * without needing Dynamic Terrain itself loaded (review 54915's "validate... in tests, not by
     * mutating user worlds" -- this performs no mutation and registers nothing).
     *
     * @return {@code fallbackIfUnknown} if no matching config of {@code type} is registered for
     *     {@code modId}, or if the key is absent or not a boolean; otherwise the real, live value.
     */
    static boolean readBooleanConfig(String modId, ModConfig.Type type, String key, boolean fallbackIfUnknown) {
        List<ModConfig> configs;
        try {
            configs = ModConfigs.getModConfigs(modId);
        } catch (RuntimeException e) {
            return fallbackIfUnknown;
        }
        for (ModConfig config : configs) {
            if (config.getType() != type) {
                continue;
            }
            try {
                Object raw = config.getLoadedConfig().config().getOrElse(key, (Object) fallbackIfUnknown);
                if (raw instanceof Boolean value) {
                    return value;
                }
            } catch (RuntimeException e) {
                return fallbackIfUnknown;
            }
        }
        return fallbackIfUnknown;
    }
}
