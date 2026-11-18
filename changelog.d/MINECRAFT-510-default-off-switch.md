bump: minor

### Added
- Explicit default-OFF erosion switch (direction, comment 54943): `ErosionConfig` gained an 8th
  field, `erosionEnabled` (default `false`), additive to the MINECRAFT-555-accepted 7-field
  tuple -- the legacy 7-arg constructor defaults it to `true`, so no existing call site's
  meaning changed. `ErosionAttempt.attempt` checks it as the very first thing, before even the
  off-thread assertion, denying with a new `Outcome.DISABLED` and never touching the chunk,
  dedup, RNG, or world when it is `false`.
- `ErosionModConfig`: registers a real `ModConfig.Type.SERVER` spec (matching Dynamic Terrain's
  own "existing worlds are safe" convention) with every tuning field independently bounded the
  same way `ErosionConfig#isValid()` bounds them. `ModConfigSpec` cannot express the accepted
  contract's relational constraints (`slipThresholdMs < referenceSlipMs`,
  `minLoadFactor <= maxLoadFactor`), so `onLoad`/`onReload` re-derive a full candidate from the
  live spec values on every load/reload and route it through the existing, already-accepted
  `ErosionConfig.Overrides#reload` atomic-reject gate -- a config file that passes every
  individual field's NeoForge-enforced bounds but describes a relationally-invalid whole tuple
  is still rejected as a whole, retaining the prior snapshot.
- `ErosionModConfigGameTests`: proves the real registered config boots with `erosionEnabled`
  defaulting `false`, and that editing its real on-disk file toggles it live without a server
  restart (confirmed to actually happen), with the production `ErosionAttempt` path transitioning
  `DISABLED` -> `APPLIED` accordingly.
- `ErosionAttemptGameTests`: a disabled-by-default attempt denies with `Outcome.DISABLED` before
  touching the off-thread check, chunk, dedup, or RNG at all, and does not consume the dedup slot
  (proven the same way the off-thread/unloaded-source cases already are: a subsequent, enabled
  attempt at the exact same position/tick still applies).
