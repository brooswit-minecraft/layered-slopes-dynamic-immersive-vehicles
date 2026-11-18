bump: minor

### Added
- Opt-in `client-sync-acceptance-rejected.yml` CI workflow, rejected/canceled-unchanged case,
  with LIVE post-join evidence (direction, comment 55058/55111/55114/55137): completes the
  live-update evidence pair alongside `client-sync-acceptance-enabled.yml`. Proves a REAL,
  SPECIFIC denial (never merely the absence of a mutation, and never `NO_CHANCE`/a dropped
  command/a pre-attempt timeout/a disconnected client standing in for it) leaves both the source
  and destination genuinely unchanged from an already-connected client's own perspective.
- Uses a real vanilla mechanism, not a mock: the world border, shrunk via `/worldborder` so its
  own real max coordinate falls exactly between the source and destination positions, exercising
  `ClaimProtection#mayMutate`'s own unconditional world-border check (real spawn protection was
  tried first, but NeoForge's FakePlayer evidently bypasses it). The exact same
  guaranteed-chance config/slip/load values as the enabled case are used, so the only possible
  denial reason is this real protection check, requiring the server to report the specific
  `erosionclientsynctest outcome=PROTECTED` -- not any other denial outcome.
- Extends `ErosionClientSyncLiveProbe` with an optional, explicit test-only acknowledge-file
  signal (review finding 55137: a plain fixed-tick timer started at baseline capture does not
  structurally prove continued observation happened AFTER the real denial was acknowledged). The
  workflow writes that file only after independently confirming the real denial in the server's
  own log; the already-connected client requires it before starting a further bounded
  post-acknowledgment watch window, reporting the distinct `TIMEOUT_NO_ACK` status (never folded
  into `UNCHANGED`) if it never appears within the overall bound. Also adds explicit,
  checked arithmetic verifying the world-border inequalities actually hold, rather than relying
  on a comment describing them.
- Fixed: the checked arithmetic itself used `source<=max`/`dest>max`, which does not match the
  real `WorldBorder#isWithinBounds(double,double,double)` semantics (`x >= min && x < max`, a
  strict upper bound) -- at `max=90`, `x=90` is already outside, so the prior source/destination
  pair at x=90/91 put both positions outside the border instead of isolating only the
  destination. Moved the pair to x=89/90 and corrected the inequalities to match the real API
  exactly.
- Fixed a second real bug this surfaced: `/worldborder center 0 0` does not center the border at
  exactly (0, 0) -- vanilla's `Vec2Argument.vec2()` is constructed with `centerCorrect=true`, and
  `WorldCoordinate#parseDouble` adds `+0.5` to any bare-integer coordinate with no decimal point,
  confirmed live by the server log reporting "Set the center of the world border to 0.50, 0.50".
  That 0.5 offset made the real border max X 90.5, not 90.0, so x=90 was still genuinely inside
  and the forced attempt was `APPLIED`, not `PROTECTED`. Fixed by sending explicit decimals
  (`0.0 0.0`), which the same parser takes literally.
