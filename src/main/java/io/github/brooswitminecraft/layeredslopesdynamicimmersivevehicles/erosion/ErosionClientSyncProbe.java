package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Test-only client-side probe: once connected to a real server and the target chunk is loaded,
 * reads a single {@code BlockPos}'s block state from the REAL {@link ClientLevel} -- built
 * purely from network packets this client process actually received, independent of whatever
 * the server believes -- and writes it to a plain text file for a CI harness to assert on, then
 * requests a clean client shutdown.
 *
 * <p>Deliberately never references anything server-side: this is the actual acceptance evidence
 * direction 55063 asked for (a real client's own observed state), not a server-only GameTest
 * result or an {@code UPDATE} flag relabeled as client-sync proof. Gated by {@link
 * #ENABLE_PROPERTY} so it is never active in an ordinary client session.
 */
public final class ErosionClientSyncProbe {
    public static final String ENABLE_PROPERTY = "layeredslopesdynamicimmersivevehicles.clientSyncProbe";
    public static final String POS_PROPERTY = "layeredslopesdynamicimmersivevehicles.clientSyncProbe.pos";
    public static final String OUTPUT_PROPERTY = "layeredslopesdynamicimmersivevehicles.clientSyncProbe.outputFile";
    /** Ticks to wait after the target chunk first becomes visible before reading its state, so a
     * block-update packet already in flight has time to arrive and apply. */
    private static final int SETTLE_TICKS = 100;

    private static boolean done = false;
    private static int ticksSinceChunkSeen = -1;

    private ErosionClientSyncProbe() {
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
        BlockPos pos = parsePos(System.getProperty(POS_PROPERTY));
        if (pos == null || !level.hasChunkAt(pos)) {
            return;
        }
        ticksSinceChunkSeen++;
        if (ticksSinceChunkSeen < SETTLE_TICKS) {
            return;
        }
        String blockId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
        writeResult(blockId);
        done = true;
        client.stop();
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

    private static void writeResult(String blockId) {
        String outputPath = System.getProperty(OUTPUT_PROPERTY);
        if (outputPath == null) {
            return;
        }
        try {
            Files.writeString(Path.of(outputPath), blockId, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("failed to write client-sync probe result", e);
        }
    }
}
