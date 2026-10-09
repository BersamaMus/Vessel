#pragma once

#include <QDateTime>
#include <QHash>
#include <QJsonObject>
#include <QList>
#include <QObject>
#include <QPointer>
#include <QTimer>
#include <QUrl>
#include <QtQml/qqmlregistration.h>

#include "VideoModels.h"

class Thumbnailer;

struct VideoInfo
{
    QString path;       // absolute, with '/' separators
    QString title;
    QString folder;     // relative to the library root; empty for the root itself
    qint64 size = 0;
    QDateTime modified;
    QDateTime added;
    double duration = 0;
    bool hasThumbnail = false;
    double position = 0;    // last watched position, seconds
    qint64 watchedAt = 0;   // ms since epoch; 0 if never watched
    bool finished = false;
};

// The videos under the library folder (the user's Videos folder), their
// cached thumbnails and durations, and how far each one has been watched.
class VideoLibrary : public QObject
{
    Q_OBJECT
    QML_ELEMENT
    QML_SINGLETON

    Q_PROPERTY(QString rootPath READ rootPath CONSTANT)
    Q_PROPERTY(bool scanning READ scanning NOTIFY scanningChanged)
    Q_PROPERTY(int count READ count NOTIFY libraryReset)
    Q_PROPERTY(VideoModel *continueWatching READ continueWatching CONSTANT)
    Q_PROPERTY(VideoModel *recentlyAdded READ recentlyAdded CONSTANT)
    Q_PROPERTY(VideoModel *quickPicks READ quickPicks CONSTANT)
    Q_PROPERTY(FolderModel *folders READ folders CONSTANT)

public:
    explicit VideoLibrary(QObject *parent = nullptr);
    ~VideoLibrary() override;

    QString rootPath() const { return m_root; }
    bool scanning() const { return m_scanning; }
    int count() const { return m_videos.size(); }
    VideoModel *continueWatching() const { return m_continueWatching; }
    VideoModel *recentlyAdded() const { return m_recentlyAdded; }
    VideoModel *quickPicks() const { return m_quickPicks; }
    FolderModel *folders() const { return m_folders; }

    Q_INVOKABLE void rescan();
    Q_INVOKABLE void reshuffleQuickPicks();
    // Models for browsing one folder (relative to the root; "" is the root).
    Q_INVOKABLE VideoModel *videosIn(const QString &folder);
    Q_INVOKABLE FolderModel *foldersIn(const QString &folder);
    Q_INVOKABLE QString folderName(const QString &folder) const;
    Q_INVOKABLE int videoCountIn(const QString &folder) const;

    Q_INVOKABLE QString titleOf(const QString &path) const;
    // For the watch page: title, folder, folderLabel, fileName, sizeText, addedText.
    Q_INVOKABLE QVariantMap detailsOf(const QString &path) const;
    Q_INVOKABLE QString folderOf(const QString &path) const;
    // The video after this one in its folder, or "" at the end.
    Q_INVOKABLE QString nextInFolder(const QString &path) const;
    // A random selection of other videos, preferring other folders.
    Q_INVOKABLE VideoModel *suggestionsFor(const QString &path);
    Q_INVOKABLE double resumePosition(const QString &path) const;
    Q_INVOKABLE void saveProgress(const QString &path, double position, double duration);
    Q_INVOKABLE QString localPath(const QUrl &url) const { return url.toLocalFile(); }
    Q_INVOKABLE QUrl settingsFile() const { return QUrl::fromLocalFile(m_dataDir + QStringLiteral("/settings.ini")); }

    // For the models.
    const VideoInfo &video(int index) const { return m_videos.at(index); }
    QUrl thumbnailUrl(int index) const;
    QList<int> indexesIn(const QString &folder) const;

signals:
    void scanningChanged();
    void libraryReset();
    void videoChanged(int index);

private:
    void onScanFinished(QList<VideoInfo> videos);
    void onThumbnailFinished(const QString &path, bool ok, double duration);
    void queueThumbnails();
    void refreshModels();
    void refreshContinueWatching();
    QString cacheKey(const VideoInfo &video) const;
    QString thumbnailFile(const VideoInfo &video) const;
    void loadState();
    void scheduleSave();
    void save();

    QString m_root;
    QString m_dataDir;
    bool m_scanning = false;
    QList<VideoInfo> m_videos;
    QHash<QString, int> m_byPath;
    QList<int> m_pickOrder;

    QJsonObject m_metadata;   // cache key -> {duration, thumb, failed}
    QJsonObject m_history;    // path -> {position, duration, watchedAt, finished}
    QTimer m_saveTimer;

    Thumbnailer *m_thumbnailer = nullptr;
    VideoModel *m_continueWatching = nullptr;
    VideoModel *m_recentlyAdded = nullptr;
    VideoModel *m_quickPicks = nullptr;
    FolderModel *m_folders = nullptr;
    QList<QPointer<VideoModel>> m_folderVideoModels;
    QList<QPointer<FolderModel>> m_folderModels;
};
