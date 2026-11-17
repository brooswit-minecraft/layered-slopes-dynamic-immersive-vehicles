bump: minor

### Added
- Initial repository scaffold.
- Pure erosion policy/config module (`erosion` package): deterministic slip/load/resistance
  mapping and immutable hot-reloadable config, per the accepted MINECRAFT-555 contract
  (comments 54607/54611/54614/54618). No Minecraft/NeoForge dependency yet; the NeoForge mod
  scaffold, DIV/Layered Slopes/Layered Slope Grading integration, protection gate, and GameTest
  acceptance evidence are deferred to a later slice of this ticket's bounded implementation
  sequence (see README.md Status).

### Fixed
- `ErosionMath.successChance` now explicitly no-ops on zero/negative `wheelLoadKg` instead of
  letting `loadFactor`'s clamp pull it up to `minLoadFactor` and return a nonzero chance for a
  wheel bearing no physical load (PR #1 review finding).
- Fails closed to 0.0 at the `successChance` integration boundary on non-finite runtime inputs
  (NaN/Infinity slip, load, hardness, or blast resistance) or an invalid config snapshot, instead
  of propagating NaN or saturating to a maximum chance.
