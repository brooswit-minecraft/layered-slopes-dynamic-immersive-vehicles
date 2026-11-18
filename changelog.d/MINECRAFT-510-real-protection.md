bump: minor

### Added
- `ClaimProtection`: real, best-effort respect for claim/protection mods, via the same pattern
  `layered-slopes-flowing-fluids`' own `Protection.mayErode` already uses -- post a real
  `BlockEvent.BreakEvent` through a fake player and honor cancellation, applied at both the
  source and destination of each erosion move. Also checks the server's own world border and
  spawn protection, which `BreakEvent` cancellation alone does not establish.
- `DynamicTerrainCaveInRisk`: a real, maximally conservative check for Dynamic Terrain's cave-ins
  -- the one concretely-named external propagation risk this ticket's reviews identified. An
  earlier, narrower version tried to exclude source/destination from a face-adjacent-neighbor tag
  scan; review 54907/54908 found that rested on today's shipped tag contents and call ordering
  (either could change) and treated an unloaded neighbor as proven-safe rather than unknown.
  Simplified to the one claim fully verifiable and enforceable: if Dynamic Terrain is loaded at
  all, deny every attempt (reads only its mod id as data, never its Java classes).
- `ErosionAttempt` now enforces both checks as mandatory, unconditional steps before any
  `Smoothing.smooth()` call -- not behind an optional, separately-installed adapter -- plus a
  final re-verification immediately before applying: `canSmooth` AND an exact source/destination
  `BlockState` equality check against the states captured just before the claim checks (review
  54910 -- a `ClaimProtection` listener runs real synchronous NeoForge event handlers that can
  mutate the world as a side effect, and a boolean-only `canSmooth` recheck cannot tell the
  original configuration apart from a DIFFERENT one that happens to still be smoothable).

### Known limitation
- Dynamic Terrain coexistence (review 54910): `DynamicTerrainCaveInRisk` denies every attempt
  whenever Dynamic Terrain is loaded at all, with no partial/opt-in coexistence. A finite
  supported policy would require replicating DT's own recursive `SupportSearch`-based
  connectivity check (bounded to 256 visited cells) to prove a specific move cannot destabilize
  a reachable DT-tagged block -- a substantial, independent reimplementation not attempted in
  this slice. Flagged explicitly as an open product question, not claimed as resolved.

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
