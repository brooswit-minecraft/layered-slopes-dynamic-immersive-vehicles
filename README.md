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
      - `DynamicTerrainCaveInRisk`: **not a required acceptance gate (scope correction, comment
        54987)** -- Dynamic Terrain is itself deprecated/legacy (hence `legacy-dynamic-terrain`),
        so coexisting with it is explicitly not something this ticket needs. Kept as merged,
        historical, defense-in-depth rather than removed: it reads Dynamic Terrain's real, live
        `caveInsEnabled` config (version-bounded against every verified `legacy-dynamic-terrain`
        release, fail-closed on anything unverified or ambiguous) through NeoForge's public,
        generic API, denying only when Dynamic Terrain is both loaded and confirmed actually
        risky; absent entirely, there is no risk to check at all. `DynamicTerrainCaveInRiskVersionTest`,
        `DynamicTerrainPairedGameTests`, and the opt-in `dt-paired-runtime.yml` workflow remain in
        the codebase proving this real behavior (including a confirmed live config-reload probe),
        but none of that is exercised by, or required for, this ticket's own acceptance going
        forward -- no further implementation or review cycles are spent extending it.
      `ErosionAttempt`'s production path is: dedup claim, the accepted MINECRAFT-555
      slip-to-probability roll, a shuffled-direction-first-`canSmooth`-accepted candidate, both
      real checks above at that candidate's source and destination, then a re-verification before
      applying: a synchronous `ClaimProtection` listener runs real NeoForge event handlers that
      could mutate the world as a side effect, and a boolean-only `canSmooth` recheck cannot tell
      the original configuration apart from a DIFFERENT one a listener swapped in that happens to
      still be smoothable (review 54910) -- so this re-verifies `canSmooth` AND compares the exact
      source/destination `BlockState`s captured just before the claim checks, failing closed on
      any difference. `Smoothing.smooth()` itself also re-derives and compares its own plan before
      applying (grading's existing TOCTOU fix), covering its own authorization event; this is
      additional, earlier defense in depth, not a replacement for it. Takes a `ServerLevel` (a
      client-side call cannot even be constructed) and asserts `level.getServer().isSameThread()`
      before touching anything -- the type alone does not rule out an off-thread call. Derives
      dimension/tick from the level/its server, never from a caller-supplied value; every
      candidate position's chunk is confirmed loaded before any block query, never force-loading
      one. Takes slip/load telemetry as plain parameters; makes no assumption about where it comes
      from, since DIV's own per-wheel contact-telemetry seam is not wired in yet (see below).
      `ErosionAttemptGameTests` (18 cases) prove this against a real world: a real move applying
      and conserving material with no claim mod/Dynamic Terrain installed (the
      "missing/unsupported integration" success case), a protected source, a protected
      destination, and a detected cave-in risk each denying (via injected orchestration-seam
      fakes), a REAL `BlockEvent.BreakEvent` listener cancelling at source and at destination
      each denying via the actual production path, a direct check that the real
      `DynamicTerrainCaveInRisk` reports no risk when Dynamic Terrain is genuinely absent, a REAL
      `BlockEvent.BreakEvent` listener that swaps the source to a DIFFERENT but still-smoothable
      state without cancelling (proving the exact-state recheck catches what a boolean-only
      recheck cannot), a REAL listener that swaps the support block for a different-but-sturdy
      one proving that harmless case still applies rather than being overcorrected into a denial
      (review 54915), a direct check that an unknown config lookup fails closed to the caller's
      fallback, per-tick dedup, a denied attempt and a failed roll each still consuming
      the dedup slot, no-valid-direction, an off-thread call denied (bounded join with safe
      worker interrupt/cleanup on timeout) before touching anything, an unloaded source denied
      without force-loading it, a deterministic instrumented-seam test proving
      `Smoothing.canSmooth` itself is never even queried for an unloaded destination -- not just
      "no mutation happened," which a reordered implementation could still satisfy; verified by
      temporarily reordering the production check, confirming the test fails, then reverting --
      and (direction, comment 54943) a disabled-by-default `erosionEnabled=false` attempt denying
      with `Outcome.DISABLED` before touching the off-thread check/chunk/dedup/RNG/world at all,
      without consuming the dedup slot.
- [x] `ErosionProtectionGate`/`ProtectionAdapter` (merged PR #3) remains in the codebase, valid on
      its own terms, but is no longer part of `ErosionAttempt`'s real path for the reason above --
      its Minecraft-free abstraction cannot host a real world-aware check.
- [x] Explicit default-OFF config switch (direction, comment 54943): `ErosionConfig` gained an
      8th field, `erosionEnabled` (default `false`), additive to the MINECRAFT-555-accepted
      7-field tuple -- the legacy 7-arg constructor every prior call site (test and production)
      used defaults it to `true`, so no existing call site's meaning changed. `ErosionAttempt`
      checks it as the very first thing, before even the off-thread assertion. `ErosionModConfig`
      registers a real `ModConfig.Type.SERVER` spec (matching DT's own "existing worlds are safe"
      convention) with every tuning field independently bounded the same way
      `ErosionConfig#isValid()` bounds them -- but `ModConfigSpec` cannot express the accepted
      contract's RELATIONAL constraints (`slipThresholdMs < referenceSlipMs`,
      `minLoadFactor <= maxLoadFactor`), so a file could pass every individual field's bounds and
      still describe a relationally-invalid whole tuple. `onLoad`/`onReload` re-derive a full
      candidate from the live spec values on every load/reload and route it through
      `ErosionConfig.Overrides#reload`, so that case is still atomically rejected as a whole --
      `ErosionConfigTest`'s case 28 already proved this in isolation; `ErosionModConfigGameTests`
      proves the real registered config actually boots with `erosionEnabled=false` and that
      editing its real on-disk file toggles it live, without a server restart (confirmed to
      actually happen, the same way the Dynamic Terrain probe above confirmed NeoForge's own
      config-reload behavior), with the production path transitioning `DISABLED` -> `APPLIED`
      accordingly.
- [x] Dependency-failure acceptance, missing-dependency case only (direction, comment 54973):
      an opt-in `dependency-failure-acceptance.yml` workflow proves NeoForge's own
      mandatory-dependency check actually refuses to boot, with a message attributing THIS
      mod's own `neoforge.mods.toml` requirement (not merely some other mod's unrelated
      requirement on the same dependency -- `layered-slope-grading` itself also requires
      `layered-slopes`, so a generic match on either name alone would not prove this), when
      `layered-slopes` or `layered-slope-grading` is absent at runtime -- not merely that some
      process exits nonzero (an earlier version of this check branched on Gradle's own process
      exit code, which stays 0/`BUILD SUCCESSFUL` even when the dedicated server itself fails
      to boot -- fixed to assert on the real log content instead). `build.gradle`'s
      `-PomitRequiredDependency=<modid>` flag skips only that jar's `runtimeOnly` entry
      (`compileOnly` stays, so main code still compiles); `neoforge.mods.toml`'s own
      `type="required"` declaration for that modId is untouched, so the dependency is
      genuinely, verifiably unmet, never simulated.

