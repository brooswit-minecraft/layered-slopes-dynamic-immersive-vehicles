bump: minor

### Added
- Real driven-wheel gameplay acceptance evidence (MINECRAFT-510's last required slice, direction
  comment 55251/55295: "a genuine Phase 3 raycast contact from an actually-driven vehicle, not a
  forced test command or manually-posted event"). A new `@GameTest`
  (`DivDrivenWheelAcceptanceGameTests`) spawns a real `EntityVehicleF_Physics` with real
  `PartGroundDevice` wheels and a real `PartEngine`, over a real carved platform seeded with 36
  independently-valid erosion-source cells, and drives it with direct writes to its own real
  public input variables (`throttleVar`/`rudderInputVar`) -- the exact, already-reviewed-and-
  proven methodology DIV's own `GroundSupportFixtureProbe` established for this purpose: no
  player, no datapack, no forced command, no manually-constructed event. The vehicle's own real
  per-tick `moveVehicle` genuinely runs through the unmodified production path
  (`VehicleMovementMixin` -> `PhysicsBridge.step` -> `Body.stepGround`), generating real
  wheel-ground raycast contacts that flow through the real, merged `WheelGroundContactEvent` ->
  `DivWheelContactConsumer` -> `ErosionAttempt` chain exactly as a real player-driven vehicle
  would produce them. Succeeds only if at least one prepared cell shows the real, conserved
  mutation shape (source `LayeredBlock.LAYERS == 15`, its own east neighbor `== 1`), not merely
  "any layered block exists."
- A real, driven negative control, in the SAME test method, strictly sequential after the
  enabled phase (review finding, direction comment 55300: "include the actual driven
  protected/disabled unchanged control... rather than using successful boot or manually-posted
  events as substitutes"): the identical real vehicle, grid, and drive maneuver, but with
  `ErosionModConfig#OVERRIDES` genuinely back at its real default (`erosionEnabled=false`) --
  proving genuine driving alone, without the config gate also being on, produces no mutation at
  all. Deliberately ONE test method running both phases sequentially, not two separate
  `@GameTest` methods -- see below.
- Real review finding (direction comment 55301/55304): an earlier revision ran the enabled and
  disabled phases as two separate `@GameTest` methods sharing a batch name. A real CI failure
  proved that distinct-batch-name separation does not reliably serialize a long-running test
  (200+ ticks) against its own batch-mates, and a second real CI failure (independently also
  flagged by review) showed the two opposing scenarios' own batch could itself interleave,
  letting the disabled scenario observe/restore the enabled one's forced config. Both findings
  are structural batch-timing issues, not something a bigger reference count alone fixes.
  Resolved by running both phases as ONE strictly sequential `runAfterDelay` chain within a
  SINGLE `@GameTest` method: phase two is only ever scheduled after phase one has entirely
  finished (vehicle removed, override released), removing any dependency on GameTest's own
  batch-advance timing.
- Extracted the reference-counted `OVERRIDES` guard (previously private to
  `DivWheelContactConsumerGameTests`) into a new, shared, always-compiled
  `GuaranteedChanceOverrideGuard`, and moved this test into that same
  `batch = "divWheelContactConsumerOverrideBatch"` -- both classes' real-wiring tests are now
  correctly serialized against each other by the SAME reference count, not by trusting
  batch-name separation alone.
- Fixed a real exception-safety gap (direction comment 55300): the recursive drive loops
  schedule each later tick's callback outside the enclosing method's own initial try/catch, so
  an exception from a later tick could leave the real spawned vehicle behind. Added a small,
  idempotent `TestContext#cleanup()` (a one-shot guard; everything here runs on the single
  server thread) called from every exit path this class's own code can throw from. Disclosed,
  NOT yet covered (direction comment 55304, correctly flagged as an open item rather than
  claiming full coverage): a genuine GameTest timeout, or a `spawnVehicle` call throwing
  partway through construction, bypasses this class's own exception handling entirely.
- Real, hard-won tuning history from actual CI runs, not guessed: applying throttle and a
  rudder pulse together from a standstill stalls the vehicle to near-zero motion (unlike
  `GroundSupportFixtureProbe`'s own proven sequence, which always drives straight first);
  100 ticks of straight driving at the real ~4-5 m/s this vehicle reaches overshoots this
  20-wide platform entirely. Settled on: drive straight for just long enough to get moving (20
  ticks), then add a steady rudder pulse while already cruising (150 ticks).
- New `src/divPairedRuntime` sourceSet (gated on both `divJar != null` AND `pairedRuntime`,
  mirroring `dtPairedRuntime`'s own two-condition shape): this test needs Minecraft Transport
  Simulator's own vehicle/part classes at compile time, unlike the rest of `src/divIntegration`,
  which never references MTS types directly. Compiles/runs only under
  `div-paired-runtime.yml`'s existing `-PdivLocalJar -PpairedRuntime=true` invocation -- never
  the plain `ci.yml` build or the plain paired-runtime smoke test alone.
- New `driven_wheel_platform` structure (20x5x20, a solid stone floor with 4 blocks of headroom)
  replacing the existing 3x3x3 `flat_platform` template, which is far too small for a vehicle
  to realistically settle, drive, and turn.
- Seeds this test's own DIV `Overrides` entry (`divtest:acceptance`, massKg/upperMassFraction)
  before boot, the same on-disk-config mechanism DIV's own `GroundSupportFixtureProbe` CI job
  relies on. Also enabled DIV's own `-Ddiv.debugGroundContact=true` diagnostic for this boot,
  which independently confirmed the real `PhysicsBridge.Body` (not a native-MTS fallback) does
  activate for this vehicle, with the registered override applied
  (`chassisReceipt ... explicitOverrideEntry=true mass=350.0`).
- `div-paired-runtime.yml`'s expected GameTest count bumped 24 -> 25 for this new test.
- Real review/CI finding, this exact head (run 44451489283): the merged single-method test ran
  clean (chassisReceipt/classify confirmed real `Body` activation, real ~5 m/s straight-line
  speed) but still found zero conserved mutations. Examining the real logged position deltas
  showed why: this vehicle's own TURN phase, like `GroundSupportFixtureProbe`'s own (a bounded
  "low-speed steer", not a translating turn -- see that probe's own BRAKE/TURN comments),
  decelerates from real speed to a near-stationary skid within only a handful of ticks of
  rudder input, then stays essentially parked (net movement across the entire 150-tick phase
  measured well under a block). Almost all of this test's real distance happens during the
  short straight run, and the one real skid moment is a single (x,z) spot, not something swept
  across the grid. That spot was directly between this chassis's own two nearest prepared grid
  columns (x=8 and x=11) -- one block off either, and this chassis's own footprint (~0.85 wide)
  cannot reach either from there. Fixed by spawning directly ON grid column x=11 instead of
  between columns, so the real straight-line run (and the real skid at the end of it) stays
  directly over that column's own prepared z=2,5,8,11,14,17 cells throughout. Also fixed a real
  logging gap found during this diagnosis (the straight phase's own 20-tick length is never
  divisible by 30, so it never actually logged) and tightened the turn phase's own cadence to
  10 ticks (30 missed the exact moment speed collapsed) for better visibility on any future
  failure.
- Real CI failure AGAIN on that exact fix (run 44452612622): this time the vehicle kept real,
  nonzero speed throughout the turn (a curving loop, not a stall), yet `anyAppliedPair` was
  still `false`. The absolute world coordinates this class was logging (around 1.05e7, since
  GameTest places each structure far from the origin) could not be checked against
  {@code buildSourceGrid}'s own local 0-19 cell coordinates at all -- there was no way to tell
  from that log alone whether the real path ever actually crossed a prepared cell. Added
  {@code localPos} to every log line (the vehicle's real position minus the structure's own
  real origin, from {@code helper.absolutePos(BlockPos.ZERO)}), directly comparable against the
  grid's own coordinates on the next real run.
- Real review findings (Jira comment 55306), fixed independently of the acceptance evidence
  above: (1) `theSharedOverrideGuardLeavesTheConfigExactlyAsFoundAfterBothTestsComplete`'s own
  fixed 10-tick wait was a guess that a batch does not actually guarantee outlasts every other
  real acquirer sharing its guard -- including this test's own ~200-tick drive, whose duration
  that observer has no way to know. It now polls a new, real
  `GuaranteedChanceOverrideGuard.isFullyReleased()` signal every tick instead (bounded by its own
  explicit `timeoutTicks`), which also tracks a real total-acquisition count so "every acquirer
  already finished" can't be confused with "no acquirer has started yet" (both look identical as
  plain `activeUsers == 0`). (2) The guard's `release()` for the enabled phase previously only
  ran from that phase's own success path; any assertion or recursion-step failure would remove
  the vehicle but leave the guard's acquisition held forever. Tied to `TestContext#cleanup()`
  instead, which already runs from every one of this class's own exit paths.
