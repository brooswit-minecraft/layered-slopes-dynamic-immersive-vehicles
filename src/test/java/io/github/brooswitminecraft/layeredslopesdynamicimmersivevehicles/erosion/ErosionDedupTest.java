package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ErosionDedupTest {

    private static final Object OVERWORLD = "minecraft:overworld";
    private static final Object NETHER = "minecraft:the_nether";

    @Test
    void firstClaimForAPositionThisTickSucceeds() {
        ErosionDedup dedup = new ErosionDedup();
        assertTrue(dedup.tryClaim(OVERWORLD, 1, 2, 3, 100));
    }

    @Test
    void secondWheelOrVehicleTargetingTheSameBlockSameTickIsDenied() {
        // Multiwheel/multivehicle dedup: whichever wheel/vehicle claims first wins the tick;
        // every other one targeting the identical block must be denied entirely.
        ErosionDedup dedup = new ErosionDedup();
        assertTrue(dedup.tryClaim(OVERWORLD, 1, 2, 3, 100));
        assertFalse(dedup.tryClaim(OVERWORLD, 1, 2, 3, 100));
        assertFalse(dedup.tryClaim(OVERWORLD, 1, 2, 3, 100));
    }

    @Test
    void aDifferentPositionSameTickIsIndependentlyClaimable() {
        ErosionDedup dedup = new ErosionDedup();
        assertTrue(dedup.tryClaim(OVERWORLD, 1, 2, 3, 100));
        assertTrue(dedup.tryClaim(OVERWORLD, 4, 5, 6, 100));
    }

    @Test
    void theSamePositionInADifferentDimensionSameTickIsIndependentlyClaimable() {
        ErosionDedup dedup = new ErosionDedup();
        assertTrue(dedup.tryClaim(OVERWORLD, 1, 2, 3, 100));
        assertTrue(dedup.tryClaim(NETHER, 1, 2, 3, 100));
    }

    @Test
    void theSamePositionOnTheNextTickIsClaimableAgain() {
        // Bounded lifecycle: a claim from a prior tick must not block the next tick.
        ErosionDedup dedup = new ErosionDedup();
        assertTrue(dedup.tryClaim(OVERWORLD, 1, 2, 3, 100));
        assertFalse(dedup.tryClaim(OVERWORLD, 1, 2, 3, 100));
        assertTrue(dedup.tryClaim(OVERWORLD, 1, 2, 3, 101));
    }

    @Test
    void advancingTheTickBoundsStorageByDiscardingThePriorTicksClaims() {
        ErosionDedup dedup = new ErosionDedup();
        dedup.tryClaim(OVERWORLD, 1, 2, 3, 100);
        dedup.tryClaim(OVERWORLD, 4, 5, 6, 100);
        dedup.tryClaim(NETHER, 7, 8, 9, 100);
        assertEquals(3, dedup.claimedCountForCurrentTick());

        dedup.tryClaim(OVERWORLD, 10, 11, 12, 101);
        // Advancing the tick must have discarded all 3 prior claims, not accumulated a 4th --
        // this is what keeps storage bounded by one tick's worth of distinct positions, not by
        // how long the server has run.
        assertEquals(1, dedup.claimedCountForCurrentTick());
    }

    @Test
    void anAttemptThatWasLaterRejectedStillConsumedItsTicksOneClaim() {
        // The dedup contract covers the ATTEMPT, not the outcome: a caller that claims, then
        // finds the actual roll/mutation rejected (blocked destination, denied authorization,
        // etc), must not retry within the same tick -- simulated here by simply never calling
        // tryClaim a second time after a "rejected" first attempt and confirming it still reads
        // as claimed.
        ErosionDedup dedup = new ErosionDedup();
        boolean claimed = dedup.tryClaim(OVERWORLD, 1, 2, 3, 100);
        assertTrue(claimed);
        // Simulate the roll/mutation being rejected here -- no further action -- then a second
        // wheel/vehicle tries the identical block in the same tick and must still be denied.
        assertFalse(dedup.tryClaim(OVERWORLD, 1, 2, 3, 100));
    }

    @Test
    void nullDimensionIsRejected() {
        ErosionDedup dedup = new ErosionDedup();
        assertThrows(NullPointerException.class, () -> dedup.tryClaim(null, 1, 2, 3, 100));
    }

    @Test
    void aRegressingTickFailsClosedInsteadOfSilentlyReauthorizingADuplicateClaim() {
        // Regression (PR #3 review 54845): the sequence 100, 99, 100 must NOT clear the tick-100
        // claims twice and let the second tick-100 call re-authorize a position already claimed
        // the first time. The regression (tick 99 after 100) must fail loudly instead.
        ErosionDedup dedup = new ErosionDedup();
        assertTrue(dedup.tryClaim(OVERWORLD, 1, 2, 3, 100));
        assertFalse(dedup.tryClaim(OVERWORLD, 1, 2, 3, 100), "sanity: still denied within the same tick 100");

        assertThrows(IllegalStateException.class, () -> dedup.tryClaim(OVERWORLD, 4, 5, 6, 99));

        // The failed regression must not have cleared or otherwise mutated the tick-100 state:
        // the original claim is still denied to a second claimant.
        assertFalse(dedup.tryClaim(OVERWORLD, 1, 2, 3, 100),
                "a rejected tick regression must not have reset tick 100's claims");
    }

    @Test
    void theSameTickRepeatedAfterAnEqualTickIsNotARegression() {
        // tick == highestTickSeen (not strictly greater) must stay within-tick dedup, not throw.
        ErosionDedup dedup = new ErosionDedup();
        assertTrue(dedup.tryClaim(OVERWORLD, 1, 2, 3, 100));
        assertFalse(dedup.tryClaim(OVERWORLD, 1, 2, 3, 100));
        assertTrue(dedup.tryClaim(OVERWORLD, 4, 5, 6, 100));
    }
}
