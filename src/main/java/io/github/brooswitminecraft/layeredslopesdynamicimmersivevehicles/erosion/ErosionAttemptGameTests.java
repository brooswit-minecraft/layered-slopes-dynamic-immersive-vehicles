package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

import io.github.brooswitminecraft.layeredslopegrading.Smoothing;
import io.github.brooswitminecraft.layeredslopes.Anchor;
import io.github.brooswitminecraft.layeredslopes.LayeredBlock;
import io.github.brooswitminecraft.layeredslopes.LayeredSlopesMod;
import io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.LayeredSlopesDynamicImmersiveVehiclesMod;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Acceptance evidence for {@link ErosionAttempt}: dedup, the accepted MINECRAFT-555
 * slip-to-probability roll, direction selection, the real {@link ClaimProtection} check, and the
 * real {@link DynamicTerrainCaveInRisk} check -- not just each primitive in isolation, and never
 * via an adapter that overclaims what it verifies (review 54855/54861/54878/54890/54899/54901).
 *
 * <p>{@code GUARANTEED_CHANCE_CONFIG} is deliberately extreme-but-valid (not the production
 * defaults) so the roll outcome is guaranteed regardless of {@code RandomSource} seed: chance
 * clamps to exactly 1.0, and {@code RandomSource.nextDouble()} never returns a value &gt;= 1.0,
 * so {@code nextDouble() < 1.0} always holds. {@code TINY_CHANCE_SLIP_MS} instead produces a
 * chance so small (~1e-5) that any seed's roll failing is a practical certainty, not an exact
 * guarantee -- used only where the point is proving dedup still consumes its slot on a failed
 * roll, not the roll's own correctness (already covered by {@code ErosionMathTest}).
 */
