#include "VideoLibrary.h"

#include "Thumbnailer.h"
#include "VideoModels.h"

#include <QCollator>
#include <QCryptographicHash>
#include <QDir>
#include <QDirIterator>
#include <QFile>
#include <QFileInfo>
#include <QFutureWatcher>
#include <QJsonDocument>
#include <QLocale>
#include <QRandomGenerator>
#include <QSaveFile>
#include <QSet>
#include <QStandardPaths>
#include <QtConcurrent/QtConcurrentRun>

#include <algorithm>
#include <numeric>

namespace {

constexpr int kShelfSize = 20;
constexpr int kQuickPicks = 16;
// Under this many seconds in, a video doesn't count as started.
constexpr double kMinProgress = 10;

QString prettyTitle(const QString &baseName)
{
    // "A.Star.Is.Born.2018" -> "A Star Is Born 2018"
    if (!baseName.contains(QLatin1Char(' ')) && (baseName.contains(QLatin1Char('.')) || baseName.contains(QLatin1Char('_')))) {
        QString title = baseName;
        title.replace(QLatin1Char('.'), QLatin1Char(' ')).replace(QLatin1Char('_'), QLatin1Char(' '));
        return title.simplified();
    }
    return baseName;
}

QList<VideoInfo> scanFolder(const QString &root)
{
    static const QSet<QString> extensions = {
        "mp4", "m4v", "mkv", "mov", "webm", "avi", "wmv", "flv", "ts", "m2ts",
        "mts", "mpg", "mpeg", "3gp", "ogv", "divx", "asf", "rmvb",
    };

    QList<VideoInfo> videos;
    const QDir rootDir(root);
    QDirIterator it(root, QDir::Files, QDirIterator::Subdirectories);
    while (it.hasNext()) {
        const QFileInfo file = it.nextFileInfo();
        // Empty files are unfinished downloads; they can't be played.
        if (!extensions.contains(file.suffix().toLower()) || file.size() == 0)
            continue;

        VideoInfo video;
        video.path = file.absoluteFilePath();
        video.title = prettyTitle(file.completeBaseName());
        video.folder = rootDir.relativeFilePath(file.absolutePath());
        if (video.folder == QLatin1String("."))
            video.folder.clear();
        video.size = file.size();
        video.modified = file.lastModified();
        // Creation time is when the file was copied here, so it's when it was "added".
        const QDateTime born = file.birthTime();
        video.added = born.isValid() ? born : video.modified;
        videos.append(video);
    }

    QCollator collator;
    collator.setNumericMode(true);
    collator.setCaseSensitivity(Qt::CaseInsensitive);
    std::sort(videos.begin(), videos.end(), [&](const VideoInfo &a, const VideoInfo &b) {
        return collator.compare(a.path, b.path) < 0;
    });
    return videos;
}

bool isFinished(double position, double duration)
{
    // 95% covers a film's end credits; short clips just need to reach the end.
    return duration > 0 && (position >= duration * 0.95 || duration - position < 5);
}

} // namespace

VideoLibrary::VideoLibrary(QObject *parent)
    : QObject(parent)
    , m_root(QDir::fromNativeSeparators(QStandardPaths::writableLocation(QStandardPaths::MoviesLocation)))
    , m_dataDir(QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation))
{
    QDir().mkpath(m_dataDir + QStringLiteral("/thumbnails"));
    loadState();

    m_saveTimer.setSingleShot(true);
    m_saveTimer.setInterval(2000);
    connect(&m_saveTimer, &QTimer::timeout, this, &VideoLibrary::save);

    m_thumbnailer = new Thumbnailer(this);
    connect(m_thumbnailer, &Thumbnailer::finished, this, &VideoLibrary::onThumbnailFinished);

    m_continueWatching = new VideoModel(this, [this] {
        QList<int> rows;
        for (int i = 0; i < m_videos.size(); ++i) {
            const VideoInfo &v = m_videos[i];
            if (v.watchedAt > 0 && !v.finished && v.position >= kMinProgress)
                rows.append(i);
        }
        std::sort(rows.begin(), rows.end(), [this](int a, int b) {
            return m_videos[a].watchedAt > m_videos[b].watchedAt;
        });
        return rows.mid(0, kShelfSize);
    }, this);

    m_recentlyAdded = new VideoModel(this, [this] {
        QList<int> rows(m_videos.size());
        std::iota(rows.begin(), rows.end(), 0);
        std::stable_sort(rows.begin(), rows.end(), [this](int a, int b) {
            return m_videos[a].added > m_videos[b].added;
        });
        return rows.mid(0, kShelfSize);
    }, this);

    m_quickPicks = new VideoModel(this, [this] { return m_pickOrder.mid(0, kQuickPicks); }, this);
    m_folders = new FolderModel(this, QString(), this);

    rescan();
}

