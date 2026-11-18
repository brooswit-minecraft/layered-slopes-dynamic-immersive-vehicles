package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Bounded, thread-safe snapshot queue between {@code DivWheelContactConsumer}'s {@code
 * WheelGroundContactEvent} listener (which only ever enqueues -- never touches the world) and
 * its {@code ServerTickEvent.Post} drain (the only place that ever calls {@code
 * ErosionAttempt.attempt}), per direction comment 55238/M55554611: "never invoke terrain
 * mutation synchronously from physics/event dispatch."
 *
 * <p>Backed by {@link LinkedBlockingQueue} with a fixed capacity -- {@link #offer} never blocks
 * and simply drops the new record when full (documented choice: a server already producing
 * contact events faster than they can be drained has a backlog problem no amount of buffering
 * fixes; dropping the newest, rather than growing unbounded or blocking the physics step, keeps
 * memory bounded and never stalls physics).
 *
 * <p>Deliberately lives in the always-compiled {@code erosion} package, not {@code
 * divIntegration} -- but ALSO deliberately stays entirely free of any Minecraft/NeoForge
 * reference, matching {@link ErosionDedup}'s own established convention in this exact package
 * ({@code dimension} is an opaque {@link Object} key, positions are plain {@code int} triples,
 * never a real {@code BlockPos}/{@code ResourceKey}/{@code Direction}). This is not merely a
 * style choice here: this project's plain {@code ./gradlew test} task has no NeoForge/FML
 * classpath of its own (see build.gradle's own comment on {@code DynamicTerrainCaveInRiskVersionTest}),
 * so a test file referencing a real Minecraft type simply cannot compile under it -- staying
 * Minecraft-free is what makes {@link WheelContactQueueTest} possible at all.
 */
final class WheelContactQueue {
    /**
     * A single, already-converted-to-final-units contact snapshot, safe to hold across ticks.
     * {@code dimension} is deliberately an opaque {@link Object} key (a real {@code
     * ResourceKey<Level>} at the real call site, compared only by {@code equals}/{@code
     * hashCode}), {@code x}/{@code y}/{@code z} are plain block coordinates, and {@code
     * faceIsUp} collapses DIV's real {@code Direction} down to the one bit {@link
     * #shouldAttempt} actually needs -- see that method's own doc for why only a top-face
     * (ground) contact is eligible at all. None of this needs (or references) any Minecraft
     * type, matching this class's own doc.
     */
    record QueuedContact(Object dimension, int x, int y, int z, boolean faceIsUp, double slipSpeedMs,
            double wheelLoadKg, long queuedServerTick) {
    }

    private final LinkedBlockingQueue<QueuedContact> queue;

    WheelContactQueue(int capacity) {
        this.queue = new LinkedBlockingQueue<>(capacity);
    }

    /** @return {@code true} if enqueued, {@code false} if the queue was full (dropped). */
    boolean offer(QueuedContact contact) {
        return queue.offer(contact);
    }

    /** Removes and returns up to {@code max} queued contacts, oldest first. Never blocks. */
    List<QueuedContact> drainUpTo(int max) {
        List<QueuedContact> drained = new ArrayList<>(Math.min(max, queue.size()));
        QueuedContact next;
        while (drained.size() < max && (next = queue.poll()) != null) {
            drained.add(next);
        }
        return drained;
    }

    /**
     * Discards every currently-queued contact. MINECRAFT-510 review finding: a static instance
     * living for the whole mod-loaded JVM (e.g. across a singleplayer world switch within one
     * running game client) must not let a PRIOR server lifetime's leftover queued contacts --
     * naming dimensions/positions/ticks from a world that may no longer even exist -- bleed into
     * a new one. Callers must call this at the start of each new server lifetime.
     */
    void clear() {
        queue.clear();
    }

    /**
     * Pure decision logic for whether a drained {@code contact} is actually eligible for an
     * {@code ErosionAttempt.attempt} call -- deliberately separated from the live
     * {@code MinecraftServer}/{@code ServerLevel} lookups that produce {@code
     * dimensionLevelFound}/{@code chunkLoaded}, so this contract is fully testable without a
     * live Minecraft environment (review finding M55554611-followup: "this diff contains no
     * consumer/queue tests").
     *
     * <ul>
     *   <li>{@code currentServerTick != contact.queuedServerTick()}: stale -- this record was not
     *       actually produced during the tick being drained right now.
     *   <li>{@code !dimensionLevelFound}: the dimension this contact named is no longer present
     *       on the server at all.
     *   <li>{@code !chunkLoaded}: the chunk at this contact's position is no longer loaded --
     *       {@code ErosionAttempt.attempt} would itself report {@code SOURCE_NOT_LOADED} for
     *       this, but checking here avoids forcing that call for an already-known-unloadable
     *       position.
     *   <li>{@code !contact.faceIsUp()}: review finding ("verify whether ignoring the preserved
     *       contact face is semantically correct") -- {@code ErosionAttempt} treats the
     *       position as a horizontal GROUND surface being worn down from above, never a generic
     *       block face. A wheel's raycast hitting a block's side (e.g. clipping a wall) is a
     *       real possibility DIV's event does not filter out, and eroding a wall as if it were
     *       ground footing would be semantically wrong -- only a real top-face (ground) contact
     *       is eligible.
     * </ul>
     */
    static boolean shouldAttempt(QueuedContact contact, long currentServerTick, boolean dimensionLevelFound,
            boolean chunkLoaded) {
        if (contact.queuedServerTick() != currentServerTick) {
            return false;
        }
        if (!dimensionLevelFound) {
            return false;
        }
        if (!chunkLoaded) {
            return false;
        }
        return contact.faceIsUp();
    }
}
