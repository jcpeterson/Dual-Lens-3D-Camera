# Development Log

This is a running record of significant features, investigations, decisions, failed approaches,
and verification for Dual Lens 3D Camera. Newest entries go first. This complements `README.md`,
which describes the app's current behavior and architecture.

When adding an entry, capture enough context that a future maintainer or agent can understand:

- what prompted the work;
- what was investigated, including approaches that failed or were deliberately rejected;
- what was changed and deliberately left unchanged;
- how the result was verified; and
- which commits, branches, or tags make the work easy to revisit.

## 2026-07-11 - Remove unsupported tonemap controls (`0.75.1`)

### Starting point

Work resumed after a hiatus with the recollection that the manual Camera2 tonemap controls did not
work as intended on Pixel phones. The first goal was to determine how the feature entered the
history, whether it could be cleanly rolled back, and which unrelated improvements needed to
survive.

The starting revision was `38143ea` (`0.75`) on both `master` and `origin/master`.

### Investigation

The feature began in `e4b4113` (`tonemapping control`) and was revised across the next four commits
through `38143ea`. The initial commit was cohesive but larger than its title suggested: it added
manual tonemap modes and curves, logical and physical Camera2 request handling, diagnostics,
settings UI, and AE exposure compensation. The AE control was only a workaround for the dark
images produced by the manual curves.

The four follow-up commits included both feature refinements and unrelated work:

- `de667c6` moved AE compensation into a live viewfinder overlay; it was entirely coupled to the
  tonemap experiment.
- `a71832d` revised curve sampling but also improved stereo-image resizing and affine warping with
  cubic interpolation.
- `af4b051` revised the S-curve but also stopped constraining preview FPS.
- `38143ea` revised hardware curve-point handling and changed the displayed version to `0.75`.

Approaches considered but rejected:

- Directly reverting only `e4b4113`: later commits depended on its symbols, a trial revert produced
  conflicts in the camera controller and settings, and the remaining exposure overlay would not
  compile cleanly.
- Resetting or rebasing back before the experiment: this would discard the unrelated interpolation
  and preview-FPS work and rewrite history already present on `origin/master`.
- Merely hiding the preference: an existing stored non-Auto value could remain active, and the dead
  implementation would still be carried indefinitely.
- Keeping a permanent archive branch: normal Git history plus a named tag already preserves the
  experiment without another branch to maintain.

### Decision and changes

The five contiguous commits were reversed without committing, then only the audited keep-list was
reapplied. The resulting forward cleanup was committed as `6ddc935` (`Remove unsupported tonemap
controls`) on `codex/remove-tonemap`.

Removed:

- all manual tonemap modes, curves, Camera2 request keys, capability checks, and result diagnostics;
- all tonemap settings and UI;
- the coupled AE-compensation preference, request behavior, and viewfinder overlay; and
- experimental helpers, imports, comments, and logging that became unused.

Deliberately preserved:

- cubic interpolation for stereo-image resizing;
- cubic affine warping when enlarging the ultrawide image, with linear interpolation otherwise;
- unlocked preview FPS behavior; and
- generic exposure-compensation capture metadata logging that predates the experiment.

The app version became `0.75.1` with Android `versionCode` 8. Future releases will use a
`major.minor.patch` display version and a monotonically increasing Android version code. The next
feature release would therefore be `0.76.0` with code 9.

### History and revisit points

- `experiment/tonemap-0.75` bookmarks the complete old implementation at `38143ea`.
- `v0.75.1` marks the verified cleanup at `6ddc935`.
- At the end of this entry, the branch and tags were local; `master` still pointed to `38143ea`, and
  nothing had been pushed or merged.

### Verification

- `:app:assembleDebug` passed.
- `:app:testDebugUnitTest` passed.
- `:app:connectedDebugAndroidTest` passed on a connected Pixel 7 Pro.
- The generated APK reported `versionName` `0.75.1` and `versionCode` 8.
- The APK installed on the Pixel 7 Pro, cold-launched successfully, resumed into `MainActivity`, and
  produced no startup fatal exception or security error in the checked process log.
- Compared with the pre-tonemap revision `93b2ab1`, only the version, stereo interpolation, and
  preview-FPS behavior remained different.

The command-line build initially selected Java 8 and failed while configuring Android Gradle
Plugin 8.2.2. Rerunning Gradle with Android Studio's bundled Java 17 runtime resolved that
environment problem without changing project configuration. The build emitted only an SDK XML
tooling-version warning.

### Testing caveat

The connected instrumentation task uninstalled its temporary app package after testing. That
cleared app-private preferences and may also have cleared app-private files. The final APK was
reinstalled and launched afterward. MediaStore photos were not targeted, but custom app settings
needed to be configured again.
