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
