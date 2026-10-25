#pragma once

#include <QDateTime>
#include <QElapsedTimer>
#include <QHash>
#include <QJsonObject>
#include <QList>
#include <QObject>
#include <QPointer>
#include <QSet>
#include <QThreadPool>
#include <QTimer>
#include <QUrl>
#include <QVariantList>
#include <QtQml/qqmlregistration.h>

#include <atomic>
#include <functional>
#include <memory>

#include "PhoneDevices.h"
#include "VideoModels.h"

class DriveWatcher;
class FolderWatcher;
class Thumbnailer;

// Under this many seconds in, a video doesn't count as started.
inline constexpr double kMinProgress = 10;

struct VideoInfo
{
    QString path;       // absolute, with '/' separators
    // Identifies the video in the history and thumbnail cache: the path, or for
    // external drives the drive's id plus the path on the drive, so it survives
    // the drive getting a different letter.
    QString key;
    // Names its thumbnail and cached metadata; changes when the file does (see cacheKeyOf).
    QString cacheKey;
    QString title;
    QString dir;        // absolute folder the file is in
    QString root;       // the drive (or Videos folder) it was found under
    QString type;       // the file extension, lowercase: "mp4"
    bool external = false;
    // On a phone (and external): `path` is "mtp://<serial>/DCIM/Camera/…", and it's read
    // through its MTP object id (see PhoneDevices) rather than as a file.
    bool phone = false;
    QString objectId;
    // In OneDrive (or another cloud folder) but not on this computer: no thumbnail is made,
    // as reading it would download it. Playing it does.
    bool onlineOnly = false;
    qint64 size = 0;
    QDateTime modified;
    QDateTime added;
    double duration = 0;
    int width = 0;      // 0 until the video has been looked at
    int height = 0;
    int year = 0;       // release year, from the name or the file's tags; 0 if unknown
    // The title and the folders it's in, as search compares them (see searchKey).
    QString titleKey;
    QString placeKey;
    bool hasThumbnail = false;
    // Listed from the last scan at launch, and not found again yet by this one: it may have
    // been deleted meanwhile, so no thumbnail is made for it until it is.
    bool unconfirmed = false;
    double position = 0;    // last watched position, seconds
    qint64 watchedAt = 0;   // ms since epoch; 0 if never watched
    bool finished = false;
};

// What's been learned about a video file (metadata.json), by its cache key.
struct VideoMeta
{
    double duration = 0;
    int width = 0;
    int height = 0;
    int year = 0;          // from its tags
    bool thumb = false;    // a thumbnail was made
    bool probed = false;   // looked at since sizes were kept (older caches lack them)
};

// A list of videos the user made (playlists.json). Its videos are kept by key (see
// VideoInfo::key), so one on a drive that's unplugged stays in it, left out of what's
// shown until the drive is back.
struct Playlist
{
    QString id;
    QString name;
    qint64 created = 0;    // ms since epoch
    qint64 updated = 0;    // when a video was last added: the most recent come first
    QStringList keys;
};

// Where videos come from: one of the computer's own drives (the library), a connected
// external drive, or a phone (also external; its path is "mtp://<serial>").
struct VideoSource
{
    QString path;
    QString name;
    bool external = false;
    bool phone = false;
    QString driveId;        // phones: the serial number
    QString kind;           // drives: "usb" or "card"; phones: "phone"
    qint64 bytesTotal = 0;
    qint64 bytesFree = 0;
    bool scanning = false;
    bool announce = false;  // say so when its first scan finishes (just plugged in)
    // Videos found so far by the scan in progress (counted on the scanning thread).
    std::shared_ptr<std::atomic_int> found = std::make_shared<std::atomic_int>(0);
};

// The videos on the computer's own drives (the library: C: and any other built-in
// drive, leaving out Windows', apps' and code's folders) and on connected external
// drives, their cached thumbnails and durations, and how far each one has been watched.
//
// Folders are absolute paths. The empty folder "" stands for the library as a
// whole: its sub-folders are its drives.
class VideoLibrary : public QObject
{
    Q_OBJECT
    QML_ELEMENT
    QML_SINGLETON

