# Where everything is

A map from each of Vessel's features to the files and functions that implement it. Read this
when you need to change something and don't know where it lives. [README.md](../README.md)
describes what each feature does for the user. This file says where it is in the code.

Line numbers drift, so search for the function names. If you add, move or rename a feature,
update this file in the same commit.

## The shape of the app

```
main.cpp ── QQmlApplicationEngine ── Main.qml (window)
                                       ├─ NavRail            tabs on the left
                                       ├─ StackView          the pages (HomePage, FolderPage, ...)
                                       ├─ WatchView          the player, laid over the pages
                                       ├─ menus + dialogs    right-click menus, rename/move/delete, playlists
                                       └─ toast              notices at the bottom left (with Undo)

QML calls into C++ through:
  VideoLibrary  (singleton)   every video, folder, drive, phone, history entry and playlist
  MpvItem       (element)     the video surface, one libmpv instance per item
  SeekPreview   (singleton)   frames above the seek bar
  MediaControls (element)     media keys and Windows' media flyout
  VideoQuery    (element)     a searchable, sortable, filterable list of videos
```

The C++ types are in the `Vessel` QML module through `QML_ELEMENT` / `QML_SINGLETON`. QML
files are served from the module root, so they use each other with no imports. **A new file in
`src/` or `qml/` has to be added to [CMakeLists.txt](../CMakeLists.txt)** or it isn't built.

### Core ideas

- **A video is identified by its path, but stored by its key.** `VideoInfo::key`
  ([VideoLibrary.h](../src/VideoLibrary.h)) is the path for library videos. For external drives
  it is `drive:<driveId>/<path on drive>`, so a drive that comes back with a different letter
  keeps its history. History, playlists and subtitle/audio choices are stored by key. Thumbnails
  and metadata are stored by `cacheKeyOf()` (key + size + modified time), so they change when the
  file changes. Use `VideoLibrary::keyFor(path)` to convert a path.
- **Folder `""` means the whole library.** Its sub-folders are the computer's own drives.
- **Sources.** Every video comes from a `VideoSource`: a built-in drive (the library), an
  external drive, or a phone (`mtp://<serial>`). `VideoLibrary::updateLibrarySources()` and
  `onDrivesChanged()` / `onPhonesChanged()` keep the list current.
- **Background work.** Anything slow runs through `QtConcurrent::run` + `QFutureWatcher`, and
  the results are applied on the UI thread. Scans report what they have found every
  `kScanReportMs` so the progress ring can show it while they run.
- **Models are views onto `VideoLibrary::m_videos`.** `VideoModel` holds library indexes and
  re-reads them on `refresh()`. `rebuild()` / `refreshModels()` re-sorts the library and refreshes
  every tracked model (`track()`).
- **Persistence.** `loadState()` / `scheduleSave()` / `save()` at the bottom of
  [VideoLibrary.cpp](../src/VideoLibrary.cpp) write `metadata.json`, `history.json` and
  `playlists.json` with `QSaveFile`. UI settings (volume, skip seconds, autoplay, subtitle look,
  recent searches) are a QML `Settings` object in [Main.qml](../qml/Main.qml), saved to
  `settings.ini`. All of it lives in `%LOCALAPPDATA%\Vessel`, or in `VESSEL_DATA_DIR` if set.

## Features → files

### Startup, single window, data folder
| What | Where |
|---|---|
| Entry point, `--thumbnail-worker` and `--unregister` modes | [main.cpp](../src/main.cpp) `main()` |
| Forcing Qt Quick onto OpenGL (needed by libmpv), `LC_NUMERIC=C` | `main()` |
| One window per data folder: a second launch sends its file over `QLocalServer` | `main()` → `Main.qml` `openFromOutside()` |
| Moving data over from the old `BetterVideoPlayer` folder | `moveOldData()` in main.cpp |
| "Open with" / Default apps registration (per user, in the registry) | [SystemIntegration.cpp](../src/SystemIntegration.cpp) |
| Exe icon, version info | [src/app.rc.in](../src/app.rc.in), [icon/](../icon) (`make-icon.ps1` renders `LogoMark.qml` → `vessel.ico`) |

