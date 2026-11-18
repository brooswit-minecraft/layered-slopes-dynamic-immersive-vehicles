bump: minor

### Added
- `ClaimProtection`: real, best-effort respect for claim/protection mods, via the same pattern
  `layered-slopes-flowing-fluids`' own `Protection.mayErode` already uses -- post a real
  `BlockEvent.BreakEvent` through a fake player and honor cancellation, applied at both the
  source and destination of each erosion move. Also checks the server's own world border and
  spawn protection, which `BreakEvent` cancellation alone does not establish.
- `DynamicTerrainCaveInRisk`: a real check for Dynamic Terrain's cave-ins -- the one
  concretely-named external propagation risk this ticket's reviews identified. Two earlier,
  narrower designs each overclaimed: a face-adjacent-neighbor tag scan (review 54907/54908 --
  rested on today's shipped tag contents/call ordering and treated an unloaded neighbor as
  proven-safe rather than unknown), then an unconditional "deny whenever Dynamic Terrain is
  loaded at all" rule (review 54910 -- more conservative than necessary). Verified directly
  against DT's own source (review 54915): its cave-in handler does nothing when its own
  `caveInsEnabled` config (default `false`) is `false`. Now reads that real, live config value
  through NeoForge's public, generic `ModConfigs.getModConfigs(modId)` API (never DT's Java
  classes), failing closed on any absence/read failure/unexpected type. Review
  54916/54917/54919/54920 found a generic key match alone insufficient: the permissive branch
  now also requires DT's loaded version to fall inside a directly-verified range (checked against
  every legacy-dynamic-terrain release from v0.28.2 through v5.0.1), and requires exactly one
  matching registered config (ambiguity also fails closed). `DynamicTerrainCaveInRiskVersionTest`
  unit-tests the version boundary directly; an opt-in `-PdtPairedRuntime=true` build
  (`.github/workflows/dt-paired-runtime.yml`) boots the real production path against an actual
  pinned Dynamic Terrain v5.0.1 twice, with its own real config set to `false` then `true`.
  Review 54925 found that alone still insufficient -- neither observed Dynamic Terrain's own
  loaded `BooleanValue` directly, so a divergence between the generic reader and Dynamic
  Terrain's real value could pass unnoticed. `DynamicTerrainPairedGameTests` now reads
  `DynamicTerrainConfig.CAVE_INS_ENABLED` directly (the one place this mod references Dynamic
  Terrain's own Java classes, deliberately confined to this opt-in-only test class) and
  cross-checks it against the generic reader. It also edits Dynamic Terrain's own config file on
  disk WHILE the server keeps running and polls for a live reload -- confirmed, on a real pinned
  Dynamic Terrain v5.0.1, that NeoForge's own config watcher picks up the change within seconds
  and the production path transitions `APPLIED` -> `NEIGHBOR_CAVE_IN_RISK` accordingly.
- `ErosionAttempt` now enforces both checks as mandatory, unconditional steps before any
  `Smoothing.smooth()` call -- not behind an optional, separately-installed adapter -- plus a
  final re-verification immediately before applying: `canSmooth` AND an exact source/destination
  `BlockState` equality check against the states captured just before the claim checks (review
  54910 -- a `ClaimProtection` listener runs real synchronous NeoForge event handlers that can
  mutate the world as a side effect, and a boolean-only `canSmooth` recheck cannot tell the
  original configuration apart from a DIFFERENT one that happens to still be smoothable).

### Known limitation
- Dynamic Terrain coexistence (narrowed by review 54915, not eliminated): a world running Dynamic
  Terrain with `caveInsEnabled=true` (not its own default) still gets no usable erosion. A finite
  supported policy for that configuration would require replicating DT's own recursive
  `SupportSearch`-based connectivity check (bounded to 256 visited cells) to prove a specific move
  cannot destabilize a reachable DT-tagged block -- a substantial, independent reimplementation
  not attempted in this slice. Flagged explicitly as the remaining open product question, not
  claimed as resolved.

### Changed
- Withdrew the `ErosionProtectionGate`/`ProtectionAdapter`-routed design from `ErosionAttempt`'s
  real path. Three separate attempts in this area (the gate itself, and two different adapters
  built against it) were each independently found to claim a capability the code did not
  actually verify. The root cause: `ErosionProtectionGate.MutationRequest` is deliberately
  Minecraft-free (to stay pure-unit-testable), which cannot host a check that needs a live
  `ServerLevel`/`BlockPos`. `ErosionProtectionGate`/`ProtectionAdapter` remain in the codebase,
  valid on their own terms, for a possible future Minecraft-free policy -- they are simply no
  longer the vehicle for these two real checks.
- `ErosionAttempt`'s public signature dropped the now-unused `ErosionProtectionGate` parameter;
  `Outcome.DENIED_BY_PROTECTION` was replaced by `Outcome.PROTECTED` (from `ClaimProtection`) and
  `Outcome.NEIGHBOR_CAVE_IN_RISK` (from `DynamicTerrainCaveInRisk`).
- Corrected an overclaiming comment in `ErosionAttempt`'s re-verification step (review 54915):
  `canSmooth` only catches support changes that flip the move's own feasibility verdict, not
  every support change -- a support block swapped for a different-but-still-sturdy one is
  harmless and correctly still applies, which the explicit source/destination `BlockState`
  equality check (unchanged) is what actually catches the real remaining gap.
