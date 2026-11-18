package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import io.github.brooswitminecraft.layeredslopes.LayeredBlock;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Test-only client-side probe for LIVE post-join synchronization evidence (direction
 * 55058/55111/55114): unlike {@link ErosionClientSyncProbe} (a single fixed-settle-then-read
 * snapshot, taken before any mutation happens -- indistinguishable from ordinary initial chunk
 * sync), this watches two known positions, signals a CI harness once it has genuinely captured
 * a baseline from the real {@link ClientLevel} (so the harness knows it is now safe to trigger a
 * mutation while this client is already connected and watching), then polls for either position
 * to actually change before writing a final result -- proving the change arrived via a real,
 * live block-update packet received after connection, not something already present at join.
 *
 * <p>Reports whichever actually happened rather than assuming a change occurs: a timeout with no
 * change is reported as {@code UNCHANGED}, which is itself the correct evidence for a future
 * rejected/canceled-case acceptance slice reusing this same class.
 */
public final class ErosionClientSyncLiveProbe {
    public static final String ENABLE_PROPERTY = "layeredslopesdynamicimmersivevehicles.clientSyncLiveProbe";
    public static final String SOURCE_POS_PROPERTY = "layeredslopesdynamicimmersivevehicles.clientSyncLiveProbe.sourcePos";
    public static final String DEST_POS_PROPERTY = "layeredslopesdynamicimmersivevehicles.clientSyncLiveProbe.destPos";
    public static final String READY_SIGNAL_FILE_PROPERTY =
            "layeredslopesdynamicimmersivevehicles.clientSyncLiveProbe.readySignalFile";
    public static final String OUTPUT_PROPERTY = "layeredslopesdynamicimmersivevehicles.clientSyncLiveProbe.outputFile";
    /** Upper bound on ticks spent watching for a change before reporting UNCHANGED. */
    private static final int MAX_WATCH_TICKS = 600;

    private static boolean baselineCaptured = false;
    private static boolean done = false;
    private static String baselineSourceId;
    private static String baselineDestId;
    private static int watchTicks = 0;

    private ErosionClientSyncLiveProbe() {
    }

    public static boolean isEnabled() {
        return Boolean.getBoolean(ENABLE_PROPERTY);
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        if (!isEnabled() || done) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        if (level == null) {
            return;
        }
        BlockPos sourcePos = parsePos(System.getProperty(SOURCE_POS_PROPERTY));
        BlockPos destPos = parsePos(System.getProperty(DEST_POS_PROPERTY));
        if (sourcePos == null || destPos == null || !level.hasChunkAt(sourcePos) || !level.hasChunkAt(destPos)) {
            return;
        }
        if (!baselineCaptured) {
            baselineSourceId = blockId(level.getBlockState(sourcePos));
            baselineDestId = blockId(level.getBlockState(destPos));
            baselineCaptured = true;
            writeReadySignal();
            return;
        }
        String currentSourceId = blockId(level.getBlockState(sourcePos));
        String currentDestId = blockId(level.getBlockState(destPos));
        watchTicks++;
        boolean changed = !currentSourceId.equals(baselineSourceId) || !currentDestId.equals(baselineDestId);
        if (changed) {
            writeResult("CHANGED", level, sourcePos, destPos);
            done = true;
            client.stop();
        } else if (watchTicks >= MAX_WATCH_TICKS) {
            writeResult("UNCHANGED", level, sourcePos, destPos);
            done = true;
            client.stop();
        }
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static int layersOrMinusOne(BlockState state) {
        return state.hasProperty(LayeredBlock.LAYERS) ? state.getValue(LayeredBlock.LAYERS) : -1;
    }

    private static BlockPos parsePos(String raw) {
        if (raw == null) {
            return null;
        }
        String[] parts = raw.split(",");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new BlockPos(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void writeReadySignal() {
        String path = System.getProperty(READY_SIGNAL_FILE_PROPERTY);
        if (path == null) {
            return;
        }
        writeFile(path, "ready");
    }

    private static void writeResult(String status, ClientLevel level, BlockPos sourcePos, BlockPos destPos) {
        String path = System.getProperty(OUTPUT_PROPERTY);
        if (path == null) {
            return;
        }
        BlockState sourceState = level.getBlockState(sourcePos);
        BlockState destState = level.getBlockState(destPos);
        String content = status + "\n"
                + blockId(sourceState) + "\n"
                + layersOrMinusOne(sourceState) + "\n"
                + blockId(destState) + "\n"
                + layersOrMinusOne(destState) + "\n";
        writeFile(path, content);
    }

    private static void writeFile(String path, String content) {
        try {
            Files.writeString(Path.of(path), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write client-sync live probe file", e);
        }
    }
}
