package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

/**
 * Shared reference-counted guard around forcing {@link ErosionModConfig#OVERRIDES} to {@link
 * ErosionAttemptGameTests#GUARANTEED_CHANCE_CONFIG} for the duration of a real-wiring
 * {@code @GameTest}. Originally a private, per-class static in {@code
 * DivWheelContactConsumerGameTests} (review findings M55554611-followup/-2, Jira comments
 * 55269/55274, GitHub comments 6995553666/6995981468): only the FIRST acquirer captures the true
 * pre-existing snapshot, and only the LAST releaser restores it, correct regardless of how any
 * number of overlapping tests' ticks interleave.
 *
 * <p>Pulled out to this shared, always-compiled class (MINECRAFT-510, direction comment 55301)
 * after a real CI failure proved the per-class guard was not enough on its own: {@code
 * DivDrivenWheelAcceptanceGameTests} (a SEPARATE class, in the DIV-paired-runtime-only {@code
 * src/divPairedRuntime} sourceSet, needing a much longer real drive window than the few-tick
 * tests above) was given its OWN separate {@code batch} name under the assumption that distinct
 * batch names fully serialize against each other -- confirmed, the hard way, NOT reliably true
 * for a long-running test: {@code theSharedOverrideGuardLeavesTheConfigExactlyAsFoundAfterBothTestsComplete}
 * (in the batch below) failed because the driven-wheel test's own independent guard/batch
 * overlapped with it regardless. Both classes now share this ONE guard AND the SAME {@code
 * batch = "divWheelContactConsumerOverrideBatch"}, so any number of tests from either class that
 * happen to interleave are correctly serialized by the same reference count, not by trusting
 * batch-name separation alone.
 */
final class GuaranteedChanceOverrideGuard {
    private GuaranteedChanceOverrideGuard() {
    }

    private static int activeUsers = 0;
    private static ErosionConfig originalOverrideBeforeAnyUser;

    /**
     * Forces {@link ErosionModConfig#OVERRIDES} to {@link
     * ErosionAttemptGameTests#GUARANTEED_CHANCE_CONFIG}. Safe to call from any number of
     * overlapping callers (even across different classes/tests): only the first call (while the
     * count is zero) captures the real pre-existing snapshot -- a later, overlapping call sees
     * the count already above zero and does NOT overwrite that captured snapshot with the
     * already-forced value.
     */
    static void acquire() {
        if (activeUsers == 0) {
            originalOverrideBeforeAnyUser = ErosionModConfig.OVERRIDES.current();
        }
        activeUsers++;
        ErosionModConfig.OVERRIDES.reload(ErosionAttemptGameTests.GUARANTEED_CHANCE_CONFIG);
    }

    /**
     * Releases one acquisition. Only restores the real pre-existing snapshot once the count
     * returns to zero -- i.e. only once EVERY overlapping caller has released -- so an
     * earlier-finishing caller can never prematurely restore the override while a
     * later-finishing one still needs it forced on.
     */
    static void release() {
        activeUsers--;
        if (activeUsers == 0) {
            ErosionModConfig.OVERRIDES.reload(originalOverrideBeforeAnyUser);
        }
    }
}
