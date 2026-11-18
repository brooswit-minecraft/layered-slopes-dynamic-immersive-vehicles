bump: minor

### Added
- Opt-in `dependency-failure-acceptance.yml` CI workflow (direction, comment 54973): proves
  NeoForge's own mandatory-dependency check actually refuses to boot, with a meaningful message
  naming the specific missing mod, when `layered-slopes` or `layered-slope-grading` is absent at
  runtime -- not merely that some process exits nonzero. `build.gradle`'s new
  `-PomitRequiredDependency=<modid>` flag skips only that jar's `runtimeOnly` entry
  (`compileOnly` stays, so main code still compiles); `neoforge.mods.toml`'s own
  `type="required"` declaration for that modId is untouched, so the dependency is genuinely,
  verifiably unmet, never simulated.
