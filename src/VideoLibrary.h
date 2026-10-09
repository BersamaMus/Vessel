#pragma once

#include <QDateTime>
#include <QHash>
#include <QJsonObject>
#include <QList>
#include <QObject>
#include <QPointer>
#include <QTimer>
#include <QUrl>
#include <QVariantList>
#include <QtQml/qqmlregistration.h>

#include "VideoModels.h"

class DriveWatcher;
class Thumbnailer;

struct VideoInfo
{
    QString path;       // absolute, with '/' separators
    // Identifies the video in the history and thumbnail cache: the path, or for
    // external drives the drive's id plus the path on the drive, so it survives
    // the drive getting a different letter.
    QString key;
    QString title;
    QString dir;        // absolute folder the file is in
    QString root;       // the library folder or drive it was found under
    bool external = false;
    qint64 size = 0;
    QDateTime modified;
    QDateTime added;
    double duration = 0;
    bool hasThumbnail = false;
    double position = 0;    // last watched position, seconds
    qint64 watchedAt = 0;   // ms since epoch; 0 if never watched
    bool finished = false;
};

// Where videos come from: a library folder, or a connected external drive.
struct VideoSource
{
    QString path;
    QString name;
    bool external = false;
    QString driveId;
    QString kind;           // drives: "usb" or "card"
    qint64 bytesTotal = 0;
    qint64 bytesFree = 0;
    bool scanning = false;
    bool announce = false;  // say so when its first scan finishes (just plugged in)
};

// The videos in the library folders (the user's Videos folder by default) and
// on connected external drives, their cached thumbnails and durations, and how
// far each one has been watched.
//
// Folders are absolute paths. The empty folder "" stands for the library as a
// whole: its sub-folders are the library folders themselves.
class VideoLibrary : public QObject
{
    Q_OBJECT
    QML_ELEMENT
    QML_SINGLETON

    Q_PROPERTY(bool scanning READ scanning NOTIFY scanningChanged)
    Q_PROPERTY(int count READ count NOTIFY libraryReset)
    Q_PROPERTY(int libraryCount READ libraryCount NOTIFY libraryReset)
    Q_PROPERTY(QStringList libraryFolders READ libraryFolders NOTIFY libraryFoldersChanged)
    // What the Library tab opens: the one library folder, or "" if there are several.
    Q_PROPERTY(QString libraryHome READ libraryHome NOTIFY libraryFoldersChanged)
    // Connected drives: {path, name, kind, count, scanning, sizeText, freeText, usedFraction}.
    Q_PROPERTY(QVariantList drives READ drives NOTIFY drivesChanged)
    Q_PROPERTY(VideoModel *continueWatching READ continueWatching CONSTANT)
    Q_PROPERTY(VideoModel *recentlyAdded READ recentlyAdded CONSTANT)
    Q_PROPERTY(VideoModel *quickPicks READ quickPicks CONSTANT)
    Q_PROPERTY(FolderModel *folders READ folders CONSTANT)

public:
    explicit VideoLibrary(QObject *parent = nullptr);
    ~VideoLibrary() override;

    bool scanning() const { return m_pendingScans > 0; }
    int count() const { return m_videos.size(); }
    int libraryCount() const;
    QStringList libraryFolders() const;
    QString libraryHome() const;
    QVariantList drives() const;
    VideoModel *continueWatching() const { return m_continueWatching; }
    VideoModel *recentlyAdded() const { return m_recentlyAdded; }
    VideoModel *quickPicks() const { return m_quickPicks; }
    FolderModel *folders() const { return m_folders; }

    Q_INVOKABLE void rescan();
    Q_INVOKABLE void reshuffleQuickPicks();
    // Returns an error message, or "" when the folder was added.
    Q_INVOKABLE QString addLibraryFolder(const QString &path);
    // Takes the folder out of the library (the files stay where they are).
    Q_INVOKABLE void removeLibraryFolder(const QString &path);

    // Models for browsing one folder.
    Q_INVOKABLE VideoModel *videosIn(const QString &folder);
    Q_INVOKABLE FolderModel *foldersIn(const QString &folder);
    // Started, unfinished videos on one drive, most recently watched first.
    Q_INVOKABLE VideoModel *continueWatchingOn(const QString &drivePath);
    Q_INVOKABLE bool isDrive(const QString &folder) const;
    Q_INVOKABLE QString folderName(const QString &folder) const;
    Q_INVOKABLE int videoCountIn(const QString &folder) const;

