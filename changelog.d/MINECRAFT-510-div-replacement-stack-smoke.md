bump: minor

### Added
- Opt-in `div-replacement-stack-smoke.yml` CI workflow (direction, comment 55019/55032): reuses
  `div-paired-runtime.yml`'s existing immutable-pinned-commit-SHA DIV source build -- "no stable,
  pinnable DIV release" is not a blocker for this.
  - `div-alone-isolation-smoke`: builds DIV fresh from the pinned SHA, resolves DIV's own real
    required runtime dependencies (mts, Sable, Sable Companion) via the new `copyPairedRuntimeJars`
    Gradle task, and boots a real NeoForge dedicated server with ONLY DIV + those deps -- this
    compat mod and layered-slopes/layered-slope-grading deliberately absent -- proving DIV boots
    standalone with no dependency on either.
  - `full-replacement-stack-smoke`: additionally builds this mod's own real packaged jar and the
    pinned layered-slopes/layered-slope-grading release jars, and boots the real, complete 7-modid
    replacement stack together, requiring the real `Done (` line, all 7 expected mod ids,
    `Found 0 mod requirements missing`, a real graceful shutdown, and the real generated config
    defaulting `erosionEnabled` to `false`. Records an immutable sha256/sha512 artifact-hash
    manifest naming the DIV commit SHA and every jar's hash.

  Neither job runs any GameTest (a real production server has no GameTest harness); success
  evidence is the real startup/shutdown/mod-list/config/manifest content, never a bare process
  exit code.

- Fixed a latent bug in the pre-existing `pairedRuntimeManifest` Gradle task: its artifact-key
  construction used a Groovy GString (`"${a}:${b}"`) directly in `Set.contains(...)`, which
  silently returns `false` even when the string content matches, because `GString.hashCode()`
  does not equal the equivalent `String.hashCode()`. This had been silently masking real CI
  evidence in every past `div-paired-runtime.yml` run, always falling through to its
  "(no paired-runtime dependencies resolved)" fallback. Fixed by converting to a real `String`
  via explicit `.toString()` before any Set/Map comparison, shared by the new
  `copyPairedRuntimeJars` task.
