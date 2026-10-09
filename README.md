# Better Video Player

Offline video player built with C++20, Qt 6 (Qt Quick/QML) and libmpv
(which bundles FFmpeg for demuxing/decoding). The look follows Blimp: black
surfaces with a yellow accent.

## Dependencies (Windows)

| Dependency | Location | Source |
|---|---|---|
| Qt 6.11.3, MSVC 64-bit | `C:\Qt\6.11.3\msvc2022_64` | qtbase, qtsvg and qtdeclarative archives from download.qt.io |
| libmpv dev package | `third_party/mpv/` (`include/`, `libmpv-2.dll`) | [shinchiro/mpv-winbuild-cmake](https://github.com/shinchiro/mpv-winbuild-cmake/releases) `mpv-dev-x86_64-*.7z` |
| MSVC + CMake + Ninja | Visual Studio 2026 Community | |

CMake generates the MSVC import library (`mpv.lib`) from `libmpv-2.dll` at configure time.
A different Qt location can be set by overriding `CMAKE_PREFIX_PATH` in a `CMakeUserPresets.json`.

## Build

From a *Developer PowerShell for VS*:

```powershell
cmake --preset debug          # or: release
cmake --build --preset debug
.\build\debug\BetterVideoPlayer.exe path\to\video.mp4
```

The post-build step copies `libmpv-2.dll` and runs `windeployqt`, so the build folder runs as-is.
You can also open the folder directly in Visual Studio or Qt Creator. Both pick up `CMakePresets.json`.

## The player

Playing a video opens the **watch page**: the video, its details below, and on the right the
rest of its folder (as a playlist) followed by more videos. The same video can shrink into a
**mini player** in the corner, so you can keep browsing while it plays, or go **full screen**.

When a video ends, the next one in its folder plays after a 5-second "Up next" countdown
(Cancel or Play now; Autoplay can be turned off in Settings). Closing the app while a video is
playing asks first.

| Gesture (mouse drag or touch) | Action |
|---|---|
| Swipe the video left or right | Full screen, and back |
| Swipe the video down | Mini player (it follows your finger) |
| Swipe the mini player up, or click it | Back to the watch page |
| Swipe the mini player down or sideways | Close it |
| Click the video | Play / pause |
| Double-click the middle of the video | Full screen |
| Double-click the left or right side | Skip back or forward (keep tapping to skip more) |

| Key | Action |
|---|---|
| Space / K | Play / pause |
| ← / → | Seek -5s / +5s |
| J / L | Skip back / forward (10s; change it in Settings) |
| Shift+P / Shift+N | Previous / next video in the folder |
| ↑ / ↓ | Volume ±5 |
| M | Mute |
| C | Subtitles on / off |
| F | Full screen |
| I | Mini player, and back |
| Esc | Leave full screen, or shrink to the mini player |
| Ctrl+O | Open a file (drag & drop also works) |

### Subtitles

When a video has a subtitle file that seems to belong to it, the player offers it the first
time you open the video ("Subtitles found: Turn on / No thanks") and remembers your answer.
A subtitle file matches when it has the same name, ignoring case, spaces and punctuation, with an
optional language tag (`Movie.en.srt`); or the same episode (`S01E02`, `1x02`, "Season 1 Episode
2"); or when it is in a folder that holds just that one video. `Subs` and `Subtitles` folders next
to the video count too. The subtitles button on the video lists every track, built-in or from a
file, and can add a subtitle file by hand.

## The library

The app scans your Videos folder in the background (empty files, such as unfinished
downloads, are skipped). For each video, a headless libmpv instance grabs a frame 20% in
and reads the duration. Both are cached, so later launches are instant.

App data lives in `%LOCALAPPDATA%\BetterVideoPlayer`:

- `thumbnails\`: JPEG thumbnails, named by a hash of path, size and modified time
- `metadata.json`: durations, and which files could not be thumbnailed
- `history.json`: how far each video was watched (for Continue watching and resume)
- `settings.ini`: the volume

Delete `metadata.json` and `thumbnails\` to rebuild the thumbnails.

## Architecture

- **C++** (`src/`), registered as the `BetterVideoPlayer` QML module:
  - `MpvItem`: a QML video item (`QQuickFramebufferObject`) that owns an mpv instance and
    draws through mpv's OpenGL render API. Qt Quick is forced onto OpenGL in `main.cpp`.
  - `VideoLibrary` (QML singleton): scanning, the metadata cache, watch history, and the models.
  - `VideoModel` / `FolderModel`: list models for shelves, folder pages and folder cards.
  - `Thumbnailer`: a two-thread pool of headless libmpv instances (`vo=image`).
- **QML** (`qml/`): `Main` (window, navigation), `HomePage`, `FolderPage`, `WatchView` (the player layer), and
  components (`Shelf`, `VideoCard`, `FolderCard`, `PickRow`, `NavRail`, ...). Colours and radii
  live in `Theme.qml`. Icons are Material Icons (Apache 2.0) path data in `Icons.js`, drawn with
  `QtQuick.Shapes` so they take any colour.
