# QwikPlayer for Android

An offline video player in the spirit of the classic MX Player: browse the videos on your phone by folder, play them with gesture controls, resume where you stopped. Package `com.qwikplayer.player`, minSdk 21, target 34. Plain Java and framework APIs only (`android.media.MediaPlayer` + `TextureView`): no AndroidX, no ExoPlayer, no Gradle.

This is a separate app from `..\android` (QwikPlayer Web, a WebView browser).

## Features

**Library**
- Folders that contain videos (MediaStore, grouped by folder), then the videos in a folder with thumbnail, duration, size and resolution.
- A "new" dot for videos you never opened, a progress bar for partly watched ones, "Watched" for finished ones.
- Sort by name, date added or size (ascending or descending), search (inside a folder, or across all videos from the folder list), pull to refresh.
- Runtime permission handling: `READ_MEDIA_VIDEO` on Android 13+, `READ_EXTERNAL_STORAGE` before; a clear screen for "denied" with a shortcut to the app's settings page; an empty-library state; "Open a file" for anything the library does not list.
- "Open with QwikPlayer" for `video/*` from other apps (`content://` and `file://`).

**Player**
- Fullscreen immersive, landscape by default (or follow the video, portrait, or the system auto-rotate setting), with a rotation lock button.
- Gestures: swipe up/down on the left half = brightness, on the right half = volume; swipe sideways = seek with a time preview; double-tap left/right = back/forward (10 s by default); single tap = show/hide controls; pinch = zoom. The aspect button cycles Fit, Fill, Crop and 100%.
- Controls: play/pause, previous/next in the folder, seek bar (brand gradient) with elapsed and remaining time, speed 0.25x to 4x (Android 6+), audio track selection, subtitles on/off, `.srt` loading (a file with the same name next to the video, or pick one) with adjustable size, touch lock, Picture-in-Picture (Android 8+), sleep timer (10 to 90 minutes or end of video).
- Resume from the last position (ask, always or never), per-file position and "watched" kept in SharedPreferences.
- Keeps the screen on while playing; pauses on headphone unplug and on audio focus loss (resumes after a short interruption such as a notification).

**Settings**: resume behaviour, double-tap seconds, subtitle size, auto-load subtitles, default orientation, auto-play next, PiP on Home, accent colour (Volt, Ember, Pulse), clear watch history.

## Build

Needs the same toolchain as `..\android\build.ps1`: JDK and Android SDK under `%USERPROFILE%\android-tools` (build-tools 34.0.0, `platforms\android-34\android.jar`).

```powershell
powershell -ExecutionPolicy Bypass -File android-player\build.ps1
# -> android-player\out\QwikPlayer.apk
adb install -r android-player\out\QwikPlayer.apk
```

The script runs aapt2 (compile, link), javac, d8 (`--min-api 21`), zipalign and apksigner. The APK is signed with the standard Android **debug** key (`%USERPROFILE%\.android\debug.keystore`, created if missing). That is fine for your own phone, not for distribution.

The launcher icons (adaptive foreground and background plus legacy mipmaps) are rendered from the brand SVGs by `tools\make-icons.py` (Python, Pillow, Microsoft Edge); the control icons are vector drawables written by `tools\make-vectors.py`. Both outputs are checked in, so a normal build does not need them.

## Limits

- **Decoders.** `MediaPlayer` plays only what the phone's own decoders support. Some MKV, AC3/DTS audio and HEVC files will not play (or play without sound) on some phones. If a file fails, the app offers "Open with..." to hand it to another player. There is no software decoder here.
- **Subtitles.** Only external SubRip (`.srt`) files; embedded subtitle tracks are not shown. Since Android 11 an app with only the video permission cannot list other files in a folder, so the "same name next to the video" auto-load works only where the system allows it; otherwise use "Choose a .srt file". Text is UTF-8 or Windows-1252.
- **Audio tracks.** Switching works for videos whose container exposes several audio tracks to `MediaPlayer`.
- **Zoom.** Pinch zooms the picture around the centre; there is no panning of a zoomed picture.
- **Background play** and PiP play/pause buttons are not implemented. Closing the PiP window stops playback.
- Progress is keyed by the video's MediaStore URI; a file opened through another app's URI is remembered separately.
- Not run on a device or emulator by the author of this change (none was available): the APK builds, links and verifies, nothing more. See "Testing" below.

## Testing

Checklist for a first run on a phone: grant and deny the permission; open a folder, sort, search, pull to refresh; play, rotate, lock rotation; each gesture; pinch and the aspect button; speed; PiP; press Home and come back (the player is released in `onStop` and comes back paused at the same position); unplug headphones; open a video from a file manager ("Open with QwikPlayer"); play an `.srt`.
