package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Test-only server command that forces a single real {@link ErosionAttempt#attempt} call
 * against the REAL live config ({@link ErosionModConfig#OVERRIDES}), so a CI harness can prove
 * what a real connected client actually receives -- never registered unless {@link
 * #ENABLE_PROPERTY} is explicitly set, never present in an ordinary gameplay session, and never
 * a substitute for the real DIV contact-telemetry trigger still blocked on MINECRAFT-545.
 *
 * <p>Uses a fixed, deterministic {@link RandomSource} seed so the same call always produces the
 * same roll outcome for a given live config and slip/load input -- the harness needs
 * reproducible CI evidence, not a flaky one-in-N chance of a false pass/fail.
 */
public final class ErosionClientSyncTestCommand {
    public static final String ENABLE_PROPERTY = "layeredslopesdynamicimmersivevehicles.clientSyncTest";
    private static final long FIXED_SEED = 42L;

    private static final ErosionDedup DEDUP = new ErosionDedup();

    private ErosionClientSyncTestCommand() {
    }

    public static boolean isEnabled() {
        return Boolean.getBoolean(ENABLE_PROPERTY);
    }

    public static void register(RegisterCommandsEvent event) {
        if (!isEnabled()) {
            return;
        }
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("erosionclientsynctest")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .then(Commands.argument("slipSpeedMs", DoubleArgumentType.doubleArg())
                                .then(Commands.argument("wheelLoadKg", DoubleArgumentType.doubleArg())
                                        .executes(ErosionClientSyncTestCommand::run)))));
    }

    private static int run(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
        double slip = DoubleArgumentType.getDouble(ctx, "slipSpeedMs");
        double load = DoubleArgumentType.getDouble(ctx, "wheelLoadKg");
        ErosionAttempt.Outcome outcome = ErosionAttempt.attempt(
                ctx.getSource().getLevel(), pos, slip, load,
                RandomSource.create(FIXED_SEED), ErosionModConfig.OVERRIDES.current(), DEDUP);
        ctx.getSource().sendSuccess(() -> Component.literal("erosionclientsynctest outcome=" + outcome), true);
        return 1;
    }
}