    Q_PROPERTY(bool scanning READ scanning NOTIFY scanningChanged)
    Q_PROPERTY(int count READ count NOTIFY libraryReset)
    Q_PROPERTY(int libraryCount READ libraryCount NOTIFY libraryReset)
    // The user's Videos folder, what the Videos tab shows. Part of the library.
    Q_PROPERTY(QString videosFolder READ videosFolder CONSTANT)
    // Background work, for progress bars: videos found so far by the scans in progress,
    // and thumbnails made out of those to make (both 0 when there are none to make).
    Q_PROPERTY(int videosFound READ videosFound NOTIFY progressChanged)
    Q_PROPERTY(int thumbnailsDone READ thumbnailsDone NOTIFY progressChanged)
    Q_PROPERTY(int thumbnailsTotal READ thumbnailsTotal NOTIFY progressChanged)
    // Connected drives: {path, name, kind, count, scanning, found, sizeText, freeText, usedFraction}.
    Q_PROPERTY(QVariantList drives READ drives NOTIFY drivesChanged)
    // Connected phones, the same, plus ready: false (and no path) for one that's locked or
    // only charging, whose videos can't be seen yet.
    Q_PROPERTY(QVariantList phones READ phones NOTIFY drivesChanged)
    Q_PROPERTY(VideoModel *continueWatching READ continueWatching CONSTANT)
    Q_PROPERTY(VideoModel *recentlyAdded READ recentlyAdded CONSTANT)
    Q_PROPERTY(VideoModel *quickPicks READ quickPicks CONSTANT)
    // Every folder in the library with videos in it (not counting its sub-folders').
    Q_PROPERTY(FolderModel *folders READ folders CONSTANT)
    // Every video watched (finished or not) that's here now, most recent first; its detail
    // is the day it was watched ("Today", "Yesterday", "12 Oct 2026").
    Q_PROPERTY(VideoModel *history READ history CONSTANT)
    // The user's playlists, most recently added to first.
    Q_PROPERTY(PlaylistModel *playlists READ playlists CONSTANT)
    // Goes up whenever a playlist changes, for bindings on playlistName() and the like.
    Q_PROPERTY(int playlistRevision READ playlistRevision NOTIFY playlistsChanged)
    // Set by the player while a phone's video is open: that phone's thumbnails wait meanwhile.
    Q_PROPERTY(bool phonePlaying READ phonePlaying WRITE setPhonePlaying NOTIFY phonePlayingChanged)

public:
    explicit VideoLibrary(QObject *parent = nullptr);
    ~VideoLibrary() override;

    bool scanning() const { return m_pendingScans > 0; }
    int videosFound() const;
    int thumbnailsDone() const { return int(m_thumbBatch.size() - m_thumbPending.size()); }
    int thumbnailsTotal() const { return int(m_thumbBatch.size()); }
    int count() const { return m_videos.size(); }
    int libraryCount() const;
    QString videosFolder() const { return m_videosFolder; }
    QVariantList drives() const;
    QVariantList phones() const;
    VideoModel *continueWatching() const { return m_continueWatching; }
    VideoModel *history() const { return m_historyModel; }
    bool phonePlaying() const { return m_phonePlaying; }
    void setPhonePlaying(bool playing);
    VideoModel *recentlyAdded() const { return m_recentlyAdded; }
    VideoModel *quickPicks() const { return m_quickPicks; }
    FolderModel *folders() const { return m_folders; }
    PlaylistModel *playlists() const { return m_playlistModel; }
    int playlistRevision() const { return m_playlistRevision; }

    // Looks for new and removed videos; rescanFinished() reports what changed.
    Q_INVOKABLE void rescan();
    Q_INVOKABLE void reshuffleQuickPicks();