VideoLibrary::~VideoLibrary()
{
    delete m_thumbnailer;   // waits for running grabs before we save
    m_thumbnailer = nullptr;
    save();
}

void VideoLibrary::rescan()
{
    if (m_scanning)
        return;
    m_scanning = true;
    emit scanningChanged();

    auto *watcher = new QFutureWatcher<QList<VideoInfo>>(this);
    connect(watcher, &QFutureWatcherBase::finished, this, [this, watcher] {
        watcher->deleteLater();
        onScanFinished(watcher->result());
    });
    watcher->setFuture(QtConcurrent::run(scanFolder, m_root));
}

void VideoLibrary::onScanFinished(QList<VideoInfo> videos)
{
    m_videos = std::move(videos);
    m_byPath.clear();
    for (int i = 0; i < m_videos.size(); ++i) {
        VideoInfo &v = m_videos[i];
        m_byPath.insert(v.path, i);

        const QJsonObject meta = m_metadata.value(cacheKey(v)).toObject();
        v.duration = meta.value(QStringLiteral("duration")).toDouble();
        v.hasThumbnail = meta.value(QStringLiteral("thumb")).toBool() && QFile::exists(thumbnailFile(v));

        const QJsonObject seen = m_history.value(v.path).toObject();
        if (!seen.isEmpty()) {
            v.position = seen.value(QStringLiteral("position")).toDouble();
            v.watchedAt = seen.value(QStringLiteral("watchedAt")).toInteger();
            v.finished = seen.value(QStringLiteral("finished")).toBool();
            if (v.duration <= 0)
                v.duration = seen.value(QStringLiteral("duration")).toDouble();
        }
    }

    m_pickOrder.resize(m_videos.size());
    std::iota(m_pickOrder.begin(), m_pickOrder.end(), 0);
    std::shuffle(m_pickOrder.begin(), m_pickOrder.end(), *QRandomGenerator::global());

    m_scanning = false;
    emit scanningChanged();
    emit libraryReset();
    refreshModels();
    queueThumbnails();
}

void VideoLibrary::queueThumbnails()
{
    m_thumbnailer->clear();

    // What's on the Home page first, then everything else.
    QList<int> order;
    for (VideoModel *model : {m_continueWatching, m_recentlyAdded, m_quickPicks})
        for (int row = 0; row < model->rowCount(); ++row)
            order.append(m_byPath.value(model->pathAt(row)));
    for (int i = 0; i < m_videos.size(); ++i)
        order.append(i);

    QSet<int> queued;
    for (int i : order) {
        if (queued.contains(i))
            continue;
        queued.insert(i);
        const VideoInfo &v = m_videos[i];
        const bool known = m_metadata.contains(cacheKey(v));
        if (!known || (m_metadata.value(cacheKey(v)).toObject().value(QStringLiteral("thumb")).toBool() && !v.hasThumbnail))
            m_thumbnailer->request(v.path, thumbnailFile(v));
    }
}

