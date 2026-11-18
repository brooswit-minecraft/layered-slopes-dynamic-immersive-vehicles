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
      (v0.2.0 -- bumped from an initially-pinned v0.1.0 that predated `SmoothingAuthorizationEvent`
      entirely, caught by review 54899/54901 via direct `unzip -l` inspection of the actual
      release jar, not just its source tag). `StandaloneBootGameTests` confirms a real boot via
      `./gradlew runGameTestServer` (0 mod requirements missing), wired into CI with
      `scripts/check_gametest_count.py`.
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
      (released starting with that mod's v0.2.0, which this mod is pinned to). Not consumed by
      this mod's own protection design below -- see why there.
- [x] `ErosionDedup`: at most one roll-and-mutation attempt per dimension/block/tick, including a
      rejected attempt, with bounded per-tick lifecycle storage. Pure logic, no Minecraft/NeoForge
      dependency.
- [x] Real, unconditional, directly-enforced protection -- not an optional adapter. Three earlier
      attempts in this area (`ErosionProtectionGate`/`ProtectionAdapter`, reviewed in PR #3, and
      two different adapters built against it) were each separately found to claim a capability
      the code did not actually verify (review 54855/54861/54878/54890/54899/54901) -- including
      the realization that `ErosionProtectionGate.MutationRequest` is deliberately Minecraft-free
      (kept pure-unit-testable), which cannot host a real per-attempt check that needs a live
      `ServerLevel`/`BlockPos`. The current design instead makes two real checks mandatory,
      unconditional steps inside `ErosionAttempt` itself:
      - `ClaimProtection`: the same real, already-accepted pattern
        [`layered-slopes-flowing-fluids`' `Protection.mayErode`](https://github.com/brooswit-minecraft/layered-slopes-flowing-fluids/blob/main/src/main/java/io/github/brooswitminecraft/layeredslopesflowingfluids/Protection.java)
        uses: post a real `BlockEvent.BreakEvent` through a fake player and honor cancellation,
        applied at both source and destination -- plus the server's own applicable built-in
        constraints a `BreakEvent` check alone does not establish: the world border
        (`ServerLevel#getWorldBorder()`) and spawn protection
        (`MinecraftServer#isUnderSpawnProtection`). Best-effort for third-party claim mods (covers
        ones that listen on `BreakEvent`), not a guarantee against every possible hook; a preflight
        check only, and explicitly not a claim of placement-hook-equivalent semantics at the
        destination.
      - `DynamicTerrainCaveInRisk`: Dynamic Terrain's cave-ins are the one concretely-named
        external propagation risk reviews identified. An earlier, narrower version of this check
        tried to exclude source/destination from a face-adjacent-neighbor tag scan on the theory
        that they become untagged `layeredslopes:*` blocks -- review 54907/54908 found that rested
        on today's shipped tag contents and today's call ordering, either of which a runtime
        datapack or mod update could change, and that it treated an unloaded neighbor as
        proven-safe rather than unknown. Rather than keep refining an increasingly fragile
        exclusion rule, this now makes the one claim it can fully verify: if Dynamic Terrain is
        loaded at all, every attempt is denied (reads only DT's mod id as data, never its Java
        classes, the same optional-dependency shape `GroundSurfaces.java` already uses for Layered
        Slopes). If DT is not loaded, there is no such risk.
      `ErosionAttempt`'s production path is: dedup claim, the accepted MINECRAFT-555
      slip-to-probability roll, a shuffled-direction-first-`canSmooth`-accepted candidate, both
      real checks above at that candidate's source and destination, a re-verified `canSmooth`
      (a synchronous `ClaimProtection` listener could have mutated the world as a side effect;
      `Smoothing.smooth()` itself also re-derives and compares its own plan before applying, per
      grading's existing TOCTOU fix, so this is defense in depth, not the only safety net), then
      `Smoothing.smooth()`. Takes a `ServerLevel` (a client-side call cannot even be constructed)
      and asserts `level.getServer().isSameThread()` before touching anything -- the type alone
      does not rule out an off-thread call. Derives dimension/tick from the level/its server,
      never from a caller-supplied value; every candidate position's chunk is confirmed loaded
      before any block query, never force-loading one. Takes slip/load telemetry as plain
      parameters; makes no assumption about where it comes from, since DIV's own per-wheel
      contact-telemetry seam is not wired in yet (see below). `ErosionAttemptGameTests` (14 cases)
      prove this against a real world: a real move applying and conserving material with no
      claim mod/Dynamic Terrain installed (the "missing/unsupported integration" success case), a
      protected source, a protected destination, and a detected cave-in risk each denying (via
      injected orchestration-seam fakes), a REAL `BlockEvent.BreakEvent` listener cancelling at
      source and at destination each denying via the actual production path, a direct check that
      the real `DynamicTerrainCaveInRisk` reports no risk when Dynamic Terrain is genuinely
      absent, per-tick dedup, a denied attempt and a failed roll each still consuming the dedup
      slot, no-valid-direction, an off-thread call denied (bounded join with safe worker
      interrupt/cleanup on timeout) before touching anything, an unloaded source denied without
      force-loading it, and a deterministic instrumented-seam test proving `Smoothing.canSmooth`
      itself is never even queried for an unloaded destination -- not just "no mutation
      happened," which a reordered implementation could still satisfy; verified by temporarily
      reordering the production check, confirming the test fails, then reverting.
- [x] `ErosionProtectionGate`/`ProtectionAdapter` (merged PR #3) remains in the codebase, valid on
      its own terms, but is no longer part of `ErosionAttempt`'s real path for the reason above --
      its Minecraft-free abstraction cannot host a real world-aware check.
- [ ] DIV's actual per-wheel call site into `ErosionAttempt` (needs DIV to expose contact
      telemetry -- blocked on MINECRAFT-545 PR #39's active `PhysicsBridge.java` changes, see
      above), an explicit default-OFF config switch before any real erosion can apply,
      multiplayer sync, dependency-failure tests, pinned-jar smoke acceptance, and gameplay
      acceptance evidence.

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
installed and runs the 15 GameTests (boot-only, plus `ErosionAttemptGameTests`' 14 real-world
orchestration cases). Wired into CI via `scripts/check_gametest_count.py`, which fails closed if
the expected test count regresses. An opt-in `div-paired-runtime.yml` workflow additionally
builds DIV fresh and boots everything together at DIV's real NeoForge floor.
