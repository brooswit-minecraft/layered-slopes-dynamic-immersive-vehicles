# Layered Slopes Dynamic Immersive Vehicles

A dedicated compatibility mod providing traction-loss erosion between
[Dynamic Immersive Vehicles](https://github.com/brooswit-minecraft/dynamic-immersive-vehicles)
and [Layered Slopes](https://github.com/brooswit-minecraft/layered-slopes) (via
[Layered Slope Grading](https://github.com/brooswit-minecraft/layered-slope-grading)'s
`Smoothing` service). Erosion policy, tuning, RNG/dedup, and all integration/compat adapters are
owned entirely by this mod: neither DIV nor Layered Slopes gains a dependency on the other, and
neither depends on Dynamic Terrain or Dynamic Vehicles.

Tracked as [MINECRAFT-510](https://wroosbit.atlassian.net/browse/MINECRAFT-510). The accepted
erosion contract (formula, defaults, bounds, acceptance matrix) was produced as prep work on
[MINECRAFT-555](https://wroosbit.atlassian.net/browse/MINECRAFT-555) (comments 54607, 54611,
54614, 54618; approved 54622/54623).

## Status

This is a bounded, multi-slice implementation. Current slice:

- [x] Repo scaffold (this PR)
- [x] Pure erosion policy/config module (`erosion` package): deterministic slip/load/resistance
      mapping, immutable config snapshot with atomic-rejected hot reload, matching the accepted
      MINECRAFT-555 contract defaults and acceptance-matrix boundary cases. No Minecraft/NeoForge
      dependency, no RNG draw, no world mutation — pure functions and unit tests only.
- [x] NeoForge mod scaffold: main mod class, `neoforge.mods.toml`, pinned (sha512-verified)
      download-and-verify dependencies on `layered-slopes` (v0.2.0) and `layered-slope-grading`
      (v0.1.0). Confirmed booting for real via `./gradlew runGameTestServer` (loads with 0 of 9
      mandatory mod requirements missing).
- [ ] DIV dependency and its contact-telemetry seam (per-wheel slip/load payload). Deferred: DIV
      ships no stable, sha512-pinnable GitHub Release at all (`gh release list` on that repo
      returns only private "not for publishing" draft test-builds), so it is not yet declared as
      a build-time dependency here (would make the default `./gradlew build` fail closed for
      everyone). Also, DIV PR #39 (MINECRAFT-545, hitch support) is an active, in-flight PR
      currently touching `PhysicsBridge.java`; this mod will not edit that file until #39 lands
      or is explicitly sequenced/rebased against.
- [x] layered-slope-grading-side generic safe-mutation/protection seam: `SmoothingAuthorizationEvent`,
      a cancellable NeoForge event fired before `Smoothing.smooth()` mutates the world, merged in
      [layered-slope-grading PR #2](https://github.com/brooswit-minecraft/layered-slope-grading/pull/2)
      (released as part of that mod's next version). Does NOT by itself cover resulting
      neighbor/cave-in propagation after an authorized move, and no existing third-party
      claim/protection mod automatically gains coverage from it — both explicitly named gaps for
      whichever erosion consumer slice needs that coverage to implement and test it.
- [x] Protection/dedup policy primitives: `ErosionDedup` (at most one roll-and-mutation attempt
      per dimension/block/tick, including a rejected attempt, with bounded per-tick lifecycle
      storage) and `ErosionProtectionGate` (a hard, code-level gate defaulting to NOT asserted
      -- fails closed -- that the erosion consumer must check before any real world mutation;
      opening it requires an explicit, specific coverage description, never a vague claim).
      Pure logic, no Minecraft/NeoForge dependency; the gate still records that full
      propagation/neighbor/cave-in coverage is NOT yet implemented or asserted.
- [ ] Erosion consumer itself (server-thread authoritative, wiring the policy primitives above
      together with real per-wheel telemetry and `Smoothing.smooth()`, multiplayer sync, default
      OFF), the explicit propagation/claim-adapter protection implementation the gate above is
      currently withholding, GameTest acceptance evidence for actual gameplay behavior,
      dependency-failure tests, pinned-jar smoke acceptance.

No dependency on Dynamic Terrain or Dynamic Vehicles exists or is planned. The erosion consumer
will default to OFF; operators running this mod alongside Dynamic Terrain's deprecated vehicle
erosion should disable DT's `vehicleErosion` to avoid double effects.

## Building

```
./gradlew build
```

Downloads and sha512-verifies real `layered-slopes`/`layered-slope-grading` release jars on
first run. The pure `erosion` package's own tests (`./gradlew test`) need neither of those --
they are plain JUnit 5 tests with no Minecraft/NeoForge dependency.

```
./gradlew runGameTestServer
```

Boots a real dedicated server with this mod, `layered-slopes`, and `layered-slope-grading`
installed. No GameTests exist yet (nothing to test until the erosion consumer lands), so this is
currently just a real-boot smoke check, not run in CI yet -- see `scripts/check_gametest_count.py`.
