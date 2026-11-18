package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

import io.github.brooswitminecraft.dynamicterrain.DynamicTerrainConfig;
import io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.LayeredSlopesDynamicImmersiveVehiclesMod;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.neoforged.fml.ModList;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Only compiled and run under the opt-in {@code -PdtPairedRuntime=true} build (see
 * build.gradle's {@code dtPairedRuntime} block, and
 * {@code .github/workflows/dt-paired-runtime.yml}) with a real, sha512-pinned
 * legacy-dynamic-terrain v5.0.1 actually installed -- never part of the default build/test, the
 * same "opt-in, never a real dependency" shape {@code div-paired-runtime.yml} already
 * established for DIV. This mod keeps no dependency on Dynamic Terrain in its default build;
 * this class, and this class alone, is allowed to reference Dynamic Terrain's own Java classes
 * directly ({@code DynamicTerrainConfig.CAVE_INS_ENABLED}), since it only exists, compiled only
 * under this opt-in profile, to prove {@link DynamicTerrainCaveInRisk}'s generic reader actually
 * agrees with Dynamic Terrain's own live value -- not just that production returns a plausible
 * outcome, which review 54925 correctly pointed out could pass even if the two diverged.
 *
 * <p>Review 54916/54917/54919/54920/54923/54925 required real coverage of: (1) both of Dynamic
 * Terrain's own {@code caveInsEnabled} states, not just "Dynamic Terrain absent"; (2) that this
 * mod's generic reader agrees with Dynamic Terrain's own {@code BooleanValue}, not merely that
 * production returns A result; and (3) a live config-reload transition within one running
 * server, or an evidenced finding that no such transition is actually observable, documented
 * rather than assumed.
 */
