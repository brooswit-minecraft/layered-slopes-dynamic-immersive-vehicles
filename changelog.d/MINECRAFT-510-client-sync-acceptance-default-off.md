bump: minor

### Added
- Opt-in `client-sync-acceptance.yml` CI workflow, default-OFF case (direction, comment
  55058/55063): the first slice that drives a REAL second process -- a real headless NeoForge
  dev client (feasibility proven by `client-sync-harness-feasibility.yml`) auto-joining a real
  dedicated server over a real loopback socket via vanilla's `--quickPlayMultiplayer` -- rather
  than a server-only GameTest result or an `UPDATE` flag relabeled as client-sync evidence.
  NeoForge's GameTest framework never sends a real client-bound packet, so no prior GameTest in
  this repo could truthfully stand in for this.
- New test-only, never-active-unless-explicitly-enabled pieces: `ErosionClientSyncTestCommand`
  (server-side `erosionclientsynctest` command forcing a single real `ErosionAttempt.attempt`
  call against the real live config) and `ErosionClientSyncProbe` (client-side tick hook reading
  a known `BlockPos`'s state from the real `ClientLevel` -- built purely from received network
  packets -- and writing it out for the harness to assert on). Both gated behind system
  properties forwarded only from new, default-false Gradle project properties; an ordinary
  `./gradlew runServer`/`runClient` is completely unaffected.
- Fixed a real Gradle behavior discovered while building this: `JavaExec`-based run tasks (what
  every NeoForge moddev run resolves to) do not forward this process's stdin to the forked JVM
  by default, so `runServer`'s `standardInput` is now wired explicitly -- this also makes a
  human's own real console input work when running `./gradlew runServer` directly, which it did
  not before either.
- This slice proves erosionEnabled's real default (`false`) leaves the world genuinely unchanged
  as observed by an actual, independently-connected client -- not a server-side flag. The
  enabled/one-layer-mass-conservation and rejected/canceled-unchanged cases are separate, later
  slices.
