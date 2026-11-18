package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.ModList;

/**
 * A real, bounded check for the one concretely-named external propagation risk this ticket's
 * reviews identified: Dynamic Terrain's cave-ins (review 54876/54899/54904). Reads only
 * Dynamic Terrain's data (its mod id string and its {@code dynamicterrain:supported} block tag
 * id), never its Java classes -- the same data-only optional-dependency shape
 * {@code GroundSurfaces.java} already uses elsewhere in this org for Layered Slopes, and
 * consistent with this mod's "no dependency on Dynamic Terrain" architecture.
 *
 * <p>Verified directly against Dynamic Terrain's source: {@code CaveIns.onNeighborNotify} reacts
 * to {@code BlockEvent.NeighborNotifyEvent} (which {@code Smoothing.smooth()}'s
 * {@code Block.UPDATE_ALL} setBlock calls do trigger) and only re-checks the 6 face-adjacent
 * neighbours of the changed position; it only ever acts on a block already in the
 * {@code dynamicterrain:supported} tag. That tag's real shipped contents (checked directly, not
 * assumed) list Dynamic Terrain's own {@code dynamicterrain:layered_*} blocks and plain vanilla
 * ground blocks -- no {@code layeredslopes:*} block is in it, so once erosion converts a block
 * here it is never itself cave-in-reactive. This check is deliberately conservative rather than
 * exact: it does not replicate Dynamic Terrain's own recursive support-search, so it treats
 * "a tagged block sits face-adjacent to source or destination" as a risk to deny, even though not
 * every such neighbour would actually lose support.
 */
final class DynamicTerrainCaveInRisk {
    private static final String DYNAMIC_TERRAIN_MOD_ID = "dynamicterrain";
    private static final TagKey<Block> SUPPORTED = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(DYNAMIC_TERRAIN_MOD_ID, "supported"));

    private DynamicTerrainCaveInRisk() {
    }

    /**
     * @return {@code true} if Dynamic Terrain is loaded AND at least one of the (up to 12)
     *     positions face-adjacent to {@code source} or {@code destination} -- excluding source
     *     and destination themselves -- is in Dynamic Terrain's {@code supported} tag.
     */
    static boolean isPresent(ServerLevel level, BlockPos source, BlockPos destination) {
        if (!ModList.get().isLoaded(DYNAMIC_TERRAIN_MOD_ID)) {
            return false;
        }
        for (Direction direction : Direction.values()) {
            BlockPos sourceNeighbor = source.relative(direction);
            if (!sourceNeighbor.equals(destination) && isSupportedTagged(level, sourceNeighbor)) {
                return true;
            }
            BlockPos destinationNeighbor = destination.relative(direction);
            if (!destinationNeighbor.equals(source) && isSupportedTagged(level, destinationNeighbor)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSupportedTagged(ServerLevel level, BlockPos pos) {
        return level.hasChunkAt(pos) && level.getBlockState(pos).is(SUPPORTED);
    }
}
