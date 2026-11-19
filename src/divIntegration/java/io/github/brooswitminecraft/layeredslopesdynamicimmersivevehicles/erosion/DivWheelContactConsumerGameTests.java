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
 * <p>Forces {@link ErosionModConfig#OVERRIDES} to {@link
 * ErosionAttemptGameTests#GUARANTEED_CHANCE_CONFIG} directly (the real, global config singleton
 * {@link DivWheelContactConsumer} itself reads -- the only consumer in this codebase that reads
 * it rather than taking an explicit config parameter) rather than relying on a pre-seeded config
 * file. No other existing test in this codebase reads {@code OVERRIDES} at all (every one of
 * them passes an explicit {@code ErosionConfig} straight to {@code ErosionAttempt.attempt}), so
 * this is a safe, isolated first use of that singleton from a {@code GameTest}, not a risk to
 * any other test's own behavior.
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

    /**
     * The full real path: post a real, valid {@link WheelGroundContactEvent} for the source
     * position (top face) -> the real registered {@link DivWheelContactConsumer} snapshot-
     * enqueues it -> the next real {@code ServerTickEvent.Post} drains it -> exactly one real
     * {@code ErosionAttempt.attempt} call actually mutates the world. Waits 3 ticks (comfortably
     * past the 1 tick actually required) before asserting, so this is never a race against the
     * real drain's own timing.
     */
    @GameTest(template = "flat_platform")
    public static void aRealPostedTopFaceEventFlowsThroughTheRealWiringToExactlyOneApplication(
            GameTestHelper helper) {
        ErosionModConfig.OVERRIDES.reload(ErosionAttemptGameTests.GUARANTEED_CHANCE_CONFIG);
        BlockPos pos = new BlockPos(1, 2, 1);
        ErosionAttemptGameTests.placeSourceWithOneValidEastNeighbor(helper, pos);
        ServerLevel level = (ServerLevel) helper.getLevel();
        BlockPos sourcePos = helper.absolutePos(pos);
        BlockPos destPos = helper.absolutePos(pos.relative(Direction.EAST));

        var event = WheelGroundContactEvent.createIfValid(level, sourcePos, Direction.UP,
                SLIP_ABOVE_THRESHOLD_MS, REFERENCE_LOAD_KG, GRAVITY, level.getServer().getTickCount());
        helper.assertTrue(event != null, "createIfValid must accept this deliberately valid payload");
        NeoForge.EVENT_BUS.post(event);

        helper.runAfterDelay(3, () -> {
            var sourceState = helper.getBlockState(pos);
            var destState = helper.getBlockState(pos.relative(Direction.EAST));
            helper.assertTrue(sourceState.hasProperty(LayeredBlock.LAYERS)
                            && sourceState.getValue(LayeredBlock.LAYERS) == 15,
                    "expected source layered_dirt/15 after the real wiring drained and applied, got " + sourceState);
            helper.assertTrue(destState.hasProperty(LayeredBlock.LAYERS)
                            && destState.getValue(LayeredBlock.LAYERS) == 1,
                    "expected destination layered_dirt/1 after the real wiring drained and applied, got " + destState);
            helper.succeed();
        });
    }

    /**
     * Review finding ("verify whether ignoring the preserved contact face is semantically
     * correct"), proven through the REAL wiring this time (unit coverage already exists in
     * {@link WheelContactQueueTest}): a real event naming a SIDE face must reach no attempt at
     * all -- the source block must still be plain dirt after the real drain runs.
     */
    @GameTest(template = "flat_platform")
    public static void aRealPostedSideFaceEventNeverReachesAnAttemptThroughTheRealWiring(GameTestHelper helper) {
        ErosionModConfig.OVERRIDES.reload(ErosionAttemptGameTests.GUARANTEED_CHANCE_CONFIG);
        BlockPos pos = new BlockPos(1, 2, 4);
        ErosionAttemptGameTests.placeSourceWithOneValidEastNeighbor(helper, pos);
        ServerLevel level = (ServerLevel) helper.getLevel();
        BlockPos sourcePos = helper.absolutePos(pos);

        var event = WheelGroundContactEvent.createIfValid(level, sourcePos, Direction.NORTH,
                SLIP_ABOVE_THRESHOLD_MS, REFERENCE_LOAD_KG, GRAVITY, level.getServer().getTickCount());
        helper.assertTrue(event != null, "createIfValid must accept this deliberately valid (side-face) payload");
        NeoForge.EVENT_BUS.post(event);

        helper.runAfterDelay(3, () -> {
            helper.assertTrue(helper.getBlockState(pos).getBlock() == Blocks.DIRT,
                    "a side-face contact must never reach an attempt through the real wiring -- the source "
                            + "must still be plain dirt, got " + helper.getBlockState(pos));
            helper.succeed();
        });
    }
}
