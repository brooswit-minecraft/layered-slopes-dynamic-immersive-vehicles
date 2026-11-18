bump: minor

### Added
- Opt-in `incompatible-version-acceptance.yml` CI workflow (direction, comment 54997): proves
  NeoForge's own `versionRange` check actually refuses to boot, with a message attributing THIS
  mod's own requirement and naming the real installed (too old) version, when `layered-slopes`
  or `layered-slope-grading` IS present but below the declared `[0.2.0,)` floor -- the companion
  case to `dependency-failure-acceptance.yml`'s missing-dependency case, which only covers fully
  absent. `build.gradle`'s new `-PincompatibleVersionDependency=<modid>` flag swaps only that
  jar's `runtimeOnly` entry for a real, pinned, sha512-verified v0.1.0 release of the same mod
  (`compileOnly` stays on the normal, current, compatible jar, so main code still compiles).
