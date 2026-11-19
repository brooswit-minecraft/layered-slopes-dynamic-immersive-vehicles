package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

import io.github.brooswitminecraft.div.WheelGroundContactEvent;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
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
 * "reject stale/world-invalid/unloaded records") -- via {@link WheelContactQueue#shouldAttempt},
 * a pure, independently-testable decision that also rejects a non-top-face contact (a wheel
 * clipping a wall's side is not a ground-erosion candidate; see that method's own doc).
 * {@code ErosionAttempt.attempt} itself already owns every other real check (the {@link
 * ErosionModConfig#OVERRIDES} default-OFF gate, same-thread assertion, per-tick dedup via
 * {@link #DEDUP}, claim/protection, and Dynamic-Terrain-cave-in-risk) -- this class duplicates
 * none of that, it only guards the facts specific to crossing a queue boundary.
 *
 * <p>{@link WheelContactQueue} itself lives in the always-compiled {@code erosion} package, not
 * here -- it has no DIV dependency, so it gets real {@code ./gradlew test} coverage rather than
 * being needlessly gated alongside this class (which does need DIV's {@code
 * WheelGroundContactEvent}).
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
        NeoForge.EVENT_BUS.addListener(DivWheelContactConsumer::onServerStarting);
        NeoForge.EVENT_BUS.addListener(DivWheelContactConsumer::onWheelGroundContact);
        NeoForge.EVENT_BUS.addListener(DivWheelContactConsumer::onServerTickPost);
    }

    /**
     * Review finding: {@link #QUEUE}/{@link #DEDUP} are {@code static}, living for the whole
     * mod-loaded JVM -- e.g. across a singleplayer world switch within one running game client,
     * NOT just one server lifetime. Without this reset, a new server's tick counter restarting
     * at a value lower than the previous session's highest-seen tick would make {@link
     * ErosionDedup#tryClaim} throw {@link IllegalStateException} on its very first real call,
     * and any contacts left queued from the previous session (naming dimensions/positions that
     * may no longer even exist) would bleed into the new one.
     */
    static void onServerStarting(ServerStartingEvent event) {
        QUEUE.clear();
        DEDUP.reset();
    }

    /**
     * Snapshot-only: never touches the world. See this class's own doc for why.
     *
     * <p>Converts DIV's real {@code ResourceKey<Level>}/{@code BlockPos}/{@code Direction} down
     * to {@link WheelContactQueue.QueuedContact}'s deliberately Minecraft-free shape at this one
     * boundary -- see that record's own doc for why it stays that way.
     */
    static void onWheelGroundContact(WheelGroundContactEvent event) {
        BlockPos pos = event.blockPos();
        QUEUE.offer(new WheelContactQueue.QueuedContact(event.level().dimension(), pos.getX(), pos.getY(),
                pos.getZ(), event.face() == Direction.UP, event.lateralSlipSpeedMetersPerSecond(),
                event.supportedLoadKg(), event.serverTick()));
    }

    /**
     * The only place that drains the queue and calls {@code ErosionAttempt.attempt}. Gathers the
     * live Minecraft-side facts ({@code dimensionLevelFound}, {@code chunkLoaded}) and defers
     * the actual eligibility decision to {@link WheelContactQueue#shouldAttempt} -- see that
     * method's own doc for exactly what it checks and why (stale tick, missing dimension,
     * unloaded chunk, non-top-face contact). The cast back to {@code ResourceKey<Level>} is
     * safe: {@link #onWheelGroundContact} is the only writer, and it always stores exactly that
     * real type as the opaque {@code dimension} object.
     */
    @SuppressWarnings("unchecked")
    static void onServerTickPost(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        long currentTick = server.getTickCount();
        for (var contact : QUEUE.drainUpTo(MAX_DRAINED_PER_TICK)) {
            BlockPos pos = new BlockPos(contact.x(), contact.y(), contact.z());
            ServerLevel level = server.getLevel((ResourceKey<Level>) contact.dimension());
            boolean chunkLoaded = level != null && level.hasChunkAt(pos);
            if (!WheelContactQueue.shouldAttempt(contact, currentTick, level != null, chunkLoaded)) {
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