    Q_INVOKABLE QString titleOf(const QString &path) const;
    // For the watch page: title, folder, folderLabel, fileName, sizeText, addedText, inLibrary, external.
    Q_INVOKABLE QVariantMap detailsOf(const QString &path) const;
    Q_INVOKABLE QString folderOf(const QString &path) const;
    // The video after this one in its folder, or "" at the end.
    Q_INVOKABLE QString nextInFolder(const QString &path) const;
    // The video before this one in its folder, or "" at the start.
    Q_INVOKABLE QString previousInFolder(const QString &path) const;
    // Subtitle files that belong to a video, best match first: same name (ignoring case,
    // spaces and punctuation, plus an optional language tag like ".en"), the same
    // episode (S01E02, 1x02, "Season 1 Episode 2"), or any subtitle in a folder that
    // holds only this video. Also looks in Subs/Subtitles sub-folders.
    Q_INVOKABLE QStringList subtitlesFor(const QString &path) const;
    // What the user chose for a video's subtitles: the subtitle file to load, "" for
    // none, or undefined if they haven't been asked (or the chosen file is gone).
    Q_INVOKABLE QVariant subtitleChoice(const QString &path) const;
    Q_INVOKABLE void setSubtitleChoice(const QString &path, const QString &subtitlePath);
    // Opens the file's folder in the system file manager, with the file selected where possible.
    Q_INVOKABLE void showInFileManager(const QString &path) const;
    // A random selection of videos from other folders: on the same drive for a
    // drive's video, otherwise from the library.
    Q_INVOKABLE VideoModel *suggestionsFor(const QString &path);
    Q_INVOKABLE double resumePosition(const QString &path) const;
    Q_INVOKABLE void saveProgress(const QString &path, double position, double duration);
    Q_INVOKABLE QString localPath(const QUrl &url) const { return url.toLocalFile(); }
    Q_INVOKABLE QUrl settingsFile() const { return QUrl::fromLocalFile(m_dataDir + QStringLiteral("/settings.ini")); }

    // For the models.
    const VideoInfo &video(int index) const { return m_videos.at(index); }
    QUrl thumbnailUrl(int index) const;
    QList<int> indexesIn(const QString &folder) const;
    // "Season 2 · House MD…": where a video is, for subtitles.
    QString locationOf(const VideoInfo &video) const;
    QStringList libraryRoots() const;

signals:
    void scanningChanged();
    void libraryReset();
    void libraryFoldersChanged();
    void drivesChanged();
    void videoChanged(int index);
    // A drive was plugged in and scanned.
    void driveConnected(const QString &name, const QString &path, int count);
    // A drive is being removed; its videos are still listed while this is handled.
    void driveRemoved(const QString &path);

private:
    void scanSource(const QString &path);
    void onScanFinished(const QString &path, QList<VideoInfo> videos);
    void onDrivesChanged();
    void rebuild();
    void onThumbnailFinished(const QString &path, bool ok, double duration);
    void queueThumbnails();
    void refreshModels();
    void refreshContinueWatching();
    // Started, unfinished videos on a drive, or in the library for "".
    QList<int> startedIn(const QString &drivePath) const;
    int sourceIndex(const QString &path) const;
    QString sourceName(const QString &path) const;
    QString keyFor(const QString &path) const;
    QString cacheKey(const VideoInfo &video) const;
    QString thumbnailFile(const VideoInfo &video) const;
    void saveLibraryFolders();
    void loadState();
    void scheduleSave();
    void save();

    QString m_dataDir;
    QList<VideoSource> m_sources;                 // library folders first, then drives
    QHash<QString, QList<VideoInfo>> m_scanned;   // source path -> its videos
    int m_pendingScans = 0;

    QList<VideoInfo> m_videos;
    QHash<QString, int> m_byPath;
    quint32 m_pickSeed = 0;

    QJsonObject m_metadata;   // cache key -> {duration, thumb}
    QJsonObject m_history;    // video key -> {position, duration, watchedAt, finished}
    QTimer m_saveTimer;

    DriveWatcher *m_driveWatcher = nullptr;
    Thumbnailer *m_thumbnailer = nullptr;
    VideoModel *m_continueWatching = nullptr;
    VideoModel *m_recentlyAdded = nullptr;
    VideoModel *m_quickPicks = nullptr;
    FolderModel *m_folders = nullptr;
    QList<QPointer<VideoModel>> m_folderVideoModels;
    QList<QPointer<VideoModel>> m_driveContinueModels;   // also refreshed as progress is saved
    QList<QPointer<FolderModel>> m_folderModels;
};
