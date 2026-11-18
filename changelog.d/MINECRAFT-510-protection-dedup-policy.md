bump: minor

### Added
- `ErosionDedup`: per-tick dedup so at most one erosion roll-and-mutation attempt happens per
  (dimension, block position) per server tick, across however many wheels/vehicles would
  otherwise target that same block -- a rejected attempt still consumes the tick's one slot.
  Bounded lifecycle storage: state is scoped to exactly the current tick, never accumulating.
  Fails closed (throws) on a regressing tick rather than silently clearing and allowing a
  duplicate claim through.
- `ErosionProtectionGate`: a hard, code-level gate the erosion consumer must check before
  performing any real world mutation. Models coverage as a `ProtectionAdapter` object whose own
  boolean capability methods are re-checked live on every call -- never a trusted free-text
  claim -- so a source/destination-only (or otherwise partial) adapter keeps the gate closed
  regardless of any accompanying description. Does not itself implement propagation/neighbor/
  cave-in protection.
- `StandaloneBootGameTests.theGameTestHarnessBootsForThisMod`: the first real GameTest for this
  mod, wired into CI with `check_gametest_count.py` fail-closed evidence.
- Opt-in DIV paired-runtime CI (`div-paired-runtime.yml`): builds DIV fresh from its pinned ref
  on a real runner, resolves its own required mts/Sable/Sable-Companion dependencies, boots a
  real dedicated server with everything loaded together, verifies the exact expected artifact
  and every expected mod id in the log, and uploads an immutable resolved-dependency manifest.
