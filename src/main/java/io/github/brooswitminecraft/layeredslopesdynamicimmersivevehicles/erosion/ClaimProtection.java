package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Best-effort respect for claim/protection mods, via the same real, already-accepted pattern
 * {@code layered-slopes-flowing-fluids}' own {@code Protection.mayErode} uses (review 54904):
 * post a real {@code BlockEvent.BreakEvent} through a fake player and honor cancellation. Erosion
 * is an environmental system, not a player action, so there is no real player break to intercept
 * -- this is the established way this org's own code already gets coverage from protection mods
 * that listen on {@code BreakEvent} (the common case). It is NOT a guarantee against every
 * possible protection hook a mod might use instead.
 *
 * <p>Applied to both the source (a real break -- material is removed) and the destination (which
 * gains material, not loses it, but is checked with the same event rather than a separate,
 * less-commonly-hooked placement event, matching the sibling's "best-effort, not universal"
 * framing rather than claiming exact placement semantics).
 */
final class ClaimProtection {
    private ClaimProtection() {
    }

    static boolean mayMutate(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        var fakePlayer = FakePlayerFactory.getMinecraft(level);
        BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(level, pos, state, fakePlayer);
        NeoForge.EVENT_BUS.post(event);
        return !event.isCanceled();
    }
}
