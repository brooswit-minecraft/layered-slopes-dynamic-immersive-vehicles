package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Per-tick dedup: at most one erosion roll-and-mutation attempt per (dimension, block position)
 * per server tick, across however many wheels or vehicles would otherwise target that same
 * block this tick -- per the ticket's acceptance requirement, counting a REJECTED roll (blocked
 * destination, denied authorization, etc) as having consumed that tick's single attempt, not
 * just a successful one. Callers must call {@link #tryClaim} before drawing the RNG roll or
 * calling {@code Smoothing.smooth()} at all, and only proceed if it returns {@code true}.
 *
 * <p>{@code dimension} is deliberately an opaque {@link Object} key (e.g. a real
 * {@code ResourceKey<Level>}, compared by {@code equals}/{@code hashCode}) so this class stays
 * free of any Minecraft/NeoForge reference, matching the rest of this package.
 *
 * <p>Bounded lifecycle storage: state is scoped to exactly the current tick, never accumulating
 * across ticks. Calling {@link #tryClaim} with a tick number different from the one most
 * recently seen clears all prior claims first, so memory use is bounded by the number of
 * distinct (dimension, position) pairs actually touched within a single tick, not by how long
 * the server has been running. Assumes {@code tick} values are non-decreasing across calls
 * (true of a real server's tick counter); a tick value that regresses is treated as a new tick
 * and clears prior state, which would be incorrect in that scenario -- callers must only pass a
 * real, monotonic tick counter.
 */
public final class ErosionDedup {
    private record Key(Object dimension, int x, int y, int z) {}

    private long currentTick = Long.MIN_VALUE;
    private boolean hasCurrentTick = false;
    private final Set<Key> claimedThisTick = new HashSet<>();

    /**
     * @return {@code true} if this (dimension, x, y, z) had not yet been claimed this tick
     *     (the caller may proceed with its roll/mutation attempt), {@code false} if something
     *     else already claimed it this tick (the caller must skip entirely -- no roll, no
     *     mutation, not even a rejected one, since that one attempt already happened).
     */
    public synchronized boolean tryClaim(Object dimension, int x, int y, int z, long tick) {
        Objects.requireNonNull(dimension, "dimension");
        if (!hasCurrentTick || tick != currentTick) {
            currentTick = tick;
            hasCurrentTick = true;
            claimedThisTick.clear();
        }
        return claimedThisTick.add(new Key(dimension, x, y, z));
    }

    /** The number of distinct (dimension, position) claims recorded for the current tick only. */
    public synchronized int claimedCountForCurrentTick() {
        return claimedThisTick.size();
    }
}
