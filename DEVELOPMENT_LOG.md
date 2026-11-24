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

## 2026-11-24 - Main lens focus modes and Pro mode (ISO / shutter)

Requested while testing on the Redmi Note 12 Pro: an option to make the main lens's focus match the fixed-focus ultrawide, with
everything else automatic; and a Pro mode for ISO and shutter only.

**Hardware facts** (`dumpsys media.camera`, Redmi Note 12 Pro):

| | Main (0) | Ultrawide (2) | Logical (4) |
|---|---|---|---|
| ISO | 50–6400 | 50–2000 | 100–3200 |
| Exposure | 0.1 ms – 30 s | 0.1 ms – 0.8 s | – |
| Aperture | f/1.88 | f/2.2 | – |
| Focus | AF modes 0–4, min focus 10 | AF OFF only, min focus 0 (fixed focus) | – |
| Hyperfocal | 0.1 | 1.11 (≈0.9 m) | – |
| Manual sensor | yes | yes | yes |

Focus calibration is UNCALIBRATED on both lenses.

**Implementation** (`StereoCameraController`):
- `applyManualControls()` is applied last to every request: preview, still, record and the
  ultrawide prime.
- `MainFocusMode`:
  - AUTO (unchanged);
  - MATCH_ULTRAWIDE: AF OFF, `LENS_FOCUS_DISTANCE` = the main lens's own
    `LENS_INFO_HYPERFOCAL_DISTANCE`, which is in the lens's own units, so it works uncalibrated;
  - LOCK: `lockFocusForSequence()` copies the last AF focus distance at RECORD or time-lapse start;
    `unlockFocusForSequence()` runs at the end.
- Pro: `ExposureMath.resolve()` (unit-tested). Both values set are used as is. With only one set,
  the other comes from the last auto-exposure reading, keeping ISO × time constant.
  - The result is clamped to the range both lenses support (ISO 100–2000, up to 0.8 s) and, for
    video, to one frame period.
  - AE is OFF; `SENSOR_FRAME_DURATION` is max(exposure, frame period).
- A `meterCallback` on the preview's repeating request records AE and AF values from the main
  lens's physical result, and logs "Manual check" (logical, main and ultra) every 2 s while
  anything is manual.
- **Bug found and fixed:** when the app starts with only one Pro value saved there is no reading
  yet; the fallback guess gave ISO 1667 (blown out). Changing a Pro value with the other on AUTO now
  re-meters: AE runs until CONVERGED (≥4 frames) or 20 frames, then the manual exposure is applied.
  Verified: metered ISO 222 at 1/99 s, then ISO 280 at a fixed 1/125 s.

**UI:**
- PRO button in the top bar (`weightSum` 6). Top-bar side padding is zeroed in code because the
  MaterialButton style's padding ellipsised "JPG"/"PRO" at 180 px width.
- Pro panel over the preview bottom: square chips that rotate with the landscape UI and scroll to
  the selected value. 1x/2x sits above the panel.
- "Main lens focus" ListPreference in Settings → 3D output.

**Verification on the phone:**
- Preview at ISO 200, 1/125: the main lens reported exactly ISO 200, 8 000 000 ns, AF mode OFF,
  focus 0.1 (match-UW).
- Still with ISO 200, 1/125: main ISO 200 at 1/125; **ultra ISO 156 at 1/125**. The HAL re-scales
  the ultrawide's ISO.
- **Rejected:** a still request created with both physical IDs (`createCaptureRequest(template, ids)`
  plus `setPhysicalCameraKey`) still gave 156, so it was removed. Shutter time is identical on both
  lenses.
- Lock at start: "Focus locked at 2.61" at RECORD. Both 2560×1920 videos had 190 frames each (12.8 s)
  and looked correct. The main file is smaller because of a plain white scene.
- `ExposureMathTest` (8), `LevelMathTest` (7) and the existing tests pass; lint is clean of errors.

## 2026-11-24 - Self-timer, time-lapse, grid and spirit level

Requested while testing on the Redmi Note 12 Pro: a 3/5/10 s self-timer, a time-lapse with user-chosen interval and frame count that
keeps the phone awake (assume it is charging), and a 3x3 grid or a gyro level that shows 0° in green.

- **Tools row** under the preview: Torch, Timer, Grid, Time-lapse (labels auto-size, because the
  test phone uses font scale 1.17).
- **Self-timer** (`KEY_SELF_TIMER_SEC`): countdown TextView plus a ToneGenerator beep. PHOTO becomes
  CANCEL; leaving the app cancels.
