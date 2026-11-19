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
  would produce them. Succeeds only if at least one of the 25 prepared cells shows a real,
  nonzero `LayeredBlock.LAYERS` mutation afterward.
- New `src/divPairedRuntime` sourceSet (gated on both `divJar != null` AND `pairedRuntime`,
  mirroring `dtPairedRuntime`'s own two-condition shape): this test needs Minecraft Transport
  Simulator's own vehicle/part classes at compile time, unlike the rest of `src/divIntegration`,
  which never references MTS types directly. Compiles/runs only under
  `div-paired-runtime.yml`'s existing `-PdivLocalJar -PpairedRuntime=true` invocation -- never
  the plain `ci.yml` build or the plain paired-runtime smoke test alone.
- New `driven_wheel_platform` structure (20x5x20, a solid stone floor with 4 blocks of headroom)
  replacing the existing 3x3x3 `flat_platform` template, which is far too small for a vehicle
  to realistically settle, drive, and turn.
- `div-paired-runtime.yml`'s expected GameTest count bumped 24 -> 25 for this new test.