- [x] Dependency-failure acceptance, incompatible-version case (direction, comment 54997): a
      companion opt-in `incompatible-version-acceptance.yml` workflow proves NeoForge's own
      `versionRange` check actually refuses to boot, with a message attributing THIS mod's own
      requirement and naming the real installed (too old) version, when `layered-slopes` or
      `layered-slope-grading` IS present but below the declared `[0.2.0,)` floor -- the
      companion case to the missing-dependency acceptance above, which only covers fully absent.
      `build.gradle`'s `-PincompatibleVersionDependency=<modid>` flag swaps only that jar's
      `runtimeOnly` entry for a real, pinned, sha512-verified v0.1.0 release of the same mod
      (`compileOnly` stays on the normal, current, compatible jar, so main code still compiles).

      **Not yet covered, named explicitly rather than implied by the checkmarks above** (review
      54978/54997): the eventual DIV dependency's own missing/incompatible case once it exists.
- [x] Pinned-jar smoke acceptance, compat+base-mods and base-mods-alone cases only (direction,
      comment 54997/55004/55009): an opt-in `pinned-jar-smoke-acceptance.yml` workflow is the one
      place that boots the REAL packaged artifact, not the dev-mode `gameTestServer` source-set
      classpath every other check in this repo uses -- `./gradlew jar` builds this mod's actual
      distributable jar, a real NeoForge-installer-produced dedicated-server distribution is
      installed (the same kind an operator would actually run), and the real jars are dropped
      into its `mods/` folder. `replacement-stack-smoke` boots this mod's packaged jar together
      with the pinned `layered-slopes`/`layered-slope-grading` release jars, requiring the real
      `Done (` startup line and all 3 expected mod ids in the real `Mod List`.
      `base-mods-isolation-smoke` boots `layered-slopes`/`layered-slope-grading` ALONE, this
      mod's jar deliberately absent, proving neither depends on this compat mod and both boot
      standalone with no mutual linkage. Both jobs shut the server down via a REAL stdin `stop`
      command (not a bare signal), asserting both a bounded clean exit and the real
      vanilla/NeoForge shutdown log lines (review 55013); `replacement-stack-smoke` additionally
      reads the real generated `layeredslopesdynamicimmersivevehicles-server.toml` and asserts it
      genuinely defaults `erosionEnabled` to `false`. No GameTests run in either job (a real
      production server has no GameTest harness); success is the real startup/shutdown/mod-list/
      config evidence, never a bare process exit code.