### Navigation and pages
| What | Where |
|---|---|
| Tabs (0 Home, 1 Videos, 2 Library, 3 Playlists, 4 External, 5 Phone, 6 Settings) | [NavRail.qml](../qml/NavRail.qml), `Main.showTab()` |
| Page stack, the `Component`s for each page, Back (Esc / Alt+Left / mouse back) | [Main.qml](../qml/Main.qml) `StackView`, `goBack()` |
| Top-right buttons (History, Search, Open, More) | [AppActions.qml](../qml/AppActions.qml) |
| Pinned page header, title | [PageBar.qml](../qml/PageBar.qml), [PageTitle.qml](../qml/PageTitle.qml) |
| Notices with Undo ("toast") | `Main.toast()` and the `Rectangle` beneath it in Main.qml |
| Drag & drop of videos and subtitle files onto the window | `DropArea` in Main.qml |
| Ctrl+O / Ctrl+F | `Shortcut`s in Main.qml, `openFileDialog()`, `openSearch()` |
| "Close Vessel?" while playing | `closeDialog` in Main.qml |

### Home
| What | Where |
|---|---|
| Page layout: Continue watching, Quick picks, Recently added, Folders | [HomePage.qml](../qml/HomePage.qml) |
| Continue watching (started but not finished, `kMinProgress` = 10 s) | `VideoLibrary::continueWatching`, `refreshContinueWatching()`, `isFinished()` (95% or < 5 s left) |
| Quick picks (random, reshuffled on demand) | `VideoLibrary::quickPicks`, `reshuffleQuickPicks()` (`m_pickSeed`) |
| Recently added (by file creation time = when it was copied in) | `VideoLibrary::recentlyAdded`, `fileVideo()` |
| Rows of cards / list rows | [Shelf.qml](../qml/Shelf.qml), [ShelfHeader.qml](../qml/ShelfHeader.qml), [VideoCard.qml](../qml/VideoCard.qml), [PickRow.qml](../qml/PickRow.qml), [FolderCard.qml](../qml/FolderCard.qml) |

### Library scanning (Videos and Library tabs)
| What | Where |
|---|---|
| Videos tab = Videos folder, Library tab = everything | `videosPage` / `libraryPage` in Main.qml, both [FolderPage.qml](../qml/FolderPage.qml) |
| Folder page: sub-folders, videos in a virtualised `GridView`, Play all / Shuffle | FolderPage.qml (`playAll()`), [VideoGrid.qml](../qml/VideoGrid.qml), `Main.playFolder()` |
| Folders / Videos views (remembered; also Drives / Videos on External, Phones / Videos on Phone) | [ViewTabs.qml](../qml/ViewTabs.qml); `FolderPage.flat` / `hasFolders`, `DevicePage.listing` (shared by External and Phone); `Main.folderView` / `externalView` / `phoneView` |
| Walking a drive or folder (runs on a worker thread) | `scanFolder()`, `listFolder()` (one `FindFirstFileExW` listing per folder) in VideoLibrary.cpp |
| Last scan's list, shown at launch while the drive is scanned again (read off the UI thread; no thumbnails until the scan finds a video again) | `saveScanList()` / `loadScanList()` (`scans/<drive>.json`, checked against the drive's id), `updateLibrarySources()`, `VideoInfo::unconfirmed` |
| Keeping that list up to date (saved at most every 30 s after a folder change, on its own thread) | `scanListChanged()`, `saveScanLists()`, `m_scanSaver` |
| What gets skipped: system/app folders, dot-folders, recycle bin, code projects, `steamapps`, empty files | `isSkippedFolder()`, `isSystemFolder()`, `isProjectFile()`, `isInProject()`, `isEditorCache()`, `isSkippedPath()` |
| Which extensions count as video (and the `.ts` sniffing) | [VideoFormats.cpp](../src/VideoFormats.cpp) |
| Title clean-up and release year from the file name | `prettyTitle()`, `yearIn()` |
| OneDrive placeholders (not thumbnailed, since that would download them) | `VideoInfo::onlineOnly`, from `isOnlineOnly()` in `listFolder()` (or `fileVideo()` for a single file) |
| Starting / finishing scans | `rescan()`, `scanAll()`, `scanSource()`, `onVideosFound()`, `onScanFinished()` |
| Updating only the folder that changed | [FolderWatcher.cpp](../src/FolderWatcher.cpp) (ReadDirectoryChangesW) → `onFolderChanged()` → `rescanPart()` |
| Folder lists and covers | `FolderModel` in [VideoModels.cpp](../src/VideoModels.cpp), `VideoLibrary::foldersIn()` |
| Progress ring on the rail, "Making thumbnails • N of M" | [NavRail.qml](../qml/NavRail.qml), [WorkProgress.qml](../qml/WorkProgress.qml), `thumbnailProgressIn()`, `videosFound` |