    // Models for browsing one folder.
    Q_INVOKABLE VideoModel *videosIn(const QString &folder);
    Q_INVOKABLE FolderModel *foldersIn(const QString &folder);
    // Started, unfinished videos on one drive, most recently watched first.
    Q_INVOKABLE VideoModel *continueWatchingOn(const QString &drivePath);
    Q_INVOKABLE bool isDrive(const QString &folder) const;
    // The connected drive (or phone) a folder is on ("E:/"), or "" if it isn't on one.
    Q_INVOKABLE QString driveOf(const QString &folder) const;
    // Whether a folder or video is on a phone.
    Q_INVOKABLE bool isPhone(const QString &path) const { return path.startsWith(QLatin1String("mtp://")); }
    // What the player opens for a video: its path, or for one on a phone, a stream from it.
    Q_INVOKABLE QString playbackUrl(const QString &path) const;
    Q_INVOKABLE QString folderName(const QString &folder) const;
    // Thumbnails for the videos in a folder (sub-folders included): {done, total}, both
    // 0 when none are being made.
    Q_INVOKABLE QVariantMap thumbnailProgressIn(const QString &folder) const;
    // Makes the thumbnails for a folder's videos next: the one the user is looking at.
    Q_INVOKABLE void prioritize(const QString &folder);
    // Whether the video's thumbnail is still to be made (it's showing a placeholder for now).
    bool thumbnailPending(const QString &path) const { return m_thumbPending.contains(path); }
    Q_INVOKABLE int videoCountIn(const QString &folder) const;

    Q_INVOKABLE QString titleOf(const QString &path) const;
    // For the watch page: title, folder, folderLabel, fileName, sizeText, addedText, modifiedText,
    // inLibrary, external, phone, watched (finished), inHistory; canManage for one on this
    // computer (see renameVideo); for a phone's video also phoneFolder, where it is on the
    // phone ("OPPO Find X9 Pro › DCIM › Camera").
    Q_INVOKABLE QVariantMap detailsOf(const QString &path) const;
    Q_INVOKABLE QString folderOf(const QString &path) const;
    // The video after this one in its folder, or "" at the end.
    Q_INVOKABLE QString nextInFolder(const QString &path) const;
    // The video before this one in its folder, or "" at the start.
    Q_INVOKABLE QString previousInFolder(const QString &path) const;
    Q_INVOKABLE QUrl thumbnailFor(const QString &path) const;
    // Subtitle files that belong to a video, best match first: same name (ignoring case,
    // spaces and punctuation, plus an optional language tag like ".en"), the same
    // episode (S01E02, 1x02, "Season 1 Episode 2"), or any subtitle in a folder that
    // holds only this video. Also looks in Subs/Subtitles sub-folders.
    Q_INVOKABLE QStringList subtitlesFor(const QString &path) const;
    // What the user chose for a video's subtitles: the subtitle file to load, "" for
    // none, or undefined if they haven't been asked (or the chosen file is gone).
    Q_INVOKABLE QVariant subtitleChoice(const QString &path) const;
    Q_INVOKABLE void setSubtitleChoice(const QString &path, const QString &subtitlePath);
    // The audio track the user chose for a video, or 0 for its default.
    Q_INVOKABLE int audioChoice(const QString &path) const;
    Q_INVOKABLE void setAudioChoice(const QString &path, int track);
    // Opens the file's folder in the system file manager, with the file selected where possible.
    // A phone's video is found in Explorer in the background (a few seconds in a big folder);
    // phoneFileShown() says how that went.
    Q_INVOKABLE void showInFileManager(const QString &path);
    // A random selection of videos from other folders: on the same drive for a
    // drive's video, otherwise from the library.
    Q_INVOKABLE VideoModel *suggestionsFor(const QString &path);
    Q_INVOKABLE double resumePosition(const QString &path) const;
    Q_INVOKABLE void saveProgress(const QString &path, double position, double duration);
    Q_INVOKABLE QString localPath(const QUrl &url) const { return url.toLocalFile(); }
    // A file or folder as a URL, for a dialog's folder ("#" and "%" in names kept as they are).
    Q_INVOKABLE QUrl fileUrl(const QString &path) const { return path.isEmpty() ? QUrl() : QUrl::fromLocalFile(path); }
    // The video extensions, for the Open dialog: "*.mp4 *.mkv …" (see VideoFormats).
    Q_INVOKABLE QString videoNameFilter() const;
    // And the subtitle ones, for the subtitle dialog, and whether a dropped file is one.
    Q_INVOKABLE QString subtitleNameFilter() const;
    Q_INVOKABLE bool isSubtitleFile(const QString &path) const;

