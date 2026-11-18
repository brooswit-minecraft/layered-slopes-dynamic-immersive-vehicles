bump: minor

### Added
- `ErosionAttempt`: the real orchestration of a single per-position erosion attempt --
  dedup claim, the accepted MINECRAFT-555 slip-to-probability roll, a shuffled
  horizontal-direction-first-`canSmooth`-accepted call into `Smoothing.smooth()`, and the
  `ErosionProtectionGate` check before any world mutation. Takes a `ServerLevel` (a client-side
  call is impossible to even construct) and asserts `level.getServer().isSameThread()` before
  touching anything, since the type alone does not rule out an off-thread call. Derives
  dimension/tick from the level/its server rather than trusting a caller-supplied value; confirms
  every candidate position's chunk is loaded before any block query, never force-loading one.
  Every real `MutationRequest` this builds has `propagationScopeKnown=false`, since no
  neighbor/cave-in consequence analysis is implemented anywhere in this mod yet -- every real
  `authorize()` call is denied by construction until that changes, regardless of whether a
  protection adapter exists.
- `ErosionAttemptGameTests`: 10 real-world acceptance cases -- default-closed denial, a granting
  adapter still denied because propagation scope is never claimed known in production, a
  known-scope granting adapter actually applying a move (with source+destination layer-count
  material conservation verified), per-tick dedup, a denied attempt and a failed roll each still
  consuming the dedup slot, no-valid-direction when every horizontal neighbor blocks the move, an
  off-thread call denied (bounded join, no deadlock risk) before touching anything or consuming
  the dedup slot, an unloaded source denied without force-loading it or consuming the dedup slot,
  and a real loaded-source/unloaded-destination chunk boundary (raw world coordinates at an
  actual chunk edge) skipped without force-loading it.
