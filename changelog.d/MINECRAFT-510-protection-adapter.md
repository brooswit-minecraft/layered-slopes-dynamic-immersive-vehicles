bump: minor

### Added
- `SmoothingScopeProtectionAdapter`: a real, not stand-in, `ErosionProtectionGate.ProtectionAdapter`.
  Correct specifically because `Smoothing.smooth()` mutates exactly `source` and
  `source.relative(direction)` and nothing else -- there is no neighbor/cave-in propagation scope
  for a consumer built on that primitive to protect against, a provable fact rather than an
  unimplemented placeholder boolean. `authorize()` denies any request whose destination is not
  exactly one cardinal step from its source (scope-creep defense) and otherwise defers the real
  source/destination decision to `Smoothing.smooth()`'s own already-wired
  `SmoothingAuthorizationEvent`, since this adapter cannot itself verify whether a real
  third-party claim mod is actually listening. 7 unit tests cover: unconditional capability
  grants, a genuine single-cardinal-step request in each of the 4 horizontal directions, denial
  when propagation scope is not known, and denial of vertical/diagonal/multi-block/same-position
  scope creep.

Building this class does not by itself enable erosion: `ErosionAttempt`'s production overloads
still pass `propagationScopeKnown=false`, and nothing installs this adapter on the gate yet --
wiring it in as the default, and an explicit default-OFF config switch, remain deliberate,
separate next decisions.
