bump: minor

### Added
- Real driven-wheel gameplay acceptance evidence (MINECRAFT-510's last required slice, direction
  comment 55251/55295: "a genuine Phase 3 raycast contact from an actually-driven vehicle, not a
  forced test command or manually-posted event"). A new `@GameTest`
  (`DivDrivenWheelAcceptanceGameTests`) spawns a real `EntityVehicleF_Physics` with real
  `PartGroundDevice` wheels and a real `PartEngine`, over a real carved platform seeded with 25
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
- A real, driven negative control (`aGenuinelyDrivenVehicleWheelAppliesNoMutationWhenErosionDisabled`,
  review finding, direction comment 55300: "include the actual driven protected/disabled
  unchanged control... rather than using successful boot or manually-posted events as
  substitutes"): the identical real vehicle, grid, and drive maneuver, but with
  `ErosionModConfig#OVERRIDES` left at its real default (`erosionEnabled=false`) -- proving
  genuine driving alone, without the config gate also being on, produces no mutation at all.
- Fixed a real exception-safety gap the same review found: the recursive `driveWithSlipFor`
  drive loop schedules each later tick's callback outside the scenario method's own initial
  try/catch, so an exception from a later tick (or a timeout) could leave the real spawned
  vehicle and the real process-wide `OVERRIDES` singleton behind. Added a small, idempotent
  `TestContext#cleanup()` (a one-shot guard; everything here runs on the single server thread)
  called from every exit path, including each individual recursive drive step.
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
- `div-paired-runtime.yml`'s expected GameTest count bumped 24 -> 26 for these two new tests.
