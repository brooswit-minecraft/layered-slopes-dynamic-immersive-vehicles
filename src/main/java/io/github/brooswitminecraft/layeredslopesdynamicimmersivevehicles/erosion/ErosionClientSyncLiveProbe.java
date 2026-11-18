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
 * 55058/55111/55114/55137): unlike {@link ErosionClientSyncProbe} (a single fixed-settle-then-read
 * snapshot, taken before any mutation happens -- indistinguishable from ordinary initial chunk
 * sync), this watches two known positions, signals a CI harness once it has genuinely captured
 * a baseline from the real {@link ClientLevel} (so the harness knows it is now safe to trigger a
 * mutation while this client is already connected and watching), then polls for either position
 * to actually change before writing a final result -- proving the change arrived via a real,
 * live block-update packet received after connection, not something already present at join.
 *
 * <p>Reports whichever actually happened rather than assuming a change occurs: a timeout with no
 * change is reported as {@code UNCHANGED}, which is itself the correct evidence for a
 * rejected/canceled-case acceptance slice reusing this same class. Review finding (55137) on
 * that exact reuse: a plain fixed-tick timer started at baseline does not structurally prove the
 * observation continued AFTER the server actually acknowledged the real denial -- a delayed
 * harness could in principle let the client's timer expire before the attempt even ran, and
 * still report UNCHANGED for the wrong reason. {@link #ACKNOWLEDGE_FILE_PROPERTY} is optional:
 * when a harness supplies it, the probe requires that file to appear (written by the harness
 * only after it has independently confirmed the real denial in the server's own log) BEFORE
 * declaring {@code UNCHANGED}, and then watches for a further bounded {@link
 * #POST_ACKNOWLEDGE_WATCH_TICKS} after that signal specifically. If the full {@link
 * #MAX_WATCH_TICKS} window elapses with the acknowledge file never appearing at all, that is
 * reported as the distinct {@code TIMEOUT_NO_ACK} status -- never silently folded into
 * {@code UNCHANGED} -- so a harness bug of exactly this kind fails loudly instead of passing for
 * the wrong reason. When no acknowledge file is configured (the enabled/CHANGED-expected case
 * never needs one), behavior is unchanged from before: a plain baseline-relative timeout.
 */
public final class ErosionClientSyncLiveProbe {
    public static final String ENABLE_PROPERTY = "layeredslopesdynamicimmersivevehicles.clientSyncLiveProbe";
    public static final String SOURCE_POS_PROPERTY = "layeredslopesdynamicimmersivevehicles.clientSyncLiveProbe.sourcePos";
    public static final String DEST_POS_PROPERTY = "layeredslopesdynamicimmersivevehicles.clientSyncLiveProbe.destPos";
    public static final String READY_SIGNAL_FILE_PROPERTY =
            "layeredslopesdynamicimmersivevehicles.clientSyncLiveProbe.readySignalFile";
    public static final String ACKNOWLEDGE_FILE_PROPERTY =
            "layeredslopesdynamicimmersivevehicles.clientSyncLiveProbe.acknowledgeFile";
    public static final String OUTPUT_PROPERTY = "layeredslopesdynamicimmersivevehicles.clientSyncLiveProbe.outputFile";
    /** Upper bound on ticks spent watching for either a change or the acknowledge file. */
    private static final int MAX_WATCH_TICKS = 600;
    /** Ticks of continued observation required after the acknowledge file appears. */
    private static final int POST_ACKNOWLEDGE_WATCH_TICKS = 100;
    /**
     * Ticks to wait, once both positions are first tracked, before trusting a baseline read.
     * A concurrent two-client CI run surfaced a real race: {@code hasChunkAt} can return true
     * before the chunk's block data has fully settled from the initial sync packets, so a
     * baseline captured on that very first tracked tick can read a transient/incomplete state
     * that differs from the real value arriving a tick later -- reported as a false CHANGED
     * before any real mutation ran. Matches the settle-then-read discipline
     * {@link ErosionClientSyncProbe} already uses for exactly this reason.
     */
    private static final int BASELINE_SETTLE_TICKS = 20;

    private static boolean baselineCaptured = false;
    private static boolean done = false;
    private static boolean acknowledged = false;
    private static String baselineSourceId;
    private static String baselineDestId;
    private static int watchTicks = 0;
    private static int ticksSinceAcknowledge = 0;
    private static int settleTicks = 0;

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
            // Review finding: a gap in tracking (chunk unloaded/reloaded) must restart the
            // settle count -- otherwise this does not actually enforce CONSECUTIVE tracked
            // ticks, only a cumulative total, which could let a baseline be captured right after
            // a fresh (potentially still-transient) reload.
            if (!baselineCaptured) {
                settleTicks = 0;
            }
            return;
        }
        if (!baselineCaptured) {
            settleTicks++;
            if (settleTicks < BASELINE_SETTLE_TICKS) {
                return;
            }
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
            return;
        }

        // build.gradle always sets this property to the empty string, never leaves it absent,
        // when a harness does not configure one (e.g. the enabled/CHANGED-expected case) -- so
        // both null and blank mean "no acknowledge signal configured" here.
        String acknowledgePath = System.getProperty(ACKNOWLEDGE_FILE_PROPERTY);
        if (acknowledgePath == null || acknowledgePath.isBlank()) {
            // No acknowledge signal configured -- unchanged, original baseline-relative timeout.
            if (watchTicks >= MAX_WATCH_TICKS) {
                writeResult("UNCHANGED", level, sourcePos, destPos);
                done = true;
                client.stop();
            }
            return;
        }

        if (!acknowledged) {
            if (Files.exists(Path.of(acknowledgePath))) {
                acknowledged = true;
                ticksSinceAcknowledge = 0;
            } else if (watchTicks >= MAX_WATCH_TICKS) {
                // The acknowledge file never appeared at all within the bound -- a pre-attempt
                // timeout (or a harness that never actually ran the attempt), never reported as
                // UNCHANGED, which could otherwise be mistaken for genuine denial evidence.
                writeResult("TIMEOUT_NO_ACK", level, sourcePos, destPos);
                done = true;
                client.stop();
            }
            return;
        }

        ticksSinceAcknowledge++;
        if (ticksSinceAcknowledge >= POST_ACKNOWLEDGE_WATCH_TICKS) {
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
