package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

import io.github.brooswitminecraft.layeredslopes.LayeredBlock;
import io.github.brooswitminecraft.layeredslopes.LayeredSlopesMod;
import io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.LayeredSlopesDynamicImmersiveVehiclesMod;

import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Acceptance evidence for {@link ErosionAttempt}, the real orchestration of dedup, the accepted
 * MINECRAFT-555 slip-to-probability roll, direction selection, and the {@link
 * ErosionProtectionGate} -- not just each primitive in isolation.
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

    private static final ErosionConfig GUARANTEED_CHANCE_CONFIG =
            new ErosionConfig(0.15, 1000.0, 10.0, 300.0, 3.0, 0.25, 4.0);
    private static final double SLIP_ABOVE_THRESHOLD_MS = 5.0;
    private static final double REFERENCE_LOAD_KG = 300.0;
    private static final double TINY_CHANCE_SLIP_MS = 0.16; // just above DEFAULT's 0.15 threshold

    private static ErosionProtectionGate.ProtectionAdapter grantingAdapter() {
        return new ErosionProtectionGate.ProtectionAdapter() {
            @Override
            public boolean supportsSourceDestinationAuthorization() {
                return true;
            }

            @Override
            public boolean supportsNeighborCaveInPropagation() {
                return true;
            }

            @Override
            public String describe() {
                return "GameTest-only adapter that grants every request, used to exercise the APPLIED path";
            }

            @Override
            public boolean authorize(ErosionProtectionGate.MutationRequest request) {
                return true;
            }
        };
    }

    /**
     * EAST is the only candidate direction {@code canSmooth} can accept: the other three are
     * explicitly STONE (a different, blocking material -- {@code SmoothRules.Destination.BLOCKED})
     * so the shuffled direction order can never pick a different real destination regardless of
     * seed.
     */
    private static void placeSourceWithOneValidEastNeighbor(GameTestHelper helper, BlockPos pos) {
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

    @GameTest(template = "flat_platform")
    public static void defaultClosedGateDeniesEvenWhenRollAndDirectionWouldOtherwiseSucceed(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 2, 1);
        placeSourceWithOneValidEastNeighbor(helper, pos);
        BlockState before = helper.getBlockState(pos);

        ErosionProtectionGate gate = new ErosionProtectionGate(); // no adapter installed: fails closed
        ErosionAttempt.Outcome outcome = ErosionAttempt.attempt(
                (ServerLevel) helper.getLevel(), helper.absolutePos(pos),
                SLIP_ABOVE_THRESHOLD_MS, REFERENCE_LOAD_KG, RandomSource.create(1),
                GUARANTEED_CHANCE_CONFIG, new ErosionDedup(), gate);

        helper.assertTrue(outcome == ErosionAttempt.Outcome.DENIED_BY_PROTECTION,
                "expected DENIED_BY_PROTECTION with no adapter installed, got " + outcome);
        helper.assertTrue(helper.getBlockState(pos).equals(before),
                "the world must stay unmutated when protection denies the only valid direction");
        helper.succeed();
    }

    /**
     * Regression (review 54878 finding 1): a request this orchestration cannot yet claim a real
     * propagation scope for must be denied EVEN BY A FULLY GRANTING ADAPTER -- the production
     * {@code attempt()} overload always builds requests with {@code propagationScopeKnown=false}
     * since no neighbor/cave-in consequence analysis exists anywhere in this mod yet.
     */
    @GameTest(template = "flat_platform")
    public static void evenAGrantingAdapterCannotApplyBecausePropagationScopeIsNeverClaimedKnown(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 2, 1);
        placeSourceWithOneValidEastNeighbor(helper, pos);
        BlockState before = helper.getBlockState(pos);

        ErosionProtectionGate gate = new ErosionProtectionGate();
        gate.assertCoverage(grantingAdapter());
        ErosionAttempt.Outcome outcome = ErosionAttempt.attempt(
                (ServerLevel) helper.getLevel(), helper.absolutePos(pos),
                SLIP_ABOVE_THRESHOLD_MS, REFERENCE_LOAD_KG, RandomSource.create(1),
                GUARANTEED_CHANCE_CONFIG, new ErosionDedup(), gate);

        helper.assertTrue(outcome == ErosionAttempt.Outcome.DENIED_BY_PROTECTION,
                "a granting adapter must still not help: propagationScopeKnown is always false in production, got " + outcome);
        helper.assertTrue(helper.getBlockState(pos).equals(before), "the world must stay unmutated");
        helper.succeed();
    }

    /**
     * Exercises the direction-selection/{@code Smoothing.smooth} mechanics via the package-only
     * test seam (propagation scope claimed known) -- proving the mechanism itself is correct,
     * independent of the separate, deliberate "never claim known in production" policy above.
     * Also verifies material conservation: a full 16-layer-equivalent dirt block splits into
     * exactly 15 (source) + 1 (destination) layers, not lost or duplicated.
     */
    @GameTest(template = "flat_platform")
    public static void aKnownPropagationScopeWithAGrantingAdapterAppliesAndConservesMaterial(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 2, 1);
        BlockPos destPos = pos.relative(Direction.EAST);
        placeSourceWithOneValidEastNeighbor(helper, pos);

        ErosionProtectionGate gate = new ErosionProtectionGate();
        gate.assertCoverage(grantingAdapter());
        ErosionAttempt.Outcome outcome = ErosionAttempt.attempt(
                (ServerLevel) helper.getLevel(), helper.absolutePos(pos),
                SLIP_ABOVE_THRESHOLD_MS, REFERENCE_LOAD_KG, RandomSource.create(1),
                GUARANTEED_CHANCE_CONFIG, new ErosionDedup(), gate, true);

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

    @GameTest(template = "flat_platform")
    public static void dedupPreventsASecondClaimInTheSameTick(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 2, 1);
        placeSourceWithOneValidEastNeighbor(helper, pos);

        ErosionProtectionGate gate = new ErosionProtectionGate();
        gate.assertCoverage(grantingAdapter());
        ErosionDedup dedup = new ErosionDedup();

        ErosionAttempt.Outcome first = ErosionAttempt.attempt(
                (ServerLevel) helper.getLevel(), helper.absolutePos(pos),
                SLIP_ABOVE_THRESHOLD_MS, REFERENCE_LOAD_KG, RandomSource.create(1),
                GUARANTEED_CHANCE_CONFIG, dedup, gate, true);
        ErosionAttempt.Outcome second = ErosionAttempt.attempt(
                (ServerLevel) helper.getLevel(), helper.absolutePos(pos),
                SLIP_ABOVE_THRESHOLD_MS, REFERENCE_LOAD_KG, RandomSource.create(2),
                GUARANTEED_CHANCE_CONFIG, dedup, gate, true);

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

        ErosionProtectionGate gate = new ErosionProtectionGate(); // no adapter: every attempt denied
        ErosionDedup dedup = new ErosionDedup();

        ErosionAttempt.Outcome first = ErosionAttempt.attempt(
                (ServerLevel) helper.getLevel(), helper.absolutePos(pos),
                SLIP_ABOVE_THRESHOLD_MS, REFERENCE_LOAD_KG, RandomSource.create(1),
                GUARANTEED_CHANCE_CONFIG, dedup, gate);
        ErosionAttempt.Outcome second = ErosionAttempt.attempt(
                (ServerLevel) helper.getLevel(), helper.absolutePos(pos),
                SLIP_ABOVE_THRESHOLD_MS, REFERENCE_LOAD_KG, RandomSource.create(2),
                GUARANTEED_CHANCE_CONFIG, dedup, gate);

        helper.assertTrue(first == ErosionAttempt.Outcome.DENIED_BY_PROTECTION, "expected first DENIED_BY_PROTECTION, got " + first);
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

        ErosionProtectionGate gate = new ErosionProtectionGate();
        gate.assertCoverage(grantingAdapter());
        ErosionDedup dedup = new ErosionDedup();

        ErosionAttempt.Outcome first = ErosionAttempt.attempt(
                (ServerLevel) helper.getLevel(), helper.absolutePos(pos),
                TINY_CHANCE_SLIP_MS, REFERENCE_LOAD_KG, RandomSource.create(1),
                ErosionConfig.DEFAULT, dedup, gate, true);
        ErosionAttempt.Outcome second = ErosionAttempt.attempt(
                (ServerLevel) helper.getLevel(), helper.absolutePos(pos),
                TINY_CHANCE_SLIP_MS, REFERENCE_LOAD_KG, RandomSource.create(2),
                ErosionConfig.DEFAULT, dedup, gate, true);

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

        ErosionProtectionGate gate = new ErosionProtectionGate();
        gate.assertCoverage(grantingAdapter());
        ErosionAttempt.Outcome outcome = ErosionAttempt.attempt(
                (ServerLevel) helper.getLevel(), helper.absolutePos(pos),
                SLIP_ABOVE_THRESHOLD_MS, REFERENCE_LOAD_KG, RandomSource.create(1),
                GUARANTEED_CHANCE_CONFIG, new ErosionDedup(), gate);

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
        BlockPos pos = new BlockPos(1, 2, 1);
        placeSourceWithOneValidEastNeighbor(helper, pos);
        BlockState before = helper.getBlockState(pos);

        ErosionProtectionGate gate = new ErosionProtectionGate();
        gate.assertCoverage(grantingAdapter());
        ErosionDedup dedup = new ErosionDedup();
        ServerLevel level = (ServerLevel) helper.getLevel();
        BlockPos absolutePos = helper.absolutePos(pos);

        AtomicReference<ErosionAttempt.Outcome> offThreadOutcome = new AtomicReference<>();
        AtomicReference<Throwable> offThreadError = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                offThreadOutcome.set(ErosionAttempt.attempt(level, absolutePos,
                        SLIP_ABOVE_THRESHOLD_MS, REFERENCE_LOAD_KG, RandomSource.create(1),
                        GUARANTEED_CHANCE_CONFIG, dedup, gate, true));
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
                GUARANTEED_CHANCE_CONFIG, dedup, gate, true);
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

        ErosionProtectionGate gate = new ErosionProtectionGate();
        gate.assertCoverage(grantingAdapter());
        ErosionDedup dedup = new ErosionDedup();

        helper.assertTrue(!level.hasChunkAt(farAwayPos), "test precondition: this position must start out unloaded");
        ErosionAttempt.Outcome outcome = ErosionAttempt.attempt(level, farAwayPos,
                SLIP_ABOVE_THRESHOLD_MS, REFERENCE_LOAD_KG, RandomSource.create(1),
                GUARANTEED_CHANCE_CONFIG, dedup, gate, true);

        helper.assertTrue(outcome == ErosionAttempt.Outcome.SOURCE_NOT_LOADED, "expected SOURCE_NOT_LOADED, got " + outcome);
        helper.assertTrue(!level.hasChunkAt(farAwayPos), "the call must not have force-loaded the chunk as a side effect");
        helper.assertTrue(dedup.claimedCountForCurrentTick() == 0, "an unloaded source must not consume a dedup slot");
        helper.succeed();
    }

    // A real "loaded source, unloaded destination" GameTest (review 54883) was implemented and
    // passed locally, but proved environment-dependent: on the actual GitHub Actions runner the
    // destination chunk it depended on staying unloaded was NOT unloaded, failing its own
    // precondition, while the identical test passed repeatedly on this local sandbox. Rather than
    // ship a test whose outcome depends on chunk-loading behavior that differs between
    // environments for reasons not fully diagnosed, it was reverted. The destination guard
    // remains the identical `hasChunkAt` check at the identical call shape as the source guard
    // tested above; a more robust way to directly prove the destination branch's ordering would
    // be extracting the loaded-check into a seam that can be driven with a controllable fake
    // rather than real chunk state -- flagged as follow-up, not attempted here.
}