void VideoLibrary::onThumbnailFinished(const QString &path, bool ok, double duration)
{
    const int index = m_byPath.value(path, -1);
    if (index < 0)
        return;
    VideoInfo &v = m_videos[index];
    if (duration > 0)
        v.duration = duration;
    v.hasThumbnail = ok;
    m_metadata.insert(cacheKey(v), QJsonObject{{"duration", v.duration}, {"thumb", ok}});
    scheduleSave();
    emit videoChanged(index);
}

void VideoLibrary::refreshModels()
{
    m_continueWatching->refresh();
    m_recentlyAdded->refresh();
    m_quickPicks->refresh();
    m_folders->refresh();
    for (const auto &model : std::as_const(m_folderVideoModels))
        if (model)
            model->refresh();
    for (const auto &model : std::as_const(m_folderModels))
        if (model)
            model->refresh();
}

void VideoLibrary::reshuffleQuickPicks()
{
    std::shuffle(m_pickOrder.begin(), m_pickOrder.end(), *QRandomGenerator::global());
    m_quickPicks->refresh();
}

VideoModel *VideoLibrary::videosIn(const QString &folder)
{
    auto *model = new VideoModel(this, [this, folder] { return indexesIn(folder); });
    m_folderVideoModels.removeAll(nullptr);
    m_folderVideoModels.append(model);
    return model;   // owned by QML (no parent)
}

FolderModel *VideoLibrary::foldersIn(const QString &folder)
{
    auto *model = new FolderModel(this, folder);
    m_folderModels.removeAll(nullptr);
    m_folderModels.append(model);
    return model;   // owned by QML (no parent)
}

QList<int> VideoLibrary::indexesIn(const QString &folder) const
{
    QList<int> rows;
    for (int i = 0; i < m_videos.size(); ++i)
        if (m_videos[i].folder == folder)
            rows.append(i);
    return rows;
}

QString VideoLibrary::folderName(const QString &folder) const
{
    return folder.isEmpty() ? QDir(m_root).dirName() : folder.section(QLatin1Char('/'), -1);
}

int VideoLibrary::videoCountIn(const QString &folder) const
{
    if (folder.isEmpty())
        return m_videos.size();
    const QString prefix = folder + QLatin1Char('/');
    return std::count_if(m_videos.begin(), m_videos.end(), [&](const VideoInfo &v) {
        return v.folder == folder || v.folder.startsWith(prefix);
    });
}

QString VideoLibrary::titleOf(const QString &path) const
{
    const int index = m_byPath.value(path, -1);
    return index >= 0 ? m_videos[index].title : prettyTitle(QFileInfo(path).completeBaseName());
}

QVariantMap VideoLibrary::detailsOf(const QString &path) const
{
    const QFileInfo file(path);
    const int index = m_byPath.value(path, -1);
    const QLocale locale = QLocale::system();
    const QString folder = index >= 0 ? m_videos[index].folder : QString();
    const QDateTime added = index >= 0 ? m_videos[index].added : file.birthTime();

    return {
        {"title", titleOf(path)},
        {"inLibrary", index >= 0},
        {"folder", folder},
        {"folderLabel", index >= 0 ? folderLabel(folder, folderName(QString())) : file.absolutePath()},
        {"fileName", file.fileName()},
        {"sizeText", locale.formattedDataSize(file.size(), 1, QLocale::DataSizeTraditionalFormat)},
        {"addedText", added.isValid() ? locale.toString(added.date(), QStringLiteral("d MMM yyyy")) : QString()},
    };
}

QString VideoLibrary::folderOf(const QString &path) const
{
    const int index = m_byPath.value(path, -1);
    return index >= 0 ? m_videos[index].folder : QString();
}

QString VideoLibrary::nextInFolder(const QString &path) const
{
    const int index = m_byPath.value(path, -1);
    // Library order is natural path order, so the next one in the same folder follows directly.
    if (index < 0 || index + 1 >= m_videos.size() || m_videos[index + 1].folder != m_videos[index].folder)
        return {};
    return m_videos[index + 1].path;
}