- **Time-lapse** (`capture/TimeLapseRunner`): frame i is planned at start + i·interval on the main
  looper, so timing does not drift. A frame that falls due while the previous capture is still busy
  retries every 250 ms. Frames reuse `captureStereoPhoto` with names `<start>_TL%04d`.
  - `FLAG_KEEP_SCREEN_ON` keeps the activity in front with no lock or Doze, and window brightness
    drops to 0.05 after 20 s (tap the status to brighten).
  - Minimum interval is 5 s with 3D correction on (correction runs about 2 s per photo on the
    single SBS executor) and 2 s with it off.
  - Leaving the app stops the run. A foreground service was not used: the camera already closes in
    `onPause`, and it is meant to run unattended on a charger.
- **Grid and level** (`ui/LevelGridOverlay`, `util/LevelMath`): the overlay sits over the preview
  only. The level uses `TYPE_GRAVITY` (accelerometer fallback) with light smoothing.
  - It snaps to the nearest right angle, so landscape works, and draws the true horizon at canvas
    angle atan2(gx, gy).
  - It turns green within 1° and is hidden when the phone is flat.
  - The same reading rotates the countdown digits upright.
- **Verification on the Redmi Note 12 Pro (agent build).**
  - `LevelMathTest` (6 tests) passes, and assemble and lint pass.
  - Grid+Level showed green at 0.5–0.6° with the phone propped in landscape.
  - A 3 s timer photo saved about 3 s after the tap.
  - A 6-frame, 5 s run saved TL0001–TL0006, each processed about 5.0 s apart (±0.2 s).
  - A 16-frame, 5 s run went past the phone's 60 s screen timeout: `mWakefulness=Awake`, brightness
    override 0.05 while running, and cleared afterwards. No crashes.
- **Icons (follow-up):** Material icons (Apache 2.0) were added as vector drawables:
  - photo_camera, videocam and stop on PHOTO and RECORD (stop while recording, counting down
    or running a time-lapse);
  - flash, timer, grid and timelapse above the tool labels.
  The main row shrank from 2:1 to 1.4:1 against the tools row. Labels are a fixed 12 sp and
  shortened ("Grid+Lvl", "T-lapse") so they fit at the test phone's 1.17 font scale; autosizing gave
  each button a different size.
- **Landscape labels (follow-up, requested while testing):** the activity stays portrait-locked; only
  content turns.
  - `ui/RotatingButton` (a MaterialButton) rotates the canvas inside `onDraw`. The background is
    drawn earlier in `View.draw`, so only the text and icon turn, while the background and touch
    area stay put.
  - `LevelMath.uiRotation` picks 0, ±90 or 180 from the gravity reading, switching only within
    35° of the new orientation (unit-tested). A flat phone keeps the current value.
  - All 11 buttons animate their content (220 ms, shortest direction); the countdown TextView
    rotates with them.
  - The time-lapse bar is rotated and moved to the preview edge that is "up" for the user.
  - SETTINGS shows as a gear only in landscape, because the turned word doesn't fit the 48 dp bar.
    "STOP TIME-LAPSE" becomes "STOP".
  - Checked on the Redmi propped in landscape: all labels, the level text and the time-lapse bar
    read upright and consistent. Not turned: AlertDialogs, the Settings screen and toasts.
