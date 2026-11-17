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
- [ ] NeoForge mod scaffold (main mod class, `neoforge.mods.toml`, DIV/layered-slopes/
      layered-slope-grading download-and-verify dependencies). Deferred: no stable, sha512-
      pinnable DIV release exists yet (`gh release list` on that repo returns only private
      "not for publishing" draft test-builds).
- [ ] DIV-side generic contact-telemetry seam (per-wheel slip/load payload). Deferred: DIV PR #39
      (MINECRAFT-545, hitch support) is an active, in-flight PR currently touching
      `PhysicsBridge.java`; this mod will not edit that file until #39 lands or is explicitly
      sequenced/rebased against.
- [ ] layered-slope-grading-side generic safe-mutation/protection seam (cancellable authorization
      hook around `Smoothing.smooth`, covering source/destination and neighbor/cave-in effects).
      Not yet reviewed or implemented; `Smoothing.smooth` as of
      `7c12f690ba4ef39f2dd5d793bd8cdf1cb3e7ec2f` has no permission hook.
- [ ] Erosion consumer (server-thread authoritative, per-tick dedup, multiplayer sync, default
      OFF), protection gate, GameTest acceptance evidence, CI.

No dependency on Dynamic Terrain or Dynamic Vehicles exists or is planned. The erosion consumer
will default to OFF; operators running this mod alongside Dynamic Terrain's deprecated vehicle
erosion should disable DT's `vehicleErosion` to avoid double effects.

## Building

```
./gradlew build
```

Only the pure `erosion` package exists today; `./gradlew test` runs its unit tests with no
external mod dependency required.