VideoModel *VideoLibrary::suggestionsFor(const QString &path)
{
    auto *model = new VideoModel(this, [this, path] {
        const QString folder = folderOf(path);
        QList<int> others;
        QList<int> sameFolder;
        for (int i = 0; i < m_videos.size(); ++i) {
            if (m_videos[i].path == path)
                continue;
            (m_videos[i].folder == folder ? sameFolder : others).append(i);
        }
        std::shuffle(others.begin(), others.end(), *QRandomGenerator::global());
        std::shuffle(sameFolder.begin(), sameFolder.end(), *QRandomGenerator::global());
        others.append(sameFolder);
        return others.mid(0, 12);
    });
    m_folderVideoModels.removeAll(nullptr);
    m_folderVideoModels.append(model);
    return model;   // owned by QML (no parent)
}

double VideoLibrary::resumePosition(const QString &path) const
{
    const QJsonObject seen = m_history.value(path).toObject();
    const double position = seen.value(QStringLiteral("position")).toDouble();
    if (seen.value(QStringLiteral("finished")).toBool() || position < kMinProgress)
        return 0;
    return qMax(0.0, position - 3);   // a little before, to pick the scene back up
}

void VideoLibrary::saveProgress(const QString &path, double position, double duration)
{
    if (path.isEmpty() || duration <= 0)
        return;
    const bool finished = isFinished(position, duration);
    const qint64 now = QDateTime::currentMSecsSinceEpoch();
    m_history.insert(path, QJsonObject{
        {"position", position}, {"duration", duration}, {"watchedAt", now}, {"finished", finished}});
    scheduleSave();

    const int index = m_byPath.value(path, -1);
    if (index < 0)
        return;
    VideoInfo &v = m_videos[index];
    v.position = position;
    v.watchedAt = now;
    v.finished = finished;
    if (v.duration <= 0)
        v.duration = duration;
    emit videoChanged(index);
    m_continueWatching->refresh();
}

QUrl VideoLibrary::thumbnailUrl(int index) const
{
    const VideoInfo &v = m_videos.at(index);
    return v.hasThumbnail ? QUrl::fromLocalFile(thumbnailFile(v)) : QUrl();
}

QString VideoLibrary::cacheKey(const VideoInfo &video) const
{
    // Changes when the file is replaced, so stale thumbnails aren't reused.
    const QByteArray id = video.path.toUtf8() + '|' + QByteArray::number(video.size) + '|'
        + QByteArray::number(video.modified.toMSecsSinceEpoch());
    return QString::fromLatin1(QCryptographicHash::hash(id, QCryptographicHash::Sha1).toHex());
}

QString VideoLibrary::thumbnailFile(const VideoInfo &video) const
{
    return m_dataDir + QStringLiteral("/thumbnails/") + cacheKey(video) + QStringLiteral(".jpg");
}

void VideoLibrary::loadState()
{
    const auto readJson = [this](const QString &name) {
        QFile file(m_dataDir + QLatin1Char('/') + name);
        if (!file.open(QIODevice::ReadOnly))
            return QJsonObject();
        return QJsonDocument::fromJson(file.readAll()).object();
    };
    m_metadata = readJson(QStringLiteral("metadata.json"));
    m_history = readJson(QStringLiteral("history.json"));
}

void VideoLibrary::scheduleSave()
{
    if (!m_saveTimer.isActive())
        m_saveTimer.start();
}

void VideoLibrary::save()
{
    m_saveTimer.stop();
    const auto writeJson = [this](const QString &name, const QJsonObject &object) {
        QSaveFile file(m_dataDir + QLatin1Char('/') + name);
        if (file.open(QIODevice::WriteOnly)) {
            file.write(QJsonDocument(object).toJson(QJsonDocument::Compact));
            file.commit();
        }
    };
    writeJson(QStringLiteral("metadata.json"), m_metadata);
    writeJson(QStringLiteral("history.json"), m_history);
}
