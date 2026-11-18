bump: patch

### Fixed
- `layered-slope-grading` dependency pin bumped from v0.1.0 to v0.2.0 (review 54899/54901):
  v0.1.0 predates `SmoothingAuthorizationEvent` entirely -- confirmed via `unzip -l` on the actual
  downloaded release jar, not just its source tag, which showed `Smoothing.class` present but
  `SmoothingAuthorizationEvent.class` absent. Any design relying on that event firing inside
  `Smoothing.smooth()` would silently never have fired it against the real pinned dependency.
  v0.2.0 is the exact version that first includes it; sha512 re-verified independently against
  the real v0.2.0 GitHub release asset.
