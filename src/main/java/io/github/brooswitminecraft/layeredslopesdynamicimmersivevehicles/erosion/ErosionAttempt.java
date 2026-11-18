package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

import java.util.ArrayList;
import java.util.List;

import io.github.brooswitminecraft.layeredslopegrading.Smoothing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One per-wheel, per-tick erosion attempt at a single block position: dedup, the accepted
 * MINECRAFT-555 slip-to-probability roll, a shuffled-direction-first-accepted
 * {@code Smoothing.canSmooth}/{@code smooth} call (555 prep packet 54611 acceptance matrix cases
 * 6/7/10/13/14), and the {@link ErosionProtectionGate} check before any world mutation.
 *
 * <p>Taking {@link ServerLevel} rather than the generic {@code Level} makes a client-side call
 * impossible to even construct, not merely checked at runtime -- but it does NOT by itself rule
 * out an off-thread call (review 54880), so {@link #attempt} also asserts {@code
 * level.getServer().isSameThread()} before touching the level/chunk/dedup state at all. Dimension
 * and tick are derived from the level/its server, never caller-supplied, so a caller cannot
 * desynchronize the dedup key or the tick from what actually happened. Every candidate position's
 * chunk is confirmed loaded before any {@code BlockState}/{@code Smoothing} query touches it, so
 * this never forces a chunk load (review 54878 finding 2).
 *
 * <p>Every {@link ErosionProtectionGate.MutationRequest} this builds has {@code
 * propagationScopeKnown=false}: no neighbor/cave-in consequence analysis is implemented anywhere
 * in this mod yet, so claiming a known (even if empty) scope would be exactly the "boolean
 * standing in for coverage" this ticket's reviews have repeatedly rejected (review 54878 finding
 * 1). This means every real {@code attempt()} call is denied by the gate's own fail-closed check
 * before ever reaching an adapter, until a real propagation-scope-deriving implementation exists
 * -- intentional, matching this mod's standing "no production adapter/propagation means all real
 * erosion stays denied" position.
 */
public final class ErosionAttempt {
    private ErosionAttempt() {
    }

    /** Why a given attempt did or did not mutate the world. */
    public enum Outcome {
        /** the calling thread is not the server's own thread -- denied before touching anything. */
        OFF_THREAD,
        /** case 10: the source position's chunk is not loaded -- never force-load it. */
        SOURCE_NOT_LOADED,
        /** case 13: a different wheel already claimed this (dimension, block, tick) this tick. */
        DUPLICATE_THIS_TICK,
        /** case 8: the slip-to-probability roll chance was zero (no slip, invalid config, or invalid input). */
        NO_CHANCE,
        /** the roll chance was nonzero but the random draw did not succeed. */
        ROLL_FAILED,
        /** every horizontal direction was either unloaded, blocked, or unanchored (case 6/7/10). */
        NO_VALID_DIRECTION,
        /** a direction would have worked, but {@link ErosionProtectionGate#authorize} denied every candidate. */
        DENIED_BY_PROTECTION,
        /** the world was actually mutated via {@code Smoothing.smooth}. */
        APPLIED
    }

    public static Outcome attempt(ServerLevel level, BlockPos pos, double slipSpeedMs, double wheelLoadKg,
                                   RandomSource random, ErosionConfig config, ErosionDedup dedup,
                                   ErosionProtectionGate gate) {
        return attempt(level, pos, slipSpeedMs, wheelLoadKg, random, config, dedup, gate, false);
    }

    /**
     * Test-only seam: lets {@code ErosionAttemptGameTests} exercise the direction-selection and
     * {@code Smoothing.smooth} mechanics under a {@code propagationScopeKnown=true} request
     * without weakening the real {@link #attempt(ServerLevel, BlockPos, double, double,
     * RandomSource, ErosionConfig, ErosionDedup, ErosionProtectionGate)} overload above, which
     * always passes {@code false} in production.
     */
    static Outcome attempt(ServerLevel level, BlockPos pos, double slipSpeedMs, double wheelLoadKg,
                            RandomSource random, ErosionConfig config, ErosionDedup dedup,
                            ErosionProtectionGate gate, boolean propagationScopeKnownForTest) {
        if (!level.getServer().isSameThread()) {
            return Outcome.OFF_THREAD;
        }
        if (!level.hasChunkAt(pos)) {
            return Outcome.SOURCE_NOT_LOADED;
        }
        Object dimension = level.dimension().location();
        long tick = level.getServer().getTickCount();
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
            BlockPos destination = pos.relative(direction);
            // The anchor-support block Smoothing/SmoothRules checks is always destination's
            // vertical neighbour -- same chunk column as destination, since chunks span full
            // world height -- so confirming destination's chunk is loaded covers it too.
            if (!level.hasChunkAt(destination)) {
                continue;
            }
            if (!Smoothing.canSmooth(level, pos, direction)) {
                continue;
            }
            ErosionProtectionGate.MutationRequest request = new ErosionProtectionGate.MutationRequest(
                    dimension, pos.getX(), pos.getY(), pos.getZ(),
                    destination.getX(), destination.getY(), destination.getZ(), propagationScopeKnownForTest);
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
