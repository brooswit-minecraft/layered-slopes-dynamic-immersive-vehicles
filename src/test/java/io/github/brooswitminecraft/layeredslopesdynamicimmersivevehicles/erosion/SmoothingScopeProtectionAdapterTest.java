package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion.ErosionProtectionGate.MutationRequest;

class SmoothingScopeProtectionAdapterTest {

    private final SmoothingScopeProtectionAdapter adapter = new SmoothingScopeProtectionAdapter();

    @Test
    void capabilitiesAreUnconditionallyTrue() {
        assertTrue(adapter.supportsSourceDestinationAuthorization());
        assertTrue(adapter.supportsNeighborCaveInPropagation());
        assertTrue(adapter.describe() != null && !adapter.describe().isBlank());
    }

    @Test
    void grantsAGenuineSingleCardinalStepWithKnownPropagationScope() {
        assertTrue(adapter.authorize(new MutationRequest("minecraft:overworld", 1, 2, 3, 2, 2, 3, true)), "EAST");
        assertTrue(adapter.authorize(new MutationRequest("minecraft:overworld", 1, 2, 3, 0, 2, 3, true)), "WEST");
        assertTrue(adapter.authorize(new MutationRequest("minecraft:overworld", 1, 2, 3, 1, 2, 4, true)), "SOUTH");
        assertTrue(adapter.authorize(new MutationRequest("minecraft:overworld", 1, 2, 3, 1, 2, 2, true)), "NORTH");
    }

    @Test
    void deniesWhenPropagationScopeIsNotKnown() {
        assertFalse(adapter.authorize(new MutationRequest("minecraft:overworld", 1, 2, 3, 2, 2, 3, false)));
    }

    @Test
    void deniesAVerticalStep() {
        assertFalse(adapter.authorize(new MutationRequest("minecraft:overworld", 1, 2, 3, 1, 3, 3, true)));
        assertFalse(adapter.authorize(new MutationRequest("minecraft:overworld", 1, 2, 3, 1, 1, 3, true)));
    }

    @Test
    void deniesADiagonalStep() {
        assertFalse(adapter.authorize(new MutationRequest("minecraft:overworld", 1, 2, 3, 2, 2, 4, true)));
    }

    @Test
    void deniesMoreThanOneBlockAway() {
        assertFalse(adapter.authorize(new MutationRequest("minecraft:overworld", 1, 2, 3, 3, 2, 3, true)));
    }

    @Test
    void deniesTheSamePosition() {
        assertFalse(adapter.authorize(new MutationRequest("minecraft:overworld", 1, 2, 3, 1, 2, 3, true)));
    }
}
