bump: minor

### Added
- Initial repository scaffold.
- Pure erosion policy/config module (`erosion` package): deterministic slip/load/resistance
  mapping and immutable hot-reloadable config, per the accepted MINECRAFT-555 contract
  (comments 54607/54611/54614/54618). No Minecraft/NeoForge dependency yet; the NeoForge mod
  scaffold, DIV/Layered Slopes/Layered Slope Grading integration, protection gate, and GameTest
  acceptance evidence are deferred to a later slice of this ticket's bounded implementation
  sequence (see README.md Status).
