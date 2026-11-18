package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The first real GameTest for this mod (review 54853/54854: {@code runGameTestServer} with zero
 * tests always terminates non-zero with "No test functions were given!", so no CI job built
 * around it can ever reach a genuine pass/fail result until a real test exists). Deliberately
 * trivial: this slice has no erosion consumer or other gameplay behavior yet to actually test
 * (see README.md Status), so this proves only that the GameTest harness itself boots and
 * terminates successfully for this mod -- real behavioral GameTests land with the erosion
 * consumer. The same test also runs, unchanged, in the opt-in DIV paired-runtime job
 * (div-paired-runtime.yml) with DIV and its own required dependencies additionally loaded,
 * which is what that job's genuine pass/fail terminal result depends on.
 */
@GameTestHolder(LayeredSlopesDynamicImmersiveVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class StandaloneBootGameTests {
    private StandaloneBootGameTests() {}

    @GameTest(template = "flat_platform")
    public static void theGameTestHarnessBootsForThisMod(GameTestHelper helper) {
        helper.succeed();
    }
}
