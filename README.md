# Dual Lens 3D Camera

An android app (Pixel 7+ phones only currently) for taking stereo photos and video using the wide 
and ultrawide lenses.

### Where to find the APK

Check the latest release [here](/releases).

### How to use it
- Hold the phone in portrait orientation only.
- Press the PHOTO or RECORD buttons to take photos or video.
- Outputs are stored in `Pictures/StereoCapture`.

### How does it work?

Modern Pixel phones arrange their wide and ultrawide lenses horizontally adjacent in the portrait 
orientation. In other words, holding the phone in portrait orientation and taking photos with both
lenses creates stereo pairs (assuming the ultrawide image is cropped correctly afterwards). 

Because modern Pixels expose their wide and ultrawide lenses are physical IDs under the same logical
camera via the Camera2 API, well-synced images (and videos) across both lenses can be obtained.

### Known issues

For video, pairs of frames from the two lenses can be out of sync by as much as little as .7ms and 
as much as 4ms. This is a relatively good result, but it's not perfect and there's essentially no
way to improve it. For most video without too much fast action, this should be fine.

