bump: minor

### Added
- `ClaimProtection`: real, best-effort respect for claim/protection mods, via the same pattern
  `layered-slopes-flowing-fluids`' own `Protection.mayErode` already uses -- post a real
  `BlockEvent.BreakEvent` through a fake player and honor cancellation, applied at both the
  source and destination of each erosion move.
- `DynamicTerrainCaveInRisk`: a real, bounded, conservative check for Dynamic Terrain's cave-ins
  -- the one concretely-named external propagation risk this ticket's reviews identified.
  Verified directly against Dynamic Terrain's source and its actual shipped block tag (not
  assumed): cave-ins only react to blocks in `dynamicterrain:supported`, which does not include
  any `layeredslopes:*` block, so this checks only the (up to 12) face-adjacent neighbors of
  source/destination for that tag, reading only Dynamic Terrain's mod id and tag id as data
  (never its Java classes).
- `ErosionAttempt` now enforces both checks as mandatory, unconditional steps before any
  `Smoothing.smooth()` call -- not behind an optional, separately-installed adapter.

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