### Sort, filter, search
| What | Where |
|---|---|
| The query model (folder, source, text, sort, length/progress/type/resolution) | `VideoQuery` in VideoModels.h/.cpp, `VideoQuery::find()` |
| Filter buckets | `lengthOf()`, `progressOf()`, `resolutionOf()` in VideoModels.cpp |
| Search normalisation (case, accents, punctuation) and ranking | `searchKey()`, `matchScore()`, `startsWord()` in VideoModels.cpp; folder text from `placeKeyOf()` in VideoLibrary.cpp |
| Sort / filter UI | [FilterBar.qml](../qml/FilterBar.qml), [DropButton.qml](../qml/DropButton.qml) |
| Search page, scope (library / drive / all drives), recent searches | [SearchPage.qml](../qml/SearchPage.qml), `Main.openSearch()`, `addRecentSearch()` |

### Thumbnails and metadata
| What | Where |
|---|---|
| Worker processes, job queue, 45 s timeout, crash = unreadable, thread fallback | [Thumbnailer.cpp](../src/Thumbnailer.cpp) (`dispatch()`, `startWorker()`, `onExited()`, `checkDeadlines()`, `grabInProcess()`) |
| Worker side (headless libmpv, frame at 20%, duration/size/year) | `Thumbnailer::runWorker()` |
| What gets queued, and in which order (open folder first) | `VideoLibrary::queueThumbnails()`, `prioritize()` |
| Storing results | `onThumbnailFinished()` → `m_meta` → `metadata.json`; file name from `thumbnailFile()` |
| Rounded thumbnail that pulses while waiting | [RoundedImage.qml](../qml/RoundedImage.qml) |

### The player
Almost all of it is [WatchView.qml](../qml/WatchView.qml), sectioned with `// --- ... ---` comments.

| What | Where |
|---|---|
| Opening a video, a list (Play all / Shuffle / playlist) | `Main.play()` / `playAll()` → `WatchView.open()` / `openList()` |
| Watch page / mini player / full screen (`mode`), swipe gestures | `minimize()`, `expand()`, `enterFull()`, `exitFull()`, `finishSwipe()`; `miniProgress` / `fullProgress` |
| Video rendering, playback properties, tracks | [MpvItem.cpp](../src/MpvItem.cpp) (`open()`, `seek()`, `scrub()`, `setSubtitleTrack()`, `setAudioTrack()`, `setMpvProperty()`) |
| Details under the video ("MP4 • H.264 (AVC) • 1920 × 1080 • …") | `codecName()` and the `info` / `fileInfo` items; data from `VideoLibrary::detailsOf()` |
| Side list (folder as playlist, then suggestions) | `side` / `queueList` in WatchView; `nextInFolder()`, `previousInFolder()`, `suggestionsFor()` (same drive only) |
| Resume and progress saving | `WatchView.saveProgress()` (also on a timer) → `VideoLibrary::saveProgress()`, `resumePosition()` |
| Autoplay "Up next" countdown | `startAutoplay()`, `cancelAutoplay()`, `autoplayTimer`, `upNext` |
| Controls bar: play, seek bar, volume (to 200%, boost colour), speed, audio, subtitles, More menu (repeat, aspect, rotate, Save to playlist) | `controls` / `bottomBar` / `moreMenu` in WatchView; [AppSlider.qml](../qml/AppSlider.qml) |
| Seek bar preview frames | [SeekPreview.cpp](../src/SeekPreview.cpp) (second libmpv, software render) → `image://seekpreview/` |
| Double-click to skip, skip bubble | `tapSeek()`, `seekBurst`, `bubble` |
| On-video pill (volume, subtitles, autoplay) | `flash()`, `indicator` |
| Keyboard shortcuts | `// --- Keyboard` at the bottom of WatchView (`Key` component) |
| Media keys and Windows' media flyout | [MediaControls.cpp](../src/MediaControls.cpp) (C++/WinRT), the `MediaControls` item in Main.qml |
| Drive unplugged mid-video | the handler under "Unplugged mid-video" in WatchView |

