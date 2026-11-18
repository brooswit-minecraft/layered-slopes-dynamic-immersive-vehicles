package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

import io.github.brooswitminecraft.div.WheelGroundContactEvent;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The real DIV wheel-contact consumer (MINECRAFT-510, direction comment 55238/M55554611):
 * provides {@link ErosionWheelContactIntegration}, discovered via {@code ServiceLoader} from
 * the always-compiled mod entry point (see that interface's own doc for why this indirection
 * exists -- this class, and this entire {@code src/divIntegration} sourceSet, is only ever
 * compiled/present when a local DIV jar is on the classpath).
 *
 * <p>Split into two listeners to honor the event's own "never mutate synchronously" requirement:
 * {@link #onWheelGroundContact} (on DIV's own event, fired synchronously inside the physics
 * step) ONLY snapshots into {@link WheelContactQueue} -- never touches {@code ErosionAttempt},
 * {@code BlockState}, or anything else with a world-mutating side effect. {@link
 * #onServerTickPost} (on {@code ServerTickEvent.Post}, the real authoritative server-tick stage,
 * confirmed to run after physics the same way the rest of this mod's own
 * {@code ServerTickEvent.Post} listeners already do) is the ONLY place that drains the queue and
 * calls {@code ErosionAttempt.attempt}.
 *
 * <p>Each drained record is re-validated against the CURRENT world state before use -- never
 * trusted as still accurate just because it was valid when enqueued (review requirement:
 * "reject stale/world-invalid/unloaded records"): a record enqueued this tick but not yet
 * drained by the time a NEXT tick's {@code ServerTickEvent.Post} runs (should not normally
 * happen, since the queue is drained every tick, but defensively checked rather than assumed)
 * is rejected as stale rather than silently attempted against a now-wrong tick; a dimension no
 * longer present on the server, or a chunk no longer loaded, is likewise rejected rather than
 * forcing a load. {@code ErosionAttempt.attempt} itself already owns every other real check
 * (the {@link ErosionModConfig#OVERRIDES} default-OFF gate, same-thread assertion, per-tick
 * dedup via {@link #DEDUP}, claim/protection, and Dynamic-Terrain-cave-in-risk) -- this class
 * duplicates none of that, it only guards the two things specific to crossing a queue boundary.
 */
public final class DivWheelContactConsumer implements ErosionWheelContactIntegration {
    /**
     * Bounded at a generous multiple of a typical single-tick wheel count -- large enough that
     * an ordinary tick's worth of contacts is never dropped, small enough that a genuine
     * backlog (the drain falling behind for any reason) cannot grow unbounded. See
     * {@link WheelContactQueue}'s own doc for the drop-newest-when-full choice.
     */
    private static final int QUEUE_CAPACITY = 256;
    /** Upper bound on how many queued contacts one tick's drain will process. */
    private static final int MAX_DRAINED_PER_TICK = 256;

    private static final WheelContactQueue QUEUE = new WheelContactQueue(QUEUE_CAPACITY);
    private static final ErosionDedup DEDUP = new ErosionDedup();

    @Override
    public void register() {
        NeoForge.EVENT_BUS.addListener(DivWheelContactConsumer::onWheelGroundContact);
        NeoForge.EVENT_BUS.addListener(DivWheelContactConsumer::onServerTickPost);
    }

    /** Snapshot-only: never touches the world. See this class's own doc for why. */
    static void onWheelGroundContact(WheelGroundContactEvent event) {
        QUEUE.offer(new WheelContactQueue.QueuedContact(event.level().dimension(), event.blockPos(),
                event.lateralSlipSpeedMetersPerSecond(), event.supportedLoadKg(),
                event.serverTick()));
    }

    /** The only place that drains the queue and calls {@code ErosionAttempt.attempt}. */
    static void onServerTickPost(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        long currentTick = server.getTickCount();
        for (var contact : QUEUE.drainUpTo(MAX_DRAINED_PER_TICK)) {
            if (contact.queuedServerTick() != currentTick) {
                // Stale: this record was not actually produced during the tick being drained
                // right now (should not normally happen -- the queue is drained every tick --
                // but never attempted against a tick it doesn't actually belong to).
                continue;
            }
            ServerLevel level = server.getLevel(contact.dimension());
            if (level == null) {
                continue;
            }
            BlockPos pos = contact.pos();
            if (!level.hasChunkAt(pos)) {
                continue;
            }
            ErosionAttempt.attempt(level, pos, contact.slipSpeedMs(), contact.wheelLoadKg(),
                    level.getRandom(), ErosionModConfig.OVERRIDES.current(), DEDUP);
        }
    }

    /** Public, no-arg: required for {@code ServiceLoader} to instantiate this provider. */
    public DivWheelContactConsumer() {
    }
}