    // --- Managing files: videos on this computer or a drive, not on a phone ----------
    Q_INVOKABLE bool canManage(const QString &path) const;
    // Renames a video file (and subtitle files named after it), keeping its place in the
    // history and its thumbnail. `name` is the new file name; the extension is kept if it's
    // left off. Returns {path} (the new one) or {error}. The player must have let go of it.
    Q_INVOKABLE QVariantMap renameVideo(const QString &path, const QString &name);
    // Moves it (and its subtitle files) to another folder, in the background: copying to
    // another drive takes a while. videoMoved() says how it went.
    Q_INVOKABLE void moveVideo(const QString &path, const QString &folder);
    // Sends it to the Recycle Bin. Returns an error message, or "".
    Q_INVOKABLE QString deleteVideo(const QString &path);

    // --- Watch history -------------------------------------------------------------
    // Watched marks it finished; unwatched forgets how far it got (it leaves the history and
    // Continue watching). Its subtitle and audio choices are kept either way.
    Q_INVOKABLE void setWatched(const QString &path, bool watched);
    Q_INVOKABLE void clearHistory();
    // How far it got and when ({position, finished, watchedAt, duration}), to put back with
    // restoreHistory() when a change to it is undone.
    Q_INVOKABLE QVariantMap historyEntry(const QString &path) const;
    Q_INVOKABLE void restoreHistory(const QString &path, const QVariantMap &saved);

    // --- Playing a list -----------------------------------------------------------
    // Every video in a folder and the folders inside it, in order ("" for the whole library).
    Q_INVOKABLE QStringList videoPathsIn(const QString &folder) const;
    // These videos, in this order (Play all, Shuffle); ones no longer here drop out.
    Q_INVOKABLE VideoModel *videosAt(const QStringList &paths);

    // --- Playlists --------------------------------------------------------------------
    // A new playlist, with these videos in it (or none yet). Returns its id.
    Q_INVOKABLE QString createPlaylist(const QString &name, const QStringList &paths = {});
    Q_INVOKABLE void renamePlaylist(const QString &id, const QString &name);
    Q_INVOKABLE void deletePlaylist(const QString &id);
    // "" if there's no such playlist (it was deleted).
    Q_INVOKABLE QString playlistName(const QString &id) const;
    // Adds a video at the end, or at `at` in the whole list (unplugged drives' videos
    // included, as removeFromPlaylist() gave it). False if it's in the playlist already.
    Q_INVOKABLE bool addToPlaylist(const QString &id, const QString &path, int at = -1);
    // Takes it out. Returns where it was, to put it back with addToPlaylist(), or -1.
    Q_INVOKABLE int removeFromPlaylist(const QString &id, const QString &path);
    // Moves a video to just before `before` (another video in it), or to the end for "".
    Q_INVOKABLE void moveInPlaylist(const QString &id, const QString &path, const QString &before);
    // The ids of the playlists a video is in.
    Q_INVOKABLE QStringList playlistsWith(const QString &path) const;
    // A playlist's videos that are here now, in its order: as a list model, and their
    // paths (for Play all).
    Q_INVOKABLE VideoModel *playlistVideos(const QString &id);
    Q_INVOKABLE QStringList playlistPaths(const QString &id) const;

    // --- Drives ---------------------------------------------------------------------
    // Asks Windows to let go of the drive so it can be unplugged safely. Works in the
    // background; on success the drive leaves (driveRemoved with `ejected`), otherwise
    // driveEjectFailed says why.
    Q_INVOKABLE void ejectDrive(const QString &path);
    Q_INVOKABLE QUrl settingsFile() const { return QUrl::fromLocalFile(m_dataDir + QStringLiteral("/settings.ini")); }

