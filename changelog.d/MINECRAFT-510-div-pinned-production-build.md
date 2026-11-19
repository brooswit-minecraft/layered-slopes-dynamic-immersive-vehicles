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
  (`ordering="AFTER"`), since this mod's other two consumers remain fully standalone of DIV and
  the `ServiceLoader` seam already registers zero providers gracefully when DIV is absent --
  accurately declares the real runtime dependency this build now ships with, rather than
  omitting it entirely.
- `ci.yml`'s separate "Run GameTests" step deliberately stays DIV-free: booting a real dedicated
  server with DIV present also loads DIV itself as a mod, which pulls in DIV's own `neo_version`
  floor and its own `mts`/Sable/Sable-Companion dependencies -- already proven out by
  `div-paired-runtime.yml`'s own dedicated job. Keeping the plain boot-smoke step DIV-free avoids
  duplicating that job and keeps this one fast; only the final "Build and test" step (compile +
  package + plain unit tests, which never boots a server) gets the verified DIV jar.
