#include "VideoLibrary.h"

#include "DriveWatcher.h"
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
#include <QSettings>
#include <QStandardPaths>
#include <QtConcurrent/QtConcurrentRun>

#include <algorithm>
#include <numeric>

namespace {

constexpr int kShelfSize = 20;
constexpr int kQuickPicks = 16;
// Under this many seconds in, a video doesn't count as started.
constexpr double kMinProgress = 10;

const QString kFoldersKey = QStringLiteral("library/folders");

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

// "C:/Videos" -> "C:/Videos/"; "E:/" stays "E:/".
QString withSlash(const QString &folder)
{
    return folder.endsWith(QLatin1Char('/')) ? folder : folder + QLatin1Char('/');
}

QList<VideoInfo> scanFolder(const QString &root, bool external, const QString &driveId)
{
    static const QSet<QString> extensions = {
        "mp4", "m4v", "mkv", "mov", "webm", "avi", "wmv", "flv", "ts", "m2ts",
        "mts", "mpg", "mpeg", "3gp", "ogv", "divx", "asf", "rmvb",
    };

    QList<VideoInfo> videos;
    const QString prefix = withSlash(root);
    // Hidden and system folders (the recycle bin, System Volume Information) are skipped.
    QDirIterator it(root, QDir::Files, QDirIterator::Subdirectories);
    while (it.hasNext()) {
        const QFileInfo file = it.nextFileInfo();
        // Empty files are unfinished downloads; they can't be played.
        if (!extensions.contains(file.suffix().toLower()) || file.size() == 0)
            continue;

        VideoInfo video;
        video.path = file.absoluteFilePath();
        video.key = external ? QStringLiteral("drive:%1/%2").arg(driveId, video.path.mid(prefix.size()))
                             : video.path;
        video.title = prettyTitle(file.completeBaseName());
        video.dir = file.absolutePath();
        video.root = root;
        video.external = external;
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
    , m_dataDir(QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation))
    , m_pickSeed(QRandomGenerator::global()->generate())
{
    QDir().mkpath(m_dataDir + QStringLiteral("/thumbnails"));
    loadState();

    m_saveTimer.setSingleShot(true);
    m_saveTimer.setInterval(2000);
    connect(&m_saveTimer, &QTimer::timeout, this, &VideoLibrary::save);

    m_thumbnailer = new Thumbnailer(this);
    connect(m_thumbnailer, &Thumbnailer::finished, this, &VideoLibrary::onThumbnailFinished);

    // The library folders; the Videos folder until the user picks others.
    const QSettings settings(m_dataDir + QStringLiteral("/settings.ini"), QSettings::IniFormat);
    const QStringList folders = settings.contains(kFoldersKey)
        ? settings.value(kFoldersKey).toStringList()
        : QStringList{QStandardPaths::writableLocation(QStandardPaths::MoviesLocation)};
    for (const QString &folder : folders) {
        VideoSource source;
        source.path = QDir::cleanPath(QDir::fromNativeSeparators(folder));
        m_sources.append(source);
    }

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

    // Recently added and Quick picks are about the library, not whatever drive is plugged in.
    m_recentlyAdded = new VideoModel(this, [this] {
        QList<int> rows;
        for (int i = 0; i < m_videos.size(); ++i)
            if (!m_videos[i].external)
                rows.append(i);
        std::stable_sort(rows.begin(), rows.end(), [this](int a, int b) {
            return m_videos[a].added > m_videos[b].added;
        });
        return rows.mid(0, kShelfSize);
    }, this);

    m_quickPicks = new VideoModel(this, [this] {
        // A shuffle that stays put when drives come and go, until reshuffled.
        QList<int> rows;
        for (int i = 0; i < m_videos.size(); ++i)
            if (!m_videos[i].external)
                rows.append(i);
        std::sort(rows.begin(), rows.end(), [this](int a, int b) {
            return qHash(m_videos[a].path, m_pickSeed) < qHash(m_videos[b].path, m_pickSeed);
        });
        return rows.mid(0, kQuickPicks);
    }, this);

    m_folders = new FolderModel(this, QString(), FolderModel::TopFolders, this);

    rescan();

    m_driveWatcher = new DriveWatcher(this);
    connect(m_driveWatcher, &DriveWatcher::drivesChanged, this, &VideoLibrary::onDrivesChanged);
    onDrivesChanged();
    // Drives already plugged in at startup aren't announced; ones plugged in later are.
    for (VideoSource &source : m_sources)
        source.announce = false;
}

VideoLibrary::~VideoLibrary()
{
    delete m_thumbnailer;   // waits for running grabs before we save
    m_thumbnailer = nullptr;
    save();
}

int VideoLibrary::libraryCount() const
{
    return std::count_if(m_videos.begin(), m_videos.end(), [](const VideoInfo &v) { return !v.external; });
}

QStringList VideoLibrary::libraryFolders() const
{
    return libraryRoots();
}

QStringList VideoLibrary::libraryRoots() const
{
    QStringList roots;
    for (const VideoSource &source : m_sources)
        if (!source.external)
            roots.append(source.path);
    return roots;
}

QString VideoLibrary::libraryHome() const
{
    const QStringList roots = libraryRoots();
    return roots.size() == 1 ? roots.first() : QString();
}

QVariantList VideoLibrary::drives() const
{
    const QLocale locale = QLocale::system();
    QVariantList list;
    for (const VideoSource &source : m_sources) {
        if (!source.external)
            continue;
        const auto size = [&](qint64 bytes) {
            return locale.formattedDataSize(bytes, 1, QLocale::DataSizeTraditionalFormat);
        };
        list.append(QVariantMap{
            {"path", source.path},
            {"name", source.name},
            {"kind", source.kind},
            {"count", m_scanned.value(source.path).size()},
            {"scanning", source.scanning},
            {"sizeText", size(source.bytesTotal)},
            {"freeText", size(source.bytesFree)},
            {"usedFraction", source.bytesTotal > 0 ? 1.0 - double(source.bytesFree) / source.bytesTotal : 0.0},
        });
    }
    return list;
}

void VideoLibrary::rescan()
{
    for (const VideoSource &source : std::as_const(m_sources))
        scanSource(source.path);
}

void VideoLibrary::scanSource(const QString &path)
{
    const int index = sourceIndex(path);
    if (index < 0)
        return;
    VideoSource &source = m_sources[index];
    source.scanning = true;
    if (m_pendingScans++ == 0)
        emit scanningChanged();
    if (source.external)
        emit drivesChanged();

    auto *watcher = new QFutureWatcher<QList<VideoInfo>>(this);
    connect(watcher, &QFutureWatcherBase::finished, this, [this, watcher, path] {
        watcher->deleteLater();
        onScanFinished(path, watcher->result());
    });
    watcher->setFuture(QtConcurrent::run(scanFolder, source.path, source.external, source.driveId));
}

void VideoLibrary::onScanFinished(const QString &path, QList<VideoInfo> videos)
{
    --m_pendingScans;
    const int index = sourceIndex(path);
    if (index >= 0) {   // not removed while it was being scanned
        const int found = videos.size();
        m_scanned.insert(path, std::move(videos));
        m_sources[index].scanning = false;
        rebuild();
        VideoSource &source = m_sources[index];
        if (source.external) {
            emit drivesChanged();
            if (std::exchange(source.announce, false))
                emit driveConnected(source.name, source.path, found);
        }
    }
    if (m_pendingScans == 0)
        emit scanningChanged();
}

void VideoLibrary::onDrivesChanged()
{
    const QList<DriveInfo> drives = m_driveWatcher->drives();

    bool removed = false;
    for (int i = m_sources.size() - 1; i >= 0; --i) {
        const VideoSource &source = m_sources[i];
        if (!source.external)
            continue;
        const bool present = std::any_of(drives.begin(), drives.end(), [&](const DriveInfo &d) {
            return d.rootPath == source.path && d.id == source.driveId;
        });
        if (present)
            continue;
        const QString path = source.path;
        emit driveRemoved(path);   // while its videos are still known, so progress can be saved
        m_sources.removeAt(i);
        m_scanned.remove(path);
        removed = true;
    }

    for (const DriveInfo &drive : drives) {
        const int index = sourceIndex(drive.rootPath);
        if (index >= 0) {
            if (m_sources[index].external) {
                m_sources[index].bytesTotal = drive.bytesTotal;
                m_sources[index].bytesFree = drive.bytesFree;
            }
            continue;
        }
        VideoSource source;
        source.path = drive.rootPath;
        source.name = drive.name;
        source.external = true;
        source.driveId = drive.id;
        source.kind = drive.kind;
        source.bytesTotal = drive.bytesTotal;
        source.bytesFree = drive.bytesFree;
        source.announce = true;
        m_sources.append(source);
        scanSource(source.path);
    }

    if (removed)
        rebuild();
    emit drivesChanged();
}

QString VideoLibrary::addLibraryFolder(const QString &input)
{
    const QString path = QDir::cleanPath(QDir::fromNativeSeparators(input));
    if (path.isEmpty() || !QFileInfo(path).isDir())
        return tr("That folder can't be opened.");

    for (const VideoSource &source : std::as_const(m_sources)) {
        if (source.external)
            continue;
        if (source.path == path)
            return tr("That folder is already in your library.");
        if (path.startsWith(withSlash(source.path)))
            return tr("That folder is already in your library, inside %1.").arg(QDir::toNativeSeparators(source.path));
    }

    // A parent folder takes the place of library folders inside it.
    int position = 0;
    for (int i = m_sources.size() - 1; i >= 0; --i) {
        if (!m_sources[i].external && m_sources[i].path.startsWith(withSlash(path))) {
            m_scanned.remove(m_sources[i].path);
            m_sources.removeAt(i);
        }
    }
    while (position < m_sources.size() && !m_sources[position].external)
        ++position;

    VideoSource source;
    source.path = path;
    m_sources.insert(position, source);
    saveLibraryFolders();
    emit libraryFoldersChanged();
    rebuild();
    scanSource(path);
    return {};
}

void VideoLibrary::removeLibraryFolder(const QString &path)
{
    const int index = sourceIndex(path);
    if (index < 0 || m_sources[index].external)
        return;
    m_sources.removeAt(index);
    m_scanned.remove(path);
    saveLibraryFolders();
    emit libraryFoldersChanged();
    rebuild();
}

void VideoLibrary::rebuild()
{
    m_videos.clear();
    m_byPath.clear();
    for (const VideoSource &source : std::as_const(m_sources)) {
        for (const VideoInfo &found : m_scanned.value(source.path)) {
            // A library folder on a drive is listed under the library, not the drive.
            if (m_byPath.contains(found.path))
                continue;
            VideoInfo v = found;
            const QJsonObject meta = m_metadata.value(cacheKey(v)).toObject();
            v.duration = meta.value(QStringLiteral("duration")).toDouble();
            v.hasThumbnail = meta.value(QStringLiteral("thumb")).toBool() && QFile::exists(thumbnailFile(v));

            const QJsonObject seen = m_history.value(v.key).toObject();
            if (!seen.isEmpty()) {
                v.position = seen.value(QStringLiteral("position")).toDouble();
                v.watchedAt = seen.value(QStringLiteral("watchedAt")).toInteger();
                v.finished = seen.value(QStringLiteral("finished")).toBool();
                if (v.duration <= 0)
                    v.duration = seen.value(QStringLiteral("duration")).toDouble();
            }
            m_byPath.insert(v.path, m_videos.size());
            m_videos.append(v);
        }
    }

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
    // Gone, e.g. the drive was unplugged mid-grab: don't remember it as unreadable.
    if (index < 0 || (!ok && !QFileInfo::exists(path)))
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
    m_pickSeed = QRandomGenerator::global()->generate();
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
    auto *model = new FolderModel(this, folder, FolderModel::Children);
    m_folderModels.removeAll(nullptr);
    m_folderModels.append(model);
    return model;   // owned by QML (no parent)
}

QList<int> VideoLibrary::indexesIn(const QString &folder) const
{
    QList<int> rows;
    for (int i = 0; i < m_videos.size(); ++i)
        if (m_videos[i].dir == folder)
            rows.append(i);
    return rows;
}

QString VideoLibrary::folderName(const QString &folder) const
{
    if (folder.isEmpty())
        return tr("Library");
    if (sourceIndex(folder) >= 0)
        return sourceName(folder);
    return QDir(folder).dirName();
}

int VideoLibrary::videoCountIn(const QString &folder) const
{
    if (folder.isEmpty())
        return libraryCount();
    const QString prefix = withSlash(folder);
    return std::count_if(m_videos.begin(), m_videos.end(), [&](const VideoInfo &v) {
        return v.dir == folder || v.dir.startsWith(prefix);
    });
}

QString VideoLibrary::locationOf(const VideoInfo &video) const
{
    const QString relative = video.dir == video.root ? QString() : video.dir.mid(withSlash(video.root).size());
    return folderLabel(relative, sourceName(video.root));
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
    const QDateTime added = index >= 0 ? m_videos[index].added : file.birthTime();

    return {
        {"title", titleOf(path)},
        {"inLibrary", index >= 0},
        {"folder", file.absolutePath()},
        {"folderLabel", index >= 0 ? locationOf(m_videos[index]) : QDir::toNativeSeparators(file.absolutePath())},
        {"fileName", file.fileName()},
        {"sizeText", locale.formattedDataSize(file.size(), 1, QLocale::DataSizeTraditionalFormat)},
        {"addedText", added.isValid() ? locale.toString(added.date(), QStringLiteral("d MMM yyyy")) : QString()},
    };
}

QString VideoLibrary::folderOf(const QString &path) const
{
    const int index = m_byPath.value(path, -1);
    return index >= 0 ? m_videos[index].dir : QString();
}

QString VideoLibrary::nextInFolder(const QString &path) const
{
    const int index = m_byPath.value(path, -1);
    // Each source is in natural path order, so the next one in the same folder follows directly.
    if (index < 0 || index + 1 >= m_videos.size() || m_videos[index + 1].dir != m_videos[index].dir)
        return {};
    return m_videos[index + 1].path;
}

VideoModel *VideoLibrary::suggestionsFor(const QString &path)
{
    auto *model = new VideoModel(this, [this, path] {
        const int current = m_byPath.value(path, -1);
        const QString folder = folderOf(path);
        // A drive's videos only suggest others on the same drive; library videos only library ones.
        const auto belongs = [&](const VideoInfo &v) {
            if (current < 0)
                return !v.external;
            const VideoInfo &playing = m_videos[current];
            return playing.external ? v.root == playing.root : !v.external;
        };
        QList<int> others;
        QList<int> sameFolder;
        for (int i = 0; i < m_videos.size(); ++i) {
            if (m_videos[i].path == path || !belongs(m_videos[i]))
                continue;
            (m_videos[i].dir == folder ? sameFolder : others).append(i);
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

QString VideoLibrary::keyFor(const QString &path) const
{
    const int index = m_byPath.value(path, -1);
    return index >= 0 ? m_videos[index].key : path;
}

double VideoLibrary::resumePosition(const QString &path) const
{
    const QJsonObject seen = m_history.value(keyFor(path)).toObject();
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
    m_history.insert(keyFor(path), QJsonObject{
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

int VideoLibrary::sourceIndex(const QString &path) const
{
    for (int i = 0; i < m_sources.size(); ++i)
        if (m_sources[i].path == path)
            return i;
    return -1;
}

QString VideoLibrary::sourceName(const QString &path) const
{
    const int index = sourceIndex(path);
    if (index >= 0 && !m_sources[index].name.isEmpty())
        return m_sources[index].name;
    const QString name = QDir(path).dirName();
    return name.isEmpty() ? QDir::toNativeSeparators(path) : name;   // a whole drive, "D:\"
}

QString VideoLibrary::cacheKey(const VideoInfo &video) const
{
    // Changes when the file is replaced, so stale thumbnails aren't reused.
    const QByteArray id = video.key.toUtf8() + '|' + QByteArray::number(video.size) + '|'
        + QByteArray::number(video.modified.toMSecsSinceEpoch());
    return QString::fromLatin1(QCryptographicHash::hash(id, QCryptographicHash::Sha1).toHex());
}

QString VideoLibrary::thumbnailFile(const VideoInfo &video) const
{
    return m_dataDir + QStringLiteral("/thumbnails/") + cacheKey(video) + QStringLiteral(".jpg");
}

void VideoLibrary::saveLibraryFolders()
{
    QSettings settings(m_dataDir + QStringLiteral("/settings.ini"), QSettings::IniFormat);
    settings.setValue(kFoldersKey, libraryRoots());
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