@GameTestHolder(LayeredSlopesDynamicImmersiveVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class ErosionAttemptGameTests {
    private ErosionAttemptGameTests() {
    }

    static final ErosionConfig GUARANTEED_CHANCE_CONFIG =
            new ErosionConfig(0.15, 1000.0, 10.0, 300.0, 3.0, 0.25, 4.0);
    static final double SLIP_ABOVE_THRESHOLD_MS = 5.0;
    static final double REFERENCE_LOAD_KG = 300.0;
    private static final double TINY_CHANCE_SLIP_MS = 0.16; // just above DEFAULT's 0.15 threshold

    /**
     * EAST is the only candidate direction {@code canSmooth} can accept: the other three are
     * explicitly STONE (a different, blocking material -- {@code SmoothRules.Destination.BLOCKED})
     * so the shuffled direction order can never pick a different real destination regardless of
     * seed. Package-private: also reused by {@code DynamicTerrainPairedGameTests} (opt-in,
     * dtPairedRuntime-only, see build.gradle).
     */
    /**
     * A handful of cases below call the real 5-arg {@code ErosionAttempt.attempt} overload
     * (true production {@code Hooks.real}, not an injected fake) and assert an outcome that is
     * only correct when Dynamic Terrain is genuinely absent -- true for every ordinary CI run,
     * but not for the opt-in {@code -PdtPairedRuntime=true} build, which installs a real,
     * pinned Dynamic Terrain specifically so {@code DynamicTerrainPairedGameTests} can exercise
     * it. Those cases skip (rather than fail) when Dynamic Terrain is loaded, since they are not
     * testing Dynamic Terrain interaction at all -- that coverage lives entirely in
     * {@code DynamicTerrainPairedGameTests}, which only exists in that opt-in build.
     */
    private static boolean dynamicTerrainIsLoaded() {
        return ModList.get().isLoaded("dynamicterrain");
    }

    static void placeSourceWithOneValidEastNeighbor(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, Blocks.DIRT.defaultBlockState());
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (direction == Direction.EAST) {
                helper.setBlock(pos.relative(direction), Blocks.AIR.defaultBlockState());
                helper.setBlock(pos.relative(direction).below(), Blocks.STONE.defaultBlockState());
            } else {
                helper.setBlock(pos.relative(direction), Blocks.STONE.defaultBlockState());
            }
        }
    }

    private static ErosionAttempt.Hooks realHooksExcept(ServerLevel level,
            Predicate<BlockPos> destinationLoaded, ErosionAttempt.SmoothFeasibilityCheck canSmooth,
            ErosionAttempt.ClaimCheck claim, ErosionAttempt.CaveInRiskCheck caveInRisk) {
        return new ErosionAttempt.Hooks(
                destinationLoaded != null ? destinationLoaded : level::hasChunkAt,
                canSmooth != null ? canSmooth : Smoothing::canSmooth,
                claim != null ? claim : ClaimProtection::mayMutate,
                caveInRisk != null ? caveInRisk : DynamicTerrainCaveInRisk::isPresent);
    }

    /**
     * The default, real-hooks path in an ordinary GameTest world (no claim mod, no Dynamic
     * Terrain) -- the "missing/unsupported integration" case review 54901/54904 required: with
     * nothing installed to deny anything, the real checks correctly let a genuine move apply.
     * Also verifies material conservation: a full 16-layer-equivalent dirt block splits into
     * exactly 15 (source) + 1 (destination) layers, not lost or duplicated.
     */
    @GameTest(template = "flat_platform")
    public static void withNoClaimModOrDynamicTerrainInstalledARealMoveStillAppliesAndConservesMaterial(GameTestHelper helper) {
        if (dynamicTerrainIsLoaded()) {
            helper.succeed();
            return;
        }
        BlockPos pos = new BlockPos(1, 2, 1);
        BlockPos destPos = pos.relative(Direction.EAST);
        placeSourceWithOneValidEastNeighbor(helper, pos);

        ErosionAttempt.Outcome outcome = ErosionAttempt.attempt(
                (ServerLevel) helper.getLevel(), helper.absolutePos(pos),
                SLIP_ABOVE_THRESHOLD_MS, REFERENCE_LOAD_KG, RandomSource.create(1),
                GUARANTEED_CHANCE_CONFIG, new ErosionDedup());

        helper.assertTrue(outcome == ErosionAttempt.Outcome.APPLIED, "expected APPLIED, got " + outcome);
        helper.assertTrue(helper.getBlockState(pos).getBlock() == LayeredSlopesMod.LAYERED_DIRT.get(),
                "the source cell must become LAYERED_DIRT after a real applied erosion move");
        int sourceLayers = helper.getBlockState(pos).getValue(LayeredBlock.LAYERS);
        int destinationLayers = helper.getBlockState(destPos).getValue(LayeredBlock.LAYERS);
        helper.assertTrue(sourceLayers == 15, "expected source to keep 15 of 16 layers, got " + sourceLayers);
        helper.assertTrue(destinationLayers == 1, "expected destination to gain exactly 1 layer, got " + destinationLayers);
        helper.assertTrue(sourceLayers + destinationLayers == 16,
                "material must be conserved: 16 total layers before and after, got " + (sourceLayers + destinationLayers));
        helper.succeed();
    }

    /** Regression (review 54901/54904): a protected SOURCE must deny, leaving the world unmutated. */
    @GameTest(template = "flat_platform")
    public static void aProtectedSourceDeniesEvenWhenEverythingElseWouldSucceed(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 2, 1);
        placeSourceWithOneValidEastNeighbor(helper, pos);
        BlockState before = helper.getBlockState(pos);
        ServerLevel level = (ServerLevel) helper.getLevel();
        BlockPos absolutePos = helper.absolutePos(pos);

        ErosionAttempt.ClaimCheck denySource = (lvl, candidate) -> !candidate.equals(absolutePos);
        ErosionAttempt.Hooks hooks = realHooksExcept(level, null, null, denySource, null);
        ErosionAttempt.Outcome outcome = ErosionAttempt.attempt(level, absolutePos,
                SLIP_ABOVE_THRESHOLD_MS, REFERENCE_LOAD_KG, RandomSource.create(1),
                GUARANTEED_CHANCE_CONFIG, new ErosionDedup(), hooks);

        helper.assertTrue(outcome == ErosionAttempt.Outcome.PROTECTED, "expected PROTECTED, got " + outcome);
        helper.assertTrue(helper.getBlockState(pos).equals(before), "the world must stay unmutated");
        helper.succeed();
    }

    /** Regression (review 54901/54904): a protected DESTINATION must deny, leaving the world unmutated. */
    @GameTest(template = "flat_platform")
    public static void aProtectedDestinationDeniesEvenWhenEverythingElseWouldSucceed(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 2, 1);
        BlockPos eastDestination = pos.relative(Direction.EAST);
        placeSourceWithOneValidEastNeighbor(helper, pos);
        BlockState before = helper.getBlockState(pos);
        ServerLevel level = (ServerLevel) helper.getLevel();
        BlockPos absoluteDestination = helper.absolutePos(eastDestination);

        ErosionAttempt.ClaimCheck denyDestination = (lvl, candidate) -> !candidate.equals(absoluteDestination);
        ErosionAttempt.Hooks hooks = realHooksExcept(level, null, null, denyDestination, null);
        ErosionAttempt.Outcome outcome = ErosionAttempt.attempt(level, helper.absolutePos(pos),
                SLIP_ABOVE_THRESHOLD_MS, REFERENCE_LOAD_KG, RandomSource.create(1),
                GUARANTEED_CHANCE_CONFIG, new ErosionDedup(), hooks);

        helper.assertTrue(outcome == ErosionAttempt.Outcome.PROTECTED, "expected PROTECTED, got " + outcome);
        helper.assertTrue(helper.getBlockState(pos).equals(before), "the world must stay unmutated");
        helper.succeed();
    }

    /** Regression (review 54876/54899/54904): a detected cave-in risk must deny, leaving the world unmutated. */
    @GameTest(template = "flat_platform")
    public static void aDetectedNeighborCaveInRiskDeniesEvenWhenEverythingElseWouldSucceed(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 2, 1);
        placeSourceWithOneValidEastNeighbor(helper, pos);
        BlockState before = helper.getBlockState(pos);
        ServerLevel level = (ServerLevel) helper.getLevel();

        ErosionAttempt.CaveInRiskCheck alwaysRisky = (lvl, src, dst) -> true;
        ErosionAttempt.Hooks hooks = realHooksExcept(level, null, null, null, alwaysRisky);
        ErosionAttempt.Outcome outcome = ErosionAttempt.attempt(level, helper.absolutePos(pos),
                SLIP_ABOVE_THRESHOLD_MS, REFERENCE_LOAD_KG, RandomSource.create(1),
                GUARANTEED_CHANCE_CONFIG, new ErosionDedup(), hooks);

        helper.assertTrue(outcome == ErosionAttempt.Outcome.NEIGHBOR_CAVE_IN_RISK, "expected NEIGHBOR_CAVE_IN_RISK, got " + outcome);
        helper.assertTrue(helper.getBlockState(pos).equals(before), "the world must stay unmutated");
        helper.succeed();
    }

    @GameTest(template = "flat_platform")
    public static void dedupPreventsASecondClaimInTheSameTick(GameTestHelper helper) {
        if (dynamicTerrainIsLoaded()) {
            helper.succeed();
            return;
        }
        BlockPos pos = new BlockPos(1, 2, 1);
        placeSourceWithOneValidEastNeighbor(helper, pos);
        ServerLevel level = (ServerLevel) helper.getLevel();
        BlockPos absolutePos = helper.absolutePos(pos);
        ErosionDedup dedup = new ErosionDedup();

        ErosionAttempt.Outcome first = ErosionAttempt.attempt(level, absolutePos,
                SLIP_ABOVE_THRESHOLD_MS, REFERENCE_LOAD_KG, RandomSource.create(1),
                GUARANTEED_CHANCE_CONFIG, dedup);
        ErosionAttempt.Outcome second = ErosionAttempt.attempt(level, absolutePos,
                SLIP_ABOVE_THRESHOLD_MS, REFERENCE_LOAD_KG, RandomSource.create(2),
                GUARANTEED_CHANCE_CONFIG, dedup);

        helper.assertTrue(first == ErosionAttempt.Outcome.APPLIED, "expected first attempt APPLIED, got " + first);
        helper.assertTrue(second == ErosionAttempt.Outcome.DUPLICATE_THIS_TICK,
                "a second wheel targeting the same block in the same tick must be rejected as a duplicate, got " + second);
        helper.succeed();
    }

    /** Regression (review 54878 finding 3): a denied attempt must still consume the dedup slot. */
    @GameTest(template = "flat_platform")
    public static void aDeniedAttemptStillConsumesTheDedupSlot(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 2, 1);
        placeSourceWithOneValidEastNeighbor(helper, pos);
        ServerLevel level = (ServerLevel) helper.getLevel();
        BlockPos absolutePos = helper.absolutePos(pos);
        ErosionDedup dedup = new ErosionDedup();

        ErosionAttempt.ClaimCheck denyEverything = (lvl, candidate) -> false;
        ErosionAttempt.Hooks hooks = realHooksExcept(level, null, null, denyEverything, null);
        ErosionAttempt.Outcome first = ErosionAttempt.attempt(level, absolutePos,
                SLIP_ABOVE_THRESHOLD_MS, REFERENCE_LOAD_KG, RandomSource.create(1),
                GUARANTEED_CHANCE_CONFIG, dedup, hooks);
        ErosionAttempt.Outcome second = ErosionAttempt.attempt(level, absolutePos,
                SLIP_ABOVE_THRESHOLD_MS, REFERENCE_LOAD_KG, RandomSource.create(2),
                GUARANTEED_CHANCE_CONFIG, dedup, hooks);

        helper.assertTrue(first == ErosionAttempt.Outcome.PROTECTED, "expected first PROTECTED, got " + first);
        helper.assertTrue(second == ErosionAttempt.Outcome.DUPLICATE_THIS_TICK,
                "a denied attempt must still have consumed the dedup slot, got " + second);
        helper.succeed();
    }

    /**
     * Regression (review 54878 finding 3): a nonzero-chance roll that fails must still consume
     * the dedup slot. {@code TINY_CHANCE_SLIP_MS} with {@code ErosionConfig.DEFAULT} produces a
     * probability of roughly 1e-5 against dirt -- any fixed seed's roll failing is a practical
     * certainty (this test is about dedup bookkeeping, not re-proving the roll's own math, which
     * {@code ErosionMathTest} already covers exactly).
     */
    @GameTest(template = "flat_platform")
    public static void aFailedRollStillConsumesTheDedupSlot(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 2, 1);
        placeSourceWithOneValidEastNeighbor(helper, pos);
        ServerLevel level = (ServerLevel) helper.getLevel();
        BlockPos absolutePos = helper.absolutePos(pos);
        ErosionDedup dedup = new ErosionDedup();

        ErosionAttempt.Outcome first = ErosionAttempt.attempt(level, absolutePos,
                TINY_CHANCE_SLIP_MS, REFERENCE_LOAD_KG, RandomSource.create(1),
                ErosionConfig.DEFAULT, dedup);
        ErosionAttempt.Outcome second = ErosionAttempt.attempt(level, absolutePos,
                TINY_CHANCE_SLIP_MS, REFERENCE_LOAD_KG, RandomSource.create(2),
                ErosionConfig.DEFAULT, dedup);

        helper.assertTrue(first == ErosionAttempt.Outcome.ROLL_FAILED, "expected first ROLL_FAILED, got " + first);
        helper.assertTrue(second == ErosionAttempt.Outcome.DUPLICATE_THIS_TICK,
                "a failed roll must still have consumed the dedup slot, got " + second);
        helper.succeed();
    }

    @GameTest(template = "flat_platform")
    public static void noValidDirectionWhenAllFourHorizontalNeighborsAreBlocked(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 2, 1);
        helper.setBlock(pos, Blocks.DIRT.defaultBlockState());
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            helper.setBlock(pos.relative(direction), Blocks.STONE.defaultBlockState());
        }
        BlockState before = helper.getBlockState(pos);

        ErosionAttempt.Outcome outcome = ErosionAttempt.attempt(
                (ServerLevel) helper.getLevel(), helper.absolutePos(pos),
                SLIP_ABOVE_THRESHOLD_MS, REFERENCE_LOAD_KG, RandomSource.create(1),
                GUARANTEED_CHANCE_CONFIG, new ErosionDedup());

        helper.assertTrue(outcome == ErosionAttempt.Outcome.NO_VALID_DIRECTION,
                "expected NO_VALID_DIRECTION when every horizontal neighbor is a different, blocking material, got " + outcome);
        helper.assertTrue(helper.getBlockState(pos).equals(before), "the world must stay unmutated");
        helper.succeed();
    }

    /**
     * Regression (review 54880): a {@code ServerLevel} type alone rules out a client call, NOT
     * an off-thread one. Calling {@code attempt} from a non-server thread must be denied before
     * touching the level/chunk/dedup state at all, and must consume no dedup slot -- proven by a
     * same-tick same-position call on the real server thread afterward still succeeding.
     */
    @GameTest(template = "flat_platform")
    public static void anOffThreadCallIsDeniedBeforeTouchingAnythingAndDoesNotConsumeTheDedupSlot(GameTestHelper helper) {
        if (dynamicTerrainIsLoaded()) {
            helper.succeed();
            return;
        }
        BlockPos pos = new BlockPos(1, 2, 1);
        placeSourceWithOneValidEastNeighbor(helper, pos);
        BlockState before = helper.getBlockState(pos);
        ServerLevel level = (ServerLevel) helper.getLevel();
        BlockPos absolutePos = helper.absolutePos(pos);
        ErosionDedup dedup = new ErosionDedup();

        AtomicReference<ErosionAttempt.Outcome> offThreadOutcome = new AtomicReference<>();
        AtomicReference<Throwable> offThreadError = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                offThreadOutcome.set(ErosionAttempt.attempt(level, absolutePos,
                        SLIP_ABOVE_THRESHOLD_MS, REFERENCE_LOAD_KG, RandomSource.create(1),
                        GUARANTEED_CHANCE_CONFIG, dedup));
            } catch (Throwable t) {
                offThreadError.set(t);
            }
        });
        // Bounded wait (review 54883): if the OFF_THREAD guard ever regressed and the worker
        // blocked on a server-scheduled chunk/dedup operation instead of returning immediately,
        // an unbounded join() would hang the whole test run instead of failing it cleanly.
        long joinTimeoutMs = 5_000L;
        thread.setDaemon(true);
        thread.start();
        try {
            thread.join(joinTimeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            helper.fail("interrupted while waiting for the off-thread call");
            return;
        }
        if (thread.isAlive()) {
            // Safe cleanup (review 54884): don't leave a live worker behind just because this
            // assertion is about to fail -- interrupt it and give it a moment to actually exit
            // before reporting the failure.
            thread.interrupt();
            try {
                thread.join(1_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            helper.fail("the off-thread call did not return within " + joinTimeoutMs
                    + "ms -- the OFF_THREAD guard may have regressed into blocking on server state instead of denying immediately");
            return;
        }

        helper.assertTrue(offThreadError.get() == null, "the off-thread call must not throw, got " + offThreadError.get());
        helper.assertTrue(offThreadOutcome.get() == ErosionAttempt.Outcome.OFF_THREAD,
                "expected OFF_THREAD, got " + offThreadOutcome.get());
        helper.assertTrue(helper.getBlockState(pos).equals(before), "the off-thread call must not mutate the world");

        ErosionAttempt.Outcome onThreadOutcome = ErosionAttempt.attempt(level, absolutePos,
                SLIP_ABOVE_THRESHOLD_MS, REFERENCE_LOAD_KG, RandomSource.create(2),
                GUARANTEED_CHANCE_CONFIG, dedup);
        helper.assertTrue(onThreadOutcome == ErosionAttempt.Outcome.APPLIED,
                "the off-thread call must not have consumed the dedup slot -- a same-tick on-thread call should still apply, got "
                        + onThreadOutcome);
        helper.succeed();
    }

    /**
     * Regression (review 54878/54880): a source position whose chunk is not loaded must be
     * denied without ever force-loading it, and without consuming a dedup slot.
     */
    @GameTest(template = "flat_platform")
    public static void anUnloadedSourceIsDeniedWithoutForceLoadingItOrConsumingTheDedupSlot(GameTestHelper helper) {
        ServerLevel level = (ServerLevel) helper.getLevel();
        BlockPos farAwayPos = new BlockPos(1_000_000, 64, 1_000_000); // far outside any loaded/ticketed chunk
        ErosionDedup dedup = new ErosionDedup();

        helper.assertTrue(!level.hasChunkAt(farAwayPos), "test precondition: this position must start out unloaded");
        ErosionAttempt.Outcome outcome = ErosionAttempt.attempt(level, farAwayPos,
                SLIP_ABOVE_THRESHOLD_MS, REFERENCE_LOAD_KG, RandomSource.create(1),
                GUARANTEED_CHANCE_CONFIG, dedup);

        helper.assertTrue(outcome == ErosionAttempt.Outcome.SOURCE_NOT_LOADED, "expected SOURCE_NOT_LOADED, got " + outcome);
        helper.assertTrue(!level.hasChunkAt(farAwayPos), "the call must not have force-loaded the chunk as a side effect");
        helper.assertTrue(dedup.claimedCountForCurrentTick() == 0, "an unloaded source must not consume a dedup slot");
        helper.succeed();
    }

    /**
     * Regression (review 54884/54886/54888/54890): proves the destination-loaded check is
     * ordered before {@code Smoothing.canSmooth} itself is ever queried for that candidate --
     * not merely that no mutation happened, which a reordered implementation (query first, skip
     * mutation after) could still satisfy. {@code failIfQueriedForEastDestination} throws if
     * ever invoked for the faked-unloaded EAST destination; it delegates to the real
     * {@code Smoothing.canSmooth} for every other direction, so the blocked WEST/NORTH/SOUTH
     * candidates are still exercised normally. Deterministic regardless of environment -- no
     * real chunk-loading behavior involved, unlike an earlier, reverted real-chunk attempt at
     * this same regression (review 54883).
     */
    @GameTest(template = "flat_platform")
    public static void anUnloadedDestinationIsSkippedBeforeAnySmoothingOrMutationCall(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 2, 1);
        BlockPos eastDestination = pos.relative(Direction.EAST);
        placeSourceWithOneValidEastNeighbor(helper, pos);
        BlockState destinationBefore = helper.getBlockState(eastDestination);
        ServerLevel level = (ServerLevel) helper.getLevel();
        BlockPos absolutePos = helper.absolutePos(pos);
        BlockPos absoluteEastDestination = helper.absolutePos(eastDestination);

        Predicate<BlockPos> fakeUnloadedAtEastDestinationOnly = candidate -> !candidate.equals(absoluteEastDestination);
        ErosionAttempt.SmoothFeasibilityCheck failIfQueriedForEastDestination = (lvl, candidatePos, direction) -> {
            if (direction == Direction.EAST) {
                throw new AssertionError("Smoothing.canSmooth must never be queried for a candidate "
                        + "whose destination chunk the loaded-check reported as unloaded");
            }
            return Smoothing.canSmooth(lvl, candidatePos, direction);
        };
        ErosionAttempt.Hooks hooks = realHooksExcept(level, fakeUnloadedAtEastDestinationOnly, failIfQueriedForEastDestination, null, null);

        ErosionAttempt.Outcome outcome = ErosionAttempt.attempt(level, absolutePos,
                SLIP_ABOVE_THRESHOLD_MS, REFERENCE_LOAD_KG, RandomSource.create(1),
                GUARANTEED_CHANCE_CONFIG, new ErosionDedup(), hooks);

        helper.assertTrue(outcome == ErosionAttempt.Outcome.NO_VALID_DIRECTION,
                "the only real candidate's destination was faked unloaded, so it must be skipped entirely, got " + outcome);
        helper.assertTrue(helper.getBlockState(eastDestination).equals(destinationBefore),
                "Smoothing.smooth must never have been invoked for the faked-unloaded destination -- "
                        + "a real mutation here would prove the loaded check was skipped or misordered");
        helper.succeed();
    }

    /**
     * Regression (review 54907/54908 point 4): the earlier {@code aProtectedSourceDeniesEvenWhen...}
     * test only proves {@code ErosionAttempt}'s own orchestration respects an injected fake
     * {@code ClaimCheck} -- it says nothing about whether {@link ClaimProtection} itself actually
     * gets real coverage from a real NeoForge listener. This test uses the production
     * {@code attempt()} overload (real {@link ClaimProtection}, real {@link
     * DynamicTerrainCaveInRisk}) and registers a genuine {@code BlockEvent.BreakEvent} listener
     * that cancels at the source position.
     */
    @GameTest(template = "flat_platform")
    public static void aRealBreakEventListenerCancelingAtSourceIsRespectedByTheProductionPath(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 2, 1);
        placeSourceWithOneValidEastNeighbor(helper, pos);
        BlockState before = helper.getBlockState(pos);
        ServerLevel level = (ServerLevel) helper.getLevel();
        BlockPos absolutePos = helper.absolutePos(pos);

        Object denyAtSource = new Object() {
            @net.neoforged.bus.api.SubscribeEvent
            public void onBreak(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent event) {
                if (event.getPos().equals(absolutePos)) {
                    event.setCanceled(true);
                }
            }
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(denyAtSource);
        ErosionAttempt.Outcome outcome;
        try {
            outcome = ErosionAttempt.attempt(level, absolutePos,
                    SLIP_ABOVE_THRESHOLD_MS, REFERENCE_LOAD_KG, RandomSource.create(1),
                    GUARANTEED_CHANCE_CONFIG, new ErosionDedup());
        } finally {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(denyAtSource);
        }

        helper.assertTrue(outcome == ErosionAttempt.Outcome.PROTECTED,
                "a real BreakEvent listener cancelling at source must deny via the production path, got " + outcome);
        helper.assertTrue(helper.getBlockState(pos).equals(before), "the world must stay unmutated");
        helper.succeed();
    }

    /** Same as above, but the real listener cancels at the DESTINATION position instead. */
    @GameTest(template = "flat_platform")
    public static void aRealBreakEventListenerCancelingAtDestinationIsRespectedByTheProductionPath(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 2, 1);
        BlockPos eastDestination = pos.relative(Direction.EAST);
        placeSourceWithOneValidEastNeighbor(helper, pos);
        BlockState before = helper.getBlockState(pos);
        ServerLevel level = (ServerLevel) helper.getLevel();
        BlockPos absolutePos = helper.absolutePos(pos);
        BlockPos absoluteDestination = helper.absolutePos(eastDestination);

        Object denyAtDestination = new Object() {
            @net.neoforged.bus.api.SubscribeEvent
            public void onBreak(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent event) {
                if (event.getPos().equals(absoluteDestination)) {
                    event.setCanceled(true);
                }
            }
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(denyAtDestination);
        ErosionAttempt.Outcome outcome;
        try {
            outcome = ErosionAttempt.attempt(level, absolutePos,
                    SLIP_ABOVE_THRESHOLD_MS, REFERENCE_LOAD_KG, RandomSource.create(1),
                    GUARANTEED_CHANCE_CONFIG, new ErosionDedup());
        } finally {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(denyAtDestination);
        }

        helper.assertTrue(outcome == ErosionAttempt.Outcome.PROTECTED,
                "a real BreakEvent listener cancelling at destination must deny via the production path, got " + outcome);
        helper.assertTrue(helper.getBlockState(pos).equals(before), "the world must stay unmutated");
        helper.succeed();
    }

    /**
     * Regression (review 54907/54908 point 4): directly exercises the real {@link
     * DynamicTerrainCaveInRisk#isPresent} (real {@code ModList} lookup, not an injected fake),
     * confirming it correctly reports no risk when Dynamic Terrain is genuinely absent from this
     * test environment's mod list.
     */
    @GameTest(template = "flat_platform")
    public static void dynamicTerrainCaveInRiskIsFalseWhenDynamicTerrainIsNotLoaded(GameTestHelper helper) {
        if (dynamicTerrainIsLoaded()) {
            helper.succeed();
            return;
        }
        BlockPos pos = new BlockPos(1, 2, 1);
        ServerLevel level = (ServerLevel) helper.getLevel();
        BlockPos absolutePos = helper.absolutePos(pos);

        boolean risk = DynamicTerrainCaveInRisk.isPresent(level, absolutePos, absolutePos.relative(Direction.EAST));

        helper.assertTrue(!risk, "Dynamic Terrain is not part of this test environment's mod list, so there must be no reported risk");
        helper.succeed();
    }

    /**
     * Regression (review 54910): a boolean-only {@code canSmooth} recheck cannot distinguish the
     * original configuration from a DIFFERENT one a listener swapped in that happens to still be
     * smoothable. A real {@code BlockEvent.BreakEvent} listener (fired by {@link ClaimProtection}
     * for the source position) replaces the source with a different, but still valid and
     * smoothable, {@code LAYERED_DIRT} state (5 layers instead of a full 16-layer-equivalent
     * dirt block) without cancelling the event. The production path must detect this exact-state
     * change and refuse to apply {@code Smoothing.smooth()} on top of it, preserving the
     * listener's own substituted state untouched.
     */
    @GameTest(template = "flat_platform")
    public static void aListenerSwappingSourceToADifferentStillSmoothableStateIsNotSilentlyAppliedOnTopOf(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 2, 1);
        placeSourceWithOneValidEastNeighbor(helper, pos);
        ServerLevel level = (ServerLevel) helper.getLevel();
        BlockPos absolutePos = helper.absolutePos(pos);
        BlockState swappedState = LayeredSlopesMod.LAYERED_DIRT.get().defaultBlockState()
                .setValue(LayeredBlock.LAYERS, 5)
                .setValue(LayeredBlock.ANCHOR, Anchor.FLOOR);

        Object swapSourceOnBreakEvent = new Object() {
            @net.neoforged.bus.api.SubscribeEvent
            public void onBreak(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent event) {
                if (event.getPos().equals(absolutePos)) {
                    level.setBlock(absolutePos, swappedState, 3);
                }
            }
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(swapSourceOnBreakEvent);
        ErosionAttempt.Outcome outcome;
        try {
            outcome = ErosionAttempt.attempt(level, absolutePos,
                    SLIP_ABOVE_THRESHOLD_MS, REFERENCE_LOAD_KG, RandomSource.create(1),
                    GUARANTEED_CHANCE_CONFIG, new ErosionDedup());
        } finally {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(swapSourceOnBreakEvent);
        }

        helper.assertTrue(outcome != ErosionAttempt.Outcome.APPLIED,
                "a source swapped to a different state during the claim check must never be smoothed on top of, got " + outcome);
        helper.assertTrue(helper.getBlockState(pos).equals(swappedState),
                "the listener's own substituted state must be preserved untouched, not overwritten by a stale plan");
        helper.succeed();
    }

    /**
     * Regression (review 54915): the fix for 54910 must not overcorrect into denying a harmless
     * change. A real {@code BlockEvent.BreakEvent} listener swaps the destination's SUPPORT
     * block (never independently checked by this mod -- not source, not destination) for a
     * DIFFERENT but still-sturdy block, without cancelling. {@code canSmooth} stays {@code true}
     * (the swap does not break anchoring) and neither source nor destination's own state
     * changes, so the move must still apply normally -- proving the source/destination
     * equality check added for 54910 does not also deny this harmless case.
     */
    @GameTest(template = "flat_platform")
    public static void aHarmlessSupportSubstitutionDoesNotPreventTheMoveFromApplying(GameTestHelper helper) {
        if (dynamicTerrainIsLoaded()) {
            helper.succeed();
            return;
        }
        BlockPos pos = new BlockPos(1, 2, 1);
        BlockPos destPos = pos.relative(Direction.EAST);
        BlockPos supportPos = destPos.below();
        placeSourceWithOneValidEastNeighbor(helper, pos);
        ServerLevel level = (ServerLevel) helper.getLevel();
        BlockPos absolutePos = helper.absolutePos(pos);
        BlockPos absoluteSupportPos = helper.absolutePos(supportPos);

        Object swapSupportOnBreakEvent = new Object() {
            @net.neoforged.bus.api.SubscribeEvent
            public void onBreak(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent event) {
                if (event.getPos().equals(absolutePos)) {
                    level.setBlock(absoluteSupportPos, Blocks.COBBLESTONE.defaultBlockState(), 3);
                }
            }
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(swapSupportOnBreakEvent);
        ErosionAttempt.Outcome outcome;
        try {
            outcome = ErosionAttempt.attempt(level, absolutePos,
                    SLIP_ABOVE_THRESHOLD_MS, REFERENCE_LOAD_KG, RandomSource.create(1),
                    GUARANTEED_CHANCE_CONFIG, new ErosionDedup());
        } finally {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(swapSupportOnBreakEvent);
        }

        helper.assertTrue(outcome == ErosionAttempt.Outcome.APPLIED,
                "a harmless support substitution (still sturdy, source/destination unchanged) must not prevent the move, got " + outcome);
        helper.assertTrue(helper.getBlockState(pos).getBlock() == LayeredSlopesMod.LAYERED_DIRT.get(),
                "the source cell must still have become LAYERED_DIRT");
        helper.assertTrue(helper.getBlockState(supportPos).getBlock() == Blocks.COBBLESTONE,
                "the listener's own support substitution must be preserved");
        helper.succeed();
    }

    /**
     * Regression (review 54915): a {@code DynamicTerrainCaveInRisk.readBooleanConfig} lookup for
     * a modid with no registered config of the requested type must fail closed to the caller's
     * supplied fallback, rather than silently proving safety from an absence of information.
     * Performs no mutation and registers nothing (does not depend on Dynamic Terrain or any
     * other real mod's config).
     */
    @GameTest(template = "flat_platform")
    public static void unknownConfigFailsClosedToTheSuppliedFallback(GameTestHelper helper) {
        boolean fallbackTrue = DynamicTerrainCaveInRisk.readBooleanConfig(
                "no-such-mod-id-xyz", net.neoforged.fml.config.ModConfig.Type.SERVER, "someKey", true);
        boolean fallbackFalse = DynamicTerrainCaveInRisk.readBooleanConfig(
                "no-such-mod-id-xyz", net.neoforged.fml.config.ModConfig.Type.SERVER, "someKey", false);

        helper.assertTrue(fallbackTrue, "an unknown config must return the caller's true fallback, not silently report safety");
        helper.assertTrue(!fallbackFalse, "an unknown config must return the caller's false fallback unchanged");
        helper.succeed();
    }
}
