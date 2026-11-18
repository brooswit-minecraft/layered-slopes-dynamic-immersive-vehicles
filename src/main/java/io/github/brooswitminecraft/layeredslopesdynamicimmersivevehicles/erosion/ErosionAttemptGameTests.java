package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

import io.github.brooswitminecraft.layeredslopes.LayeredSlopesMod;
import io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.LayeredSlopesDynamicImmersiveVehiclesMod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Acceptance evidence for {@link ErosionAttempt}, the real orchestration of dedup, the accepted
 * MINECRAFT-555 slip-to-probability roll, direction selection, and the {@link
 * ErosionProtectionGate} -- not just each primitive in isolation. The success-chance inputs below
 * are deliberately extreme-but-valid (not the production defaults) so the roll outcome is
 * guaranteed regardless of {@code RandomSource} seed: chance clamps to exactly 1.0, and {@code
 * RandomSource.nextDouble()} never returns a value &gt;= 1.0, so {@code nextDouble() < 1.0} always
 * holds -- this is a property of the math, not a hidden dependency on a specific seed.
 */
@GameTestHolder(LayeredSlopesDynamicImmersiveVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class ErosionAttemptGameTests {
    private ErosionAttemptGameTests() {
    }

    // base=1000 (the valid maximum) makes amount so large that probability clamps to 1.0 for any
    // slip above threshold and any in-bounds load, against ordinary block resistance (e.g. dirt).
    private static final ErosionConfig GUARANTEED_CHANCE_CONFIG =
            new ErosionConfig(0.15, 1000.0, 10.0, 300.0, 3.0, 0.25, 4.0);
    private static final double SLIP_ABOVE_THRESHOLD_MS = 5.0;
    private static final double REFERENCE_LOAD_KG = 300.0;

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

    @GameTest(template = "flat_platform")
    public static void protectionGateDeniesEvenWhenRollAndDirectionWouldOtherwiseSucceed(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 2, 1);
        helper.setBlock(pos, Blocks.DIRT.defaultBlockState());
        helper.setBlock(pos.relative(Direction.EAST), Blocks.AIR.defaultBlockState());
        helper.setBlock(pos.relative(Direction.EAST).below(), Blocks.STONE.defaultBlockState());
        BlockState before = helper.getBlockState(pos);

        ErosionProtectionGate gate = new ErosionProtectionGate(); // no adapter installed: fails closed
        ErosionAttempt.Outcome outcome = ErosionAttempt.attempt(
                helper.getLevel(), helper.absolutePos(pos), "gametest:dimension",
                SLIP_ABOVE_THRESHOLD_MS, REFERENCE_LOAD_KG, 1L, RandomSource.create(1),
                GUARANTEED_CHANCE_CONFIG, new ErosionDedup(), gate);

        helper.assertTrue(outcome == ErosionAttempt.Outcome.DENIED_BY_PROTECTION,
                "expected DENIED_BY_PROTECTION with no adapter installed, got " + outcome);
        helper.assertTrue(helper.getBlockState(pos).equals(before),
                "the world must stay unmutated when protection denies the only valid direction");
        helper.succeed();
    }

    @GameTest(template = "flat_platform")
    public static void aGrantingAdapterAllowsErosionToApply(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 2, 1);
        helper.setBlock(pos, Blocks.DIRT.defaultBlockState());
        helper.setBlock(pos.relative(Direction.EAST), Blocks.AIR.defaultBlockState());
        helper.setBlock(pos.relative(Direction.EAST).below(), Blocks.STONE.defaultBlockState());

        ErosionProtectionGate gate = new ErosionProtectionGate();
        gate.assertCoverage(grantingAdapter());
        ErosionAttempt.Outcome outcome = ErosionAttempt.attempt(
                helper.getLevel(), helper.absolutePos(pos), "gametest:dimension",
                SLIP_ABOVE_THRESHOLD_MS, REFERENCE_LOAD_KG, 1L, RandomSource.create(1),
                GUARANTEED_CHANCE_CONFIG, new ErosionDedup(), gate);

        helper.assertTrue(outcome == ErosionAttempt.Outcome.APPLIED, "expected APPLIED, got " + outcome);
        helper.assertTrue(helper.getBlockState(pos).getBlock() == LayeredSlopesMod.LAYERED_DIRT.get(),
                "the source cell must become LAYERED_DIRT after a real applied erosion move");
        helper.succeed();
    }

    @GameTest(template = "flat_platform")
    public static void dedupPreventsASecondClaimInTheSameTick(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 2, 1);
        helper.setBlock(pos, Blocks.DIRT.defaultBlockState());
        helper.setBlock(pos.relative(Direction.EAST), Blocks.AIR.defaultBlockState());
        helper.setBlock(pos.relative(Direction.EAST).below(), Blocks.STONE.defaultBlockState());

        ErosionProtectionGate gate = new ErosionProtectionGate();
        gate.assertCoverage(grantingAdapter());
        ErosionDedup dedup = new ErosionDedup();

        ErosionAttempt.Outcome first = ErosionAttempt.attempt(
                helper.getLevel(), helper.absolutePos(pos), "gametest:dimension",
                SLIP_ABOVE_THRESHOLD_MS, REFERENCE_LOAD_KG, 1L, RandomSource.create(1),
                GUARANTEED_CHANCE_CONFIG, dedup, gate);
        ErosionAttempt.Outcome second = ErosionAttempt.attempt(
                helper.getLevel(), helper.absolutePos(pos), "gametest:dimension",
                SLIP_ABOVE_THRESHOLD_MS, REFERENCE_LOAD_KG, 1L, RandomSource.create(2),
                GUARANTEED_CHANCE_CONFIG, dedup, gate);

        helper.assertTrue(first == ErosionAttempt.Outcome.APPLIED, "expected first attempt APPLIED, got " + first);
        helper.assertTrue(second == ErosionAttempt.Outcome.DUPLICATE_THIS_TICK,
                "a second wheel targeting the same block in the same tick must be rejected as a duplicate, got " + second);
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
                helper.getLevel(), helper.absolutePos(pos), "gametest:dimension",
                SLIP_ABOVE_THRESHOLD_MS, REFERENCE_LOAD_KG, 1L, RandomSource.create(1),
                GUARANTEED_CHANCE_CONFIG, new ErosionDedup(), gate);

        helper.assertTrue(outcome == ErosionAttempt.Outcome.NO_VALID_DIRECTION,
                "expected NO_VALID_DIRECTION when every horizontal neighbor is a different, blocking material, got " + outcome);
        helper.assertTrue(helper.getBlockState(pos).equals(before), "the world must stay unmutated");
        helper.succeed();
    }
}