- **Automation note:** HyperOS dropped the first injected tap after each `am start` ("Dropping event
  because the pointer is not down"); a second tap works. This was not seen as an app bug.

## 2026-11-23 - Unplayable `.pending-` videos: encoder size limit and crash on stop

**Symptom.** Three 30–50 s recordings on the Redmi Note 12 Pro were left as
`.pending-<expiry>-…_wide/ultrawide.mp4`. They had no `moov` box, so they wouldn't play. A 2 s recording
made the same evening was fine.

**Cause, from `logcat -b crash` and the ANR log.**
- The default video size was the largest common 4:3 camera size, 3264×2448. The MediaTek H.264
  encoder (`c2.mtk.avc.encoder`) declares a maximum of 3840×2176, so 2448 rows is out of range. Two
  encoders at that size only managed about 3 fps; the codec service grew to about 6 GB of
  DMA-BUF, and the UI hit an ANR.
- On stop, `VideoEncoder.stopAndRelease()` waited 5 s for EOS, then released the codec while its
  drain thread was still inside `dequeueOutputBuffer`. The resulting `IllegalStateException`
  ("Invalid to call at Released state") was uncaught on the drain thread and killed the process
  before the muxers stopped and before `IS_PENDING` was cleared.

**Fix.**
- The drain and feed threads (video and audio) catch everything. If EOS doesn't arrive in 5 s they
  are told to abandon the rest, and the codec is released only after they have exited. The muxers
  always reach `stop()`, so the file keeps whatever was recorded.
- `AppSettings.encoderSupportsStereo(size, fps)` checks the hardware AVC encoder:
  - the size, padded to the encoder's block alignment, must be supported. Without the padding,
    1080-row sizes were wrongly rejected.
  - one stream at twice the frame rate must be covered by a declared performance point.
- Video sizes and frame rates that fail the check are no longer offered. The default is the
  largest 4:3 size that records at 30 fps. A saved size the encoder can't take is replaced at
  launch.
- On this phone 3264×2448 is dropped, and the default becomes 1440×1088 at 30 fps.

**Verification.**
- `:app:assembleAgent :app:testAgentUnitTest :app:lintAgent` pass.
- Agent build on the Redmi: the resolution list tops out at 2560×1920, and a fresh install chooses
  1440×1088 at 30 fps.
- A 43 s recording at 2560×1920 / 15 fps produced two finalised MP4s (both EOS reached, no crash)
  that decode fully with ffmpeg.
- The picture from the six broken files was recovered off-device by walking the length-prefixed
  NAL units in `mdat` and borrowing SPS/PPS from a good file. Audio was not recovered.

**Not changed.** `MainActivity.onPause()` still blocks the UI thread on `controller.stop()`. A slow
stop can still cause an ANR, but the files are now finalised either way.

## 2026-11-23 - Symmetric correction, linear colour, on-device verification

### Symmetric correction (requested while testing: correct both views, not one to the other)

The ultrawide->wide similarity s*R(theta) is split: the wide image gets s^-1/2 R(-theta/2), the
ultrawide s^+1/2 R(+theta/2), and both image centres meet at the canvas centre, so scale, rotation
and the vertical/horizontal shift are shared. Canvas is the main frame scaled by s^-1/2 (about
2577x1929 per eye instead of 3264x2448), so the ultrawide is upscaled ~1.23x instead of ~1.52x.
Ultrawide distortion is still removed entirely from the ultrawide: the main stream comes from the
camera with high-quality distortion correction, so splitting k would bend its straight lines.
Implemented in `stereo_fix.symmetric_plan` and `StereoAlignMath.symmetricPlan` (unit-tested against
Python), used for photos and Make 3D video. On the test pair, final vertical mismatch 2.46 px vs
2.72 px for the wide-anchored method.

### Colour: linear transfer instead of histogram matching

On-device symmetric photos showed speckle on smooth surfaces in both halves. Measured on a real pair
(smooth-area high-pass noise): before colour main 1.30 / ultrawide 3.61; histogram matching to the
average 7.64 / 5.99; linear per-channel mean/contrast transfer (Reinhard, gain clamped 0.5-2) 1.33 /
3.62 with the same brightness balance. Histogram matching (to the main lens or to the average) was
the cause of the earlier "grain" as well. All colour paths now use the linear LUTs; the default moves
both views to the average, the "Average colour of both lenses" setting can be turned off to copy the
main lens instead.

### Rejected

- A CLAHE-style slope cap on the histogram LUT: measured worse (dark-area noise 4.80 -> 5.48 and a
  17-level brightness drop); removed.

### On-device results (Redmi, ADB)

- Compatibility test: 3D photos YES (0.00 ms between lenses), 3D video YES with workaround (direct
  main-lens encoder ~52 bytes/frame, i.e. blank; logical stream ~34.7 kB/frame), live 3D preview YES
  (18 fps). The blank main-lens video reproduces only when a preview stream on physical 0 runs next to
  the encoder on physical 0; the test now mirrors the app's 3-stream recording session for that
  reason, and its photo check waits for in-flight warm-up frames (an earlier version mis-reported a
  167 ms gap).
- Photos with the symmetric path: 221-291 inliers, scale 1.51-1.52, 3.4-4.3 s in the background.
  A shot with fingers ~5 cm from the lenses gave only 25 inliers; weak fits now reuse the last strong
  (>= 120 inliers) alignment from the session.
- Make 3D video from the app: 124 frames, 3008x832 cross-eyed with AAC audio in 25 s.
- Automation notes: the phone locks after 60 s; uiautomator dumps can go stale while the live 3D
  overlay updates, so stale dumps are now deleted first and taps are guarded by call state, keyguard
  and foreground activity.

## 2026-11-23 - Live 3D preview and compatibility test

### Live 3D preview

- Top-bar button cycles 2D / 3D (side-by-side, honouring the cross-eyed and swap settings) / ANA
  (Dubois red-cyan). `LivePreview3d` adds small (~640 wide) YUV readers for both lenses, pairs frames
  by sensor timestamp (25 ms window), warps the ultrawide with the last photo correction
  (`StereoSbs.lastAlignment`, scaled to preview size) or a centred default, composes with OpenCV on
  its own thread and shows bitmaps in an overlay `ImageView`.
- The small wide reader replaces the TextureView stream so the photo session has 4 outputs, not 5.
  Still captures that add the preview as an extra target (non-Pixel workaround) now use the
  session's actual preview surface. If the session is refused, the controller falls back to 2D and
  the UI says so. Recording keeps the original 3-stream session and shows the normal preview.
- Composition uses the still JPEG rotation, then rotates by sensorOrientation - jpegRotation to
  match how the TextureView preview appears.

### Compatibility test

Settings > 3D output > Compatibility test (`CompatibilityTester`): lists listed and hidden camera
IDs, finds the rear pair with `LensDiscovery`, captures a simultaneous YUV still from both lenses
(blank check by luma deviation, timestamp gap), records 1.5 s into two surface-input H.264 encoders
(blank-frame check by bytes per frame) and retries with the main lens on the logical stream, and
tries the 4-stream live preview setup. Reports YES / YES (with workaround) / NO for photos, video and
live preview, with a share button for the text report.

### Verification

Builds, unit tests and lint pass. Neither feature has run on a phone yet (no USB connection).

## 2026-11-23 - Combine recordings into corrected 3D video

### Change

- `tools/stereo_fix/stereo_video.py`: per-clip correction measured from 8 sampled frame pairs
  (pooled SIFT matches, same refinement as photos), one undistort/warp map, one B/G/R colour LUT,
  crop, L|R or R|L layout, optional half-width SBS, audio copied from the wide clip. On the 1080p
  Redmi test clip: 1918 inliers, median vertical error 0.6 px, 3840x1080 output in 20 s on the PC.
- App: Settings > 3D output > "Make 3D video from a recording" opens `Make3dVideoActivity`, which
  lists the app's `*_wide.mp4` / `*_ultrawide.mp4` pairs from MediaStore and runs
  `StereoVideoComposer`: MediaCodec decode of both clips (YUV_420_888 to BGR), analysis pass,
  render pass with OpenCV remap + LUT, H.264 encode via `getInputImage` (stride-aware I420 copy),
  MediaMuxer with audio passthrough. Output goes to `Pictures/StereoCapture/3d/<stem>_3d*.mp4`.
  Falls back to half-width SBS if the encoder cannot take the full width; sizes are multiples of 16.
- Per-frame colour uses B/G/R LUTs instead of Lab to avoid two colour-space conversions per frame.

### Not possible

- Focus / depth-of-field mismatch: the ultrawide keeps the background sharp while the main lens
  blurs it; detail the lens did not record cannot be restored.

### Verification

PC tool verified on a real clip (frame inspection, stream check). App code builds, unit tests and
lint pass; not yet run on the phone (no USB available). Live preview still shows one lens.

## 2026-11-23 - In-app 3D correction for photos

### Change

- `tools/stereo_fix` (Python/OpenCV) is the reference: SIFT matches, RANSAC similarity, then
  scale/rotation/vertical offset refined on vertical error only (IRLS) so horizontal parallax is
  kept, one ultrawide radial term (OpenCV k1 on coordinates normalised by the half diagonal,
  coarse/fine grid search), stereo window at the 98th parallax percentile, Lab histogram colour
  match, crop to the largest fully valid rectangle.
- `util/StereoAlignMath.kt` ports the deterministic numerics. `StereoAlignMathTest` checks it
  against a fixture exported from Python (`tools/stereo_fix/export_fixture.py`) on a real Redmi
  pair: k, a, b, ty, inlier count and window shift match exactly; the colour LUT matches within one
  level (numpy's pairwise `sum` vs sequential `cumsum` rounding at the last bin).
- `StereoSbs.correctAndCreateSbsJpegBytes` does the OpenCV part on device (SIFT + FLANN, single
  `initUndistortRectifyMap` + `remap`, `Core.LUT` per Lab channel). It returns null on any failure
  or implausible fit and the controller falls back to the original ORB alignment. 2x mode keeps the
  original path.
- New photo settings: 3D correction (default on), cross-eyed layout (R|L), swap left/right.
- Eye order: on the Redmi (hidden logical camera) the main lens is the left eye, measured from
  parallax on objects at known depths in an upright landscape shot. Other devices keep the original
  ultrawide-left layout until verified.

### Rejected

- Plain similarity fitted on all coordinates (the original app method): its horizontal term absorbs
  scene parallax and its vertical fit is pulled by distortion.
- A "lower part of the frame is nearer" heuristic for eye order: wrong on the first real test scene.
- A distortion model in the opposite direction (corrected = distorted * (1 + k r^2)): needs a
  custom inverse map; the OpenCV k1 form lets the app warp with one built-in call.

### Verification

`:app:assembleAgent :app:testAgentUnitTest :app:lintAgent` pass. On-device run pending (phone
disconnected).

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
