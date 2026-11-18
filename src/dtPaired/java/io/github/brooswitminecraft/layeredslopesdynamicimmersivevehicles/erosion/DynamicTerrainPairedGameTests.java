package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

import io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.LayeredSlopesDynamicImmersiveVehiclesMod;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Only compiled and run under the opt-in {@code -PdtPairedRuntime=true} build (see
 * build.gradle's {@code dtPairedRuntime} block, and
 * {@code .github/workflows/dt-paired-runtime.yml}) with a real, sha512-pinned
 * legacy-dynamic-terrain v5.0.1 actually installed -- never part of the default build/test, the
 * same "opt-in, never a real dependency" shape {@code div-paired-runtime.yml} already
 * established for DIV. This mod keeps no dependency on Dynamic Terrain; this class exists only
 * to prove {@link DynamicTerrainCaveInRisk}'s real behavior against Dynamic Terrain actually
 * loaded, since every other {@code ErosionAttemptGameTests} case fakes that seam.
 *
 * <p>Review 54916/54917/54919/54920 required real coverage of both of Dynamic Terrain's own
 * {@code caveInsEnabled} states, not just the "Dynamic Terrain absent" case already covered in
 * the default suite. The paired-runtime CI workflow runs the whole GameTestServer TWICE against
 * the identical pinned jar: once with Dynamic Terrain's own default {@code caveInsEnabled=false}
 * config on disk before boot, once with a config file pre-written with {@code true} before a
 * separate boot -- never editing a real user's world or config, only this throwaway CI
 * workspace's own fresh one. The single test below reads which scenario is active from a system
 * property the workflow passes, and asserts the real production {@link ErosionAttempt} outcome
 * matches. (The two-separate-boots shape proves both states; it does NOT prove a live config
 * reload mid-run without a server restart -- that remains unverified, see the workflow's own
 * comments and the ticket's README.)
 */
@GameTestHolder(LayeredSlopesDynamicImmersiveVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class DynamicTerrainPairedGameTests {
    private DynamicTerrainPairedGameTests() {
    }

    private static final String DYNAMIC_TERRAIN_MOD_ID = "dynamicterrain";
    private static final String CAVE_INS_ENABLED_SYSTEM_PROPERTY = "dt.caveInsEnabled";

    @GameTest(template = "flat_platform")
    public static void productionPathMatchesDynamicTerrainsRealCaveInsEnabledConfig(GameTestHelper helper) {
        if (!ModList.get().isLoaded(DYNAMIC_TERRAIN_MOD_ID)) {
            throw new AssertionError("dtPairedRuntime test ran without Dynamic Terrain actually "
                    + "loaded -- the paired-runtime build/workflow wiring is broken, not the "
                    + "production code this test exists to verify");
        }
        boolean expectedCaveInsEnabled =
                Boolean.parseBoolean(System.getProperty(CAVE_INS_ENABLED_SYSTEM_PROPERTY, "false"));

        BlockPos pos = new BlockPos(1, 2, 1);
        ErosionAttemptGameTests.placeSourceWithOneValidEastNeighbor(helper, pos);
        ServerLevel level = (ServerLevel) helper.getLevel();
        BlockPos absolutePos = helper.absolutePos(pos);

        ErosionAttempt.Outcome outcome = ErosionAttempt.attempt(level, absolutePos,
                ErosionAttemptGameTests.SLIP_ABOVE_THRESHOLD_MS, ErosionAttemptGameTests.REFERENCE_LOAD_KG,
                RandomSource.create(1), ErosionAttemptGameTests.GUARANTEED_CHANCE_CONFIG, new ErosionDedup());

        if (expectedCaveInsEnabled) {
            helper.assertTrue(outcome == ErosionAttempt.Outcome.NEIGHBOR_CAVE_IN_RISK,
                    "expected NEIGHBOR_CAVE_IN_RISK: Dynamic Terrain's own real caveInsEnabled "
                            + "config is true this boot, but production attempt returned " + outcome);
        } else {
            helper.assertTrue(outcome == ErosionAttempt.Outcome.APPLIED,
                    "expected APPLIED: Dynamic Terrain's own real caveInsEnabled config is false "
                            + "(its own default) this boot, but production attempt returned " + outcome);
        }
        helper.succeed();
    }
}
