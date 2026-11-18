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
 * across ticks. Calling {@link #tryClaim} with a tick number greater than the highest one seen
 * so far clears all prior claims first, so memory use is bounded by the number of distinct
 * (dimension, position) pairs actually touched within a single tick, not by how long the
 * server has been running.
 *
 * <p>Fails closed on a regressing tick (review 54845 on an earlier version of this class: a
 * sequence like 100, 99, 100 would clear the tick-100 claims when 99 arrived, then clear AGAIN
 * on the second 100 and silently re-authorize a position already claimed the first time --
 * documenting "callers must pass a monotonic tick" is not the same as actually handling a
 * violation). {@link #tryClaim} now throws {@link IllegalStateException} the moment a tick
 * lower than the highest one already seen is passed, before touching any state, rather than
 * silently clearing and allowing a duplicate claim to slip through. A single authoritative tick
 * source must be used across every dimension in the real consumer; per-world clocks that could
 * diverge are not a correct caller of this class.
 */
public final class ErosionDedup {
    private record Key(Object dimension, int x, int y, int z) {}

    private long highestTickSeen = Long.MIN_VALUE;
    private boolean hasSeenATick = false;
    private final Set<Key> claimedThisTick = new HashSet<>();

    /**
     * @return {@code true} if this (dimension, x, y, z) had not yet been claimed this tick
     *     (the caller may proceed with its roll/mutation attempt), {@code false} if something
     *     else already claimed it this tick (the caller must skip entirely -- no roll, no
     *     mutation, not even a rejected one, since that one attempt already happened).
     * @throws IllegalStateException if {@code tick} is lower than the highest tick already seen
     *     -- see the class doc. No state is modified when this is thrown.
     */
    public synchronized boolean tryClaim(Object dimension, int x, int y, int z, long tick) {
        Objects.requireNonNull(dimension, "dimension");
        if (hasSeenATick && tick < highestTickSeen) {
            throw new IllegalStateException("tick " + tick + " regressed behind the highest tick already seen ("
                    + highestTickSeen + ") -- ErosionDedup requires a single, monotonic, authoritative tick source");
        }
        if (!hasSeenATick || tick > highestTickSeen) {
            highestTickSeen = tick;
            hasSeenATick = true;
            claimedThisTick.clear();
        }
        return claimedThisTick.add(new Key(dimension, x, y, z));
    }

    /** The number of distinct (dimension, position) claims recorded for the current tick only. */
    public synchronized int claimedCountForCurrentTick() {
        return claimedThisTick.size();
    }

    /**
     * Clears all tracked state, so the next {@link #tryClaim} call -- whatever tick it passes --
     * is treated as the first one ever seen, never a regression. A caller holding this instance
     * across more than one server lifetime (MINECRAFT-510 review finding: a static instance
     * living for the whole mod-loaded JVM, e.g. across a singleplayer world switch within one
     * running game client, would otherwise see the new server's tick counter restart at a value
     * lower than the previous session's {@code highestTickSeen} and hit the regression guard's
     * {@link IllegalStateException} on its very first real call) MUST call this at the start of
     * each new server lifetime, before any {@link #tryClaim} call from that new lifetime.
     */
    public synchronized void reset() {
        highestTickSeen = Long.MIN_VALUE;
        hasSeenATick = false;
        claimedThisTick.clear();
    }
}
