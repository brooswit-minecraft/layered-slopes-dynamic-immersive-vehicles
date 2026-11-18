bump: minor

### Added
- Opt-in `pinned-jar-smoke-acceptance.yml` CI workflow (direction, comment 54997/55004): the one
  place that boots the REAL packaged artifact, not the dev-mode `gameTestServer` source-set
  classpath every other check in this repo uses. `./gradlew jar` builds this mod's actual
  distributable jar, a real NeoForge-installer-produced dedicated-server distribution is
  installed, and the real jars are dropped into its `mods/` folder.
  - `replacement-stack-smoke`: boots this mod's packaged jar together with the pinned
    `layered-slopes`/`layered-slope-grading` release jars, requiring the real `Done (` startup
    line and all 3 expected mod ids in the real `Mod List`.
  - `base-mods-isolation-smoke`: boots `layered-slopes`/`layered-slope-grading` ALONE, this
    mod's jar deliberately absent, proving neither depends on this compat mod and both boot
    standalone with no mutual linkage.

  No GameTests run in either job (a real production server has no GameTest harness); success
  evidence is the real startup/mod-list content, never a bare process exit code.
