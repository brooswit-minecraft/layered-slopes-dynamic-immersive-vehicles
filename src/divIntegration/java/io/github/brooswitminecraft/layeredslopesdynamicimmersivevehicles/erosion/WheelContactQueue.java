package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Bounded, thread-safe snapshot queue between {@link DivWheelContactConsumer}'s {@code
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
 */
final class WheelContactQueue {
    /** A single, already-converted-to-final-units contact snapshot, safe to hold across ticks. */
    record QueuedContact(ResourceKey<Level> dimension, BlockPos pos, double slipSpeedMs, double wheelLoadKg,
            long queuedServerTick) {
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
}