    // For the models.
    const VideoInfo &video(int index) const { return m_videos.at(index); }
    QUrl thumbnailUrl(int index) const;
    QList<int> indexesIn(const QString &folder) const;
    // Keeps a model up to date as the library changes.
    void track(VideoModel *model);
    // "Season 2 · House MD…": where a video is, for subtitles.
    QString locationOf(const VideoInfo &video) const;
    const QList<Playlist> &playlistList() const { return m_playlists; }
    // The library indexes of a playlist's videos that are here now, in its order.
    QList<int> playlistIndexes(const Playlist &playlist) const;

signals:
    void scanningChanged();
    void phonePlayingChanged();
    void libraryReset();
    void drivesChanged();
    void videoChanged(int index);
    void progressChanged();
    // Which videos are waiting for a thumbnail has been worked out afresh.
    void thumbnailsQueued();
    // A drive was plugged in and scanned.
    void driveConnected(const QString &name, const QString &path, int count);
    // A drive is being removed; its videos are still listed while this is handled.
    void driveRemoved(const QString &path, const QString &name, bool ejected);
    void driveEjectFailed(const QString &path, const QString &name, const QString &error);
    // A rescan the user asked for is done: how many library videos, and how many came and went.
    void rescanFinished(int count, int added, int removed);
    // A phone's video asked for with showInFileManager() is showing in Explorer, or couldn't be found.
    void phoneFileShown(const QString &path, bool shown);
    // A move asked for with moveVideo() is done: `error` is "" if it worked.
    void videoMoved(const QString &oldPath, const QString &newPath, const QString &error);
    // A playlist was made, renamed, deleted, or had videos added, taken out or moved.
    void playlistsChanged();

private:
    void scanAll();
    void scanSource(const QString &path);
    // Looks through one folder of a source again after a change there: just its own files,
    // or (`recursive`) the folders inside it too.
    void rescanPart(const QString &source, const QString &folder, bool recursive);
    // The library's sources are the computer's own drives (and the Videos folder, if it's
    // somewhere else): taken in or let go as they come and go.
    void updateLibrarySources();
    void onScanFinished(const QString &path, QList<VideoInfo> videos);
    // What the last scan of a library source found is kept for the next launch (scans/*.json).
    // A change (a folder looked at again, a video moved) is saved a little later, so a busy
    // folder doesn't have the whole drive's list written out every couple of seconds.
    void scanListChanged(const QString &source);
    // Writes out the lists that have changed, on m_scanSaver's thread.
    void saveScanLists();
    // Some of a phone's videos, found while it's still being scanned.
    void onVideosFound(const QString &path, const QList<VideoInfo> &found);
    void onDrivesChanged();
    // The library's drives are watched for videos coming and going.
    void updateWatchedFolders();
    // Looked at again a moment after the last change: `folder`'s own files, or its whole tree.
    void onFolderChanged(const QString &folder, bool recursive);
    // Phones: looked for every couple of seconds (off the UI thread), and taken in or let
    // go like drives as they're connected, unlocked, or unplugged.
    void pollPhones();
    void onPhonesChanged(const QList<Phones::Phone> &phones);
    void rebuild();
    // While scanning: batches of videos are listed at most every couple of seconds, as
    // each rebuild resets every list and the thumbnail queue.
    void scheduleRebuild();
    // Takes a video out of every source's list (the Videos folder on an external drive is in both).
    void forgetScanned(const QString &path);
    void onThumbnailFinished(const QString &path, bool ok, double duration, int width, int height, int year);
    void queueThumbnails();
    void refreshModels();
    void refreshContinueWatching();
    // After a video file was renamed or moved: its entry, history, thumbnail and cached
    // details follow it (or it leaves the library, if it went somewhere outside it).
    void relocate(const QString &oldPath, const QString &newPath);
    // Changes a video's history entry (`change` edits the JSON) and what's shown of it.
    void editHistory(const QString &path, const std::function<void(QJsonObject &)> &change);
    // Started, unfinished videos on a drive, or in the library for "".
    QList<int> startedIn(const QString &drivePath) const;
    Playlist *findPlaylist(const QString &id);
    const Playlist *findPlaylist(const QString &id) const;
    // After a playlist changed: saved, and its lists shown afresh.
    void playlistsEdited();
    void refreshPlaylists();
    int sourceIndex(const QString &path) const;
    QString sourceName(const QString &path) const;
    QString keyFor(const QString &path) const;
    QString thumbnailFile(const VideoInfo &video) const;
    void loadState();
    void scheduleSave();
    void save();

