package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

import java.util.ArrayList;
import java.util.List;

import io.github.brooswitminecraft.layeredslopegrading.Smoothing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One per-wheel, per-tick erosion attempt at a single block position: dedup, the accepted
 * MINECRAFT-555 slip-to-probability roll, a shuffled-direction-first-accepted
 * {@code Smoothing.canSmooth}/{@code smooth} call (555 prep packet 54611 acceptance matrix cases
 * 6/7/10/13/14), and the {@link ErosionProtectionGate} check before any world mutation. Pure
 * orchestration -- the actual per-wheel telemetry (slip speed, wheel load) is supplied by the
 * caller; this class makes no assumption about where that telemetry came from, since DIV's own
 * contact-telemetry seam is not wired yet (blocked on MINECRAFT-545 PR #39's PhysicsBridge.java
 * changes).
 */
public final class ErosionAttempt {
    private ErosionAttempt() {
    }

    /** Why a given attempt did or did not mutate the world. */
    public enum Outcome {
        /** case 8: the slip-to-probability roll chance was zero (no slip, invalid config, or invalid input). */
        NO_CHANCE,
        /** case 13: a different wheel already claimed this (dimension, block, tick) this tick. */
        DUPLICATE_THIS_TICK,
        /** the roll chance was nonzero but the random draw did not succeed. */
        ROLL_FAILED,
        /** every horizontal direction was either blocked or unanchored (case 6/7/10). */
        NO_VALID_DIRECTION,
        /** a direction would have worked, but {@link ErosionProtectionGate#authorize} denied every candidate. */
        DENIED_BY_PROTECTION,
        /** the world was actually mutated via {@code Smoothing.smooth}. */
        APPLIED
    }

    /**
     * @param dimension opaque dimension identity, consistent between this call's dedup claim and
     *     every {@link ErosionProtectionGate.MutationRequest} it builds -- callers must pass the
     *     same object/equal value for the same dimension across calls (e.g. {@code
     *     level.dimension().location()}).
     */
    public static Outcome attempt(Level level, BlockPos pos, Object dimension, double slipSpeedMs,
                                   double wheelLoadKg, long tick, RandomSource random,
                                   ErosionConfig config, ErosionDedup dedup, ErosionProtectionGate gate) {
        if (!dedup.tryClaim(dimension, pos.getX(), pos.getY(), pos.getZ(), tick)) {
            return Outcome.DUPLICATE_THIS_TICK;
        }
        BlockState state = level.getBlockState(pos);
        double hardness = state.getDestroySpeed(level, pos);
        double blastResistance = state.getExplosionResistance(level, pos, null);
        double chance = ErosionMath.successChance(slipSpeedMs, wheelLoadKg, hardness, blastResistance, config);
        if (chance <= 0.0) {
            return Outcome.NO_CHANCE;
        }
        if (random.nextDouble() >= chance) {
            return Outcome.ROLL_FAILED;
        }

        List<Direction> candidates = shuffledHorizontalDirections(random);
        boolean deniedByProtection = false;
        for (Direction direction : candidates) {
            if (!Smoothing.canSmooth(level, pos, direction)) {
                continue;
            }
            BlockPos destination = pos.relative(direction);
            ErosionProtectionGate.MutationRequest request = new ErosionProtectionGate.MutationRequest(
                    dimension, pos.getX(), pos.getY(), pos.getZ(),
                    destination.getX(), destination.getY(), destination.getZ(), true);
            if (!gate.authorize(request)) {
                deniedByProtection = true;
                continue;
            }
            if (Smoothing.smooth(level, pos, direction, true)) {
                return Outcome.APPLIED;
            }
        }
        return deniedByProtection ? Outcome.DENIED_BY_PROTECTION : Outcome.NO_VALID_DIRECTION;
    }

    private static List<Direction> shuffledHorizontalDirections(RandomSource random) {
        List<Direction> directions = new ArrayList<>(Direction.Plane.HORIZONTAL.stream().toList());
        for (int i = directions.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            Direction tmp = directions.get(i);
            directions.set(i, directions.get(j));
            directions.set(j, tmp);
        }
        return directions;
    }
}
