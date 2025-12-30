# Tunas - Jazz Tune Player for Android

A simple Android app for browsing and playing jazz tunes with chord sheets.

## Features

- Browse jazz tune folders alphabetically
- Filter tunes by name
- Random tune selection
- View chord sheets (images) with swipe navigation
- Audio playback with controls:
  - Play/Pause
  - Stop
  - Rewind 5 seconds
  - Forward 5 seconds
  - Loop toggle
  - Seek bar for position
- Swipe between audio files

## Requirements

- Android 5.0 (API 21) or higher
- Pixel 4 compatible
- Storage permission for reading tune files

## Directory Structure

The app reads from `/storage/emulated/0/Tunas/` directory.

Each tune should be in its own subfolder:

```
/storage/emulated/0/Tunas/
├── All The Things You Are/
│   ├── chords.jpg
│   ├── backing_track.mp3
│   └── melody.mp3
├── Autumn Leaves/
│   ├── chart.png
│   └── play_along.mp3
└── Blue Bossa/
    ├── sheet1.jpg
    ├── sheet2.jpg
    └── audio.mp3
```

### Supported File Formats

- **Images**: .jpg, .jpeg, .png, .gif
- **Audio**: .mp3, .wav, .m4a, .ogg

## Building with android-build-box

```bash
# Using docker
docker run --rm -v $(pwd):/project mingc/android-build-box bash -c "cd /project && ./gradlew assembleDebug"
```

The APK will be in `app/build/outputs/apk/debug/app-debug.apk`

## Project Structure

```
Tunas/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/tunas/app/
│   │   │   │   ├── MainActivity.java
│   │   │   │   └── PlayerActivity.java
│   │   │   ├── res/
│   │   │   │   ├── layout/
│   │   │   │   │   ├── activity_main.xml
│   │   │   │   │   └── activity_player.xml
│   │   │   │   ├── values/
│   │   │   │   │   └── styles.xml
│   │   │   │   └── mipmap/
│   │   │   │       └── ic_launcher.png (tuna icon)
│   │   │   └── AndroidManifest.xml
│   │   └── build.gradle
├── build.gradle
├── settings.gradle
└── gradle.properties
```

## Installation

1. Create the Tunas directory on your device
2. Add tune folders with images and audio files
3. Install the APK
4. Grant storage permission when prompted
5. Browse and play your jazz tunes!

## Usage

### Main Screen
- View alphabetically sorted list of tune folders
- Use the filter box to search for specific tunes
- Tap "Random" to select a random tune
- Tap any tune name to open it

### Player Screen
- **Upper half**: Swipe left/right to navigate through chord sheet images
- **Lower half**: Audio controls
  - Top: Current audio file name (swipe to change files)
  - Middle: Seek bar for playback position
  - Bottom: Control buttons
    - ⏪ Rewind 5 seconds
    - ▶/⏸ Play/Pause
    - ⏹ Stop
    - ⏩ Forward 5 seconds
    - ↻/🔁 Toggle loop
- Press back button to return to tune list

## Icon

Replace `app/src/main/res/mipmap/ic_launcher.png` with a tuna icon (recommended 192x192px).

## License

This is a simple app for personal use.