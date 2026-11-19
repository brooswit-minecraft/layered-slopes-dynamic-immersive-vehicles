package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

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
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
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
 * ErosionAttemptGameTests#GUARANTEED_CHANCE_CONFIG} for the duration (same real, process-wide
 * singleton {@link DivWheelContactConsumer} itself reads), restored on every path exactly like
 * {@link DivWheelContactConsumerGameTests}. Runs in its own dedicated {@code batch} for the same
 * reason that class's own doc explains: every {@code @GameTest} in this codebase defaults to the
 * same concurrently-ticking {@code "defaultBatch"}, and mutating this process-wide singleton
 * there risks any other default-batch test that happens to read it.
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
     * Shared per-run state (review finding, direction comment 55300: {@code driveWithSlipFor}'s
     * recursive {@code runAfterDelay} chain schedules later callbacks OUTSIDE the method's own
     * initial try/catch -- an exception thrown from a later tick, or a timeout, could otherwise
     * leave the real spawned vehicle and the real process-wide {@link ErosionModConfig#OVERRIDES}
     * singleton behind). {@link #cleanup()} is idempotent (a plain one-shot guard -- everything
     * here runs on the single server thread, never truly concurrently) and is called from every
     * possible exit path below, including each individual recursive drive step.
     */
    private static final class TestContext {
        final ErosionConfig priorConfig;
        VehicleHandle handle;
        private boolean cleanedUp = false;

        TestContext(ErosionConfig priorConfig) {
            this.priorConfig = priorConfig;
        }

        void cleanup() {
            if (cleanedUp) {
                return;
            }
            cleanedUp = true;
            if (handle != null) {
                handle.vehicle.remove();
            }
            ErosionModConfig.OVERRIDES.reload(priorConfig);
        }
    }

    /**
     * The full real path, end to end: carve a grid of independently-valid erosion-source cells
     * across this template's real stone floor ({@link
     * ErosionAttemptGameTests#placeSourceWithOneValidEastNeighbor}, spaced {@link #GRID_STEP}
     * blocks apart so no two cells' own self-contained neighbor requirements overlap), spawn a
     * real vehicle centered on that grid, let it settle onto the real floor, then drive it
     * forward with a steady rudder pulse for long enough to pass over several cells while
     * genuinely slipping sideways -- real wheel contacts at real positions, through the real
     * unmodified production chain. Succeeds only if at least one prepared cell shows the real,
     * conserved {@code ErosionAttempt} mutation shape (source {@code LayeredBlock.LAYERS == 15},
     * its own east neighbor {@code == 1} -- the exact pair {@link DivWheelContactConsumerGameTests}'
     * own real-wiring test already established as genuine {@code Outcome.APPLIED} evidence, not
     * merely "any layered block exists").
     */
    @GameTest(template = "driven_wheel_platform", batch = "divDrivenWheelAcceptanceBatch", timeoutTicks = 400)
    public static void aGenuinelyDrivenVehicleWheelProducesARealErosionMutation(GameTestHelper helper) {
        runDrivenWheelScenario(helper, ErosionAttemptGameTests.GUARANTEED_CHANCE_CONFIG, true);
    }

    /**
     * The real, driven negative control the review explicitly asked for (direction comment
     * 55300: "include the actual driven protected/disabled unchanged control... rather than
     * using successful boot or manually-posted events as substitutes"): the IDENTICAL real
     * vehicle, grid, and drive maneuver as the enabled test above, but with {@link
     * ErosionModConfig#OVERRIDES} left at {@link ErosionConfig#DEFAULT} (real
     * {@code erosionEnabled=false}) -- proving genuine driving alone, without the config gate
     * also being on, produces no mutation at all. This is the same real-world/config contrast
     * {@code ErosionModConfigGameTests} already establishes for the non-DIV path; this is that
     * same contrast for the real, driven-vehicle path.
     */
    @GameTest(template = "driven_wheel_platform", batch = "divDrivenWheelAcceptanceBatch", timeoutTicks = 400)
    public static void aGenuinelyDrivenVehicleWheelAppliesNoMutationWhenErosionDisabled(GameTestHelper helper) {
        runDrivenWheelScenario(helper, ErosionConfig.DEFAULT, false);
    }

    private static void runDrivenWheelScenario(GameTestHelper helper, ErosionConfig scenarioConfig, boolean expectApplied) {
        TestContext ctx = new TestContext(ErosionModConfig.OVERRIDES.current());
        try {
            ErosionModConfig.OVERRIDES.reload(scenarioConfig);
            ServerLevel level = (ServerLevel) helper.getLevel();

            List<BlockPos> sourceCells = new ArrayList<>();
            for (int x = 2; x <= 18; x += GRID_STEP) {
                for (int z = 2; z <= 18; z += GRID_STEP) {
                    BlockPos cellPos = new BlockPos(x, 1, z);
                    ErosionAttemptGameTests.placeSourceWithOneValidEastNeighbor(helper, cellPos);
                    sourceCells.add(cellPos);
                }
            }

            // Centered on the grid (not an edge/corner) so genuine driving reaches several cells
            // regardless of which way the vehicle's own "forward" axis actually points.
            BlockPos spawnRelative = new BlockPos(10, 1, 10);
            BlockPos spawnAbsolute = helper.absolutePos(spawnRelative);
            ctx.handle = spawnVehicle(level, spawnAbsolute);

            helper.runAfterDelay(SETTLE_TICKS, () -> driveStraightFor(helper, ctx, STRAIGHT_DRIVE_TICKS, () -> driveWithSlipFor(helper, ctx, TURN_TICKS, () -> {
                try {
                    ctx.handle.vehicle.throttleVar.setTo(0.0, false);
                    ctx.handle.vehicle.rudderInputVar.setTo(0.0, false);
                    boolean anyAppliedPair = sourceCells.stream().anyMatch(cell -> {
                        var sourceState = helper.getBlockState(cell);
                        var destState = helper.getBlockState(cell.east());
                        return sourceState.hasProperty(LayeredBlock.LAYERS) && sourceState.getValue(LayeredBlock.LAYERS) == 15
                                && destState.hasProperty(LayeredBlock.LAYERS) && destState.getValue(LayeredBlock.LAYERS) == 1;
                    });
                    LayeredSlopesDynamicImmersiveVehiclesMod.LOGGER.info(
                            "[DIV drivenWheelAcceptance] expectApplied=" + expectApplied
                                    + " final vehicle position=" + ctx.handle.vehicle.position
                                    + " motion=" + ctx.handle.vehicle.motion
                                    + " anyAppliedPair=" + anyAppliedPair);
                    if (expectApplied) {
                        helper.assertTrue(anyAppliedPair, "expected at least one of the " + sourceCells.size()
                                + " prepared dirt source cells to show the real conserved erosion mutation "
                                + "(source LAYERS=15, east neighbor LAYERS=1) after genuinely driving a real "
                                + "vehicle over this platform, but none did");
                    } else {
                        boolean anyChangedAtAll = sourceCells.stream().anyMatch(cell ->
                                helper.getBlockState(cell).hasProperty(LayeredBlock.LAYERS));
                        helper.assertTrue(!anyChangedAtAll, "expected every prepared dirt source cell to remain "
                                + "plain dirt after genuinely driving a real vehicle over this platform with "
                                + "erosion disabled, but at least one changed");
                    }
                    helper.succeed();
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
            if (remainingTicks % 30 == 0) {
                LayeredSlopesDynamicImmersiveVehiclesMod.LOGGER.info(
                        "[DIV drivenWheelAcceptance] phase=straight ticksRemaining=" + remainingTicks
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
            if (remainingTicks % 30 == 0) {
                LayeredSlopesDynamicImmersiveVehiclesMod.LOGGER.info(
                        "[DIV drivenWheelAcceptance] phase=turn ticksRemaining=" + remainingTicks
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
    private static VehicleHandle spawnVehicle(ServerLevel level, BlockPos surfacePos) {
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

        addWheel(vehicle, wheelSlotFL);
        addWheel(vehicle, wheelSlotFR);
        addWheel(vehicle, wheelSlotRL);
        addWheel(vehicle, wheelSlotRR);
        PartEngine engine = addEngine(vehicle, engineSlot);
        return new VehicleHandle(vehicle, engine);
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
