package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;

/**
 * A real, maximally conservative check for the one concretely-named external propagation risk
 * this ticket's reviews identified: Dynamic Terrain's cave-ins (review 54876/54899/54904).
 *
 * <p>An earlier version of this class tried to narrow the risk to only the (up to 12)
 * face-adjacent neighbours of source/destination, excluding source and destination themselves on
 * the theory that they become untagged {@code layeredslopes:*} blocks. Review 54907/54908 found
 * two real problems with that: (1) it treated an unloaded neighbour as proven-safe rather than
 * unknown, and (2) the exclusion rested on today's shipped {@code dynamicterrain:supported} tag
 * contents and today's call ordering inside {@code Smoothing.smooth()} -- a runtime datapack can
 * add {@code layeredslopes:*} blocks to that tag, and source's own neighbor-notify can reach
 * destination before destination's own state update, so "source/destination are safe" was an
 * assumption, not a verified runtime fact. Rather than keep refining an increasingly fragile
 * exclusion rule, this class now makes the one claim it can fully verify and enforce: if Dynamic
 * Terrain is loaded at all, there is a real cave-in system active in this world that this mod has
 * not bounded, so every attempt is denied. Reads only Dynamic Terrain's mod id as data, never its
 * Java classes -- the same data-only optional-dependency shape {@code GroundSurfaces.java}
 * already uses elsewhere in this org for Layered Slopes, and consistent with this mod's "no
 * dependency on Dynamic Terrain" architecture.
 */
final class DynamicTerrainCaveInRisk {
    private static final String DYNAMIC_TERRAIN_MOD_ID = "dynamicterrain";

    private DynamicTerrainCaveInRisk() {
    }

    /** @return {@code true} if Dynamic Terrain is loaded -- its cave-in system is unbounded by this mod. */
    static boolean isPresent(ServerLevel level, BlockPos source, BlockPos destination) {
        return ModList.get().isLoaded(DYNAMIC_TERRAIN_MOD_ID);
    }
}
