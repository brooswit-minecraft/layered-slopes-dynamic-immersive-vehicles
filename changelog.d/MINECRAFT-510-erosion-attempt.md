bump: minor

### Added
- `ErosionAttempt`: the real orchestration of a single per-position erosion attempt --
  `ErosionDedup` claim, the accepted MINECRAFT-555 slip-to-probability roll, a shuffled
  horizontal-direction-first-`canSmooth`-accepted call into `Smoothing.smooth()`, and the
  `ErosionProtectionGate` check before any world mutation. Takes slip/load telemetry as plain
  parameters -- it makes no assumption about where that telemetry comes from, since DIV's own
  per-wheel contact-telemetry seam is not wired in yet (still blocked on MINECRAFT-545 PR #39's
  active `PhysicsBridge.java` changes). Every real `authorize()` call is still denied by
  construction, since no production protection adapter exists yet -- this orchestration does not
  itself claim any gameplay/protection acceptance.
- `ErosionAttemptGameTests`: 4 real-world acceptance cases -- protection-gate denial leaves the
  world unmutated even when the roll and direction would otherwise succeed, a granting adapter
  lets a real move apply, per-tick dedup rejects a second wheel targeting the same block in the
  same tick, and no-valid-direction when every horizontal neighbor blocks the move.