- [x] DIV artifact-based isolation and full packaged replacement-stack smoke (direction, comment
      55019/55032): an opt-in `div-replacement-stack-smoke.yml` workflow reuses
      `div-paired-runtime.yml`'s existing immutable-pinned-commit-SHA DIV source build (no stable,
      pinnable DIV release is required for this). `div-alone-isolation-smoke` builds DIV fresh
      from that pinned SHA, resolves DIV's own real required runtime dependencies (mts, Sable,
      Sable Companion) via the `-PpairedRuntime=true` `copyPairedRuntimeJars` task, and boots a
      real NeoForge dedicated server with ONLY DIV + those deps -- this compat mod and
      layered-slopes/layered-slope-grading deliberately absent -- proving DIV boots standalone
      with no dependency on either. `full-replacement-stack-smoke` additionally builds this mod's
      own real packaged jar and the pinned layered-slopes/layered-slope-grading release jars, and
      boots the real, complete 7-modid replacement stack (DIV + its deps + layered-slopes +
      layered-slope-grading + this compat mod) together, requiring the real `Done (` line, all 7
      expected mod ids, `Found 0 mod requirements missing`, a real graceful shutdown (all three
      vanilla/NeoForge shutdown markers independently), and the real generated
      `layeredslopesdynamicimmersivevehicles-server.toml` genuinely defaulting `erosionEnabled` to
      `false`. Records an immutable sha256/sha512 artifact-hash manifest naming the DIV commit SHA,
      the DIV jar's hash, this mod's own jar's hash, and the existing layered-slopes/
      layered-slope-grading version+sha512 pins. Neither job runs any GameTest; success is the
      real startup/shutdown/mod-list/config/manifest content, never a bare process exit code. This
      is independent of the MINECRAFT-545-gated `PhysicsBridge`/contact-telemetry work below, which
      remains a real, separate hold.
- [ ] DIV's actual per-wheel call site into `ErosionAttempt` (needs DIV to expose contact
      telemetry -- blocked on MINECRAFT-545 PR #39's active `PhysicsBridge.java` changes, see
      above), multiplayer sync, and gameplay acceptance evidence.

No dependency on Dynamic Terrain or Dynamic Vehicles exists or is planned. The erosion consumer
defaults to OFF via this mod's own real `erosionEnabled` server config (default `false`, see
above); operators running this mod alongside Dynamic Terrain's deprecated vehicle erosion should
disable DT's `vehicleErosion` to avoid double effects.

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
installed and runs the 21 GameTests (boot-only, plus `ErosionAttemptGameTests`' 18 real-world
orchestration cases, plus `ErosionModConfigGameTests`' 2 real default-OFF-switch cases). Wired
into CI via `scripts/check_gametest_count.py`, which fails closed if
the expected test count regresses. An opt-in `div-paired-runtime.yml` workflow additionally
builds DIV fresh and boots everything together at DIV's real NeoForge floor. A second opt-in
`dt-paired-runtime.yml` workflow boots this mod with a real, pinned Dynamic Terrain installed
(never a default dependency) to prove `DynamicTerrainCaveInRisk`'s real behavior end to end --
historical/defense-in-depth evidence, not a required acceptance gate (scope correction, comment
54987; see the Status section above).
