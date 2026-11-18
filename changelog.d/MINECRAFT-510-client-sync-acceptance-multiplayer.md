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
- The existing deterministic block arrangements and `GUARANTEED_CHANCE_CONFIG` values are
  reused unmodified; `ErosionClientSyncLiveProbe`'s baseline/watch/acknowledge logic required one
  fix (below). The only other new surface is an optional
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
- Fixed a `set -e` footgun the previous fix introduced: a bare `var=$(failing_command)`
  assignment is not exempt from `set -e` the way `cmd && break` is, so replacing `|| echo 0`
  with a plain assignment made the whole script abort silently (zero further output) the first
  time `grep -c` found no matches yet. Restored a `|| true` shield (adds no extra stdout, unlike
  `|| echo 0`) and applied the same shield plus a short retry loop to the new `/list`
  line-extraction pipeline, which had the identical exposure.
- Fixed a real race surfaced by running two client JVMs concurrently on one CI runner:
  `ErosionClientSyncLiveProbe` captured its baseline on the very first tick `hasChunkAt` became
  true, with no settle delay (unlike `ErosionClientSyncProbe`, which already waits `SETTLE_TICKS`
  for exactly this reason) -- a transient/incomplete chunk read on that first tick could differ
  from the real synced value a tick later, reported as a false `CHANGED` before any real mutation
  ran, disconnecting the client early. Added the same settle-then-read discipline
  (`BASELINE_SETTLE_TICKS`).
- Review finding on that fix: the settle-tick counter was not reset when an intervening tick
  found the chunk no longer tracked, so it accumulated a cumulative total rather than actually
  enforcing consecutive tracked ticks as claimed. Fixed to reset the counter on any such gap.
- Fixed an intermittent CI failure where client A crashed outright with an LWJGL native-library
  `UnsatisfiedLinkError` ("Platform/architecture mismatch") while client B booted fine from the
  same commit -- consistent with the two concurrent `./gradlew runClient` processes racing over
  the shared `GRADLE_USER_HOME` cache during first-time native-library extraction. A first fix
  attempt (isolating client B's Gradle user home) traded the race for a reliable client-timeout
  failure, since that forced a cold ~790MB re-download every run. The actual fix: stagger the
  launch so client B's own first-time dependency/native resolution does not start until client
  A's has already finished (both keep the SAME shared, pre-warmed cache, so there is nothing left
  to race over, and no cold-cache cost for either side) -- "concurrently connected" only needs to
  hold during the observation window around the forced attempt, not at JVM start.
- Review finding on that fix: the wait loop for client A's marker fell through silently (client B
  would start anyway) if the marker never appeared within the bound, so the claimed serialization
  was not actually enforced under a timeout. Added an explicit assertion after the loop that
  fails with client A's own diagnostic log rather than falling through.
