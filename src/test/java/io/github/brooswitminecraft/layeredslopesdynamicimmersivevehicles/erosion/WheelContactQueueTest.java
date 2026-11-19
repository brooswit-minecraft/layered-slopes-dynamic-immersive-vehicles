package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused coverage for {@link WheelContactQueue}'s bounded/drop-newest, drain, and lifecycle-
 * reset contract, plus {@link WheelContactQueue#shouldAttempt}'s drained-record eligibility
 * decision (MINECRAFT-510 review finding: "the new WheelContactQueue and DivWheelContactConsumer
 * still have no focused tests for bounded/drop-newest and drain behavior, start-of-server
 * clearing of already queued work, or stale/missing-dimension/unloaded records being discarded").
 *
 * <p>Uses a plain {@code String} as the stand-in "dimension" object throughout -- {@link
 * WheelContactQueue} is deliberately Minecraft-free (see its own doc), so this test, like {@link
 * ErosionDedupTest}, runs under plain {@code ./gradlew test} with no NeoForge/FML classpath.
 */
class WheelContactQueueTest {
    private static final Object OVERWORLD = "minecraft:overworld";

    private static WheelContactQueue.QueuedContact contact(int x, boolean faceIsUp, long tick) {
        return new WheelContactQueue.QueuedContact(OVERWORLD, x, 0, 0, faceIsUp, 5.0, 300.0, tick);
    }

    @Test
    void offerThenDrainReturnsTheSameContactInFifoOrder() {
        WheelContactQueue queue = new WheelContactQueue(10);
        var first = contact(0, true, 1);
        var second = contact(1, true, 1);
        assertTrue(queue.offer(first));
        assertTrue(queue.offer(second));

        List<WheelContactQueue.QueuedContact> drained = queue.drainUpTo(10);
        assertEquals(List.of(first, second), drained);
    }

    @Test
    void drainRemovesEveryDrainedContactFromTheQueue() {
        WheelContactQueue queue = new WheelContactQueue(10);
        queue.offer(contact(0, true, 1));
        queue.drainUpTo(10);
        assertEquals(List.of(), queue.drainUpTo(10), "already-drained contacts must not be returned again");
    }

    @Test
    void drainUpToLimitsHowManyAreRemovedPerCall() {
        WheelContactQueue queue = new WheelContactQueue(10);
        queue.offer(contact(0, true, 1));
        queue.offer(contact(1, true, 1));
        queue.offer(contact(2, true, 1));

        assertEquals(2, queue.drainUpTo(2).size());
        assertEquals(1, queue.drainUpTo(2).size(), "the one remaining contact must still be queued");
    }

    @Test
    void offerBeyondCapacityDropsTheNewestRatherThanBlockingOrGrowing() {
        // Documented choice (WheelContactQueue's own doc): drop the newest when full, never
        // block the physics step and never grow unbounded.
        WheelContactQueue queue = new WheelContactQueue(2);
        var first = contact(0, true, 1);
        var second = contact(1, true, 1);
        var third = contact(2, true, 1);
        assertTrue(queue.offer(first));
        assertTrue(queue.offer(second));
        assertFalse(queue.offer(third), "the queue is already at capacity -- the newest offer must be dropped");

        assertEquals(List.of(first, second), queue.drainUpTo(10), "only the first two offers were ever actually queued");
    }

    @Test
    void clearDiscardsEveryCurrentlyQueuedContact() {
        // MINECRAFT-510 review finding: a static instance living for the whole mod-loaded JVM
        // must not let a prior server lifetime's leftover queued contacts bleed into a new one.
        WheelContactQueue queue = new WheelContactQueue(10);
        queue.offer(contact(0, true, 1));
        queue.offer(contact(1, true, 1));
        queue.clear();
        assertEquals(List.of(), queue.drainUpTo(10), "clear() must discard every already-queued contact");
    }

    @Test
    void clearThenOfferStillWorksNormally() {
        WheelContactQueue queue = new WheelContactQueue(10);
        queue.offer(contact(0, true, 1));
        queue.clear();
        var afterClear = contact(5, true, 2);
        assertTrue(queue.offer(afterClear));
        assertEquals(List.of(afterClear), queue.drainUpTo(10));
    }

    @Test
    void shouldAttemptAcceptsAFreshTopFaceContactOnTheCurrentTickWithAFoundLoadedLevel() {
        var fresh = contact(0, true, 100);
        assertTrue(WheelContactQueue.shouldAttempt(fresh, 100, true, true));
    }

    @Test
    void shouldAttemptRejectsAStaleTick() {
        var stale = contact(0, true, 99);
        assertFalse(WheelContactQueue.shouldAttempt(stale, 100, true, true));
    }

    @Test
    void shouldAttemptRejectsAMissingDimension() {
        var fresh = contact(0, true, 100);
        assertFalse(WheelContactQueue.shouldAttempt(fresh, 100, false, true));
    }

    @Test
    void shouldAttemptRejectsAnUnloadedChunk() {
        var fresh = contact(0, true, 100);
        assertFalse(WheelContactQueue.shouldAttempt(fresh, 100, true, false));
    }

    @Test
    void shouldAttemptRejectsANonTopFaceContact() {
        // Review finding: "verify whether ignoring the preserved contact face is semantically
        // correct for terrain attempts rather than silently assuming all contacts are top
        // faces" -- ErosionAttempt treats the position as horizontal ground; a wheel clipping a
        // wall's side is not a ground-erosion candidate, regardless of how fresh/loaded it is.
        var sideContact = contact(0, false, 100);
        assertFalse(WheelContactQueue.shouldAttempt(sideContact, 100, true, true));
    }
}
