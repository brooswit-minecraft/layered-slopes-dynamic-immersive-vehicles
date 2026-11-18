bump: minor

### Added
- Real NeoForge mod scaffold: main mod class, `neoforge.mods.toml`, pinned (sha512-verified)
  dependencies on `layered-slopes` v0.2.0 and `layered-slope-grading` v0.1.0, plus the standard
  release/CI scripts (`check_neoforge_floor.py`, `next_version.py`, `last_release_tag.py`,
  `check_gametest_count.py`, not yet wired into CI since no GameTests exist yet). Confirmed
  booting for real via `./gradlew runGameTestServer` with 0 of 9 mandatory mod requirements
  missing.
- No dependency on Dynamic Immersive Vehicles yet: it ships no stable, sha512-pinnable GitHub
  Release, so declaring it here would make the default build fail closed for everyone. Deferred
  to its own later slice.
