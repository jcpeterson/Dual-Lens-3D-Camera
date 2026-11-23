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

## 2026-11-23 - Compatibility test

Settings > Debugging > Compatibility test (`CompatibilityTester`) checks whether a phone supports
stereo capture with this app: lists listed and hidden camera IDs, finds the rear pair with
`LensDiscovery`, captures one simultaneous YUV still from both lenses (blank check by luma deviation,
timestamp gap; warm-up frames are drained first), and records 1.5 s into two surface-input H.264
encoders with the same layout as the recording session (preview + two encoders; blank-frame check by
bytes per frame), retrying with the main lens on the logical stream. Reports YES / YES (with
workaround) / NO for photos and video with a share button.

On `ruby`: photos YES (0.00 ms between lenses), video YES with workaround (direct main-lens encoder
~52 bytes/frame, logical stream ~35 kB/frame). Without the preview stream the direct path also
passes, which is why the test mirrors the app's session.

## 2026-11-23 - Hidden logical rear camera on Redmi Note 12 Pro 5G

### Finding

The schema-2 dump from `ruby` (`OS2.0.15.0.UMOINXM`) shows `getCameraIdList()` = `0, 1`, but IDs
`2`, `3` and `4` answer direct `getCameraCharacteristics` and open alone from a third-party app:

- `2`: rear ultrawide, 1.66 mm, 3.65x2.74 mm sensor, 3264x2448.
- `3`: rear macro, 1.98 mm, 1600x1200.
- `4`: rear `LOGICAL_MULTI_CAMERA`, FULL level, sync type APPROXIMATE, physical IDs `0` (main)
  and `2` (ultrawide).

Opening `2`, `3` or `4` while `0` is open fails with `ERROR_MAX_CAMERAS_IN_USE`, which is expected
for two independent devices and says nothing about physical streams under the logical camera.
`concurrentCameraIds` is empty.

### Change

`LensDiscovery` now considers numeric IDs `0..15` that the list omits, after the listed IDs, when
searching for a rear logical multi-camera. Lookups of absent IDs throw and are skipped.

### On-device results (agent build, USB)

- Discovery picks logical `4` (wide `0`, ultrawide `2`); 2x mode is unavailable because no
  half-size wide physical camera exists. Stills capture both lenses simultaneously and SBS output
  works.
- Video initially produced a valid ultrawide file but a wide file of uniform green frames
  (all-zero YUV) at every resolution. The HAL accepted both encoder streams
  (`physicalCameraId:0` and `:2`, `HW_VIDEO_ENCODER`) but its feature policy planned no record
  stream (`rec[0xffff...]`) for the master physical camera.
- A repeated ISP log line, `TG size is smaller than pipeline min width size(1920/1632)`, also
  appears in working sessions and at other video sizes; it is not the cause.
- Fix: `StereoRig.logicalIsHidden` marks a logical camera found only by ID probing, and the
  recording session then binds the wide encoder surface to the logical stream (no
  `setPhysicalCameraId`), which the HAL serves from the wide sensor at 1x. Preview, ultrawide and
  stills are unchanged; listed-logical devices (Pixel, Samsung) keep the old path.
- Verified at 1440x1080 and 1920x1080: both MP4s carry real, simultaneous footage
  (about 33 MB each for 5.5 s, 15 fps setting).

### Verification

`:app:assembleAgent :app:testAgentUnitTest :app:lintAgent` pass; lint reports only the three
pre-existing `StereoCameraController` warnings. Installed and exercised on `ruby` over ADB.

### Narrowing the video cause

A later two-encoder test (see the compatibility test entry) showed the main-lens encoder bound to
physical `0` records real frames when it is the only stream on that lens, and blank frames
(~52 bytes/frame) when a preview stream on physical `0` runs next to it, which is the app's
recording layout. With the main-lens encoder on the logical stream it records ~35 kB/frame.

### Open items

- Video runs at the 15 fps setting; higher rates through logical `4` untested.
- Eye order: on `ruby` held in upright landscape the main lens is the left eye (measured from
  parallax on objects at known depths), so the ultrawide-left SBS layout came out depth-reversed.
  `alignAndCreateSbsJpegBytes(wideOnLeft = true)` is now used for hidden-logical devices only;
  other phones keep ultrawide-left.
- Automation note: check `mCallState`, the keyguard and the foreground activity before every
  injected input; the test phone received calls and locked itself during UI automation.

## 2026-11-23 - Redmi Note 12 Pro 5G investigation and hidden camera ID probe

### Device finding

A camera dump from a Redmi Note 12 Pro 5G (`22101316I`, codename `ruby`, MediaTek `mt6877`,
HyperOS `OS2.0.15.0.UMOINXM`, Android 14) showed that `getCameraIdList()` returns only `0` (rear
main, 5.59 mm, 8.19x6.14 mm sensor, LEVEL_3, no physical sub-cameras) and `1` (front). There is no
rear `LOGICAL_MULTI_CAMERA` and the 8 MP ultrawide and 2 MP macro are not listed, so
`LensDiscovery` finds no stereo rig and the app cannot run on this firmware as-is.

### Change

