# Vessel

Offline-first video player built with C++20, Qt 6 (Qt Quick/QML) and libmpv
(which bundles FFmpeg for demuxing/decoding). The look follows Blimp: black
surfaces with a light purple accent.

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

The exe is `VesselPlayer.exe` rather than `Vessel.exe`: Xbox Game Bar takes `Vessel.exe` for a
game of that name and, with background recording on, records it, microphone included.

```powershell
cmake --preset debug          # or: release
cmake --build --preset debug
.\build\debug\VesselPlayer.exe path\to\video.mp4
```

The post-build step copies `libmpv-2.dll` and runs `windeployqt`, so the build folder runs as-is.

Online subtitle search needs an [OpenSubtitles API key](https://www.opensubtitles.com/consumers),
in a file named `opensubtitles.key` next to `CMakeLists.txt` (git ignores it) or passed as
`-DVESSEL_OPENSUBTITLES_KEY=…`. Without one, Vessel builds without that menu item. Adding the
file later is picked up by the next build.

You can also open the folder directly in Visual Studio or Qt Creator. Both pick up `CMakePresets.json`.

The **Release** build is several times faster than Debug at scanning and searching (Debug Qt
and libmpv checks are slow): use it for everyday watching, and Debug for development.

For testing and profiling:

- `VESSEL_DATA_DIR=<folder>` points the app at its own settings, history and thumbnail cache, so a
  test copy can't touch your real ones.
- `QT_LOGGING_RULES=vessel.perf.debug=true` logs how long scans and library updates take.

## The player

Playing a video opens the **watch page**: the video, its details below, and on the right the
rest of its folder (as a playlist) followed by more videos, laid out in YouTube's proportions.
The same video can shrink into a **mini player** in the corner, so you can keep browsing while it
plays, or go **full screen**.

When a video ends, the next one in its folder plays after a 5-second "Up next" countdown
(Cancel or Play now; turn Autoplay off with the switch next to the volume, or in Settings). Closing the app while a video is
playing asks first.

While something plays, the computer doesn't go to sleep, however long it runs. The screen stays on
too while there's a picture to watch (a video, in the window or the mini player); for music, or
with Vessel minimised, it can turn off as usual. Paused, finished, or if a file fails part-way,
the usual power settings apply again.

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
| Z / X | Subtitles 0.1s earlier / later (Shift: 1s) |
| A | Next audio track |
| < / > | Slower / faster playback |
| F | Full screen |
| I | Mini player, and back |
| Esc | Leave full screen, or shrink to the mini player |
| Ctrl+O | Open a file (drag & drop also works) |
| Ctrl+F | Search |

Below the video, the file's details read like "MP4 • H.264 (AVC) • 1920 × 1080 • 23.98 fps • 1.4 GB":
the file type, compression standard, size in pixels, frame rate and file size.

### Subtitles

When a video has a subtitle file that seems to belong to it, the player offers it the first
time you open the video ("Subtitles found: Turn on / No thanks") and remembers your answer.
A subtitle file matches when it has the same name, ignoring case, spaces and punctuation, with an
optional language tag (`Movie.en.srt`); or the same episode (`S01E02`, `1x02`, "Season 1 Episode
2"); or when it is in a folder that holds just that one video. `Subs` and `Subtitles` folders next
to the video count too. The subtitles button on the video lists every track, built-in or from a
file, and can add a subtitle file by hand.

**Search online…** in the same menu looks for subtitles on OpenSubtitles.com, the only time
Vessel goes online, and only when you ask. It searches by the video's title (which you can edit)
in the language you pick (Windows' own at first, then the last one used), and by a fingerprint of
the file: the hash OpenSubtitles uses, made from its size and its first and last 64 KB. Subtitles
that match the fingerprint were made for that exact file, so they're in sync; they're listed
first as "Made for this file". Phone videos are searched by title only. Picking one downloads it
next to the video as `Movie.en.srt` (`Movie.en.2.srt` if that's taken), so it's found again and
moves with the video, and turns it on. When the folder can't be written to (a phone, a read-only
drive) it goes in Vessel's data folder instead, under `subtitles`; those are deleted at the next
start once no video's history points to them (the video was deleted, say). Subtitles downloaded for the
video before are marked "Downloaded" in later searches (as long as the file is still there, also
after the video is renamed or moved), and picking one turns the saved file on again without
downloading it. Without an OpenSubtitles
account a few downloads a day are allowed; once they're used up, the dialog says so and when more
are allowed (counting down, and remembered after a restart), and picking a subtitle opens its
page on the website, to download it there and drop it on the video.

Subtitles that are out of step can be fixed with Z / X (0.1s earlier / later, Shift for 1s) or
**Timing…** in the subtitles menu, a panel in the corner of the video that leaves the subtitles in
view while you move them (**Reset** goes back to the file's own timing). It works for subtitle
files and the ones built into a video alike, without changing any file: the timing is remembered
for that video and that subtitle track, and follows a subtitle file renamed or moved with its
video.

## The library

The app scans the computer's own drives (C: and any other built-in drive) in the background.
It skips Windows' and apps' folders (`Windows`, `Program Files`, `ProgramData`, `AppData`,
dot-folders, the recycle bin), code projects (a folder with `.git`, `package.json`,
`CMakeLists.txt` and the like), games' files (`steamapps`) and empty files, such as unfinished
downloads. The side rail has two views of the result: **Videos** is your Videos folder, and
**Library** is every folder on those drives that holds videos. New and removed videos show up by
themselves; only the folder that changed is looked at again. For each video, a headless libmpv instance grabs a frame 20% in
and reads the duration, size in pixels and any release-date tag. All are cached, and so is
the list of videos each drive's last scan found: later launches show the library straight away
and look through the drives again in the background, adding and removing what changed.

Background work shows as it happens. A ring at the bottom of the side rail spins while folders
and drives are scanned, then fills as thumbnails are made (hover it for counts). Drive cards and
folder pages show "Making thumbnails • 124 of 830" for their own videos, and cards waiting for a
thumbnail pulse. The folder you're looking at is done first: its own videos, then a cover for
each sub-folder, then the rest.

Every Library and folder page with sub-folders has two views, picked with the pills under the
title: **Folders** (the sub-folders, then the folder's own videos) and **Videos** (every video in
the folder and its sub-folders in one grid). The choice is remembered for all folder pages. The
External and Phone tabs have the same switch: **Drives** / **Phones** (the cards) or **Videos**
(every video on the connected drives, or phones). The Videos view has a **sort** menu (Name,
Recently added, Recently watched, Release year, Longest first, Largest first) and **filters**
(Length, Progress, Type, Resolution). The release year comes from the file name ("Speed (1994)", "Ne Zha 2019 1080p"), or
failing that from the file's tags.

**Search** (the magnifier on Home and Library, or Ctrl+F) looks through the library and connected
drives by title and folder name, ignoring case, accents and punctuation. Titles that start with
what you typed come first, then titles that contain it, then videos whose title and folders
contain every word ("house season 2"). It has the same sort and filters, and remembers your
last few searches.

## Watch stats

**Your stats** (the Stats tab, or Your stats on the History page, where Back returns to the
History) shows how long you've spent
watching: today, the last 7 days and all time, your streak of days in a row, videos played and
watched to the end, and your biggest day. Bar charts show the last 30 days, the hours of the day
and the days of the week you watch most (hover a bar for its time), with a title to match
("Night owl", "Early bird", "Prime-time viewer").
It's the time that passes while a video plays (a minute at 2x is a minute), counted from when
this version was first run (`stats.json` in the data folder). Clearing the history leaves the
stats be; Reset stats starts them over.

## Playlists

The **Playlists** tab lists the playlists you've made, the most recently added to first. Save a
video to one from anywhere: right-click it (on Home, in a folder, in Search, History, or the
watch page's side list) and choose **Save to playlist…**, or use **Save** beside the title on the
watch page (also in the player's More menu, for full screen). The dialog ticks the playlists the
video is in; ticking or unticking one saves it there and then, and a new playlist can be made
from it with the video already in it.

A playlist's page plays it from the top, shuffled, or from any of its videos on, and each video
can be moved up or down or taken out (with Undo). Right-click a playlist's card (or More on its
page) to rename or delete it; deleting one leaves the videos where they are. Videos are kept by
the same key as the watch history, so they stay in their playlists when renamed or moved in
Vessel, and a drive's videos come back when it's plugged in again (they're hidden meanwhile).

App data lives in `%LOCALAPPDATA%\Vessel` (moved there from `%LOCALAPPDATA%\BetterVideoPlayer`,
the app's folder before it was renamed, the first time Vessel runs):

- `thumbnails\`: JPEG thumbnails, named by a hash of path, size and modified time
- `metadata.json`: durations, sizes and tagged years, and which files could not be thumbnailed
- `history.json`: how far each video was watched (for Continue watching and resume)
- `playlists.json`: your playlists, each a name and its videos' keys in order
- `settings.ini`: the volume, other settings, and recent searches

Delete `metadata.json` and `thumbnails\` to rebuild the thumbnails.

## Phones (Smartphone tab)

A phone connected by USB doesn't get a drive letter: Android phones speak MTP (iPhones PTP),
which Windows exposes through Windows Portable Devices. `PhoneDevices` checks for phones every
two seconds, walks their folders (one bulk request per folder: about 6 s for 1,500 videos among
20,000 files), and skips hidden folders and files (the bin's `.trashed-…` ones) and apps'
private `Android/data` and `Android/obb`.

Videos are played and thumbnailed **straight from the phone**, without copying: MTP's
GetPartialObject64 reads any part of a file, and an `mtp://` stream protocol hands those reads
to libmpv, so seeking works too (about 35 MB/s over USB). The phone answers one request at a
time and turns others away as busy, so requests are retried for a few seconds, and only one
thumbnail worker reads from a phone at once.

Phone videos are listed as `mtp://<serial>/<path on the phone>`; watch history and thumbnails
are keyed by the serial number and path, which stay the same across connections (MTP's object
ids don't). The phone must be unlocked and, on Android, set to **File transfer**; until then its
card says so. Windows only, for now.

## Architecture

- **C++** (`src/`), registered as the `Vessel` QML module:
  - `MpvItem`: a QML video item (`QQuickFramebufferObject`) that owns an mpv instance and
    draws through mpv's OpenGL render API. Qt Quick is forced onto OpenGL in `main.cpp`.
  - `VideoLibrary` (QML singleton): scanning, the metadata cache, watch history, playlists, and the models.
  - `VideoModel` / `FolderModel`: list models for shelves, folder pages and folder cards.
  - `PlaylistModel`: the playlists (Playlists tab); `VideoLibrary` keeps them and saves `playlists.json`.
  - `PhoneDevices`: phones over USB (Windows Portable Devices / MTP), and the `mtp://` stream.
  - `VideoQuery`: a `VideoModel` created in QML, with search text, sort and filter properties
    (behind `FilterBar` and `SearchPage`).
  - `Thumbnailer`: 2 to 4 worker processes (the app run as `VesselPlayer --thumbnail-worker`),
    each grabbing one frame at a time with headless libmpv (`vo=image`) and answering over
    stdin/stdout. A file that crashes the decoder only takes its worker down (the file is then
    remembered as unreadable), one taking over 45 s is killed, and workers exit when the queue
    is empty so their memory goes too. If workers can't start, it falls back to threads.
- **QML** (`qml/`): `Main` (window, navigation), `HomePage`, `FolderPage`, `WatchView` (the player layer), and
  components (`Shelf`, `VideoCard`, `FolderCard`, `PickRow`, `NavRail`, ...). Colours and radii
  live in `Theme.qml`. Icons are Material Icons (Apache 2.0) path data in `Icons.js`, drawn with
  `QtQuick.Shapes` so they take any colour. Lists that can hold thousands of videos (folder
  pages, search results) are `GridView`s, which only create the cards on screen.
