bump: minor

### Added
- DIV's wheel-contact consumer (`src/divIntegration`, PR #17) now ships in the actual build
  artifact CI uploads as "layered-slopes-dynamic-immersive-vehicles" -- previously it only
  existed in opt-in `-PdivLocalJar` test builds, so the real, intended ordinary deliverable had
  no erosion-wheel integration at all (direction, comment 55251: "src/divIntegration and provider
  metadata currently disappear from a normal build, leaving a nonfunctional wheel-erosion
  artifact"). `ci.yml`'s "Build and test" step now builds DIV fresh from a pinned source commit
  (`div_pinned_ref` in `gradle.properties`), verifies the resulting jar's sha256 against a pinned
  value (`div_pinned_jar_sha256`) before using it, and only then builds this mod against it --
  the exact immutable-source-pin-and-verified-jar-hashing route `div-paired-runtime.yml` already
  proved, reused here for the real uploaded artifact rather than only an opt-in smoke test. No
  publication/deployment is added by this change.
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