`DebugDump` (schema 2) now writes a `hiddenCameraProbe` section. For IDs `0..15` missing from the
list it attempts `getCameraCharacteristics`, then (with CAMERA permission) opens the ID alone and
again while the listed rear camera is open, recording status and `CameraDevice` error codes. It also
records `concurrentCameraIds` on API 30+. Responsive hidden IDs get the full per-camera dump. Some
Xiaomi builds filter auxiliary lenses from the list but still answer direct lookups; this checks
whether `ruby` does.

### Rejected or deferred

- Root plus aux-camera property overrides: needs bootloader unlock (restricted on HyperOS) and the
  common props target the Qualcomm camera HAL, not MediaTek.
- If the probe finds nothing, the fallback directions are a guided two-shot (slide) stereo mode and a
  side-by-side mirror adapter mode (for example a Kula Bebe-style four-mirror clip-on) on camera 0.

### Verification

`:app:assembleAgent :app:testAgentUnitTest :app:lintAgent` pass (JDK 23, SDK at a non-default
path with `platforms;android-34` and `build-tools;35.0.0` added). The older `cmdline-tools`
`sdkmanager` misparses JDK 23's version string and needs `SKIP_JDK_VERSION_CHECK=1`. On-device
dump from `ruby` still pending.

## 2026-07-12 - Establish a reproducible agent-development baseline

### Starting point

The `0.75.1` cleanup had been committed and tagged locally, but `master`, `origin/master`, and the
remote tag set still pointed to the old `0.75` state. The project built successfully, but continued
agent work exposed several environment and safety problems:

- Android Studio metadata remained visible as an untracked `.idea/` directory and included local
  device-selection state.
- The owner's everyday app was the normal debug APK, so running connected instrumentation against
  `debug` could uninstall that package and erase its private settings.
- The build actually used AGP 8.2.2 and Kotlin 1.9.22, while the wrapper requested Gradle 8.13 and
  the partially used version catalog declared different plugin and dependency versions.
- The wrapper properties requested Gradle 8.13, but the checked-in wrapper JAR came from Gradle
  9.0/9.1.
- Command-line shells found Java 8 unless `JAVA_HOME` was overridden manually.

### Decisions and changes

- Fast-forwarded `master` through the cleanup and documentation commits, then pushed `master`,
  `experiment/tonemap-0.75`, and `v0.75.1` to `origin`.
- Added a separate `agent` build type with application ID
  `com.example.duallens3dcamera.agent` and an `-agent` version-name suffix. The normal `debug`
  package remains the owner's personal app, and the agent launcher label is visibly distinct.
- Configured instrumentation to target only `agent`; agent test/install cleanup can no longer
  remove the personal debug package.
- Made `gradle/libs.versions.toml` the single source for plugin and dependency versions while
  preserving the app's existing runtime dependency versions.
- Aligned on Gradle 8.13, AGP 8.13.2, and Kotlin 2.3.21. The app still targets Java 17 bytecode.
- Regenerated all wrapper files from Gradle 8.13 and added the official binary-distribution SHA-256
  checksum. The regenerated wrapper JAR matches Gradle's published 8.13 checksum.
- Persisted the primary Windows user's `JAVA_HOME` to Android Studio's bundled JBR 21 without
  changing `PATH` or committing a machine-specific JDK path.
- Ignored `.idea/` and `.kotlin/`, added cross-platform line-ending rules, and added `AGENTS.md` with
  build, device-safety, verification, and history guidance.

The first wrapper-regeneration command completed Gradle's `wrapper` task successfully but returned
a transient batch error because `gradlew.bat` replaced itself while the running batch process was
returning. The generated files were intact; their hashes were verified before continuing.

### Verification

- `gradlew --version` reported Gradle 8.13 on JetBrains JBR 21.0.8.
- Both `assembleDebug` and `assembleAgent` passed from a clean build.
- Both `testDebugUnitTest` and `testAgentUnitTest` passed.
- `lintAgent` passed with 0 errors and 64 warnings.
- Running with `--warning-mode all` produced no previous AGP/Gradle-9 incompatibility warnings.
- The personal APK remained `com.example.duallens3dcamera`, version `0.75.1`, code 8.
- The agent APK reported `com.example.duallens3dcamera.agent`, version `0.75.1-agent`, code 8.
- `connectedAgentAndroidTest` passed on the Pixel 7 Pro and left the personal debug package
  installed and unchanged.
- The agent APK installed alongside the personal app, cold-launched into `MainActivity`, and
  produced no fatal startup exception in the checked process log.

### Remaining work

The project still has template-level automated tests and source/lint warnings worth addressing over
time. Public-release preparation also still requires an intentional application ID, current target
SDK, durable release signing, licensing, CI, and store/privacy assets.

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
Plugin 8.2.2. Rerunning Gradle with Android Studio's bundled JBR 21 runtime (21.0.8 at the time)
resolved that environment problem without changing the app's Java 17 bytecode target. The build
emitted only an SDK XML tooling-version warning.

### Testing caveat

The connected instrumentation task uninstalled its temporary app package after testing. That
cleared app-private preferences and may also have cleared app-private files. The final APK was
reinstalled and launched afterward. MediaStore photos were not targeted, but custom app settings
needed to be configured again.
