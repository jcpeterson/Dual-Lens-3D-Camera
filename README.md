# Dual Lens 3D Camera

An android app (Pixel 7+ and newer Samsung S-series) for taking stereo photos and video using
the wide and ultrawide lenses.

It's currently tested and working on:
- Samsung S22 (photos only), S24 and Fold5.
- Pixel 7, 7a, 7 Pro, 8, 8 Pro, 9a, and 10.

So far, photo and video sync is best on Pixel 7 series (~2ms for photos, ~4ms for video).

Phones with ultrawide autofocus (e.g., Pixel 7 Pro) will provide the best image quality.

### Where to find the APK

Check the latest release [here](../../releases).

### How to use it
- Pixels: hold the phone in **portrait**.
- Samsung: hold the phone in **landscape**.
- Press the PHOTO or RECORD buttons to take photos or video.
- Use 1x/2x toggle to zoom (2x uses wide sensor crop when a device supports/exposes it).
- Outputs are stored in `Pictures/StereoCapture`.

### Known issues

- Video recording doesn't work on Samsung S22.
- Not working at all on Samsung A or FE models.
- RAW shooting doesn't work on any Samsung device.
- Alignment not working on Samsung Fold5.
- Left-Right photo sync is ~30ms+ on Samsung.
- Left-Right photo sync is ~20ms+ on Pixel 8 / Pixel 8 Pro.

### How to assess sync between left and right photos

Method 1 (easiest):
1. Enable "Photo sync toast" in the settings.
2. A brief message will be displayed.
3. `Δstart` is the difference in sensor timestamps (wide - ultra).
4. `overlap` is the duration where both lenses were integrating light at the same time.

Method 2 (advanced / more detail):
1. Enable "Stereo photo JSON log" in the settings.
2. Take a photo. Then check your `Documents/StereoCapture` folder for the json output.

The JSON file contains several fields, such as:

- `sensorTimestampMs`: start time of exposure (milliseconds, monotonic sensor clock)
- `exposureTimeMs`: how long the sensor integrated light (milliseconds)
- `deltaStartMs`: difference in sensor timestamps (wide - ultra)
- `overlapMs`: duration where both lenses were integrating light at the same time
- `nonOverlapMs`: duration where exactly one lens was integrating (wide+ultra - 2*overlap)
- `idleGapMs`: if exposures are disjoint, time when neither lens was integrating (else 0)
- `unionMs`: total span from earliest start to latest end (overlap + nonOverlap + idleGap)
- `overlapPctOfShorter`: % of shorter exposure was that was simultaneous
- `overlapPctOfLonger`: % of longer exposure was that was simultaneous

### How to assess sync between left and right videos

1. Enable "Stereo video JSON log" in the settings.
2. Optional: Enable "frames only" for less logging detail.
3. Take a video. Then check your `Documents/StereoCapture` folder for the json output.

The JSON file contains several fields, such as:
- `frameNumber`: `TotalCaptureResult.frameNumber` for the capture request.
- `sensorTimestampNs`: `CaptureResult.SENSOR_TIMESTAMP` (nanoseconds).
This is the **start of exposure** of the first sensor row.
- `exposureTimeNs` (full mode): `CaptureResult.SENSOR_EXPOSURE_TIME`.
- `codecPresentationTimeUs` (full mode): `MediaCodec.BufferInfo.presentationTimeUs`.
- `muxerPresentationTimeUs` (full mode): the PTS written to `MediaMuxer` (per-track normalized to
start at 0 in this app).

### Implementation Details

This app uses the Android **Camera2** API to capture from the rear **logical** camera and route output
buffers to the **wide** and **ultrawide** physical cameras simultaneously (current IDs
are discovered at runtime via `LensDiscovery.kt`).

High-level flow:
- **MainActivity** owns the UI (preview, PHOTO/RECORD buttons, RAW toggle, torch, bitrate/size/EIS
  settings) and delegates camera work to `StereoCameraController`.
- **StereoCameraController.kt** is the core:
    - Opens the logical rear camera and creates two capture sessions:
        - **Preview session**: wide preview surface + ImageReaders for stereo still capture.
        - **Recording session**: wide + ultrawide `MediaCodec` input surfaces + preview surface.
    - Uses `createCaptureSessionByOutputConfigurations()` and
      `OutputConfiguration.setPhysicalCameraId()` so each output surface is bound to the intended
      physical lens.
    - Requests fixed FPS (`CONTROL_AE_TARGET_FPS_RANGE = 30..30`) and minimizes hidden cropping when
      stabilization is OFF (full `SCALER_CROP_REGION`).
    - Optionally restricts ultrawide 3A (AF/AE/AWB) to a centered region that roughly overlaps the
      wide view by setting physical `CONTROL_*_REGIONS` for the ultrawide (this does **not** crop saved
      images; it only affects metering/AF decisions).

Encoding / muxing:
- **VideoEncoder.kt**: one H.264 encoder per lens (two independent bitstreams). Uses a `Surface`
  input from the camera session. Logs both the codec output PTS and the normalized muxer PTS when
  enabled.
- **AudioEncoder.kt**: AAC encoder fed by `AudioRecord`. The same encoded audio samples are written
  into **both** MP4 files so each stereo video is self-contained.
- **Mp4Muxer.kt**: a thin synchronized wrapper around `MediaMuxer`:
    - waits to start until both audio + video tracks are added,
    - buffers samples briefly before `muxer.start()`,
    - writes samples to one MP4 file (one muxer per output video).

Optional per-recording JSON logging:
- **StereoRecordingLogger.kt** writes one `*_stereo.json` per recording (when enabled).
- Two modes exist:
    - **framesOnly**: logs only `{frameNumber, sensorTimestampNs}` per lens.
    - **full**: also logs exposure time and encoder/muxer timestamps.

Output files:
- Photos/videos are saved to `Pictures/StereoCapture` with timestamped filenames:
  - `*_wide.*`, `*_ultrawide.*`, `*_sbs.jpg` (JPEG mode), and optional `*_stereo.json`.
