# Better Video Player

Offline desktop video player built with C++20, Qt 6 (Widgets) and libmpv
(which bundles FFmpeg for demuxing/decoding).

## Dependencies (Windows)

| Dependency | Location | Source |
|---|---|---|
| Qt 6.11.3, MSVC 64-bit | `C:\Qt\6.11.3\msvc2022_64` | qtbase + qtsvg archives from download.qt.io |
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

## Controls

| Key | Action |
|---|---|
| Space / K | Play / pause |
| ← / → | Seek -5s / +5s |
| J / L | Seek -10s / +10s |
| ↑ / ↓ | Volume ±5 |
| M | Mute |
| F / double-click | Fullscreen |
| Esc | Leave fullscreen |
| Ctrl+O | Open file (drag & drop also works) |

## Architecture

- `MpvWidget`: a `QOpenGLWidget` that owns the `mpv_handle` and renders through
  mpv's render API (`mpv_render_context`). mpv events and property changes
  (`time-pos`, `duration`, `pause`, `volume`, `mute`, `media-title`) are re-emitted as Qt signals.
- `MainWindow`: UI controls, shortcuts, drag & drop and fullscreen. It talks to the player only
  through `MpvWidget`'s methods and signals.
