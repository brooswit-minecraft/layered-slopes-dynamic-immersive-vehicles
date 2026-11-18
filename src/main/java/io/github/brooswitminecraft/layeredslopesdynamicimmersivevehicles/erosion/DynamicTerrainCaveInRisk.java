package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfigs;

import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.VersionRange;

/**
 * A real check for the one concretely-named external propagation risk this ticket's reviews
 * identified: Dynamic Terrain's cave-ins (review 54876/54899/54904/54915/54916/54917/54919).
 *
 * <p>Verified directly against Dynamic Terrain's own source (not assumed): its cave-in handler
 * ({@code CaveIns.onNeighborNotify}) returns immediately, doing nothing at all, whenever its own
 * {@code DynamicTerrainConfig.CAVE_INS_ENABLED} config value is {@code false} -- and that key
 * ({@code "caveInsEnabled"}, registered under {@code ModConfig.Type.SERVER}) defaults to
 * {@code false}. Checked directly against every tagged release of that repo (now renamed
 * {@code legacy-dynamic-terrain}) from v0.28.2 (the version review 54915/54916 verified) through
 * its current latest, v5.0.1: the key name, default, config type, and the early-return guard are
 * byte-for-byte identical across that entire span. So "Dynamic Terrain is loaded" and "Dynamic
 * Terrain's cave-ins are actually active" are different, independently-verifiable facts; treating
 * the first as proof of the second (an earlier, withdrawn version of this class) was more
 * conservative than necessary.
 *
 * <p>This reads DT's actual loaded config value through NeoForge's own public, generic,
 * supported API ({@code ModConfigs.getModConfigs(modId)} plus the standard
 * {@code night-config} {@code getOrElse} reader) -- never DT's Java classes, and never DT's own
 * {@code ModConfigSpec.BooleanValue} field, matching this mod's "no dependency on Dynamic
 * Terrain" architecture. Review 54916/54917/54919 found that alone insufficient: reading a
 * generically-matched key proves nothing about whether a DIFFERENT, unverified Dynamic Terrain
 * release still wires that key to the same early-return guard. So the permissive branch (treating
 * a read {@code false} as genuinely safe) is now reached ONLY when Dynamic Terrain's own loaded
 * {@link ArtifactVersion} falls inside {@link #VERIFIED_VERSION_RANGE_SPEC}, the exact span
 * checked above; any other installed version -- older, newer, or unreadable -- fails closed
 * (treated as risk present) rather than trusting an unverified contract. Within the verified
 * range, any config-read absence, ambiguity (more than one matching config), or unexpected value
 * type also fails closed, per review 54915's "unknown/unreadable/version-mismatched config
 * remains denied."
 */
final class DynamicTerrainCaveInRisk {
    private static final String DYNAMIC_TERRAIN_MOD_ID = "dynamicterrain";
    private static final String CAVE_INS_ENABLED_KEY = "caveInsEnabled";

    /**
     * Inclusive range, in the exact syntax {@code neoforge.mods.toml} dependency ranges use
     * ({@code org.apache.maven.artifact.versioning.VersionRange}), spanning every
     * brooswit-minecraft/legacy-dynamic-terrain tagged release this class has been directly
     * checked against (v0.28.2 through v5.0.1 -- see the class javadoc). Update only after
     * re-verifying a newer release's source the same way, never by assumption.
     */
    static final String VERIFIED_VERSION_RANGE_SPEC = "[0.28.2,5.0.1]";

    private static final VersionRange VERIFIED_VERSION_RANGE = parseVerifiedRange();

    private static VersionRange parseVerifiedRange() {
        try {
            return VersionRange.createFromVersionSpec(VERIFIED_VERSION_RANGE_SPEC);
        } catch (InvalidVersionSpecificationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private DynamicTerrainCaveInRisk() {
    }

    /**
     * @return {@code true} if Dynamic Terrain is loaded AND either its loaded version falls
     *     outside the verified range above, or its real {@code caveInsEnabled} config value
     *     could not be confirmed {@code false}, or it is genuinely {@code true}. {@code false}
     *     only when Dynamic Terrain is absent, or is present at a verified version with that
     *     value confirmed {@code false}.
     */
    static boolean isPresent(ServerLevel level, BlockPos source, BlockPos destination) {
        if (!ModList.get().isLoaded(DYNAMIC_TERRAIN_MOD_ID)) {
            return false;
        }
        if (!isLoadedVersionVerified()) {
            return true;
        }
        return readBooleanConfig(DYNAMIC_TERRAIN_MOD_ID, ModConfig.Type.SERVER, CAVE_INS_ENABLED_KEY, true);
    }

    private static boolean isLoadedVersionVerified() {
        return ModList.get().getModContainerById(DYNAMIC_TERRAIN_MOD_ID)
                .map(ModContainer::getModInfo)
                .map(info -> isVerifiedVersion(info.getVersion()))
                .orElse(false);
    }

    /**
     * Package-only, pure function (no {@code ModList}/FML environment needed) so the version
     * boundary itself is directly unit-testable without Dynamic Terrain, or any mod environment,
     * actually loaded.
     */
    static boolean isVerifiedVersion(ArtifactVersion version) {
        return VERIFIED_VERSION_RANGE.containsVersion(version);
    }

    /**
     * Package-only, modid-generic so the fail-closed "no such config" path is directly testable
     * without needing Dynamic Terrain itself loaded (review 54915's "validate... in tests, not by
     * mutating user worlds" -- this performs no mutation and registers nothing).
     *
     * <p>Requires exactly one registered config of {@code type} for {@code modId}: review
     * 54916/54917's "reader can accept first arbitrary false key if multiple configs exist" --
     * if more than one config of that type is registered, which one actually backs the key
     * cannot be uniquely resolved, so this fails closed rather than guessing the first match.
     *
     * @return {@code fallbackIfUnknown} if no exactly-one matching config of {@code type} is
     *     registered for {@code modId}, or if the key is absent or not a boolean; otherwise the
     *     real, live value from that uniquely-resolved config.
     */
    static boolean readBooleanConfig(String modId, ModConfig.Type type, String key, boolean fallbackIfUnknown) {
        List<ModConfig> configs;
        try {
            configs = ModConfigs.getModConfigs(modId);
        } catch (RuntimeException e) {
            return fallbackIfUnknown;
        }
        ModConfig uniqueMatch = null;
        for (ModConfig config : configs) {
            if (config.getType() != type) {
                continue;
            }
            if (uniqueMatch != null) {
                return fallbackIfUnknown;
            }
            uniqueMatch = config;
        }
        if (uniqueMatch == null) {
            return fallbackIfUnknown;
        }
        try {
            Object raw = uniqueMatch.getLoadedConfig().config().getOrElse(key, (Object) fallbackIfUnknown);
            if (raw instanceof Boolean value) {
                return value;
            }
        } catch (RuntimeException e) {
            return fallbackIfUnknown;
        }
        return fallbackIfUnknown;
    }
}
