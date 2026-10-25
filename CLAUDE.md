# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Vessel is an offline desktop video player: C++20, Qt 6 Quick/QML, and libmpv. Windows is the
only platform that is built and tested in practice (MSVC, Qt at `C:\Qt\6.11.3\msvc2022_64`).
`README.md` documents user-visible behaviour in detail (gestures, keys, library rules, subtitle
matching, phones); read it before changing a feature, and keep it in sync when behaviour changes.
[docs/FEATURES.md](docs/FEATURES.md) maps every feature to the files and functions that implement
it; start there to find where something lives, and update it when features move or are added.

## Build and run

Run these from a *Developer PowerShell for VS* (MSVC must be on PATH):

```powershell
cmake --preset debug            # or: release (RelWithDebInfo)
cmake --build --preset debug
.\build\debug\VesselPlayer.exe path\to\video.mp4
powershell -File installer\build_installer.ps1   # Release build + Inno Setup -> build\installer\Vessel-Setup-<ver>.exe
```

- libmpv is not in git. It goes in `third_party/mpv/` (`include/mpv/*.h`, `libmpv-2.dll`, from the
  shinchiro mpv-dev package). CMake generates `mpv.lib` from the DLL with dumpbin at configure time.
- The post-build step copies `libmpv-2.dll` and runs `windeployqt`, so the build folder runs as-is.
- The exe is named `VesselPlayer.exe`, not `Vessel.exe`, on purpose: Xbox Game Bar treats `Vessel.exe` as a game and records it.
- Debug is several times slower at scanning and searching than Release.
- There are no automated tests and no linter. Check changes by building and running the app.

When running the app for testing, set `VESSEL_DATA_DIR=<folder>` so it uses separate settings,
history, playlists and thumbnails (it also gets its own single-instance window and skips the
file-type registration). `QT_LOGGING_RULES=vessel.perf.debug=true` logs scan and update timings.

## Architecture

- **One QML module, flat.** Every C++ type in `src/` and every file in `qml/` is in the `Vessel`
  module (`qt_add_qml_module` in `CMakeLists.txt`). QML files are aliased to the module root, so
  they refer to each other without imports. **Each new QML or C++ file must be added to
  `CMakeLists.txt`** (`QML_FILES` or `SOURCES`). C++ types are exposed with `QML_ELEMENT` /
  `QML_SINGLETON`. `Theme.qml` is a singleton (colours, radii). Icons are Material path data in `Icons.js`.
- **`main.cpp` has several modes.** `--thumbnail-worker` runs `Thumbnailer::runWorker()` (a
  child process with no window), and `--unregister` is called by the uninstaller. A normal launch
  forces Qt Quick onto OpenGL (libmpv renders through its OpenGL API into `MpvItem`, a
  `QQuickFramebufferObject`) and sets `LC_NUMERIC` to "C", which libmpv requires. It enforces a
  single instance per data dir with a `QLocalServer`, so a second launch sends its file to
  `Main.qml`'s `openFromOutside()`.
- **`VideoLibrary` (QML singleton) is the core** (`src/VideoLibrary.cpp`, the largest file). It
  handles scanning, the metadata cache, watch history, playlists, subtitle and audio choices, and
  rename/move/delete, and it owns the models (`VideoModel`, `FolderModel`, `PlaylistModel` in
  `VideoModels.*`). Folders are absolute paths, and `""` means the whole library. Slow work (scans,
  ejecting, phone listing, moves) runs through `QtConcurrent::run` + `QFutureWatcher`, and results
  are applied on the UI thread. State is saved to JSON/INI in the data dir with `QSaveFile`.
- **Paths can be `mtp://<serial>/<path>`** for phone videos (`PhoneDevices`, Windows Portable
  Devices). These are streamed to libmpv through a custom stream protocol, not as local files.
  Use `VideoLibrary::isPhone()` / `playbackUrl()` instead of treating every path as a file.
  History, thumbnails and playlists are keyed so phone and removable-drive videos survive reconnects.
- **Thumbnails** are made by 2–4 `--thumbnail-worker` child processes (headless libmpv, stdin/stdout
  protocol), so a crashing decoder only takes down its worker. Threads are the fallback. Folders
  passed to `prioritize()` are done first.
- **Watchers:** `DriveWatcher` (drives plugged in or out) and `FolderWatcher` (rescans only the
  folder that changed). Windows integration is in `MediaControls` (C++/WinRT media flyout and
  keys), `DriveEject`, and `SystemIntegration` ("Open with" registration).
- **QML:** `Main.qml` holds the window, nav rail and a `StackView` of pages. `WatchView.qml` (the
  largest QML file) is the player layer: watch page, mini player, full screen, gestures, and the
  autoplay countdown. Large lists use `GridView`, so only the cards on screen are created.

## Conventions

- Code comments are plain-English explanations of *why*, written for a non-specialist (see
  `main.cpp`, `VideoLibrary.h`). Match that voice.
- Commit messages are one sentence-case line about the user-visible change, with no prefix
  (e.g. "Playlists: a Playlists tab, Save to playlist from any video, …"). A release is its own
  commit, "Vessel X.Y.Z", which only bumps `project(... VERSION ...)` in `CMakeLists.txt` (the
  installer reads the version from there).
