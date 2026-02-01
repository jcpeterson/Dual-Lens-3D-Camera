# Dual Lens 3D Camera

An android app (Pixel 7+ and newer Samsung S-series) for taking stereo photos and video using
the wide and ultrawide lenses.

It's currently tested and working on:
- Samsung S24
- Pixel 7, Pixel 7a, Pixel 7 Pro, Pixel 8, Pixel 8 Pro and Pixel 9a

So far, photo and video sync is best on Pixel 7 series (~4ms).

### Where to find the APK

Check the latest release [here](../../releases).

### How to use it
- Pixels: hold the phone in **portrait**.
- Samsung: hold the phone in **landscape**.
- Press the PHOTO or RECORD buttons to take photos or video.
- Use 1x/2x toggle to zoom (2x uses wide sensor crop when a device supports/exposes it).
- Outputs are stored in `Pictures/StereoCapture`.

### Known issues

- RAW shooting currently does not work on Samsung devices.
- Left-Right photo sync is ~30ms on Samsung.
- Left-Right photo sync is ~60ms on Pixel 8 Pro.

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
- Key fields:
    - `frameNumber`: `TotalCaptureResult.frameNumber` for the capture request.
    - `sensorTimestampNs`: `CaptureResult.SENSOR_TIMESTAMP` (nanoseconds; typically REALTIME on Pixel).
      This is the **start of exposure** of the first sensor row.
    - `exposureTimeNs` (full mode): `CaptureResult.SENSOR_EXPOSURE_TIME`.
    - `codecPresentationTimeUs` (full mode): `MediaCodec.BufferInfo.presentationTimeUs`.
    - `muxerPresentationTimeUs` (full mode): the PTS written to `MediaMuxer` (per-track normalized to
      start at 0 in this app).

Output files:
- Photos/videos are saved to `Pictures/StereoCapture` with timestamped filenames:
  - `*_wide.*`, `*_ultrawide.*`, `*_sbs.jpg` (JPEG mode), and optional `*_stereo.json`.
