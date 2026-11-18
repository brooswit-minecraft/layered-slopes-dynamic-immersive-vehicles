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
        external propagation risk reviews identified. Two earlier, narrower designs were each
        found to overclaim: a face-adjacent-neighbor tag scan (review 54907/54908 -- rested on
        today's shipped tag contents and call ordering, and treated an unloaded neighbor as
        proven-safe rather than unknown), then an unconditional "deny whenever Dynamic Terrain is
        loaded at all" rule (review 54910 -- more conservative than necessary, since "DT is
        loaded" and "DT's cave-ins are actually active" are different, independently-verifiable
        facts). Verified directly against DT's own source (review 54915): its cave-in handler
        returns immediately, doing nothing, whenever its own `caveInsEnabled` config value
        (`ModConfig.Type.SERVER`, default `false`) is `false`. This now reads that real, live
        config value through NeoForge's own public, generic, supported API
        (`ModConfigs.getModConfigs(modId)` plus the standard `night-config` reader) -- never DT's
        Java classes, never DT's own `ModConfigSpec.BooleanValue` field, the same
        data/API-only optional-dependency shape `GroundSurfaces.java` already uses for Layered
        Slopes. Any absence, read failure, or unexpected value type fails closed (treated as
        enabled/risky), per review 54915's "unknown/unreadable/version-mismatched config remains
        denied." If Dynamic Terrain is not loaded at all, there is no such risk to check.

        Review 54916/54917/54919/54920 found that alone insufficient: a generically-matched key
        proves nothing about whether a DIFFERENT, unverified Dynamic Terrain release still wires
        that key to the same early-return guard. So the permissive branch (treating a read
        `false` as genuinely safe) is reached only when Dynamic Terrain's own loaded version
        falls inside a directly-verified range (checked tag-by-tag against every
        brooswit-minecraft/legacy-dynamic-terrain release from v0.28.2 through its current
        latest, v5.0.1 -- identical key/default/type/guard the whole way); any other installed
        version fails closed, and the config read itself requires exactly one matching
        registered config (ambiguity -- more than one -- also fails closed, rather than guessing
        the first match). `DynamicTerrainCaveInRiskVersionTest` unit-tests the version boundary
        directly (lower/upper bound, below, above -- no Dynamic Terrain or mod environment
        needed, since it is a pure function over `org.apache.maven.artifact.versioning` types).
        An opt-in `./gradlew runGameTestServer -PdtPairedRuntime=true` build (wired into CI via
        `.github/workflows/dt-paired-runtime.yml`, never part of the default build/test) proves
        the real production path against an actual pinned Dynamic Terrain v5.0.1, booting twice
        with its own real config file on disk set to `false` then `true` and requiring the
        correct `ErosionAttempt.Outcome` each time. Review 54925 correctly found that alone still
        insufficient: neither the test nor the workflow had ever observed Dynamic Terrain's own
        loaded `DynamicTerrainConfig.CAVE_INS_ENABLED` value directly, so a divergence between
        this mod's generic reader and Dynamic Terrain's real value could pass unnoticed.
        `DynamicTerrainPairedGameTests` (compiled only in this opt-in profile, the one place
        referencing Dynamic Terrain's own Java classes directly is deliberate and safe) now reads
        that `BooleanValue` directly and cross-checks it against the generic reader before
        checking the production outcome. It also edits Dynamic Terrain's own config file on disk
        WHILE the false-boot server keeps running (no restart) and polls for the live value to
        flip -- an evidence-gathering probe, not an assumption either way: confirmed, on a real
        pinned Dynamic Terrain v5.0.1, that NeoForge's own config file watcher DOES pick up the
        change and flip the value within a couple of seconds, and that the production path
        transitions from `APPLIED` to `NEIGHBOR_CAVE_IN_RISK` accordingly, with this mod's generic
        reader agreeing throughout. Had no reload been observed, the test would fail loudly
        rather than silently pass, per review 54923's "fail closed for runtime changes" --
        reload is confirmed to work, not merely hoped to.

        **Narrowed, not eliminated, limitation:** a world running Dynamic Terrain with
        `caveInsEnabled=true` (not the default) still gets no usable erosion -- building a finite
        supported policy for that configuration would require replicating DT's own recursive
        `SupportSearch`-based connectivity check (`CaveIns.java`/`SupportSearch.java`, bounded to
        256 visited cells and re-checking the changed position plus all six face-adjacent
        neighbors) to prove a specific move cannot destabilize a reachable DT-tagged block -- a
        substantial, independent reimplementation not attempted in this slice. Flagged explicitly
        as the remaining open product question, not claimed as resolved.
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
- [ ] DIV's actual per-wheel call site into `ErosionAttempt` (needs DIV to expose contact
      telemetry -- blocked on MINECRAFT-545 PR #39's active `PhysicsBridge.java` changes, see
      above), multiplayer sync, dependency-failure tests, pinned-jar smoke acceptance, and
      gameplay acceptance evidence.

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
see the Status section above.
