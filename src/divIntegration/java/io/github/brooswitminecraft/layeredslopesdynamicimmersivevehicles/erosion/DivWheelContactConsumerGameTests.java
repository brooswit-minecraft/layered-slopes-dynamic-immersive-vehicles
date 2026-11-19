package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

import io.github.brooswitminecraft.div.WheelGroundContactEvent;
import io.github.brooswitminecraft.layeredslopes.LayeredBlock;
import io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.LayeredSlopesDynamicImmersiveVehiclesMod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Real production-wiring evidence for {@link DivWheelContactConsumer} (MINECRAFT-510 review
 * finding on PR #17: "this head has no deterministic production-wiring evidence that a real
 * WheelGroundContactEvent is snapshot-enqueued, is drained only at the matching
 * ServerTickEvent.Post, and produces exactly one ErosionAttempt; nor that stale/missing-level/
 * unloaded records reach no attempt through that wiring"). Unlike {@link
 * WheelContactQueueTest}/{@link ErosionDedupTest} (pure, Minecraft-free, run under plain {@code
 * ./gradlew test}), these tests post a REAL {@link WheelGroundContactEvent} onto the REAL {@code
 * NeoForge.EVENT_BUS} and let the REAL registered {@link DivWheelContactConsumer} (registered
 * once, at mod construction, via the {@code ServiceLoader} seam -- never called directly here)
 * handle it -- exactly the path a real DIV wheel contact would take, with nothing faked or
 * bypassed.
 *
 * <p>Only exists in the opt-in {@code -PdivLocalJar} build ({@code src/divIntegration}), run by
 * {@code div-paired-runtime.yml}'s real {@code runGameTestServer} boot -- same as every other
 * class in this sourceSet.
 *
 * <p>Both event-posting tests below need {@link ErosionModConfig#OVERRIDES} (the real, global
 * config singleton {@link DivWheelContactConsumer} itself reads) forced to {@link
 * ErosionAttemptGameTests#GUARANTEED_CHANCE_CONFIG} for the whole of their asynchronous
 * three-tick window, not just their own synchronous setup. {@code OVERRIDES} is real,
 * process-wide, shared-static state -- {@code ErosionModConfigGameTests} and {@code
 * ErosionClientSyncTestCommand} also read it. An earlier revision had each test independently
 * capture-and-restore its own snapshot (review finding M55554611-followup, Jira comment 55269 /
 * GitHub comment 6995553666), which is unsound once two such tests can overlap in the same
 * default batch: if test B's setup runs while test A's forced override is still in effect, B
 * would capture A's temporary value as its own "original," and whichever of the two restores
 * last would decide the final (wrong) state -- review finding M55554611-followup-2, Jira comment
 * 55274 / GitHub comment 6995981468. {@link GuaranteedChanceOverrideGuard#acquire()}/{@link
 * GuaranteedChanceOverrideGuard#release()} fix the race between overlapping acquisitions with a
 * simple reference count: only the FIRST acquirer (whichever test reaches it first) captures the
 * true pre-existing snapshot, and only the LAST releaser restores it -- correct regardless of
 * how any number of tests' ticks interleave with each other.
 *
 * <p>That alone was not enough: every {@code @GameTest} in this codebase defaults to the same
 * {@code "defaultBatch"}, and a batch's tests all run CONCURRENTLY (interleaved tick-by-tick on
 * the single server thread, not run to completion one at a time) -- confirmed the hard way, by a
 * real CI failure, when the first version of this fix still broke {@code
 * ErosionModConfigGameTests#realConfigBootsWithErosionDisabledByDefault} (a completely unrelated,
 * pre-existing, default-batch test that asserts {@code OVERRIDES.current().erosionEnabled()} is
 * {@code false} at its own start): it happened to tick while one of these tests' forced-enabled
 * window was still open, and failed. All three {@code @GameTest} methods below are therefore
 * given their own {@code batch = "divWheelContactConsumerOverrideBatch"}, which the real
 * framework (see {@code GameTestBatchFactory}) schedules as a separate batch from {@code
 * defaultBatch}. Even distinct batch names do not guarantee full serialization against EVERY
 * other batch for a long-running test, though (direction comment 55301: {@code
 * DivDrivenWheelAcceptanceGameTests}' own much-longer real drive window still overlapped this
 * batch's own deterministic-evidence test under an earlier, separate-batch design) -- both
 * classes now share this exact batch name AND the same {@link GuaranteedChanceOverrideGuard}, so
 * the reference count itself is what guarantees correctness, not batch-name separation alone.
 */
@GameTestHolder(LayeredSlopesDynamicImmersiveVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class DivWheelContactConsumerGameTests {
    private DivWheelContactConsumerGameTests() {
    }

    private static final double SLIP_ABOVE_THRESHOLD_MS = 5.0;
    private static final double REFERENCE_LOAD_KG = 300.0;
    /** {@code ErosionConfig}'s real gravity-like unit: Earth-standard, matching other acceptance workflows. */
    private static final double GRAVITY = 9.8;

    // Acquire/release moved to the shared GuaranteedChanceOverrideGuard (direction comment
    // 55301): a real CI failure proved this class's OWN guard, combined with trusting a
    // distinct batch name to fully serialize against OTHER classes, was not enough once a
    // second, much-longer-running real-wiring test (DivDrivenWheelAcceptanceGameTests, a
    // separate class) shares this same batch and singleton. See that shared class's own doc.

    /**
     * The full real path: post a real, valid {@link WheelGroundContactEvent} for the source
     * position (top face) -> the real registered {@link DivWheelContactConsumer} snapshot-
     * enqueues it -> the next real {@code ServerTickEvent.Post} drains it -> exactly one real
     * {@code ErosionAttempt.attempt} call actually mutates the world. Waits 3 ticks (comfortably
     * past the 1 tick actually required) before asserting the eventual mutation, so that
     * assertion is never a race against the real drain's own timing.
     *
     * <p>Also asserts immediately after posting the event that the source is still unchanged --
     * proof the event listener itself performs no synchronous mutation, only the later {@code
     * ServerTickEvent.Post} drain does (review finding M55554611-followup, Jira comment 55269 /
     * GitHub comment 6995553666) -- and passes {@code REFERENCE_LOAD_KG * GRAVITY} as the
     * {@code normalForce} argument, since {@link WheelGroundContactEvent#createIfValid} divides
     * that argument by gravity to compute {@code supportedLoadKg} (same review finding).
     */
    @GameTest(template = "flat_platform", batch = "divWheelContactConsumerOverrideBatch")
    public static void aRealPostedTopFaceEventFlowsThroughTheRealWiringToExactlyOneApplication(
            GameTestHelper helper) {
        GuaranteedChanceOverrideGuard.acquire();
        try {
            BlockPos pos = new BlockPos(1, 2, 1);
            ErosionAttemptGameTests.placeSourceWithOneValidEastNeighbor(helper, pos);
            ServerLevel level = (ServerLevel) helper.getLevel();
            BlockPos sourcePos = helper.absolutePos(pos);
            BlockPos destPos = helper.absolutePos(pos.relative(Direction.EAST));

            var event = WheelGroundContactEvent.createIfValid(level, sourcePos, Direction.UP,
                    SLIP_ABOVE_THRESHOLD_MS, REFERENCE_LOAD_KG * GRAVITY, GRAVITY, level.getServer().getTickCount());
            helper.assertTrue(event != null, "createIfValid must accept this deliberately valid payload");
            NeoForge.EVENT_BUS.post(event);

            helper.assertTrue(helper.getBlockState(pos).getBlock() == Blocks.DIRT,
                    "the event listener itself must never mutate synchronously -- expected the source to "
                            + "still be plain dirt immediately after NeoForge.EVENT_BUS.post(event) returned, "
                            + "before any ServerTickEvent.Post drain has run, got " + helper.getBlockState(pos));

            helper.runAfterDelay(3, () -> {
                try {
                    var sourceState = helper.getBlockState(pos);
                    var destState = helper.getBlockState(pos.relative(Direction.EAST));
                    helper.assertTrue(sourceState.hasProperty(LayeredBlock.LAYERS)
                                    && sourceState.getValue(LayeredBlock.LAYERS) == 15,
                            "expected source layered_dirt/15 after the real wiring drained and applied, got "
                                    + sourceState);
                    helper.assertTrue(destState.hasProperty(LayeredBlock.LAYERS)
                                    && destState.getValue(LayeredBlock.LAYERS) == 1,
                            "expected destination layered_dirt/1 after the real wiring drained and applied, got "
                                    + destState);
                    helper.succeed();
                } finally {
                    GuaranteedChanceOverrideGuard.release();
                }
            });
        } catch (RuntimeException | Error e) {
            GuaranteedChanceOverrideGuard.release();
            throw e;
        }
    }

    /**
     * Review finding ("verify whether ignoring the preserved contact face is semantically
     * correct"), proven through the REAL wiring this time (unit coverage already exists in
     * {@link WheelContactQueueTest}): a real event naming a SIDE face must reach no attempt at
     * all -- the source block must still be plain dirt after the real drain runs. Same {@code
     * REFERENCE_LOAD_KG * GRAVITY} unit correction and shared-override-guard usage as the
     * top-face test above; see that method's own doc, and this class's own doc, for why.
     */
    @GameTest(template = "flat_platform", batch = "divWheelContactConsumerOverrideBatch")
    public static void aRealPostedSideFaceEventNeverReachesAnAttemptThroughTheRealWiring(GameTestHelper helper) {
        GuaranteedChanceOverrideGuard.acquire();
        try {
            BlockPos pos = new BlockPos(1, 2, 4);
            ErosionAttemptGameTests.placeSourceWithOneValidEastNeighbor(helper, pos);
            ServerLevel level = (ServerLevel) helper.getLevel();
            BlockPos sourcePos = helper.absolutePos(pos);

            var event = WheelGroundContactEvent.createIfValid(level, sourcePos, Direction.NORTH,
                    SLIP_ABOVE_THRESHOLD_MS, REFERENCE_LOAD_KG * GRAVITY, GRAVITY, level.getServer().getTickCount());
            helper.assertTrue(event != null, "createIfValid must accept this deliberately valid (side-face) payload");
            NeoForge.EVENT_BUS.post(event);

            helper.runAfterDelay(3, () -> {
                try {
                    helper.assertTrue(helper.getBlockState(pos).getBlock() == Blocks.DIRT,
                            "a side-face contact must never reach an attempt through the real wiring -- the "
                                    + "source must still be plain dirt, got " + helper.getBlockState(pos));
                    helper.succeed();
                } finally {
                    GuaranteedChanceOverrideGuard.release();
                }
            });
        } catch (RuntimeException | Error e) {
            GuaranteedChanceOverrideGuard.release();
            throw e;
        }
    }

    /**
     * Deterministic evidence required by review (Jira comment 55274 / GitHub comment
     * 6995981468: "add deterministic evidence that both tests may execute without changing the
     * pre-existing override after the pair completes") that the guard above actually leaves
     * {@link ErosionModConfig#OVERRIDES} exactly as it found it, regardless of how the two tests
     * above interleave. Waits 10 ticks -- a comfortable margin past both other tests' own
     * 3-tick completion -- then asserts the real registered config is back to its real
     * boot-time default ({@code erosionEnabled=false}, independently proven by {@link
     * ErosionModConfigGameTests#realConfigBootsWithErosionDisabledByDefault}).
     *
     * <p>Review finding, Jira comment 55306: a batch does NOT serialize different
     * {@code @GameTest} methods against each other, so this test cannot assume a fixed delay is
     * long enough to outlast every real acquirer sharing this batch's guard -- including
     * {@code DivDrivenWheelAcceptanceGameTests}' own real vehicle drive, which this class has no
     * way to know the duration of. Polls {@link GuaranteedChanceOverrideGuard#isFullyReleased()}
     * (the real signal, not a guess) every tick instead, bounded by this test's own explicit
     * {@code timeoutTicks} -- a real timeout here (the guard never actually finishing releasing)
     * is a genuine failure the framework should report, not something to silently paper over
     * with an even longer guess.
     */
    @GameTest(template = "flat_platform", batch = "divWheelContactConsumerOverrideBatch", timeoutTicks = 1000)
    public static void theSharedOverrideGuardLeavesTheConfigExactlyAsFoundAfterBothTestsComplete(
            GameTestHelper helper) {
        pollUntilGuardFullyReleased(helper);
    }

    private static void pollUntilGuardFullyReleased(GameTestHelper helper) {
        if (!GuaranteedChanceOverrideGuard.isFullyReleased()) {
            helper.runAfterDelay(1, () -> pollUntilGuardFullyReleased(helper));
            return;
        }
        helper.assertTrue(!ErosionModConfig.OVERRIDES.current().erosionEnabled(),
                "expected the real registered config's erosionEnabled to be back to its original "
                        + "(default false) value now that every real acquirer sharing this batch's "
                        + "guard has released, regardless of their relative execution order");
        helper.succeed();
    }
}
