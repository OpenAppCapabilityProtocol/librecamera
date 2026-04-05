# Libre Camera — OACP Context

## What this app does
A free, open-source camera app for Android. Takes photos and records video using
the front (selfie) or rear (back) camera. Supports countdown timers before
capturing a photo.

## Capabilities
- take_photo_front_camera: Take a photo with the front (selfie) camera. Accepts optional `duration_seconds` countdown.
- take_photo_rear_camera: Take a photo with the rear (back) camera. Accepts optional `duration_seconds` countdown.
- start_video_recording_front_camera: Start recording video with the front camera.
- start_video_recording_rear_camera: Start recording video with the rear camera.

## Disambiguation
- "take a selfie" / "front camera photo" / "selfie in 5 seconds" → take_photo_front_camera
- "take a photo" / "capture a picture" / "take a picture in 4 seconds" → take_photo_rear_camera (rear is default)
- "record a selfie video" / "front camera video" → start_video_recording_front_camera
- "record a video" / "start recording" / "back camera video" → start_video_recording_rear_camera (rear is default)

## Vague command examples
- "snap a pic" → take_photo_rear_camera
- "get a shot of that" → take_photo_rear_camera
- "film this" → start_video_recording_rear_camera
- "take a quick selfie" → take_photo_front_camera
