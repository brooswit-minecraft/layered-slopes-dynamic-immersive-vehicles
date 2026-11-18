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
