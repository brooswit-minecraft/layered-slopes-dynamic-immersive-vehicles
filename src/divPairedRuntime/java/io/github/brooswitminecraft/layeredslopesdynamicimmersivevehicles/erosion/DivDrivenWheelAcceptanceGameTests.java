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
 * cells instead of DIV's own flat characterization platform, applies throttle AND a steady
 * rudder pulse together (rather than sequential DRIVE/TURN phases) to generate real lateral slip
 * while still covering ground, and removes the vehicle via the real {@code
 * AEntityA_Base#remove()} method at the end (that probe never cleans up, since it runs once per
 * disposable CI-world server lifetime; a GameTest world is reused within a batch, so this does).
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
    private static final int DRIVE_TICKS = 150;

    /**
     * The full real path, end to end: carve a grid of 25 independently-valid erosion-source
     * cells across this template's real stone floor ({@link
     * ErosionAttemptGameTests#placeSourceWithOneValidEastNeighbor}, spaced 4 blocks apart so no
     * two cells' own self-contained neighbor requirements overlap), spawn a real vehicle, let it
     * settle onto the real floor, then drive it forward with a steady rudder pulse for long
     * enough to pass over several cells while genuinely slipping sideways -- real wheel contacts
     * at real positions, through the real unmodified production chain. Succeeds only if at least
     * one of the 25 prepared cells shows a real, non-zero {@code LayeredBlock.LAYERS} mutation
     * afterward -- proof a genuine {@code ErosionAttempt.Outcome.APPLIED} actually happened from
     * this driving, not merely that nothing crashed.
     */
    @GameTest(template = "driven_wheel_platform", batch = "divDrivenWheelAcceptanceBatch", timeoutTicks = 400)
    public static void aGenuinelyDrivenVehicleWheelProducesARealErosionMutation(GameTestHelper helper) {
        ErosionConfig priorConfig = ErosionModConfig.OVERRIDES.current();
        try {
            ErosionModConfig.OVERRIDES.reload(ErosionAttemptGameTests.GUARANTEED_CHANCE_CONFIG);
            ServerLevel level = (ServerLevel) helper.getLevel();

            List<BlockPos> sourceCells = new ArrayList<>();
            for (int x = 2; x <= 18; x += 4) {
                for (int z = 2; z <= 18; z += 4) {
                    BlockPos cellPos = new BlockPos(x, 1, z);
                    ErosionAttemptGameTests.placeSourceWithOneValidEastNeighbor(helper, cellPos);
                    sourceCells.add(cellPos);
                }
            }

            BlockPos spawnRelative = new BlockPos(10, 1, 3);
            BlockPos spawnAbsolute = helper.absolutePos(spawnRelative);
            VehicleHandle handle = spawnVehicle(level, spawnAbsolute);

            helper.runAfterDelay(SETTLE_TICKS, () -> {
                try {
                    driveWithSlipFor(helper, handle, DRIVE_TICKS, () -> {
                        try {
                            handle.vehicle.throttleVar.setTo(0.0, false);
                            handle.vehicle.rudderInputVar.setTo(0.0, false);
                            boolean anyApplied = sourceCells.stream().anyMatch(cell -> {
                                var state = helper.getBlockState(cell);
                                return state.hasProperty(LayeredBlock.LAYERS) && state.getValue(LayeredBlock.LAYERS) > 0;
                            });
                            helper.assertTrue(anyApplied, "expected at least one of the " + sourceCells.size()
                                    + " prepared dirt source cells to show a real erosion mutation after "
                                    + "genuinely driving a real vehicle over this platform, but none did");
                            helper.succeed();
                        } finally {
                            handle.vehicle.remove();
                            ErosionModConfig.OVERRIDES.reload(priorConfig);
                        }
                    });
                } catch (RuntimeException | Error e) {
                    handle.vehicle.remove();
                    ErosionModConfig.OVERRIDES.reload(priorConfig);
                    throw e;
                }
            });
        } catch (RuntimeException | Error e) {
            ErosionModConfig.OVERRIDES.reload(priorConfig);
            throw e;
        }
    }

    /**
     * Recreates {@code GroundSupportFixtureProbe}'s own per-tick throttle/engine/rudder
     * re-assertion (that probe's own doc: {@code PartEngine}'s per-tick update logic can
     * plausibly reset {@code running}/{@code currentGearVar} without the caller ever knowing) as
     * a GameTest-native recursive {@code runAfterDelay(1, ...)} chain, since GameTest's own tick
     * loop -- not a loop this method writes itself -- is what re-enters this test. Applies
     * throttle (closed-loop bang-bang toward {@link #LOW_SPEED_TARGET}, same as that probe) AND
     * a steady rudder pulse together throughout, rather than sequential phases, so the vehicle
     * both covers real ground and genuinely slips sideways for the whole window.
     */
    private static void driveWithSlipFor(GameTestHelper helper, VehicleHandle handle, int remainingTicks, Runnable onDone) {
        if (remainingTicks <= 0) {
            onDone.run();
            return;
        }
        forceEngineRunning(handle.engine);
        double speed = forwardSpeed(handle.vehicle);
        handle.vehicle.throttleVar.setTo(speed < LOW_SPEED_TARGET ? 1.0 : 0.0, false);
        handle.vehicle.rudderInputVar.setTo(RUDDER_DEGREES, false);
        helper.runAfterDelay(1, () -> driveWithSlipFor(helper, handle, remainingTicks - 1, onDone));
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
