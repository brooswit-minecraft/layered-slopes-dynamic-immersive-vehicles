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
 * framing rather than claiming exact placement semantics). This event is a PREFLIGHT check only
 * (fired before any mutation): it does not and cannot establish placement-hook compatibility,
 * only break-hook coverage.
 *
 * <p>Also checks the server's own applicable built-in environmental constraints (review 54907),
 * which {@code BreakEvent} cancellation alone does not establish: spawn protection
 * ({@code MinecraftServer#isUnderSpawnProtection}, real on a {@code DedicatedServer}) and the
 * world border ({@code ServerLevel#getWorldBorder()}).
 */
final class ClaimProtection {
    private ClaimProtection() {
    }

    static boolean mayMutate(ServerLevel level, BlockPos pos) {
        if (!level.getWorldBorder().isWithinBounds(pos)) {
            return false;
        }
        var fakePlayer = FakePlayerFactory.getMinecraft(level);
        if (level.getServer().isUnderSpawnProtection(level, pos, fakePlayer)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(level, pos, state, fakePlayer);
        NeoForge.EVENT_BUS.post(event);
        return !event.isCanceled();
    }
}
