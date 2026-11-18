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
      (released as part of that mod's next version). Does NOT by itself cover resulting
      neighbor/cave-in propagation after an authorized move, and no existing third-party
      claim/protection mod automatically gains coverage from it — both explicitly named gaps for
      whichever erosion consumer slice needs that coverage to implement and test it.
- [x] Protection/dedup policy primitives: `ErosionDedup` (at most one roll-and-mutation attempt
      per dimension/block/tick, including a rejected attempt, with bounded per-tick lifecycle
      storage) and `ErosionProtectionGate`, which separates READINESS (`isReady()`: is a capable
      `ProtectionAdapter` installed) from the actual per-attempt decision (`authorize(request)`):
      the gate itself fails closed on missing/unknown mandatory request context (null dimension,
      unknown propagation scope) before ever consulting the adapter, and otherwise still requires
      the adapter's own per-request grant -- `isReady()` alone is never permission to mutate.
      Pure logic, no Minecraft/NeoForge dependency. No production adapter exists yet, so every
      real `authorize()` call is denied by construction; full propagation/neighbor/cave-in
      coverage remains unimplemented.
- [x] `ErosionAttempt`: the real per-position orchestration -- dedup claim, the accepted
      MINECRAFT-555 slip-to-probability roll, a shuffled-direction-first-`canSmooth`-accepted
      call into `Smoothing.smooth()`, and the `ErosionProtectionGate` check before any world
      mutation. Takes a `ServerLevel` (a client-side call cannot even be constructed) and asserts
      `level.getServer().isSameThread()` before touching anything -- the type alone does not rule
      out an off-thread call. Derives dimension/tick from the level/its server, never from a
      caller-supplied value; every candidate position's chunk is confirmed loaded before any
      block query, never force-loading one. Every real request this builds has
      `propagationScopeKnown=false` -- no neighbor/cave-in consequence analysis exists yet, so
      every real call is denied by construction regardless of whether a production adapter
      exists (see above). Takes slip/load telemetry as plain parameters; makes no assumption
      about where it comes from, since DIV's own per-wheel contact-telemetry seam is not wired in
      yet (see below). `ErosionAttemptGameTests` (10 cases: default-closed denial, a granting
      adapter still denied because propagation scope is never claimed known, a known-scope
      granting adapter actually applying a move with material conservation verified, per-tick
      dedup, a denied attempt and a failed roll each still consuming the dedup slot,
      no-valid-direction, an off-thread call denied (bounded join with safe worker
      interrupt/cleanup on timeout) before touching anything, an unloaded source denied without
      force-loading it, and a deterministic instrumented-seam test proving `Smoothing.canSmooth`
      itself is never even queried for an unloaded destination -- not just "no mutation
      happened," which a reordered implementation (query first, skip mutation after) could still
      satisfy -- using injectable destination-loaded/feasibility-check seams rather than real
      chunk state, after an earlier real-chunk version of that case passed locally but proved
      environment-dependent on the real GitHub Actions runner. The seam's own sensitivity was
      verified by temporarily reordering the production check, confirming the test fails, then
      reverting) prove it against a real world, not primitives in isolation.
- [ ] A real production `ProtectionAdapter`. A first attempt (`SmoothingScopeProtectionAdapter`)
      was built and withdrawn after review 54899/54901: it claimed
      `supportsSourceDestinationAuthorization=true` on the theory that `Smoothing.smooth()`'s own
      `SmoothingAuthorizationEvent` backs it, but that event firing with no listener registered
      is not a protection decision -- "event exists" and "a real claim system is actually
      listening and would deny" are different claims, and this adapter could not verify the
      second one. It also could not rule out `Block.UPDATE_ALL`-driven neighbor/cave-in
      consequences (`LayeredBlock`'s own docs name Dynamic Terrain's cave-ins as one real,
      external example). No concrete, named third-party claim/protection integration is in scope
      to build against yet, so per the review's explicit alternative, this remains an open
      question rather than another guessed design: either a specific supported integration gets
      named (with real per-attempt authorization and tested effect scope, including protected
      source/destination, missing-integration denial, and neighbor-sensitive cases), or
      propagation/claim authorization stays denied indefinitely by design.
- [ ] DIV's actual per-wheel call site into `ErosionAttempt` (needs DIV to expose contact
      telemetry -- blocked on MINECRAFT-545 PR #39's active `PhysicsBridge.java` changes, see
      above), the real `ProtectionAdapter` above, an explicit default-OFF config switch before
      any real erosion can apply, multiplayer sync, dependency-failure tests, pinned-jar smoke
      acceptance, and gameplay acceptance evidence.

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
installed and runs the 11 GameTests (boot-only, plus `ErosionAttemptGameTests`' 10 real-world
orchestration cases). Wired into CI via `scripts/check_gametest_count.py`, which fails closed if
the expected test count regresses. An opt-in `div-paired-runtime.yml` workflow additionally
builds DIV fresh and boots everything together at DIV's real NeoForge floor.
