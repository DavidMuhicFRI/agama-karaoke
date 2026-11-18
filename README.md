# Karaoke for AGAMA

Full-screen, synced lyrics over the AGAMA Car Launcher on an Android head unit.

- Follows whatever is playing (Spotify etc.) through Android media sessions
- Synced lyrics from [LRCLIB](https://lrclib.net), cached on the device for offline replay
- Hides itself while the car is moving (GPS speed gate), optional auto-show when stopped

## Install

Open this on the head unit's browser and install:

**https://github.com/DavidMuhicFRI/agama-karaoke/releases/latest/download/karaoke.apk**

Then open **Karaoke Setup**, grant the permissions, and add **Karaoke** as a shortcut in AGAMA.

Every push to `main` builds a new signed APK via GitHub Actions; installing it updates the app in place.

## How it works

| Part | File |
| --- | --- |
| Playing song and position | `media/MediaTracker.kt` |
| Lyrics lookup and cache | `lyrics/LyricsRepository.kt`, `lyrics/LrcParser.kt` |
| Overlay drawing | `overlay/LyricsView.kt`, `overlay/OverlayController.kt` |
| Speed gate | `SpeedMonitor.kt` |
| Glue | `KaraokeService.kt` |
