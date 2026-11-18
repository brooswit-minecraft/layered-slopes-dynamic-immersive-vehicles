package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

import io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.LayeredSlopesDynamicImmersiveVehiclesMod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Real end-to-end evidence for {@link ErosionModConfig} (direction, comment 54943): this mod's
 * OWN config, so unlike {@code DynamicTerrainPairedGameTests} this runs in the DEFAULT test
 * suite, no opt-in flag needed.
 */
@GameTestHolder(LayeredSlopesDynamicImmersiveVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class ErosionModConfigGameTests {
    private ErosionModConfigGameTests() {
    }

    private static final Path CONFIG_PATH =
            Path.of("config", LayeredSlopesDynamicImmersiveVehiclesMod.MODID + "-server.toml");

    /**
     * Boot-time evidence that the real registered config actually defaulted {@code
     * erosionEnabled} to {@code false} -- not just that {@link ErosionConfig#DEFAULT} (a plain
     * Java constant, provable without NeoForge at all) says so, but that the real
     * {@code ModConfigSpec} wiring in {@code LayeredSlopesDynamicImmersiveVehiclesMod}'s
     * constructor actually loaded and routed it into {@link ErosionModConfig#OVERRIDES}.
     */
    @GameTest(template = "flat_platform")
    public static void realConfigBootsWithErosionDisabledByDefault(GameTestHelper helper) {
        helper.assertTrue(!ErosionModConfig.OVERRIDES.current().erosionEnabled(),
                "the real registered ErosionModConfig.SPEC must have loaded erosionEnabled=false "
                        + "by default, same as the on-disk generated config file");
        helper.succeed();
    }

    /**
     * Mirrors {@code DynamicTerrainPairedGameTests}' live-reload probe, but for this mod's OWN
     * config: edits the real on-disk config file from false to true WHILE this server keeps
     * running (no restart), polls {@link ErosionModConfig#OVERRIDES} for the live reload, then
     * proves the production {@link ErosionAttempt} path transitions {@code DISABLED} ->
     * {@code APPLIED} accordingly. Reverts the file and polls the value back to {@code false}
     * afterward (success or failure, suppressing any cleanup failure onto a real primary
     * failure rather than replacing it -- the exact mistake review 54931/54934 caught in the
     * Dynamic Terrain probe, deliberately not repeated here), since {@code OVERRIDES} is static,
     * process-wide state shared with every other {@code @GameTest} in this run.
     */
    @GameTest(template = "flat_platform")
    public static void liveConfigFileEditTogglesErosionEnabledWithoutARestart(GameTestHelper helper) {
        if (ErosionAttemptGameTests.dynamicTerrainIsLoaded()) {
            // Not testing Dynamic Terrain interaction -- under the opt-in dtPairedRuntime
            // profile with its own caveInsEnabled=true, a real enabled attempt correctly denies
            // via NEIGHBOR_CAVE_IN_RISK regardless of erosionEnabled, which this test is not
            // about. Same reasoning as ErosionAttemptGameTests' own DT-absence guard.
            helper.succeed();
            return;
        }
        helper.assertTrue(pollForEnabled(false, 1),
                "preconditions: erosionEnabled must start false for this test");

        ServerLevel level = (ServerLevel) helper.getLevel();
        BlockPos pos = new BlockPos(1, 2, 1);
        ErosionAttemptGameTests.placeSourceWithOneValidEastNeighbor(helper, pos);
        BlockPos absolutePos = helper.absolutePos(pos);

        // Reads the LIVE erosionEnabled flag from the real registered config (the thing this
        // test exists to prove), but keeps the roll's own tuning deterministic
        // (GUARANTEED_CHANCE_CONFIG's extreme-but-valid values) rather than the real registered
        // tuning defaults, which do not guarantee a successful roll for a fixed seed -- the roll
        // succeeding or failing is ErosionMathTest's concern, not this test's.
        ErosionAttempt.Outcome before = ErosionAttempt.attempt(level, absolutePos,
                ErosionAttemptGameTests.SLIP_ABOVE_THRESHOLD_MS, ErosionAttemptGameTests.REFERENCE_LOAD_KG,
                RandomSource.create(1), guaranteedChanceWithLiveEnabledFlag(), new ErosionDedup());
        helper.assertTrue(before == ErosionAttempt.Outcome.DISABLED,
                "expected DISABLED before the live edit, got " + before);

        rewriteConfigFile(true);
        boolean observedFlipToTrue = pollForEnabled(true, 5);

        Throwable primaryFailure = null;
        try {
            helper.assertTrue(observedFlipToTrue,
                    "erosionEnabled did NOT flip to true within 5s of editing this mod's own "
                            + "config file on disk while the server kept running -- no live "
                            + "reload was observed");

            ErosionAttemptGameTests.placeSourceWithOneValidEastNeighbor(helper, pos);
            ErosionAttempt.Outcome after = ErosionAttempt.attempt(level, absolutePos,
                    ErosionAttemptGameTests.SLIP_ABOVE_THRESHOLD_MS, ErosionAttemptGameTests.REFERENCE_LOAD_KG,
                    RandomSource.create(1), guaranteedChanceWithLiveEnabledFlag(), new ErosionDedup());
            helper.assertTrue(after == ErosionAttempt.Outcome.APPLIED,
                    "after the observed live reload to erosionEnabled=true, expected APPLIED, got " + after);
        } catch (Throwable t) {
            primaryFailure = t;
            throw t;
        } finally {
            try {
                rewriteConfigFile(false);
                boolean observedRevertToFalse = pollForEnabled(false, 5);
                if (!observedRevertToFalse) {
                    throw new AssertionError("cleanup failed: erosionEnabled did not revert to "
                            + "false -- this static state would leak into later @GameTest methods");
                }
            } catch (Throwable cleanupFailure) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }

        helper.succeed();
    }

    /**
     * {@code ErosionAttemptGameTests.GUARANTEED_CHANCE_CONFIG}'s extreme-but-valid tuning
     * (chance clamps to exactly 1.0, so any seed's roll succeeds), but with {@code
     * erosionEnabled} read live from {@link ErosionModConfig#OVERRIDES} -- so this test's roll
     * outcome is deterministic while its enabled/disabled outcome still genuinely depends on the
     * real registered config's current value.
     */
    private static ErosionConfig guaranteedChanceWithLiveEnabledFlag() {
        return new ErosionConfig(0.15, 1000.0, 10.0, 300.0, 3.0, 0.25, 4.0,
                ErosionModConfig.OVERRIDES.current().erosionEnabled());
    }

    private static void rewriteConfigFile(boolean erosionEnabled) {
        try {
            String content = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            String target = erosionEnabled ? "true" : "false";
            String other = erosionEnabled ? "false" : "true";
            String edited = content.replaceAll("(?m)^erosionEnabled\\s*=\\s*" + other, "erosionEnabled = " + target);
            Files.writeString(CONFIG_PATH, edited, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("failed to edit this mod's own config file", e);
        }
    }

    private static boolean pollForEnabled(boolean expected, long timeoutSeconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            if (ErosionModConfig.OVERRIDES.current().erosionEnabled() == expected) {
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
}