### Subtitles and audio tracks
| What | Where |
|---|---|
| Finding matching subtitle files (same name, episode tag, sole video, `Subs/` folders) | `VideoLibrary::subtitlesFor()`, `normalizedName()`, `episodeOf()` |
| "Subtitles found: Turn on / No thanks", and remembering the choice | `subtitlePrompt` in WatchView, `acceptSubtitles()` / `declineSubtitles()`; `VideoLibrary::subtitleChoice()` / `setSubtitleChoice()` |
| Subtitle menu, adding a file, C to toggle | `subtitleMenu`, `addSubtitleFiles()`, `chooseSubtitle()`, `toggleSubtitles()` |
| Subtitle look (size, colour, background) | Settings in Main.qml → `WatchView.applySubtitleStyle()`; UI in [SettingsPage.qml](../qml/SettingsPage.qml) |
| Audio track choice (remembered per video), A to cycle | `chooseAudio()`, `cycleAudio()`; `VideoLibrary::audioChoice()` / `setAudioChoice()` |

### Watch history
| What | Where |
|---|---|
| History page grouped by day | [HistoryPage.qml](../qml/HistoryPage.qml), `HistoryModel` in VideoModels.cpp |
| Mark watched / unwatched, remove, clear all, Undo | `setWatched()`, `historyEntry()` / `restoreHistory()`, `clearHistory()`, `editHistory()`; menu items in Main.qml |

