bump: minor

### Added
- DIV's wheel-contact consumer (`src/divIntegration`, PR #17) now ships in the actual build
  artifact CI uploads as "layered-slopes-dynamic-immersive-vehicles" -- previously it only
  existed in opt-in `-PdivLocalJar` test builds, so the real, intended ordinary deliverable had
  no erosion-wheel integration at all (direction, comment 55251: "src/divIntegration and provider
  metadata currently disappear from a normal build, leaving a nonfunctional wheel-erosion
  artifact"). `ci.yml`'s "Build and test" step now builds DIV fresh from a pinned source commit
  (`div_pinned_ref` in `gradle.properties`, an immutable git commit SHA -- itself a cryptographic
  hash of the exact tree content, the real source-integrity guarantee), verifies exactly one jar
  was produced, and only then builds this mod against it -- the exact route
  `div-paired-runtime.yml` already proved, reused here for the real uploaded artifact rather than
  only an opt-in smoke test. No publication/deployment is added by this change.
- A per-build sha256 of the produced DIV jar is logged for audit but deliberately NOT pinned as
  a cross-run equality gate: a real CI failure on this exact step showed DIV's own `jar` task
  embeds per-build timestamps (no `preserveFileTimestamps`/`reproducibleFileOrder` set in its
  build.gradle), so the same pinned commit produces a different jar sha256 on every separate
  build. `div-paired-runtime.yml`'s own equivalent step has always only recorded this hash, never
  compared it across runs, for exactly this reason -- an earlier revision of this change tried
  to gate on hash equality and failed closed on its very first real CI run once two separate
  builds of the same pinned commit were actually compared.
- Declared `dynamicimmersivevehicles` as an `optional` `neoforge.mods.toml` dependency
  (`ordering="AFTER"`), since this mod's other two consumers remain fully standalone of DIV --
  accurately declares the real runtime dependency this build now ships with, rather than
  omitting it entirely.
- Real review finding (direction comment 55286): "the new optional-DIV explanation is not true
  for the SAME compiled artifact. When src/divIntegration and META-INF/services are packaged,
  ServiceLoader sees that provider regardless of DIV presence." Correct -- the "empty iterator
  when DIV absent" guarantee only held while that provider metadata was itself conditionally
  omitted; it is now always baked into this mod's own jar, so `ServiceLoader` always finds the
  real provider class name, and resolving its real DIV-typed method signatures throws
  `LinkageError`/`ServiceConfigurationError` if DIV itself is genuinely absent at runtime. Fixed
  `LayeredSlopesDynamicImmersiveVehiclesMod`'s own registration loop to catch that per-provider
  and skip it (logging a warning) rather than crashing mod construction over a different mod's
  optional dependency being missing.
- Added `missing-div-isolation-smoke` and `with-div-isolation-smoke` to `ci.yml` itself (not a
  separate opt-in workflow), proving the fix above against the real shipped artifact. An earlier
  revision put these in `pinned-jar-smoke-acceptance.yml`, independently rebuilding this mod's
  jar "by the same recipe" ci.yml uses -- real review follow-through (direction comment 55289)
  correctly flagged that as still only proving same-recipe behavior, not that the actual
  uploaded deliverable bytes were tested (this ticket already proved jar builds aren't
  byte-reproducible across separate builds). Both jobs now `needs: build`, download the EXACT
  artifact that job uploaded via `actions/download-artifact`, and verify its sha256 against a
  value `build` recorded as its own job output before ever booting it -- never an independent
  rebuild. `missing-div-isolation-smoke` boots that exact jar with DIV's own jar deliberately
  never installed (0 registered integrations expected); `with-div-isolation-smoke` boots the
  SAME exact jar with DIV and its own real required dependencies (mts/Sable/Sable-Companion, via
  `build.gradle`'s existing `copyPairedRuntimeJars` task) genuinely present (exactly 1 registered
  integration expected) -- "test that same jar both with and without DIV," per that review
  finding's own words.
- `ci.yml`'s separate "Run GameTests" step deliberately stays DIV-free: booting a real dedicated
  server with DIV present also loads DIV itself as a mod, which pulls in DIV's own `neo_version`
  floor and its own `mts`/Sable/Sable-Companion dependencies -- already proven out by
  `div-paired-runtime.yml`'s own dedicated job. Keeping the plain boot-smoke step DIV-free avoids
  duplicating that job and keeps this one fast; only the final "Build and test" step (compile +
  package + plain unit tests, which never boots a server) gets the verified DIV jar.
