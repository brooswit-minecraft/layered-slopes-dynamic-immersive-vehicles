bump: minor

### Added
- The real DIV wheel-contact consumer (direction, comment 55238/M55554611): listens for DIV's
  new `WheelGroundContactEvent` (PR #54, merged into DIV main) and routes it into
  `ErosionAttempt.attempt`, completing the last missing link between a real driven vehicle's
  wheel contact and this mod's erosion policy/config/dedup/protection.
- Split into a snapshot-only event listener and a separate `ServerTickEvent.Post` drain of a new
  bounded `WheelContactQueue`, per the explicit "never invoke terrain mutation synchronously from
  physics/event dispatch" requirement -- the event fires synchronously inside DIV's physics step,
  not from a safe authoritative server-tick stage. Each drained record is re-validated against
  the current world state before use (rejected if stale, the dimension is no longer present, or
  the chunk is no longer loaded) rather than trusted as still accurate just because it was valid
  when enqueued. `ErosionAttempt.attempt` itself still owns every other real check (default-OFF
  gate, same-thread assertion, per-tick dedup, claim/protection, cave-in risk) -- this consumer
  duplicates none of that.
- Because DIV still ships no stable, sha512-pinnable release (`div_version=UNRELEASED`), this
  consumer cannot live in the always-compiled `src/main/java` the way everything else does --
  doing so would fail every ordinary contributor/CI build that doesn't opt into `-PdivLocalJar`.
  It lives in a new `src/divIntegration/java` sourceSet instead, added to the compiled output
  only under that same opt-in flag (the exact same shape `dtPairedRuntime`/
  `DynamicTerrainPairedGameTests` already use), discovered from the always-compiled mod entry
  point via a new `ErosionWheelContactIntegration` `ServiceLoader` seam rather than a direct
  reference -- `ServiceLoader` returns an empty iterator, never an error, when no DIV-aware
  provider is present, so the ordinary build is completely unaffected either way.
- `div-paired-runtime.yml`'s pinned DIV ref is bumped to DIV's current main (post PR #54 merge)
  and its trigger paths extended to cover this new sourceSet, so the existing paired-runtime CI
  job now actually compiles and boots this consumer against the real, merged DIV event class --
  though that job places no vehicle and drives nothing, so it proves compile/boot compatibility
  only, not live contact cadence; that remains a further, still-open gameplay-acceptance step.
- Fixed a real lifecycle bug a review caught before any CI run proved it: `WheelContactQueue`
  and `ErosionDedup` were held as `static` fields living for the whole mod-loaded JVM, not just
  one server lifetime -- across a singleplayer world switch within one running game client, the
  new server's tick counter restarting at a low value would have hit `ErosionDedup#tryClaim`'s
  own regression guard and thrown `IllegalStateException` on its very first real call, and any
  contacts left queued from the previous session (naming dimensions/positions that might no
  longer exist) would have bled into the new one. Added `ErosionDedup#reset()` and
  `WheelContactQueue#clear()`, both called from a new `ServerStartingEvent` listener. Added
  focused `ErosionDedupTest` coverage for the new `reset()` contract.
- Added full focused coverage for the queue/drain contract itself (`WheelContactQueueTest`):
  bounded/drop-newest behavior, FIFO drain, `clear()`, and `shouldAttempt`'s stale-tick/missing-
  dimension/unloaded-chunk/non-top-face rejection -- review finding: "the new WheelContactQueue
  and DivWheelContactConsumer still have no focused tests ... those are explicit MINECRAFT-510
  bounded-queue, lifecycle-isolation, and safe-drain acceptance conditions". Writing these tests
  surfaced a real question the first version silently ignored: "verify whether ignoring the
  preserved contact face is semantically correct." `ErosionAttempt` treats a position as
  horizontal ground being worn down from above, never a generic block face -- a wheel's raycast
  clipping a wall's side is a real possibility DIV's event does not filter out, and eroding a
  wall as if it were ground footing would be wrong. Fixed by rejecting any non-top-face contact
  in `shouldAttempt`.
- To make that testable at all, `WheelContactQueue` moved from `src/divIntegration` to the
  always-compiled `erosion` package and lost its only DIV-shaped field (`ResourceKey<Level>`/
  `BlockPos`/`Direction`), becoming Minecraft-free (`Object` dimension, plain `int` coordinates,
  a single `boolean faceIsUp`) -- matching `ErosionDedup`'s own established convention in this
  exact package, and for the same reason: this project's plain `./gradlew test` has no NeoForge/
  FML classpath at all, so a test referencing a real Minecraft type cannot compile under it.
  `DivWheelContactConsumer` converts at the one real boundary that still needs the real types.
- Added `DivWheelContactConsumerGameTests` (review finding: the queue/drain unit tests above
  never actually exercise `DivWheelContactConsumer`'s own listener wiring, and the paired-runtime
  boot only compiles/registers it against an empty server) -- posts a real `WheelGroundContactEvent`
  onto the real `NeoForge.EVENT_BUS` and lets the real, `ServiceLoader`-registered
  `DivWheelContactConsumer` handle it end to end, asserting that a top-face contact drains into
  exactly one real `ErosionAttempt.attempt` application (`layered_dirt` 15/1 split) and that a
  side-face contact reaches no attempt at all. Only runs under `div-paired-runtime.yml`'s real
  `runGameTestServer` boot, which now expects 23 GameTests (21 default + these 2) via
  `check_gametest_count.py`'s explicit override argument, mirroring `dt-paired-runtime.yml`'s own
  established convention for its paired extra GameTest.
- Fixed three real defects a review caught in that same `DivWheelContactConsumerGameTests` before
  approval: (1) both tests forced the process-wide `ErosionModConfig.OVERRIDES` singleton to an
  enabled guaranteed-chance snapshot and never restored it -- real shared static state also read
  by `ErosionModConfigGameTests` and `ErosionClientSyncTestCommand`, so leaving it mutated would
  have made the whole 23-test paired run order/concurrency-dependent; now captured and restored
  on every synchronous and delayed-assertion path (success or failure); (2) the test's
  `REFERENCE_LOAD_KG = 300` was passed directly as `WheelGroundContactEvent.createIfValid`'s
  `normalForce` argument, which divides by gravity to compute `supportedLoadKg` -- so the test
  was actually publishing ~30.6 kg, not the documented 300 kg reference load; fixed by passing
  `REFERENCE_LOAD_KG * GRAVITY`; (3) the top-face test only asserted final state after a 3-tick
  delay, which did not itself prove the event listener performs no synchronous mutation before
  the safe `ServerTickEvent.Post` drain -- added an immediate assertion right after
  `NeoForge.EVENT_BUS.post(event)` that the source is still unchanged, ahead of the existing
  delayed assertion that it was eventually mutated.
- Fixed a real race in that same `OVERRIDES` restore fix: both tests run in the same default
  GameTest batch and each independently captured/restored the singleton, so an overlapping
  setup/callback could capture the other test's temporary forced value instead of the true
  original, and whichever restored last would decide the final (possibly wrong) state. Replaced
  the two independent capture/restore pairs with a single reference-counted guard
  (`acquireGuaranteedChanceOverride`/`releaseGuaranteedChanceOverride`): only the first acquirer
  captures the real pre-existing snapshot, and only the last releaser restores it, correct
  regardless of how the two tests' ticks interleave. Added a third GameTest,
  `theSharedOverrideGuardLeavesTheConfigExactlyAsFoundAfterBothTestsComplete`, as the
  deterministic post-batch evidence the review explicitly required: it waits past both other
  tests' own completion and asserts the real registered config is back to its real default.
  `div-paired-runtime.yml`'s expected GameTest count is bumped again, 23 -> 24, for this third
  test.