    QString m_dataDir;
    QString m_videosFolder;
    QList<VideoSource> m_sources;                 // the library's first, then external drives and phones
    QHash<QString, QList<VideoInfo>> m_scanned;   // source path -> its videos
    int m_pendingScans = 0;
    QSet<QString> m_unsavedScans;   // sources whose list has changed since it was saved
    QTimer m_scanSaveTimer;
    // One thread, so the lists are written (and removed) in the order asked.
    QThreadPool m_scanSaver;
    // The library before a user-requested rescan, to report what changed (empty: not reporting).
    QSet<QString> m_beforeRescan;
    bool m_reportRescan = false;

    // While scanning, reports how many videos have been found so far.
    QTimer m_scanProgressTimer;
    // Reports thumbnails made, at most a few times a second.
    QTimer m_progressTimer;

    // Thumbnails still to make, and every one asked for since there were none to make
    // (the batch a progress bar counts through). Video paths.
    QSet<QString> m_thumbPending;
    QSet<QString> m_thumbBatch;
    QString m_priorityFolder;   // its thumbnails go first ("" for the library), if m_hasPriority
    bool m_hasPriority = false;

    QList<VideoInfo> m_videos;
    QHash<QString, int> m_byPath;
    QHash<QString, int> m_byKey;   // video key -> index, for playlists
    quint32 m_pickSeed = 0;

    QHash<QString, VideoMeta> m_metadata;   // by cache key
    QSet<QString> m_thumbFiles;             // cache keys with a thumbnail file on disk
    bool m_metadataChanged = false;         // since it was last saved
    QJsonObject m_history;    // video key -> {position, duration, watchedAt, finished}
    QList<Playlist> m_playlists;   // most recently added to first
    bool m_playlistsChanged = false;   // since they were last saved
    int m_playlistRevision = 0;
    QTimer m_saveTimer;

    DriveWatcher *m_driveWatcher = nullptr;
    FolderWatcher *m_folderWatcher = nullptr;
    QHash<QString, bool> m_changedFolders;   // waiting for m_changeTimer, then rescanned (whole tree: true)
    QSet<QString> m_ejected;          // drives ejected from here, until they leave
    bool m_phonePlaying = false;
    QTimer m_changeTimer;
    QTimer m_rebuildTimer;          // a rebuild waiting (scheduleRebuild)
    QElapsedTimer m_sinceRebuild;   // since the last one
    QSet<QString> m_unsorted;       // sources whose m_scanned list has had videos added at the end
    QTimer m_phoneTimer;
    bool m_pollingPhones = false;
    QList<Phones::Phone> m_phones;   // connected, ready or not
    Thumbnailer *m_thumbnailer = nullptr;
    VideoModel *m_continueWatching = nullptr;
    VideoModel *m_historyModel = nullptr;
    VideoModel *m_recentlyAdded = nullptr;
    VideoModel *m_quickPicks = nullptr;
    FolderModel *m_folders = nullptr;
    PlaylistModel *m_playlistModel = nullptr;
    QList<QPointer<VideoModel>> m_playlistVideoModels;   // also refreshed as playlists change
    QList<QPointer<VideoModel>> m_folderVideoModels;
    QList<QPointer<VideoModel>> m_driveContinueModels;   // also refreshed as progress is saved
    QList<QPointer<FolderModel>> m_folderModels;
};
