bump: minor

### Added
- `ErosionDedup`: per-tick dedup so at most one erosion roll-and-mutation attempt happens per
  (dimension, block position) per server tick, across however many wheels/vehicles would
  otherwise target that same block -- a rejected attempt still consumes the tick's one slot.
  Bounded lifecycle storage: state is scoped to exactly the current tick, never accumulating.
- `ErosionProtectionGate`: a hard, code-level gate the erosion consumer must check before
  performing any real world mutation, defaulting to NOT asserted (fails closed). Requires an
  explicit, specific coverage description to open -- never a vague "protection is handled"
  claim. Does not itself implement propagation/neighbor/cave-in protection; records whether
  that coverage has actually been implemented and tested so the consumer can honestly refuse to
  run until it has.
