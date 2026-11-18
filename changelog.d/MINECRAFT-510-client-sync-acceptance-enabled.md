bump: minor

### Added
- Opt-in `client-sync-acceptance-enabled.yml` CI workflow, enabled one-layer-conservation case,
  with LIVE post-join update evidence (direction, comment 55058/55111/55114): the previously
  merged default-OFF slice's evidence was an initial-chunk-snapshot read -- the block was placed
  and the attempt forced BEFORE the client ever joined, so the client's read happened as part of
  ordinary chunk sync on connect, not proof of a LIVE update being delivered. This slice proves
  that distinctly: the client is already connected and watching two positions before any
  mutation happens, signals once it has genuinely captured a baseline from its own `ClientLevel`,
  and only then does the forced attempt run -- the client must observe the real post-join
  transition.
- New test-only, never-active-unless-explicitly-enabled `ErosionClientSyncLiveProbe`: watches a
  source and destination position, signals readiness via a file once baseline is captured, then
  polls for either to actually change, reporting `CHANGED` or `UNCHANGED` (the latter deliberately
  reusable for a future rejected/canceled-case slice) rather than assuming a change occurs.
- Uses the exact extreme-but-valid config values `ErosionAttemptGameTests.GUARANTEED_CHANCE_CONFIG`
  already uses (chance clamps to 1.0 regardless of RNG seed) and the exact deterministic block
  arrangement `placeSourceWithOneValidEastNeighbor` uses, so the expected post-mutation result
  (source becomes `dynamicterrain:layered_dirt` with 15 layers, destination with 1 layer, 16
  total conserved) is the same material-conservation evidence that GameTest already proves in
  isolation -- now proven live, from a real independently-connected client's own perspective.