@GameTestHolder(LayeredSlopesDynamicImmersiveVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class DynamicTerrainPairedGameTests {
    private DynamicTerrainPairedGameTests() {
    }

    private static final String DYNAMIC_TERRAIN_MOD_ID = "dynamicterrain";
    private static final String CAVE_INS_ENABLED_SYSTEM_PROPERTY = "dt.caveInsEnabled";
    private static final String CAVE_INS_ENABLED_KEY = "caveInsEnabled";

    private static void requireDynamicTerrainLoaded() {
        if (!ModList.get().isLoaded(DYNAMIC_TERRAIN_MOD_ID)) {
            throw new AssertionError("dtPairedRuntime test ran without Dynamic Terrain actually "
                    + "loaded -- the paired-runtime build/workflow wiring is broken, not the "
                    + "production code this test exists to verify");
        }
    }

    private static ErosionAttempt.Outcome attemptAt(ServerLevel level, GameTestHelper helper, BlockPos pos) {
        ErosionAttemptGameTests.placeSourceWithOneValidEastNeighbor(helper, pos);
        BlockPos absolutePos = helper.absolutePos(pos);
        return ErosionAttempt.attempt(level, absolutePos,
                ErosionAttemptGameTests.SLIP_ABOVE_THRESHOLD_MS, ErosionAttemptGameTests.REFERENCE_LOAD_KG,
                RandomSource.create(1), ErosionAttemptGameTests.GUARANTEED_CHANCE_CONFIG, new ErosionDedup());
    }

    /**
     * Fixes review 54925's exact finding: the prior version of this test only compared the
     * SYSTEM-PROPERTY-stated scenario against the production outcome, never against Dynamic
     * Terrain's own loaded value -- so a divergence between this mod's generic reader and
     * Dynamic Terrain's real {@code BooleanValue} could not have been caught. This now reads
     * {@code DynamicTerrainConfig.CAVE_INS_ENABLED.get()} directly (safe here, and only here --
     * see the class javadoc) as the ground truth, cross-checks the generic reader against it,
     * and only then checks the production outcome against that SAME ground truth. The system
     * property still selects which boot-time scenario the workflow is asserting, as a sanity
     * check that the intended scenario is the one that actually landed.
     */
    @GameTest(template = "flat_platform")
    public static void productionPathMatchesDynamicTerrainsRealCaveInsEnabledConfig(GameTestHelper helper) {
        requireDynamicTerrainLoaded();
        boolean expectedByWorkflow =
                Boolean.parseBoolean(System.getProperty(CAVE_INS_ENABLED_SYSTEM_PROPERTY, "false"));
        boolean dynamicTerrainsOwnValue = DynamicTerrainConfig.CAVE_INS_ENABLED.get();
        helper.assertTrue(dynamicTerrainsOwnValue == expectedByWorkflow,
                "the workflow pre-wrote caveInsEnabled=" + expectedByWorkflow + " before this boot, "
                        + "but Dynamic Terrain's own loaded BooleanValue reads " + dynamicTerrainsOwnValue);

        boolean genericRead = DynamicTerrainCaveInRisk.readBooleanConfig(
                DYNAMIC_TERRAIN_MOD_ID, ModConfig.Type.SERVER, CAVE_INS_ENABLED_KEY, true);
        helper.assertTrue(genericRead == dynamicTerrainsOwnValue,
                "this mod's generic config reader returned " + genericRead
                        + " but Dynamic Terrain's own loaded BooleanValue is " + dynamicTerrainsOwnValue
                        + " -- the generic reader does not actually agree with Dynamic Terrain's real value");

        ServerLevel level = (ServerLevel) helper.getLevel();
        ErosionAttempt.Outcome outcome = attemptAt(level, helper, new BlockPos(1, 2, 1));
        if (dynamicTerrainsOwnValue) {
            helper.assertTrue(outcome == ErosionAttempt.Outcome.NEIGHBOR_CAVE_IN_RISK,
                    "expected NEIGHBOR_CAVE_IN_RISK: Dynamic Terrain's own real caveInsEnabled "
                            + "value is true this boot, but production attempt returned " + outcome);
        } else {
            helper.assertTrue(outcome == ErosionAttempt.Outcome.APPLIED,
                    "expected APPLIED: Dynamic Terrain's own real caveInsEnabled value is false "
                            + "(its own default) this boot, but production attempt returned " + outcome);
        }
        helper.succeed();
    }

    private static final Path DYNAMIC_TERRAIN_CONFIG_PATH = Path.of("config", "dynamicterrain-server.toml");

    private static void rewriteConfigFile(boolean caveInsEnabled) {
        try {
            String content = Files.readString(DYNAMIC_TERRAIN_CONFIG_PATH, StandardCharsets.UTF_8);
            String target = caveInsEnabled ? "true" : "false";
            String other = caveInsEnabled ? "false" : "true";
            String edited = content.replaceAll("(?m)^caveInsEnabled\\s*=\\s*" + other, "caveInsEnabled = " + target);
            Files.writeString(DYNAMIC_TERRAIN_CONFIG_PATH, edited, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new AssertionError("failed to edit Dynamic Terrain's own config file", e);
        }
    }

    private static boolean pollForValue(boolean expected, long timeoutSeconds) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            if (DynamicTerrainConfig.CAVE_INS_ENABLED.get() == expected) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while polling for a live config reload", e);
            }
        }
        return false;
    }

    /**
     * Review 54923/54925's live-reload requirement: edits Dynamic Terrain's own real config
     * file on disk from false to true WHILE this single server is still running (no restart),
     * then polls Dynamic Terrain's own {@code BooleanValue} directly for up to 5 real seconds.
     * This is an EVIDENCE-GATHERING test, not an assumption either way: if the value is observed
     * to flip, it asserts the production path transitions from APPLIED to NEIGHBOR_CAVE_IN_RISK
     * accordingly; if it is NOT observed to flip within the timeout, this fails with a message
     * that says so explicitly, rather than silently passing -- per review 54923's "if DT has no
     * supported live reload, establish that from actual behavior/API... fail closed for runtime
     * changes." This test only runs in the {@code caveInsEnabled=false}-boot scenario (the other
     * boot has nothing to transition FROM); the workflow runs it exactly once.
     *
     * <p>Reverts the file and REQUIRES the value to actually be observed back at {@code false}
     * before returning, since {@code DynamicTerrainConfig.CAVE_INS_ENABLED} is static,
     * process-wide state shared with every other {@code @GameTest} method in this same server
     * run, and NeoForge's own batch execution order between methods is not something this test
     * can rely on. Review 54931 found an earlier version of this cleanup discarded
     * {@code pollForValue}'s result instead of asserting it -- so the test could pass while the
     * reset silently failed, leaving the static value (and the on-disk file) mutated for later
     * {@code @GameTest} methods, with the documented isolation guarantee unverified. The cleanup
     * failure is attached as a suppressed exception rather than replacing a real assertion
     * failure from the body above, so neither failure masks the other.
     */
    @GameTest(template = "flat_platform")
    public static void liveConfigFileEditIsObservedWithoutARestartOrElseThisFailsExplicitly(GameTestHelper helper) {
        requireDynamicTerrainLoaded();
        boolean expectedByWorkflow =
                Boolean.parseBoolean(System.getProperty(CAVE_INS_ENABLED_SYSTEM_PROPERTY, "false"));
        if (expectedByWorkflow) {
            // This boot's config is already true -- nothing to transition FROM. The false-boot
            // run of this same test exercises the transition; skip here rather than duplicate it.
            helper.succeed();
            return;
        }
        helper.assertTrue(pollForValue(false, 1),
                "preconditions: Dynamic Terrain's own value must start false for this test");

        ServerLevel level = (ServerLevel) helper.getLevel();
        BlockPos pos = new BlockPos(1, 2, 1);
        ErosionAttempt.Outcome before = attemptAt(level, helper, pos);
        helper.assertTrue(before == ErosionAttempt.Outcome.APPLIED,
                "expected APPLIED before the live edit, got " + before);

        rewriteConfigFile(true);
        boolean observedFlipToTrue = pollForValue(true, 5);

        Throwable primaryFailure = null;
        try {
            helper.assertTrue(observedFlipToTrue,
                    "Dynamic Terrain's own caveInsEnabled BooleanValue did NOT flip to true within 5s "
                            + "of editing its config file on disk while this server kept running -- no "
                            + "live reload was observed; see DynamicTerrainCaveInRisk's javadoc and the "
                            + "README for what this means for this mod (it always re-reads fresh per "
                            + "attempt regardless, so this is a Dynamic-Terrain-side finding, not a "
                            + "defect in this mod's reader)");

            ErosionAttempt.Outcome after = attemptAt(level, helper, pos);
            helper.assertTrue(after == ErosionAttempt.Outcome.NEIGHBOR_CAVE_IN_RISK,
                    "after the observed live reload to caveInsEnabled=true, expected NEIGHBOR_CAVE_IN_RISK, got "
                            + after);

            boolean genericReadAfter = DynamicTerrainCaveInRisk.readBooleanConfig(
                    DYNAMIC_TERRAIN_MOD_ID, ModConfig.Type.SERVER, CAVE_INS_ENABLED_KEY, true);
            helper.assertTrue(genericReadAfter,
                    "after the observed live reload, this mod's generic reader should also observe true, got "
                            + genericReadAfter);
        } catch (Throwable t) {
            primaryFailure = t;
            throw t;
        } finally {
            rewriteConfigFile(false);
            boolean observedRevertToFalse = pollForValue(false, 5);
            String onDiskAfterRevert;
            try {
                onDiskAfterRevert = Files.readString(DYNAMIC_TERRAIN_CONFIG_PATH, StandardCharsets.UTF_8)
                        .lines()
                        .filter(line -> line.startsWith(CAVE_INS_ENABLED_KEY))
                        .findFirst().orElse("<missing>");
            } catch (java.io.IOException e) {
                onDiskAfterRevert = "<unreadable: " + e + ">";
            }
            boolean onDiskReflectsFalse = onDiskAfterRevert.contains("false");
            if (!observedRevertToFalse || !onDiskReflectsFalse) {
                AssertionError cleanupFailure = new AssertionError(
                        "cleanup failed: Dynamic Terrain's own caveInsEnabled BooleanValue did not "
                                + "revert to false (observed=" + observedRevertToFalse + ") and/or the "
                                + "on-disk file does not reflect it (" + onDiskAfterRevert + ") -- this "
                                + "static state would leak into later @GameTest methods in this run");
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }

        helper.succeed();
    }
}
