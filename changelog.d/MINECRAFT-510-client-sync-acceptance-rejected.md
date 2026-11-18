bump: minor

### Added
- Opt-in `client-sync-acceptance-rejected.yml` CI workflow, rejected/canceled-unchanged case,
  with LIVE post-join evidence (direction, comment 55058/55111/55114/55137): completes the
  live-update evidence pair alongside `client-sync-acceptance-enabled.yml`. Proves a REAL,
  SPECIFIC denial (never merely the absence of a mutation, and never `NO_CHANCE`/a dropped
  command/a pre-attempt timeout/a disconnected client standing in for it) leaves both the source
  and destination genuinely unchanged from an already-connected client's own perspective.
- Uses a real vanilla mechanism, not a mock: a nonzero `spawn-protection` radius plus
  `/setworldspawn` pinned exactly at the test position, so `ClaimProtection#mayMutate`'s real
  `MinecraftServer#isUnderSpawnProtection` check genuinely denies the attempt. The exact same
  guaranteed-chance config/slip/load values as the enabled case are used, so the only possible
  denial reason is this real protection check, requiring the server to report the specific
  `erosionclientsynctest outcome=PROTECTED` -- not any other denial outcome.
- Reuses `ErosionClientSyncLiveProbe` exactly as-is (no new Java code): its existing `UNCHANGED`
  report path, already built with this case in mind, is exactly the correct evidence here.
