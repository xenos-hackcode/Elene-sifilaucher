# Settings wallpaper update

Settings > Wallpaper now has Image, Video and Live tabs, a portrait preview, separate Apply and Save controls, and a terminal-style collection. Browsing changes only the preview. Apply saves the selected launcher background.

New designs: Source / XENOS (image), Terminal / Stream (animation), Circuit / Touch (interactive). A shared XENOS identity overlay is drawn in launcher backgrounds and supported exports. Globe wallpapers retain their existing separate viewer, now with the identity overlay.

Collection thumbnails are stills. Interactive previews start only after tapping Start Preview; camera and microphone designs request permission when needed. Stopping preview, switching designs, leaving the screen or backgrounding the activity releases the preview composition. Some live thumbnails are illustrative stills, and globe/camera thumbnails are placeholders.

Supported images save as PNG and supported animations as six-second H.264 MP4 on Android 10+. MP4 rendering uses an EGL input surface with explicit frame timestamps. Encoding checks coroutine cancellation and bounds output draining. MediaStore entries remain pending until writing succeeds; failures clean up partial entries. Interactive scenes and globe views do not support file export.

## Device verification still required

- Browse all three tabs: no wallpaper is applied until Apply is pressed.
- Preview and apply all three new designs; tap the circuit scene and check its response.
- Confirm XENOS remains visible in existing animated/live designs and the globe viewer.
- Start/stop camera and microphone previews, deny permission, switch designs, and background the app; check that resources stop.
- Save a PNG and MP4; inspect their orientation, XENOS branding, video timing and playback.
- Leave during MP4 encoding and verify no broken file appears in the gallery.
- Check small displays, landscape, large system fonts and battery-saver mode.
