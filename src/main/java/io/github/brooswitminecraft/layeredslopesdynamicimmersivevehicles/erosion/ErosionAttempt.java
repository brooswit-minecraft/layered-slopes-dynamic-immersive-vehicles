package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

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
 * 6/7/10/13/14), a real claim/protection check at both ends ({@link ClaimProtection}), and a real
 * Dynamic-Terrain-cave-in-risk check ({@link DynamicTerrainCaveInRisk}) before any world mutation.
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
 * <p>Earlier attempts at protection in this area (review 54855/54861/54878/54890/54899/54901)
 * each separately overclaimed a capability the code did not actually back. The current shape
 * avoids that by making the real checks mandatory, unconditional steps performed directly here --
 * never gated behind an optional, separately-installed adapter whose capabilities could claim
 * more than it verifies -- and by never claiming anything beyond what {@link ClaimProtection} and
 * {@link DynamicTerrainCaveInRisk} actually, verifiably check (both documented as best-effort /
 * conservative, not universal, in their own class docs). {@link ErosionProtectionGate} (merged
 * PR #3) is deliberately NOT part of this path: its {@code MutationRequest} is Minecraft-free by
 * design (kept pure-unit-testable, confirmed against {@code ErosionProtectionGateTest}), which
 * cannot host a real per-attempt world check that needs a live {@code ServerLevel}/{@code
 * BlockPos} -- routing these checks through it would have meant either breaking that design goal
 * or, as happened three times, having the adapter claim a capability it could not verify. The
 * gate class itself remains valid for a future, actually-Minecraft-free policy; it is simply not
 * the vehicle for these two checks.
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
        /** {@link ClaimProtection#mayMutate} denied source or destination for every candidate direction. */
        PROTECTED,
        /** {@link DynamicTerrainCaveInRisk#isPresent} found a real risk for every candidate direction. */
        NEIGHBOR_CAVE_IN_RISK,
        /** the world was actually mutated via {@code Smoothing.smooth}. */
        APPLIED
    }

    /** Matches {@code Smoothing.canSmooth}'s signature so a test can intercept/count/throw on it. */
    @FunctionalInterface
    interface SmoothFeasibilityCheck {
        boolean canSmooth(ServerLevel level, BlockPos pos, Direction direction);
    }

    /** Matches {@link ClaimProtection#mayMutate}'s signature so a test can fake/count it. */
    @FunctionalInterface
    interface ClaimCheck {
        boolean mayMutate(ServerLevel level, BlockPos pos);
    }

    /** Matches {@link DynamicTerrainCaveInRisk#isPresent}'s signature so a test can fake/count it. */
    @FunctionalInterface
    interface CaveInRiskCheck {
        boolean isPresent(ServerLevel level, BlockPos source, BlockPos destination);
    }

    /** Bundles every real-world seam so {@code attempt}'s signature does not grow with each one. */
    static final class Hooks {
        final Predicate<BlockPos> destinationLoaded;
        final SmoothFeasibilityCheck canSmooth;
        final ClaimCheck claim;
        final CaveInRiskCheck caveInRisk;

        Hooks(Predicate<BlockPos> destinationLoaded, SmoothFeasibilityCheck canSmooth,
              ClaimCheck claim, CaveInRiskCheck caveInRisk) {
            this.destinationLoaded = destinationLoaded;
            this.canSmooth = canSmooth;
            this.claim = claim;
            this.caveInRisk = caveInRisk;
        }

        static Hooks real(ServerLevel level) {
            return new Hooks(level::hasChunkAt, Smoothing::canSmooth,
                    ClaimProtection::mayMutate, DynamicTerrainCaveInRisk::isPresent);
        }
    }

    public static Outcome attempt(ServerLevel level, BlockPos pos, double slipSpeedMs, double wheelLoadKg,
                                   RandomSource random, ErosionConfig config, ErosionDedup dedup) {
        return attempt(level, pos, slipSpeedMs, wheelLoadKg, random, config, dedup, Hooks.real(level));
    }

    /**
     * Package-only instrumented seam: lets {@code ErosionAttemptGameTests} fake any of the
     * real-world checks deterministically (a destination as unloaded, a position as
     * claim-protected, a cave-in risk as present) without depending on real chunk-loading or
     * event-listener behavior, which proved to differ between environments in earlier, reverted
     * attempts at these regressions. Production always uses {@link Hooks#real} via the overload
     * above; this one is never called from production code.
     */
    static Outcome attempt(ServerLevel level, BlockPos pos, double slipSpeedMs, double wheelLoadKg,
                            RandomSource random, ErosionConfig config, ErosionDedup dedup, Hooks hooks) {
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
        boolean anyProtected = false;
        boolean anyCaveInRisk = false;
        for (Direction direction : candidates) {
            BlockPos destination = pos.relative(direction);
            // The anchor-support block Smoothing/SmoothRules checks is always destination's
            // vertical neighbour -- same chunk column as destination, since chunks span full
            // world height -- so confirming destination's chunk is loaded covers it too.
            if (!hooks.destinationLoaded.test(destination)) {
                continue;
            }
            if (!hooks.canSmooth.canSmooth(level, pos, direction)) {
                continue;
            }
            if (!hooks.claim.mayMutate(level, pos) || !hooks.claim.mayMutate(level, destination)) {
                anyProtected = true;
                continue;
            }
            if (hooks.caveInRisk.isPresent(level, pos, destination)) {
                anyCaveInRisk = true;
                continue;
            }
            if (Smoothing.smooth(level, pos, direction, true)) {
                return Outcome.APPLIED;
            }
        }
        if (anyProtected) {
            return Outcome.PROTECTED;
        }
        if (anyCaveInRisk) {
            return Outcome.NEIGHBOR_CAVE_IN_RISK;
        }
        return Outcome.NO_VALID_DIRECTION;
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