### Watch stats
| What | Where |
|---|---|
| Counting time spent watching (measured from when it started playing, handed over with each save, to the video that was playing) | `playingSince` / `watchingPath`, `saveWatchTime()` in WatchView → `VideoLibrary::addWatchTime()`; plays: `notePlay()` in `WatchView.open()`; played to the end: `noteFinished()` from `saveProgress()` |
| Kept per day, hour of the day and video (`stats.json`), following renames and moves (in the library or not) | `m_statDays` / `m_statVideos`, `readStats()` / `statsJson()`, `statsEdited()`, `relocate()` in VideoLibrary.cpp |
| Totals, streaks, last 30 days, hours, weekdays, most watched | `VideoLibrary::watchStats()` |
| Stats page (tiles, bar charts, "Night owl" title), Reset | [StatsPage.qml](../qml/StatsPage.qml) (`BarChart`, `Tile`, `kind`); `clearStats()`; opened by `Main.openStats()` (gone back to if it's open already, see `openOnce()`) from HistoryPage and AppActions |

### Playlists
| What | Where |
|---|---|
| Data and storage (`playlists.json`, videos kept by key) | `// --- Playlists` section of VideoLibrary.cpp; `Playlist` struct in VideoLibrary.h |
| Playlists tab, cards | [PlaylistsPage.qml](../qml/PlaylistsPage.qml), `PlaylistModel` |
| One playlist: play, shuffle, reorder, remove with Undo | [PlaylistPage.qml](../qml/PlaylistPage.qml), `moveInPlaylist()`, `removeFromPlaylist()`, `addToPlaylist(id, path, at)` |
| "Save to playlist" dialog | [SaveToPlaylist.qml](../qml/SaveToPlaylist.qml), opened from `// --- Playlists` in Main.qml |
| Rename / delete playlist | `playlistMenu`, `playlistNameDialog`, `deletePlaylistDialog` in Main.qml |
| Bindings that must re-evaluate on change | `VideoLibrary::playlistRevision` |

### Managing files (right-click menu)
| What | Where |
|---|---|
| Video menu and folder/drive menu | `videoMenu`, `folderMenu` in Main.qml |
| Rename / Move / Delete (to the recycle bin), subtitle files go with the video | `// --- Managing files` in VideoLibrary.cpp: `renameVideo()`, `moveVideo()`, `deleteVideo()`, `relocate()`, `sidecarSubtitles()`; dialogs at the bottom of Main.qml |
| Letting the player close the file first | `withVideoReleased()` in Main.qml (`SeekPreview.close()` too) |
| Show in File Explorer (including phone files) | `VideoLibrary::showInFileManager()`, `Phones::showInExplorer()` |

### External drives (External tab)
| What | Where |
|---|---|
| Detecting drives plugged in / removed (polling) | [DriveWatcher.cpp](../src/DriveWatcher.cpp) → `VideoLibrary::onDrivesChanged()` |
| External tab, drive cards with space used | [ExternalPage.qml](../qml/ExternalPage.qml) (on [DevicePage.qml](../qml/DevicePage.qml), the bar, pills and Videos grid it shares with Phone), `VideoLibrary::drives` |
| Continue watching on a drive | `continueWatchingOn()`, `startedIn()` |
| Eject | [DriveEject.cpp](../src/DriveEject.cpp) via `VideoLibrary::ejectDrive()` (the player lets go first) |

### Phones (Smartphone tab)
| What | Where |
|---|---|
| Listing phones, walking their folders, reading parts of files (WPD / MTP) | [PhoneDevices.cpp](../src/PhoneDevices.cpp) (`Phones::list()`, `Phones::findVideos()`) |
| `mtp://` stream protocol for libmpv | `Phones::registerStream()`, `VideoLibrary::playbackUrl()` |
| Polling, scanning, cached phone list | `pollPhones()`, `onPhonesChanged()`, `scanPhone()`, `savePhoneList()` / `loadPhoneList()` |
| Thumbnails one at a time per phone, held while a phone video plays | `Thumbnailer::setPhoneHeld()`, `VideoLibrary::phonePlaying` |
| Phone tab | [PhonePage.qml](../qml/PhonePage.qml) |

### Settings
[SettingsPage.qml](../qml/SettingsPage.qml): Library (rescan), Player (skip seconds, autoplay),
Subtitles (look, with a live preview), Default video player, About. The values are aliases on
the root of [Main.qml](../qml/Main.qml).

### Look and shared components
| What | Where |
|---|---|
| Colours, radii (black surfaces, light purple accent `#C9A7FF`) | [Theme.qml](../qml/Theme.qml) (singleton) |
| Icons (Material path data, drawn as shapes) | [Icons.js](../qml/Icons.js), [Icon.qml](../qml/Icon.qml), [IconButton.qml](../qml/IconButton.qml) |
| Logo | [LogoMark.qml](../qml/LogoMark.qml), [AppLogo.qml](../qml/AppLogo.qml) |
| Menus, dialogs, fields, switches, buttons | `AppMenu`, `AppMenuItem`, `AppDialog`, `DialogButton`, `AppTextField`, `AppSwitch`, `TextAction`, `EmptyNote`, `SelectableText` |
| Scrolling: drag-to-scroll pages, smooth wheel | [PageScroll.qml](../qml/PageScroll.qml), [SmoothWheel.qml](../qml/SmoothWheel.qml) |

## Release and installer

1. Bump `project(Vessel VERSION x.y.z ...)` in [CMakeLists.txt](../CMakeLists.txt) in its own
   commit, "Vessel x.y.z".
2. `powershell -File installer\build_installer.ps1` builds Release, gathers `build\package`
   (exe, libmpv, Qt via windeployqt, the VC++ runtime) and runs Inno Setup on
   [installer/vessel.iss](../installer/vessel.iss) to produce `build\installer\Vessel-Setup-<version>.exe`.
   Never change the `AppId` in vessel.iss. The uninstaller runs `VesselPlayer.exe --unregister`
   and leaves the user's data in `%LOCALAPPDATA%\Vessel`.

## Known gaps

- Windows only in practice. `FolderWatcher`, `MediaControls`, `SystemIntegration`, `DriveEject`
  and phones do nothing on other platforms. The non-Windows CMake branch (pkg-config mpv)
  exists but isn't maintained.
- No automated tests. Run the app with `VESSEL_DATA_DIR` set to a scratch folder to try changes.
