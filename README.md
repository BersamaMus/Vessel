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

| Gesture (mouse drag or touch) | Action |
|---|---|
| Swipe the video left or right | Full screen, and back |
| Swipe the video down | Mini player (it follows your finger) |
| Swipe the mini player up, or click it | Back to the watch page |
| Swipe the mini player down or sideways | Close it |
| Click / double-click the video | Play-pause / full screen |

| Key | Action |
|---|---|
| Space / K | Play / pause |
| ← / → | Seek -5s / +5s |
| J / L | Seek -10s / +10s |
| ↑ / ↓ | Volume ±5 |
| M | Mute |
| F | Full screen |
| I | Mini player, and back |
| Shift+N | Next video in the folder |
| Esc | Leave full screen, or shrink to the mini player |
| Ctrl+O | Open a file (drag & drop also works) |

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
