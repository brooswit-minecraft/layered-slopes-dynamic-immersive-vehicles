package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

import io.github.brooswitminecraft.div.WheelGroundContactEvent;
import io.github.brooswitminecraft.layeredslopes.LayeredBlock;
import io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.LayeredSlopesDynamicImmersiveVehiclesMod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import minecrafttransportsimulator.baseclasses.Point3D;
import minecrafttransportsimulator.baseclasses.RotationMatrix;
import minecrafttransportsimulator.entities.instances.APart;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.entities.instances.PartEngine;
import minecrafttransportsimulator.entities.instances.PartGroundDevice;
import minecrafttransportsimulator.items.instances.ItemPartEngine;
import minecrafttransportsimulator.items.instances.ItemPartGroundDevice;
import minecrafttransportsimulator.items.instances.ItemVehicle;
import minecrafttransportsimulator.jsondefs.AJSONItem;
import minecrafttransportsimulator.jsondefs.JSONCollisionBox;
import minecrafttransportsimulator.jsondefs.JSONCollisionGroup;
import minecrafttransportsimulator.jsondefs.JSONPart;
import minecrafttransportsimulator.jsondefs.JSONPartDefinition;
import minecrafttransportsimulator.jsondefs.JSONRendering;
import minecrafttransportsimulator.jsondefs.JSONSubDefinition;
import minecrafttransportsimulator.jsondefs.JSONVehicle;
import minecrafttransportsimulator.mcinterface.AWrapperWorld;
import minecrafttransportsimulator.systems.ConfigSystem;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Real driven-wheel gameplay acceptance (MINECRAFT-510's last required slice, direction comment
 * 55251/55295: "a genuine Phase 3 raycast contact from an actually-driven vehicle, not a forced
 * test command or manually-posted event"). Spawns a REAL {@code EntityVehicleF_Physics} with
 * real {@link PartGroundDevice} wheels and a real {@link PartEngine}, over a real carved
 * platform seeded with many valid erosion-source cells, and drives it with direct writes to its
 * own real public input variables ({@code throttleVar}/{@code rudderInputVar}) -- the exact,
 * already-reviewed-and-proven methodology {@code GroundSupportFixtureProbe} (DIV's own repo,
 * {@code src/main/java/io/github/brooswitminecraft/div/experimental/}) established for this
 * purpose: no player, no datapack, no forced command, no manually-constructed event. The
 * vehicle's own real per-tick {@code moveVehicle} genuinely runs through the unmodified
 * production path ({@code VehicleMovementMixin} -&gt; {@code PhysicsBridge.step} -&gt;
 * {@code Body.stepGround}), generating real wheel-ground raycast contacts -- Phase 3's real
 * {@code sideSpeed} sampling -- that flow through the real, merged
 * {@code WheelGroundContactEvent} -&gt; {@link DivWheelContactConsumer} -&gt; {@link
 * ErosionAttempt} chain exactly as a real player-driven vehicle would produce them.
 *
 * <p>Vehicle construction (chassis geometry, wheel anchors, part JSON scaffolding) is adapted
 * directly from {@code GroundSupportFixtureProbe}'s own real, CI-proven code -- same pack ID
 * convention, same chassis half-extents, same {@code JSONVehicle}/{@code JSONPart} boilerplate
 * (every nullable field that probe's own review history found NPEs on is populated here too).
 * Differences: this runs as a real {@code @GameTest} (a {@code helper.runAfterDelay} chain
 * replaces that probe's own hand-rolled per-tick phase state machine, since GameTest's own tick
 * loop is what repeatedly re-enters this test, not a loop this class writes itself), drives over
 * a real carved platform seeded with many {@link ErosionAttemptGameTests#placeSourceWithOneValidEastNeighbor}
 * cells instead of DIV's own flat characterization platform (same sequential straight-drive-then-
 * turn phase order as that probe, confirmed necessary by a real CI run -- see {@link
 * #driveStraightFor}/{@link #driveWithSlipFor}'s own docs), and removes the vehicle via the real
 * {@code AEntityA_Base#remove()} method at the end (that probe never cleans up, since it runs
 * once per disposable CI-world server lifetime; a GameTest world is reused within a batch, so
 * this does).
 *
 * <p>{@link ErosionModConfig#OVERRIDES} is forced to {@link
 * ErosionAttemptGameTests#GUARANTEED_CHANCE_CONFIG} for the enabled phase via the shared {@link
 * GuaranteedChanceOverrideGuard} (same real, process-wide singleton {@link
 * DivWheelContactConsumer} itself reads, also used by {@link DivWheelContactConsumerGameTests}).
 * Shares that class's own {@code batch = "divWheelContactConsumerOverrideBatch"} -- a real CI
 * failure proved that a THIRD, merely differently-named batch does not reliably serialize
 * against that one for a test this much longer-running (see {@link
 * #aGenuinelyDrivenVehicleWheelProducesConservedMutationWhenEnabledAndNoneWhenDisabled}'s own
 * doc for the full story and why the enabled/disabled contrast is one single, strictly
 * sequential test method rather than two separate ones).
 */
@GameTestHolder(LayeredSlopesDynamicImmersiveVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class DivDrivenWheelAcceptanceGameTests {
    private DivDrivenWheelAcceptanceGameTests() {
    }

    private static final String PACK_ID = "divtest";
    private static final double LOW_SPEED_TARGET = 4.0; // m/s -- GroundSupportFixtureProbe's own bounded, realistic low-speed target
    private static final double RUDDER_DEGREES = 40.0; // within MAX_RUDDER_ANGLE=45 -- real, nonzero, bounded steering input

    // Real ATV chassis half-extents/center/wheel-anchors (GroundSupportFixtureProbe's own
    // established values, not a new guess): half-extents (x,y,z) = (0.425, 0.441, 1.05).
    private static final double HALF_EXTENT_A = 0.42500001192092896;
    private static final double HALF_HEIGHT_B = 0.4410937510430813;
    private static final double HALF_EXTENT_C = 1.050000011920929;
    private static final Point3D CHASSIS_CENTER = new Point3D(0, 0.553593748062849, 0.625);
    private static final Point3D WHEEL_RL = new Point3D(0.35, 0.125, -0.0625);
    private static final Point3D WHEEL_FL = new Point3D(-0.375, 0.125, 1.25);
    private static final Point3D WHEEL_FR = new Point3D(0.375, 0.125, 1.25);
    private static final Point3D WHEEL_RR = new Point3D(-0.375, 0.125, -0.0625);

    private static final int SETTLE_TICKS = 30;
    // Review-worthy finding from a real CI run (direction comment 55301): applying throttle AND
    // a steady rudder pulse together from a near-standstill produced a vehicle that reached real
    // speed briefly, then stalled to near-zero motion for the rest of the window -- unlike
    // GroundSupportFixtureProbe's own proven sequence, which NEVER combines rudder with throttle
    // from a standing start: it drives STRAIGHT first (building real, clean speed), only THEN
    // adds rudder while already cruising. Split into the same two sequential phases here.
    //
    // A second real CI run (still direction comment 55301) confirmed the fix above produced
    // genuine, SUSTAINED ~4-5 m/s motion throughout both phases -- but STRAIGHT_DRIVE_TICKS=100
    // at that real speed is up to ~25 blocks of straight-line travel, more than this 20-wide
    // platform's own extent: the vehicle overshot every prepared grid cell (which stop at 18)
    // before the TURN phase (which curves, rather than traveling indefinitely far) ever began.
    // Shortened to just enough real time to get moving, so the TURN phase's own curving starts
    // close to the grid's own center instead of far past its edge.
    private static final int STRAIGHT_DRIVE_TICKS = 20;
    private static final int TURN_TICKS = 150;
    private static final int GRID_STEP = 3; // footprint radius 1 + this margin keeps adjacent cells' own neighbor requirements from overlapping

    /**
     * Per-drive-phase state. {@link #cleanup()} is idempotent (a plain one-shot guard --
     * everything here runs on the single server thread, never truly concurrently) and is called
     * from every exit path this class's own code can throw from, including each individual
     * recursive drive step (review finding, direction comment 55300: the recursive {@code
     * runAfterDelay} chain schedules later callbacks OUTSIDE the enclosing method's own initial
     * try/catch).
     *
     * <p>Previously disclosed as NOT covered (review finding, direction comment 55304, then
     * re-confirmed as still required by a self-review kickback on this exact PR, Jira comment
     * 55318): a genuine GameTest {@code timeoutTicks} expiry is detected by the framework's own
     * tick loop directly calling {@code GameTestInfo#fail(...)}, never through any exception
     * this class's own code could catch, so a timeout mid-phase would previously have left
     * {@link GuaranteedChanceOverrideGuard#release()} un-called and the vehicle unremoved. Now
     * covered: {@link #runDrivenWheelPhase} registers a {@code GameTestListener} directly on
     * {@code helper.testInfo} whose {@code testPassed}/{@code testFailed} both call {@code
     * cleanup()} -- confirmed, via the framework's own decompiled {@code GameTestInfo#tick}, to
     * fire for EVERY real completion of this test (success or failure, by any cause, timeout
     * included), independent of whether this class's own code is ever re-entered again. {@link
     * #spawnVehicle} also now assigns {@code ctx.handle} immediately once the real entity
     * exists in the world (before any part is attached), rather than only once that whole
     * method returns, so a throw partway through attaching parts no longer leaves an
     * unconfigured entity behind that nothing holds a reference to.
     */
    private static final class TestContext {
        VehicleHandle handle;
        /**
         * The structure's own real-world origin (block coordinates at local (0,0,0)), captured
         * once per phase so every log line can report the vehicle's own LOCAL (template-
         * relative) position, not just its real absolute world one. Review-worthy diagnostic
         * gap found while triaging run 44452612622: the absolute coordinates this class already
         * logged (around 1.05e7) cannot be correlated against {@link #buildSourceGrid}'s own
         * local 0-19 cell coordinates at all, so there was no way to tell from that log alone
         * whether the vehicle's real path ever actually crossed a prepared cell.
         */
        double originX;
        double originZ;
        /**
         * Whether THIS phase is the one holding {@link GuaranteedChanceOverrideGuard}'s
         * acquisition (only the enabled phase does). Review finding, Jira comment 55306:
         * {@link #release()} previously only ran from the enabled phase's own success path (the
         * {@code onDone} callback) -- any assertion/callback failure, or an exception from a
         * recursive drive step, removed the vehicle via the old per-step {@code catch} blocks
         * but left the guard's acquisition held forever, leaking it past this test's own real
         * lifetime. Tying the release to {@link #cleanup()} instead -- already called from every
         * one of this class's own exit paths -- makes it unconditional.
         */
        boolean releaseGuardOnCleanup;
        /**
         * The real, bounded, fixture-scoped {@code WheelGroundContactEvent} listener registered
         * for this phase (see {@link #runDrivenWheelPhase}'s own doc), unregistered here.
         * Review finding, Jira comment 55308: aggregate production-consumer counters could not
         * establish that the SAME real event simultaneously had an UP face, positive supported
         * load, and above-threshold slip AT one of this test's own known prepared positions --
         * only that some events somewhere had high slip and some (possibly different) events
         * were at a known position. This listener correlates all of that per real event instead,
         * scoped to this test's own dimension and grid, and is removed on cleanup like any other
         * test-registered listener in this codebase (see {@code ErosionAttemptGameTests}' own
         * {@code denyAtSource}/{@code swapSourceOnBreakEvent} pattern).
         */
        Object correlationListener;
        private boolean cleanedUp = false;

        void cleanup() {
            if (cleanedUp) {
                return;
            }
            cleanedUp = true;
            if (correlationListener != null) {
                NeoForge.EVENT_BUS.unregister(correlationListener);
            }
            if (handle != null) {
                handle.vehicle.remove();
            }
            if (releaseGuardOnCleanup) {
                GuaranteedChanceOverrideGuard.release();
            }
        }
    }

    /**
     * Both the enabled real-driven-mutation evidence AND the disabled real-driven negative
     * control the review explicitly asked for (direction comment 55300: "include the actual
     * driven protected/disabled unchanged control... rather than using successful boot or
     * manually-posted events as substitutes") in ONE {@code @GameTest} method, run strictly
     * sequentially: spawn vehicle A with {@link ErosionModConfig#OVERRIDES} forced to {@link
     * ErosionAttemptGameTests#GUARANTEED_CHANCE_CONFIG} (via the shared {@link
     * GuaranteedChanceOverrideGuard}, released once vehicle A is fully done and removed), THEN
     * -- only after that first phase has entirely finished -- reset the same grid and spawn a
     * SECOND vehicle B with the override genuinely back at its real default
     * ({@code erosionEnabled=false}), asserting NO mutation occurs despite identical real
     * driving.
     *
     * <p>Real review finding (direction comment 55301): an earlier revision ran these as TWO
     * separate {@code @GameTest} methods sharing a batch name with {@code
     * DivWheelContactConsumerGameTests}' own three tests. A real CI failure proved that batch-
     * name separation alone does not reliably serialize a MUCH longer-running test (200+ ticks)
     * against batch-mates: {@code
     * theSharedOverrideGuardLeavesTheConfigExactlyAsFoundAfterBothTestsComplete}'s own 10-tick
     * check observed {@code erosionEnabled} still forced on, left behind by this class's own
     * still-in-flight long drive window. Running both phases as one strictly sequential chain
     * within a SINGLE test method removes any dependency on GameTest's own batch-advance timing
     * entirely -- phase two provably cannot start, let alone finish, before phase one's own
     * {@link GuaranteedChanceOverrideGuard#release()} call has already happened.
     */
    @GameTest(template = "driven_wheel_platform", batch = "divWheelContactConsumerOverrideBatch", timeoutTicks = 900)
    public static void aGenuinelyDrivenVehicleWheelProducesConservedMutationWhenEnabledAndNoneWhenDisabled(
            GameTestHelper helper) {
        ServerLevel level = (ServerLevel) helper.getLevel();
        List<BlockPos> sourceCells = buildSourceGrid(helper);
        GuaranteedChanceOverrideGuard.acquire();
        runDrivenWheelPhase(helper, level, sourceCells, true, true, () -> {
            // Reset the SAME cells back to their clean dirt+neighbor shape (discarding phase
            // one's own real mutation) before phase two reuses them. Phase one's own cleanup()
            // (its enclosing runDrivenWheelPhase call's own finally, running right after this
            // onDone callback returns -- still the SAME tick) releases the guard immediately
            // after this method returns; phase two's own real world-mutating ticks never start
            // until a future tick (its own SETTLE_TICKS delay), by which point that release has
            // already happened, so erosionEnabled is genuinely back to its real default before
            // phase two's own driving ever actually runs.
            for (BlockPos cell : sourceCells) {
                ErosionAttemptGameTests.placeSourceWithOneValidEastNeighbor(helper, cell);
            }
            runDrivenWheelPhase(helper, level, sourceCells, false, false, helper::succeed);
        });
    }

    private static List<BlockPos> buildSourceGrid(GameTestHelper helper) {
        // Review finding, Jira comment 55314: ErosionAttemptGameTests#placeSourceWithOneValidEastNeighbor
        // only blocks the SOURCE cell's own other 3 sides -- its own destination's north/south/
        // east sides are left open (ambient platform), so material arriving there could
        // redistribute further outward once that cell itself becomes a real LayeredBlock
        // neighbor. Sealing the destination's OTHER 3 sides too (its own west side is already
        // the source -- the one real connection) makes this a genuinely isolated 2-cell pocket,
        // so a real transfer can only ever move between these exact two positions, never spill
        // beyond what {@link #isConservedNonzeroTransferPair} can actually see.
        List<BlockPos> sourceCells = new ArrayList<>();
        for (int x = 2; x <= 18; x += GRID_STEP) {
            for (int z = 2; z <= 18; z += GRID_STEP) {
                BlockPos cellPos = new BlockPos(x, 1, z);
                ErosionAttemptGameTests.placeSourceWithOneValidEastNeighbor(helper, cellPos);
                BlockPos destPos = cellPos.east();
                for (Direction direction : Direction.Plane.HORIZONTAL) {
                    if (direction != Direction.WEST) {
                        helper.setBlock(destPos.relative(direction), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                    }
                }
                sourceCells.add(cellPos);
            }
        }
        return sourceCells;
    }

    /**
     * Real conservation check (review finding, Jira comment 55314: "destination gained
     * material alone is not a conservative signature... count source/destination material with
     * plain source=16, matching layered material=LAYERS, air=0, reject unexpected block/
     * material"). Counts each side via {@link #materialAmount}, which rejects (returns -1) a
     * side whose own real block does not match the expected shape for this exact pair --
     * confirmed necessary by a real false-reading in run 44457380550 (head fb33010): cell
     * (8,11) logged a changed "destination" that was actually an UNRELATED block (a different
     * mod's own incidental material at that position, not a real transfer from this pair's own
     * source) -- its sum (16 + 5 = 21) correctly fails the conservation check below regardless,
     * but the explicit same-block rejection here makes that rejection a stated, visible step of
     * the check, not an accidental side effect of the arithmetic alone.
     */
    private static boolean isConservedNonzeroTransferPair(GameTestHelper helper, BlockPos sourceCell) {
        BlockState sourceState = helper.getBlockState(sourceCell);
        BlockState destState = helper.getBlockState(sourceCell.east());
        int sourceAmount = materialAmount(sourceState, destState);
        int destAmount = materialAmount(destState, sourceState);
        if (sourceAmount < 0 || destAmount < 0) {
            return false;
        }
        return sourceAmount + destAmount == io.github.brooswitminecraft.layeredslopes.LayerMath.MAX_LAYERS
                && destAmount >= io.github.brooswitminecraft.layeredslopes.LayerMath.MIN_LAYERS;
    }

    /**
     * @return {@code 16} for the real, plain, never-yet-touched source material (vanilla
     *     {@code DIRT}), {@code 0} for real air, this side's own real {@code LAYERS} value when
     *     it is a real {@code LayeredBlock} that matches the PAIRED side's own real block
     *     (once both sides are layered -- a mismatch means this isn't a real transfer between
     *     this exact pair, see this method's own caller's doc), or {@code -1} (reject) for
     *     anything else unexpected.
     */
    private static int materialAmount(BlockState state, BlockState pairedSideState) {
        if (state.isAir()) {
            return 0;
        }
        if (state.is(net.minecraft.world.level.block.Blocks.DIRT)) {
            return 16;
        }
        if (state.hasProperty(LayeredBlock.LAYERS)) {
            if (pairedSideState.hasProperty(LayeredBlock.LAYERS) && pairedSideState.getBlock() != state.getBlock()) {
                return -1;
            }
            return state.getValue(LayeredBlock.LAYERS);
        }
        return -1;
    }

    /**
     * One full real phase: spawn a real vehicle centered on the grid (not an edge/corner, so
     * genuine driving reaches several cells regardless of which way the vehicle's own "forward"
     * axis actually points), let it settle onto the real floor, drive it forward then add a
     * steady rudder pulse while already cruising (see {@link #driveStraightFor}/{@link
     * #driveWithSlipFor}'s own docs for why that exact order), then check the real conserved
     * mutation shape (source {@code LayeredBlock.LAYERS == 15}, its own east neighbor
     * {@code == 1} -- the exact pair {@link DivWheelContactConsumerGameTests}' own real-wiring
     * test already established as genuine {@code Outcome.APPLIED} evidence, not merely "any
     * layered block exists") against {@code expectApplied}, removes the vehicle, then runs
     * {@code onDone}. {@code releaseGuardOnCleanup} is {@code true} only for the enabled phase,
     * which is the one that actually holds {@link GuaranteedChanceOverrideGuard}'s acquisition
     * (review finding, Jira comment 55306: tying the release to {@link TestContext#cleanup()}
     * -- already called from every one of this method's own exit paths, success or failure --
     * makes it unconditional, rather than only running from a success-only callback).
     */
    private static void runDrivenWheelPhase(GameTestHelper helper, ServerLevel level, List<BlockPos> sourceCells,
            boolean expectApplied, boolean releaseGuardOnCleanup, Runnable onDone) {
        TestContext ctx = new TestContext();
        ctx.releaseGuardOnCleanup = releaseGuardOnCleanup;
        // Real review finding (PR #19 comment, self-review kickback, Jira comment 55318):
        // a genuine GameTest timeoutTicks expiry (or any other framework-driven failure, e.g.
        // a later batch-mate's own assertion) is detected by the framework's OWN tick loop
        // directly calling GameTestInfo#fail(...), never through any exception this class's
        // own code could catch -- the try/catch and per-step guards below only cover exceptions
        // thrown from THIS class's own code, not a timeout that simply stops re-entering it.
        // GameTestInfo#addListener's own testPassed/testFailed callbacks ARE invoked by the
        // framework for every real completion of this test, success OR failure, REGARDLESS of
        // cause (confirmed via the framework's own decompiled GameTestInfo#tick, which checks
        // isDone()/hasFailed() every tick and dispatches to every registered listener) -- unlike
        // this class's own try/catch, this one doesn't depend on being re-entered at all.
        // ctx.cleanup() is idempotent, so registering this is additive, never a double-release
        // hazard, on top of every existing explicit cleanup() call below.
        helper.testInfo.addListener(new GameTestListener() {
            @Override
            public void testStructureLoaded(GameTestInfo info) {
            }

            @Override
            public void testPassed(GameTestInfo info, GameTestRunner runner) {
                ctx.cleanup();
            }

            @Override
            public void testFailed(GameTestInfo info, GameTestRunner runner) {
                ctx.cleanup();
            }

            @Override
            public void testAddedForRerun(GameTestInfo before, GameTestInfo after, GameTestRunner runner) {
            }
        });
        try {
            // Real CI failure on this exact head (run 44451489283): chassisReceipt/classify
            // confirmed real Body activation, and the vehicle genuinely built real speed (~5
            // m/s) during the straight phase, but anyAppliedPair stayed false. The real boot log
            // showed WHY once the recorded position deltas were examined: as soon as rudder is
            // applied, this vehicle decelerates to near-zero net motion within only a handful of
            // ticks (matching GroundSupportFixtureProbe's own TURN phase, which is a bounded
            // "low-speed steer" that plateaus at a near-stationary skid, not a translating turn
            // -- see that probe's own BRAKE/TURN comments). So almost all of this phase's real
            // distance happens during the straight run, and whatever slip genuinely occurs
            // happens at essentially ONE (x,z) spot, not swept across the grid. Spawning at
            // x=10 -- one block off BOTH of the nearest prepared grid columns (x=8 and x=11,
            // see buildSourceGrid) -- put that one spot outside this chassis's own ~0.85-wide
            // footprint of either column, so genuine slip never happened over a prepared cell.
            // Spawning directly on grid column x=11 instead means every tick of real straight-
            // line travel (and the real skid at the end of it) stays directly over that column's
            // own z=2,5,8,11,14,17 cells.
            BlockPos spawnAbsolute = helper.absolutePos(new BlockPos(11, 1, 10));
            BlockPos originAbsolute = helper.absolutePos(BlockPos.ZERO);
            ctx.originX = originAbsolute.getX();
            ctx.originZ = originAbsolute.getZ();
            spawnVehicle(level, spawnAbsolute, ctx);
            // Diagnostic (MINECRAFT-510, review finding Jira comment 55308): real localPos
            // telemetry across 3 real CI runs showed this vehicle only ever travels/circles
            // within ~1-2 blocks of its own spawn point, never sweeping across the grid the way
            // GroundSupportFixtureProbe's own real DRIVE_CHECK displacement does. Aggregate
            // production-consumer counters (a prior, now-reverted attempt at this diagnosis)
            // could not establish that the SAME real event simultaneously had an UP face,
            // positive supported load, and above-threshold slip AT one of this test's own known
            // prepared positions. This bounded, fixture-scoped listener -- registered on the real
            // NeoForge.EVENT_BUS the same way ErosionAttemptGameTests' own denyAtSource/
            // swapSourceOnBreakEvent listeners are, and unregistered from cleanup() below --
            // correlates all of that per real event, scoped to this test's own dimension and
            // grid, answering that directly instead.
            // Real review finding, Jira comment 55310: the first version of this listener
            // subtracted origin X/Z to convert the event's own ABSOLUTE position down to local,
            // but left Y untouched -- event.blockPos().getY() is absolute, while sourceCells'
            // own Y is template-local 1, so that comparison could only ever match if this
            // structure's own real world origin happened to have Y=0. Comparing against each
            // cell's own real ABSOLUTE position (via helper.absolutePos, the same conversion
            // every other real position in this class already goes through) instead avoids
            // manual per-axis origin math entirely -- and is robust to structure rotation too,
            // not just this one axis's own off-by-Y bug.
            Set<BlockPos> absoluteSourceCells = sourceCells.stream()
                    .map(helper::absolutePos)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            Object correlationListener = new Object() {
                @net.neoforged.bus.api.SubscribeEvent
                public void onContact(WheelGroundContactEvent event) {
                    if (!event.level().dimension().equals(level.dimension())) {
                        return;
                    }
                    if (!absoluteSourceCells.contains(event.blockPos())) {
                        return;
                    }
                    LayeredSlopesDynamicImmersiveVehiclesMod.LOGGER.info(
                            "[DIV drivenWheelAcceptance] correlated contact on prepared cell absolute="
                                    + event.blockPos() + " face=" + event.face() + " slip="
                                    + event.lateralSlipSpeedMetersPerSecond() + " load=" + event.supportedLoadKg()
                                    + " tick=" + event.serverTick());
                }
            };
            NeoForge.EVENT_BUS.register(correlationListener);
            ctx.correlationListener = correlationListener;

            helper.runAfterDelay(SETTLE_TICKS, () -> driveStraightFor(helper, ctx, STRAIGHT_DRIVE_TICKS, () -> driveWithSlipFor(helper, ctx, TURN_TICKS, () -> {
                try {
                    ctx.handle.vehicle.throttleVar.setTo(0.0, false);
                    ctx.handle.vehicle.rudderInputVar.setTo(0.0, false);
                    // Real review findings, Jira comments 55312/55313/55314: dozens of real,
                    // qualifying contacts land on a prepared cell (confirmed by the correlated-
                    // contact listener above), but a real multi-contact drive need not finish
                    // at the single manually-posted-event's own clean one-hop 15/1 snapshot --
                    // real runs showed 11/5 and 8/8, both real conserved pairs (sum=16) just not
                    // that exact one. isConservedNonzeroTransferPair checks the real
                    // conservation law directly (source+destination material sums to the real
                    // LayerMath.MAX_LAYERS, with a nonzero real transfer, rejecting either side
                    // if it is not the expected recognized material for this exact pair) instead
                    // of a single hardcoded snapshot.
                    boolean anyAppliedPair = sourceCells.stream()
                            .anyMatch(cell -> isConservedNonzeroTransferPair(helper, cell));
                    // Diagnostic (MINECRAFT-510): logging every cell that actually changed at all, with its real
                    // final LAYERS value, to see the real end state directly rather than
                    // guessing whether a stricter or looser check is the real gap.
                    for (BlockPos cell : sourceCells) {
                        var sourceState = helper.getBlockState(cell);
                        var destState = helper.getBlockState(cell.east());
                        // Review-worthy gap in the prior diagnostic: logging only
                        // hasProperty(LAYERS)-present cells silently conflated "still plain
                        // dirt, never touched" with "fully depleted back to air" -- both show
                        // "n/a" for LAYERS, but Smoothing.smooth()'s own real source-depletion
                        // rule (SmoothRules.plan: sourceAfter becomes null, written back as
                        // literal AIR, once layers would drop to/below MIN_LAYERS) means those
                        // are NOT the same real state. Logging each side's own real block id
                        // too, and widening the trigger to ANY deviation from the expected
                        // initial shape (source dirt, destination air), not just LAYERS
                        // presence, so air-vs-dirt is never silently conflated again.
                        boolean sourceIsStillPlainDirt = sourceState.is(net.minecraft.world.level.block.Blocks.DIRT);
                        boolean destIsStillAir = destState.isAir();
                        if (!sourceIsStillPlainDirt || !destIsStillAir) {
                            LayeredSlopesDynamicImmersiveVehiclesMod.LOGGER.info(
                                    "[DIV drivenWheelAcceptance] changed cell=" + cell
                                            + " sourceBlock=" + sourceState.getBlock()
                                            + " sourceLayers=" + (sourceState.hasProperty(LayeredBlock.LAYERS)
                                                    ? sourceState.getValue(LayeredBlock.LAYERS) : "n/a")
                                            + " destBlock=" + destState.getBlock()
                                            + " destLayers=" + (destState.hasProperty(LayeredBlock.LAYERS)
                                                    ? destState.getValue(LayeredBlock.LAYERS) : "n/a"));
                        }
                    }
                    LayeredSlopesDynamicImmersiveVehiclesMod.LOGGER.info(
                            "[DIV drivenWheelAcceptance] expectApplied=" + expectApplied
                                    + " final vehicle localPos=(" + (ctx.handle.vehicle.position.x - ctx.originX)
                                    + "," + (ctx.handle.vehicle.position.z - ctx.originZ) + ")"
                                    + " position=" + ctx.handle.vehicle.position
                                    + " motion=" + ctx.handle.vehicle.motion
                                    + " anyAppliedPair=" + anyAppliedPair);
                    if (expectApplied) {
                        helper.assertTrue(anyAppliedPair, "expected at least one of the " + sourceCells.size()
                                + " prepared dirt source cells to show a real conserved erosion mutation "
                                + "(source+destination material summing to a real, nonzero, conserved transfer) "
                                + "after genuinely driving a real vehicle over this platform, but none did");
                    } else {
                        boolean anyChangedAtAll = sourceCells.stream().anyMatch(cell ->
                                helper.getBlockState(cell).hasProperty(LayeredBlock.LAYERS));
                        helper.assertTrue(!anyChangedAtAll, "expected every prepared dirt source cell to remain "
                                + "plain dirt after genuinely driving a real vehicle over this platform with "
                                + "erosion disabled, but at least one changed");
                    }
                    onDone.run();
                } finally {
                    ctx.cleanup();
                }
            })));
        } catch (RuntimeException | Error e) {
            ctx.cleanup();
            throw e;
        }
    }

    /**
     * First phase, matching {@code GroundSupportFixtureProbe}'s own {@code drive()}: straight
     * (no rudder) closed-loop bang-bang throttle toward {@link #LOW_SPEED_TARGET}, building real,
     * clean forward speed before any steering input is ever introduced. Same per-tick
     * engine/throttle re-assertion and per-step exception guard as {@link #driveWithSlipFor}
     * below; see that method's own doc for why both are needed.
     */
    private static void driveStraightFor(GameTestHelper helper, TestContext ctx, int remainingTicks, Runnable onDone) {
        try {
            if (remainingTicks <= 0) {
                onDone.run();
                return;
            }
            forceEngineRunning(ctx.handle.engine);
            double speed = forwardSpeed(ctx.handle.vehicle);
            ctx.handle.vehicle.throttleVar.setTo(speed < LOW_SPEED_TARGET ? 1.0 : 0.0, false);
            // STRAIGHT_DRIVE_TICKS is shorter than 30, so a plain "% 30 == 0" cadence (copied
            // from driveWithSlipFor's own, much longer window) would never fire here -- a real
            // gap found while diagnosing run 44451489283. Logging every tick's own start/end
            // instead gives real visibility into this short phase without flooding the log.
            if (remainingTicks == STRAIGHT_DRIVE_TICKS || remainingTicks == 1) {
                LayeredSlopesDynamicImmersiveVehiclesMod.LOGGER.info(
                        "[DIV drivenWheelAcceptance] phase=straight ticksRemaining=" + remainingTicks
                                + " localPos=(" + (ctx.handle.vehicle.position.x - ctx.originX)
                                + "," + (ctx.handle.vehicle.position.z - ctx.originZ) + ")"
                                + " position=" + ctx.handle.vehicle.position
                                + " motion=" + ctx.handle.vehicle.motion + " forwardSpeed=" + speed);
            }
            helper.runAfterDelay(1, () -> driveStraightFor(helper, ctx, remainingTicks - 1, onDone));
        } catch (RuntimeException | Error e) {
            ctx.cleanup();
            throw e;
        }
    }

    /**
     * Second phase, matching {@code GroundSupportFixtureProbe}'s own {@code turn()}: a real,
     * deliberate review-worthy finding from a prior CI run (direction comment 55301) is why this
     * is a SEPARATE phase from {@link #driveStraightFor} above, not combined from the start --
     * applying rudder together with throttle from a near-standstill produced a vehicle that
     * reached real speed briefly, then stalled to near-zero motion for the rest of the window.
     * Only once already cruising (after {@link #driveStraightFor}) does this add a steady,
     * nonzero rudder pulse, continuing the same closed-loop throttle, to generate real lateral
     * slip while the vehicle is already moving cleanly -- the exact same ordering that probe's
     * own real, CI-proven TURN phase uses. Recreates that probe's own per-tick throttle/engine/
     * rudder re-assertion (its own doc: {@code PartEngine}'s per-tick update logic can plausibly
     * reset {@code running}/{@code currentGearVar} without the caller ever knowing) as a
     * GameTest-native recursive {@code runAfterDelay(1, ...)} chain, since GameTest's own tick
     * loop -- not a loop this method writes itself -- is what re-enters this test. Each recursive
     * step is independently guarded (review finding, direction comment 55300): an exception here
     * triggers {@link TestContext#cleanup()} before propagating, not only the final step's own
     * {@code finally}, since GameTest re-enters this chain on its own schedule, not through the
     * one try/catch the scenario method itself wraps around the first {@code runAfterDelay} call.
     */
    private static void driveWithSlipFor(GameTestHelper helper, TestContext ctx, int remainingTicks, Runnable onDone) {
        try {
            if (remainingTicks <= 0) {
                onDone.run();
                return;
            }
            forceEngineRunning(ctx.handle.engine);
            double speed = forwardSpeed(ctx.handle.vehicle);
            ctx.handle.vehicle.throttleVar.setTo(speed < LOW_SPEED_TARGET ? 1.0 : 0.0, false);
            ctx.handle.vehicle.rudderInputVar.setTo(RUDDER_DEGREES, false);
            // 10-tick cadence (not 30): a real CI run (44451489283) showed this phase's own
            // speed collapsing from a real ~5 m/s to near-zero within the first ~30 ticks of
            // rudder input -- a 30-tick cadence missed exactly when that happened.
            if (remainingTicks % 10 == 0) {
                LayeredSlopesDynamicImmersiveVehiclesMod.LOGGER.info(
                        "[DIV drivenWheelAcceptance] phase=turn ticksRemaining=" + remainingTicks
                                + " localPos=(" + (ctx.handle.vehicle.position.x - ctx.originX)
                                + "," + (ctx.handle.vehicle.position.z - ctx.originZ) + ")"
                                + " position=" + ctx.handle.vehicle.position
                                + " motion=" + ctx.handle.vehicle.motion + " forwardSpeed=" + speed);
            }
            helper.runAfterDelay(1, () -> driveWithSlipFor(helper, ctx, remainingTicks - 1, onDone));
        } catch (RuntimeException | Error e) {
            ctx.cleanup();
            throw e;
        }
    }

    private static double forwardSpeed(EntityVehicleF_Physics vehicle) {
        return vehicle.velocity * vehicle.speedFactor * 20;
    }

    /** Review-equivalent note from {@code GroundSupportFixtureProbe}: re-assert every tick, never trusted from a single construction-time write. */
    private static void forceEngineRunning(PartEngine engine) {
        engine.running = true;
        engine.currentGearVar.setTo(1.0, false);
    }

    private static final class VehicleHandle {
        final EntityVehicleF_Physics vehicle;
        final PartEngine engine;

        VehicleHandle(EntityVehicleF_Physics vehicle, PartEngine engine) {
            this.vehicle = vehicle;
            this.engine = engine;
        }
    }

    /**
     * Adapted directly from {@code GroundSupportFixtureProbe#spawn}/{@code
     * #buildVehicleDefinition}/{@code #addWheel}/{@code #addEngine} -- same pack-ID convention,
     * same chassis geometry, same {@code JSONVehicle}/{@code JSONPart} scaffolding, including
     * every nullable field that probe's own review history found a real NPE on (definitions,
     * rendering, general, frictionModifiers). Spawns a little above the real surface so the
     * vehicle genuinely falls and settles onto real ground, confirming real wheel contact,
     * rather than starting already perfectly resting.
     */
    private static VehicleHandle spawnVehicle(ServerLevel level, BlockPos surfacePos, TestContext ctx) {
        var packSpeedFactors = ConfigSystem.settings.general.packSpeedFactors.value;
        var packVehicleScales = ConfigSystem.settings.general.packVehicleScales.value;
        packSpeedFactors.putIfAbsent(PACK_ID, 1.0);
        packVehicleScales.putIfAbsent(PACK_ID, 1.0);

        var wheelSlotFL = wheelSlot(WHEEL_FL, true);
        var wheelSlotFR = wheelSlot(WHEEL_FR, true);
        var wheelSlotRL = wheelSlot(WHEEL_RL, false);
        var wheelSlotRR = wheelSlot(WHEEL_RR, false);
        var engineSlot = engineSlot();

        JSONVehicle definition = buildVehicleDefinition("acceptance");
        definition.parts = List.of(wheelSlotFL, wheelSlotFR, wheelSlotRL, wheelSlotRR, engineSlot);
        AWrapperWorld world = mcinterface1211.WrapperWorld.getWrapperFor(level);
        var item = new ItemVehicle(definition, definition.definitions.get(0), PACK_ID);
        var vehicle = new EntityVehicleF_Physics(world, null, item, null);
        vehicle.position.set(surfacePos.getX() + 0.5, surfacePos.getY() + 1.0, surfacePos.getZ() + 0.5);
        vehicle.orientation.set(new RotationMatrix());
        world.spawnEntity(vehicle);
        // Real review finding (self-review kickback, Jira comment 55318): capture the handle
        // immediately once the real entity exists in the world, before any part is attached --
        // if addWheel/addEngine below throws, ctx.cleanup() (via the unconditional
        // testPassed/testFailed listener registered in runDrivenWheelPhase, or any of this
        // class's own catch blocks) can still remove this real spawned entity rather than
        // leaking it. cleanup() only ever needs handle.vehicle, never handle.engine, so a null
        // engine here is safe.
        ctx.handle = new VehicleHandle(vehicle, null);

        addWheel(vehicle, wheelSlotFL);
        addWheel(vehicle, wheelSlotFR);
        addWheel(vehicle, wheelSlotRL);
        addWheel(vehicle, wheelSlotRR);
        PartEngine engine = addEngine(vehicle, engineSlot);
        ctx.handle = new VehicleHandle(vehicle, engine);
        return ctx.handle;
    }

    private static JSONVehicle buildVehicleDefinition(String systemName) {
        var v = new JSONVehicle();
        v.packID = PACK_ID;
        v.systemName = systemName;
        v.motorized = new JSONVehicle.VehicleMotorized();
        v.motorized.litVariable = "";
        v.motorized.panel = "";
        v.definitions = List.of(firstSubDefinition(v.systemName));
        v.rendering = new JSONRendering();
        v.rendering.modelType = JSONRendering.ModelType.NONE;
        v.general = new AJSONItem.General();
        v.general.health = 100;

        var mainBox = new JSONCollisionBox();
        mainBox.pos = CHASSIS_CENTER.copy();
        mainBox.width = (float) (HALF_EXTENT_A * 2);
        mainBox.height = (float) (HALF_HEIGHT_B * 2);
        var zMarkerFront = new JSONCollisionBox();
        zMarkerFront.pos = new Point3D(CHASSIS_CENTER.x, CHASSIS_CENTER.y, CHASSIS_CENTER.z + HALF_EXTENT_C);
        zMarkerFront.width = 0;
        zMarkerFront.height = 0;
        var zMarkerRear = new JSONCollisionBox();
        zMarkerRear.pos = new Point3D(CHASSIS_CENTER.x, CHASSIS_CENTER.y, CHASSIS_CENTER.z - HALF_EXTENT_C);
        zMarkerRear.width = 0;
        zMarkerRear.height = 0;
        var group = new JSONCollisionGroup();
        group.collisionTypes = Set.of(JSONCollisionGroup.CollisionType.VEHICLE);
        group.collisions = List.of(mainBox, zMarkerFront, zMarkerRear);
        v.collisionGroups = List.of(group);
        return v;
    }

    private static JSONSubDefinition firstSubDefinition(String name) {
        var sub = new JSONSubDefinition();
        sub.subName = "";
        sub.name = name;
        return sub;
    }

    private static JSONPartDefinition wheelSlot(Point3D localPos, boolean steers) {
        var slot = new JSONPartDefinition();
        slot.pos = localPos;
        slot.turnsWithSteer = steers;
        slot.types = List.of("ground_wheel");
        return slot;
    }

    private static JSONPartDefinition engineSlot() {
        var slot = new JSONPartDefinition();
        slot.pos = new Point3D(0, 0, 0);
        slot.types = List.of("engine");
        return slot;
    }

    private static void addWheel(EntityVehicleF_Physics vehicle, JSONPartDefinition slot) {
        var ground = new JSONPart.JSONPartGroundDevice();
        ground.isWheel = true;
        ground.width = 0.4f;
        ground.height = 0.4f;
        ground.frictionModifiers = new LinkedHashMap<>();
        var def = new JSONPart();
        def.packID = PACK_ID;
        def.systemName = "wheel";
        def.generic = new JSONPart.JSONPartGeneric();
        def.ground = ground;
        def.definitions = List.of(firstSubDefinition(def.systemName));
        def.rendering = new JSONRendering();
        def.rendering.modelType = JSONRendering.ModelType.NONE;
        def.general = new AJSONItem.General();
        def.general.health = 100;
        var item = new ItemPartGroundDevice(def, def.definitions.get(0), PACK_ID);
        APart part = new PartGroundDevice(vehicle, null, slot, item, null);
        vehicle.addPart(part, false);
    }

    private static PartEngine addEngine(EntityVehicleF_Physics vehicle, JSONPartDefinition slot) {
        var engineDef = new JSONPart.JSONPartEngine();
        engineDef.type = JSONPart.EngineType.NORMAL;
        engineDef.gearRatios = List.of(-2.0f, 0.0f, 2.0f);
        engineDef.maxRPM = 6000;
        engineDef.maxSafeRPM = 5500;
        engineDef.idleRPM = 800;
        engineDef.startRPM = 400;
        engineDef.stallRPM = 200;
        var def = new JSONPart();
        def.packID = PACK_ID;
        def.systemName = "engine";
        def.generic = new JSONPart.JSONPartGeneric();
        def.engine = engineDef;
        def.definitions = List.of(firstSubDefinition(def.systemName));
        def.rendering = new JSONRendering();
        def.rendering.modelType = JSONRendering.ModelType.NONE;
        def.general = new AJSONItem.General();
        def.general.health = 100;
        var item = new ItemPartEngine(def, def.definitions.get(0), PACK_ID);
        var engine = new PartEngine(vehicle, null, slot, item, null);
        vehicle.addPart(engine, false);
        forceEngineRunning(engine);
        return engine;
    }
}
