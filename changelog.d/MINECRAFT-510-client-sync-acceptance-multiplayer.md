bump: minor

### Added
- Two opt-in CI workflows, `client-sync-acceptance-multiplayer-enabled.yml` and
  `client-sync-acceptance-multiplayer-rejected.yml` (direction, comment 55159/55161): boot TWO
  genuinely distinct, concurrently-connected client identities (different offline-mode
  usernames/UUIDs -- not the same login replacing itself, verified against distinct real
  "joined the game" log lines) against the same server. Both independently capture their own
  baseline before a single forced erosion attempt, and both independently observe the same real
  outcome: the enabled case's 15+1 material-conserving transition, or the rejected case's
  specific `outcome=PROTECTED` world-border denial leaving both endpoints unchanged (reusing the
  existing post-acknowledgment observation window as-is).
- `ErosionClientSyncLiveProbe` and the existing deterministic block arrangements/
  `GUARANTEED_CHANCE_CONFIG` values are reused unmodified. The only new surface is an optional
  `--username` launch-argument override in `build.gradle` (`erosionClientSyncUsername`,
  blank/unset by default), letting a second client JVM join under a distinct identity instead of
  every dev client defaulting to the same account; every existing single-client workflow is
  unaffected.
- Review fixes before the first real CI run: cumulative distinct "joined the game" log lines
  alone only prove each identity connected at SOME point, not that both were concurrently
  present at the single forced attempt -- both workflows now also issue a real `/list` command
  immediately beforehand and assert its own response names both identities together. Also fixed
  a `grep -c ... || echo 0` bug that printed two numbers (grep's own "0" plus the fallback's)
  whenever no match was found, breaking the numeric comparison.
