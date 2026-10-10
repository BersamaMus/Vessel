#include "VideoLibrary.h"

#include "DriveWatcher.h"
#include "Thumbnailer.h"
#include "VideoModels.h"

#include <QCollator>
#include <QCryptographicHash>
#include <QDir>
#include <QDirIterator>
#include <QElapsedTimer>
#include <QLoggingCategory>
#include <QFile>
#include <QFileInfo>
#include <QFutureWatcher>
#include <QJsonDocument>
#include <QLocale>
#include <QRandomGenerator>
#include <QRegularExpression>
#include <QSaveFile>
#include <QSet>
#include <QSettings>
#include <QStandardPaths>
#include <QtConcurrent/QtConcurrentRun>

#include <algorithm>
#include <numeric>

#ifdef Q_OS_WIN
#include <shlobj.h>
#else
#include <QDesktopServices>
#endif

// Timings of scans and library updates: QT_LOGGING_RULES="bvp.perf.debug=true".
Q_LOGGING_CATEGORY(lcPerf, "bvp.perf", QtWarningMsg)

namespace {

constexpr int kShelfSize = 20;
constexpr int kQuickPicks = 16;

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

bool isVideoFile(const QFileInfo &file)
{
    static const QSet<QString> extensions = {
        "mp4", "m4v", "mkv", "mov", "webm", "avi", "wmv", "flv", "ts", "m2ts",
        "mts", "mpg", "mpeg", "3gp", "ogv", "divx", "asf", "rmvb",
    };
    // Empty files are unfinished downloads; they can't be played.
    return extensions.contains(file.suffix().toLower()) && file.size() > 0;
}

// "Spider-Man Homecoming (2017)" -> "spidermanhomecoming2017", so names that only
// differ in case, spacing or punctuation compare equal.
QString normalizedName(const QString &name)
{
    QString result;
    for (const QChar c : name)
        if (c.isLetterOrNumber())
            result += c.toLower();
    return result;
}

// Season and episode from "S01E02", "1x02" or "Season 1 Episode 2"; {-1, -1} if none.
std::pair<int, int> episodeOf(const QString &name)
{
    static const QRegularExpression patterns[] = {
        QRegularExpression(QStringLiteral("s(\\d{1,2})[ ._-]*e(\\d{1,3})"), QRegularExpression::CaseInsensitiveOption),
        QRegularExpression(QStringLiteral("season\\D{0,3}(\\d{1,2})\\D{0,3}episode\\D{0,3}(\\d{1,3})"), QRegularExpression::CaseInsensitiveOption),
        // Not "1920x1080": no digits either side.
        QRegularExpression(QStringLiteral("(?<!\\d)(\\d{1,2})x(\\d{2})(?!\\d)")),
    };
    for (const QRegularExpression &pattern : patterns) {
        const QRegularExpressionMatch match = pattern.match(name);
        if (match.hasMatch())
            return {match.captured(1).toInt(), match.captured(2).toInt()};
    }
    return {-1, -1};
}

// The year in a name: "Speed (1994)", "Ne Zha 2019 1080p" or "Capture 2025-04-23"; 0 if
// none. Not a resolution ("1920x1080", "1080p"), part of an ID ("22ed0772-2042-44b9-…")
// or in the future. With several, the last one: "Blade Runner 2049 (2017)" is from 2017.
int yearIn(const QString &name)
{
    static const QRegularExpression guid(QStringLiteral("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"),
                                         QRegularExpression::CaseInsensitiveOption);
    static const QRegularExpression pattern(QStringLiteral("(?<![\\dx])(19[2-9]\\d|20[0-4]\\d)(?![\\dpx])"),
                                            QRegularExpression::CaseInsensitiveOption);
    const int latest = QDate::currentDate().year() + 1;
    int year = 0;
    for (const QRegularExpressionMatch &match : pattern.globalMatch(QString(name).remove(guid))) {
        const int found = match.captured(1).toInt();
        if (found <= latest)
            year = found;
    }
    return year;
}

// Names a video's thumbnail and cached metadata. Changes when the file is replaced
// (another size or modified time), so a stale thumbnail isn't reused.
QString cacheKeyOf(const QString &key, qint64 size, const QDateTime &modified)
{
    const QByteArray id = key.toUtf8() + '|' + QByteArray::number(size) + '|'
        + QByteArray::number(modified.toMSecsSinceEpoch());
    return QString::fromLatin1(QCryptographicHash::hash(id, QCryptographicHash::Sha1).toHex());
}

// Every video under `root`, in natural path order, with everything that can be worked
// out from the file system alone (on the scanning thread, keeping the UI free).
// `sourceName` is what the library folder or drive is called, for search.
QList<VideoInfo> scanFolder(const QString &root, bool external, const QString &driveId,
                            const QString &sourceName, std::shared_ptr<std::atomic_int> found)
{
    QList<VideoInfo> videos;
    const QString prefix = withSlash(root);
    // One copy of each folder's path (and its search text), shared by its videos.
    struct Folder
    {
        QString path;
        QString placeKey;
    };
    QHash<QString, Folder> folders;
    // Hidden and system folders (the recycle bin, System Volume Information) are skipped.
    QDirIterator it(root, QDir::Files, QDirIterator::Subdirectories);
    while (it.hasNext()) {
        const QFileInfo file = it.nextFileInfo();
        if (!isVideoFile(file))
            continue;

        VideoInfo video;
        video.path = file.absoluteFilePath();
        video.key = external ? QStringLiteral("drive:%1/%2").arg(driveId, video.path.mid(prefix.size()))
                             : video.path;
        const QString baseName = file.completeBaseName();
        video.title = prettyTitle(baseName);
        video.year = yearIn(baseName);
        const QString dir = file.absolutePath();
        auto folder = folders.find(dir);
        if (folder == folders.end()) {
            const QString inside = dir == root ? QString() : dir.mid(prefix.size());
            folder = folders.insert(dir, {dir, searchKey(inside + QLatin1Char(' ') + sourceName)});
        }
        video.dir = folder->path;
        video.placeKey = folder->placeKey;
        video.root = root;
        video.type = file.suffix().toLower();
        video.external = external;
        video.size = file.size();
        video.modified = file.lastModified();
        // Creation time is when the file was copied here, so it's when it was "added".
        const QDateTime born = file.birthTime();
        video.added = born.isValid() ? born : video.modified;
        video.cacheKey = cacheKeyOf(video.key, video.size, video.modified);
        video.titleKey = searchKey(video.title);
        videos.append(std::move(video));
        ++*found;
    }

    // Sort keys once per path, rather than a locale comparison for every pair.
    QCollator collator;
    collator.setNumericMode(true);
    collator.setCaseSensitivity(Qt::CaseInsensitive);
    std::vector<std::pair<QCollatorSortKey, qsizetype>> order;
    order.reserve(videos.size());
    for (qsizetype i = 0; i < videos.size(); ++i)
        order.emplace_back(collator.sortKey(videos[i].path), i);
    std::sort(order.begin(), order.end(), [](const auto &a, const auto &b) { return a.first.compare(b.first) < 0; });
    QList<VideoInfo> sorted;
    sorted.reserve(videos.size());
    for (const auto &[key, index] : order)
        sorted.append(std::move(videos[index]));
    return sorted;
}

bool isFinished(double position, double duration)
{
    // 95% covers a film's end credits; short clips just need to reach the end.
    return duration > 0 && (position >= duration * 0.95 || duration - position < 5);
}

} // namespace

VideoLibrary::VideoLibrary(QObject *parent)
    : QObject(parent)
    // BVP_DATA_DIR points a test copy at its own settings, history and cache.
    , m_dataDir(qEnvironmentVariable("BVP_DATA_DIR",
                                     QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation)))
    , m_pickSeed(QRandomGenerator::global()->generate())
{
    QDir().mkpath(m_dataDir + QStringLiteral("/thumbnails"));
    loadState();

    m_saveTimer.setSingleShot(true);
    m_saveTimer.setInterval(2000);
    connect(&m_saveTimer, &QTimer::timeout, this, &VideoLibrary::save);

    m_progressTimer.setSingleShot(true);
    m_progressTimer.setInterval(250);
    connect(&m_progressTimer, &QTimer::timeout, this, &VideoLibrary::progressChanged);

    m_scanProgressTimer.setInterval(400);
    connect(&m_scanProgressTimer, &QTimer::timeout, this, [this] {
        emit progressChanged();
        if (std::any_of(m_sources.begin(), m_sources.end(), [](const VideoSource &s) { return s.external && s.scanning; }))
            emit drivesChanged();
    });

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

    // Home's shelves are about the library, not whatever drive is plugged in.
    m_continueWatching = new VideoModel(this, [this] { return startedIn(QString()); }, this);

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

    scanAll();

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
            {"found", source.found->load()},
            {"sizeText", size(source.bytesTotal)},
            {"freeText", size(source.bytesFree)},
            {"usedFraction", source.bytesTotal > 0 ? 1.0 - double(source.bytesFree) / source.bytesTotal : 0.0},
        });
    }
    return list;
}

void VideoLibrary::rescan()
{
    m_beforeRescan.clear();
    for (const VideoInfo &v : std::as_const(m_videos))
        if (!v.external)
            m_beforeRescan.insert(v.path);
    m_reportRescan = true;
    scanAll();
    if (m_pendingScans == 0) {   // nothing to scan
        m_reportRescan = false;
        emit rescanFinished(0, 0, int(m_beforeRescan.size()));
    }
}

void VideoLibrary::scanAll()
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
    *source.found = 0;
    if (m_pendingScans++ == 0) {
        emit scanningChanged();
        m_scanProgressTimer.start();
    }
    if (source.external)
        emit drivesChanged();

    auto *watcher = new QFutureWatcher<QList<VideoInfo>>(this);
    QElapsedTimer timer;
    timer.start();
    connect(watcher, &QFutureWatcherBase::finished, this, [this, watcher, path, timer] {
        watcher->deleteLater();
        qCDebug(lcPerf) << "scanned" << path << watcher->result().size() << "videos in" << timer.elapsed() << "ms";
        onScanFinished(path, watcher->result());
    });
    watcher->setFuture(QtConcurrent::run(scanFolder, source.path, source.external, source.driveId,
                                         sourceName(source.path), source.found));
}

int VideoLibrary::videosFound() const
{
    int found = 0;
    for (const VideoSource &source : m_sources)
        if (source.scanning)
            found += *source.found;
    return found;
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
        } else if (std::exchange(source.announce, false)) {
            emit libraryFolderAdded(folderName(source.path), source.path, found);
        }
    }
    if (m_pendingScans == 0) {
        m_scanProgressTimer.stop();
        emit scanningChanged();
        emit progressChanged();
        if (std::exchange(m_reportRescan, false)) {
            QSet<QString> now;
            for (const VideoInfo &v : std::as_const(m_videos))
                if (!v.external)
                    now.insert(v.path);
            const int added = int((now - m_beforeRescan).size());
            const int removed = int((m_beforeRescan - now).size());
            m_beforeRescan.clear();
            emit rescanFinished(int(now.size()), added, removed);
        }
    }
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
        emit driveRemoved(path, source.name);   // while its videos are still known, so progress can be saved
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

QString VideoLibrary::addLibraryFolder(const QString &input, int at)
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
    if (at >= 0 && at < position)
        position = at;

    VideoSource source;
    source.path = path;
    source.announce = true;
    m_sources.insert(position, source);
    saveLibraryFolders();
    emit libraryFoldersChanged();
    rebuild();
    scanSource(path);
    return {};
}

int VideoLibrary::removeLibraryFolder(const QString &path)
{
    const int index = sourceIndex(path);
    if (index < 0 || m_sources[index].external)
        return -1;
    m_sources.removeAt(index);
    m_scanned.remove(path);
    saveLibraryFolders();
    emit libraryFoldersChanged();
    rebuild();
    return index;   // library folders come first, so this is also their position
}

void VideoLibrary::rebuild()
{
    QElapsedTimer timer;
    timer.start();
    m_videos.clear();
    m_byPath.clear();
    for (const VideoSource &source : std::as_const(m_sources)) {
        for (const VideoInfo &found : m_scanned.value(source.path)) {
            // A library folder on a drive is listed under the library, not the drive.
            if (m_byPath.contains(found.path))
                continue;
            VideoInfo v = found;
            const VideoMeta meta = m_metadata.value(v.cacheKey);
            v.duration = meta.duration;
            v.width = meta.width;
            v.height = meta.height;
            if (v.year == 0)
                v.year = meta.year;
            v.hasThumbnail = meta.thumb && m_thumbFiles.contains(v.cacheKey);

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

    const qint64 merged = timer.elapsed();
    emit libraryReset();
    refreshModels();
    const qint64 refreshed = timer.elapsed();
    queueThumbnails();
    qCDebug(lcPerf) << "rebuild:" << m_videos.size() << "videos; merge" << merged << "ms, models"
                    << refreshed - merged << "ms, thumbnail queue" << timer.elapsed() - refreshed << "ms";
}

void VideoLibrary::queueThumbnails()
{
    m_thumbnailer->clear();

    // The folder being looked at first, then what's on the Home page, then everything else.
    // In the folder, what's on screen first: its own videos, then a cover for each
    // sub-folder (their first video), then the rest of the sub-folders.
    QList<int> order;
    if (m_hasPriority) {
        const QString prefix = withSlash(m_priorityFolder);
        QList<int> covers;
        QList<int> deeper;
        QSet<QString> covered;
        for (int i = 0; i < m_videos.size(); ++i) {
            const VideoInfo &v = m_videos[i];
            if (m_priorityFolder.isEmpty() ? v.external : !(v.dir == m_priorityFolder || v.dir.startsWith(prefix)))
                continue;
            if (v.dir == m_priorityFolder) {
                order.append(i);
                continue;
            }
            // The sub-folder it's under: a library folder for "", otherwise the next folder down.
            const QString sub = m_priorityFolder.isEmpty() ? v.root : v.dir.mid(prefix.size()).section(QLatin1Char('/'), 0, 0);
            (covered.contains(sub) ? deeper : covers).append(i);
            covered.insert(sub);
        }
        order += covers;
        order += deeper;
    }
    for (VideoModel *model : {m_continueWatching, m_recentlyAdded, m_quickPicks})
        for (int row = 0; row < model->rowCount(); ++row)
            order.append(m_byPath.value(model->pathAt(row)));
    for (int i = 0; i < m_videos.size(); ++i)
        order.append(i);

    QList<bool> queued(m_videos.size(), false);
    QSet<QString> pending;
    for (int i : order) {
        if (std::exchange(queued[i], true))
            continue;
        const VideoInfo &v = m_videos[i];
        const VideoMeta meta = m_metadata.value(v.cacheKey);
        // Looked at again when the cache is from before sizes were kept.
        if (!meta.probed || (meta.thumb && !v.hasThumbnail)) {
            m_thumbnailer->request(v.path, thumbnailFile(v));
            pending.insert(v.path);
        }
    }

    // A new batch starts when the last one was finished; videos that have gone (an
    // unplugged drive) drop out of it.
    if (m_thumbPending.isEmpty())
        m_thumbBatch.clear();
    m_thumbBatch.removeIf([this](const QString &path) { return !m_byPath.contains(path); });
    m_thumbPending = pending;
    m_thumbBatch.unite(pending);
    if (m_thumbPending.isEmpty())
        m_thumbBatch.clear();
    emit progressChanged();
    emit thumbnailsQueued();
}

void VideoLibrary::prioritize(const QString &folder)
{
    if (m_hasPriority && m_priorityFolder == folder)
        return;
    m_priorityFolder = folder;
    m_hasPriority = true;
    if (!m_thumbPending.isEmpty())
        queueThumbnails();
}

QVariantMap VideoLibrary::thumbnailProgressIn(const QString &folder) const
{
    const QString prefix = withSlash(folder);
    const auto inside = [&](const QString &path) {
        return folder.isEmpty() ? !m_videos.value(m_byPath.value(path, -1)).external : path.startsWith(prefix);
    };
    const int total = int(std::count_if(m_thumbBatch.begin(), m_thumbBatch.end(), inside));
    const int left = int(std::count_if(m_thumbPending.begin(), m_thumbPending.end(), inside));
    return {{"done", total - left}, {"total", left > 0 ? total : 0}};
}

void VideoLibrary::onThumbnailFinished(const QString &path, bool ok, double duration, int width, int height, int year)
{
    if (m_thumbPending.remove(path)) {
        if (m_thumbPending.isEmpty()) {
            m_thumbBatch.clear();
            emit progressChanged();
        } else if (!m_progressTimer.isActive()) {
            m_progressTimer.start();   // a few updates a second, not one per thumbnail
        }
    }

    const int index = m_byPath.value(path, -1);
    // Gone, e.g. the drive was unplugged mid-grab: don't remember it as unreadable.
    if (index < 0 || (!ok && !QFileInfo::exists(path)))
        return;
    VideoInfo &v = m_videos[index];
    if (duration > 0)
        v.duration = duration;
    v.width = width;
    v.height = height;
    if (v.year == 0)
        v.year = year;
    v.hasThumbnail = ok;
    m_metadata.insert(v.cacheKey, {v.duration, width, height, year, ok, true});
    if (ok)
        m_thumbFiles.insert(v.cacheKey);
    m_metadataChanged = true;
    scheduleSave();
    emit videoChanged(index);
}

void VideoLibrary::refreshContinueWatching()
{
    m_continueWatching->refresh();
    for (const auto &model : std::as_const(m_driveContinueModels))
        if (model)
            model->refresh();
}

void VideoLibrary::refreshModels()
{
    refreshContinueWatching();
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

QList<int> VideoLibrary::startedIn(const QString &drivePath) const
{
    QList<int> rows;
    for (int i = 0; i < m_videos.size(); ++i) {
        const VideoInfo &v = m_videos[i];
        const bool here = drivePath.isEmpty() ? !v.external : v.external && v.root == drivePath;
        if (here && v.watchedAt > 0 && !v.finished && v.position >= kMinProgress)
            rows.append(i);
    }
    std::sort(rows.begin(), rows.end(), [this](int a, int b) {
        return m_videos[a].watchedAt > m_videos[b].watchedAt;
    });
    return rows.mid(0, kShelfSize);
}

VideoModel *VideoLibrary::continueWatchingOn(const QString &drivePath)
{
    auto *model = new VideoModel(this, [this, drivePath] { return startedIn(drivePath); });
    m_driveContinueModels.removeAll(nullptr);
    m_driveContinueModels.append(model);
    return model;   // owned by QML (no parent)
}

bool VideoLibrary::isDrive(const QString &folder) const
{
    const int index = sourceIndex(folder);
    return index >= 0 && m_sources[index].external;
}

QString VideoLibrary::driveOf(const QString &folder) const
{
    if (folder.isEmpty())
        return {};
    for (const VideoSource &source : m_sources)
        if (source.external && (folder == source.path || folder.startsWith(withSlash(source.path))))
            return source.path;
    return {};
}

FolderModel *VideoLibrary::foldersIn(const QString &folder)
{
    auto *model = new FolderModel(this, folder, FolderModel::Children);
    m_folderModels.removeAll(nullptr);
    m_folderModels.append(model);
    return model;   // owned by QML (no parent)
}

void VideoLibrary::track(VideoModel *model)
{
    m_folderVideoModels.removeAll(nullptr);
    m_folderVideoModels.append(model);
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
        {"external", index >= 0 && m_videos[index].external},
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

QUrl VideoLibrary::thumbnailFor(const QString &path) const
{
    const int index = m_byPath.value(path, -1);
    return index >= 0 ? thumbnailUrl(index) : QUrl();
}

QString VideoLibrary::previousInFolder(const QString &path) const
{
    const int index = m_byPath.value(path, -1);
    if (index <= 0 || m_videos[index - 1].dir != m_videos[index].dir)
        return {};
    return m_videos[index - 1].path;
}

QStringList VideoLibrary::subtitlesFor(const QString &path) const
{
    static const QStringList patterns{QStringLiteral("*.srt"), QStringLiteral("*.ass"),
                                      QStringLiteral("*.ssa"), QStringLiteral("*.vtt")};
    const QFileInfo video(path);
    const QDir dir = video.absoluteDir();
    const QString baseName = video.completeBaseName();

    // Next to the video, and in Subs / Subtitles folders (including Subs/<video name>/,
    // as some releases do). Files in a folder named after the video are its own.
    QFileInfoList candidates = dir.entryInfoList(patterns, QDir::Files);
    QFileInfoList ownFolder;
    for (const QFileInfo &folder : dir.entryInfoList(QDir::Dirs | QDir::NoDotAndDotDot)) {
        const QString name = folder.fileName().toLower();
        if (name != QLatin1String("subs") && name != QLatin1String("sub")
            && name != QLatin1String("subtitles") && name != QLatin1String("subtitle"))
            continue;
        const QDir subs(folder.absoluteFilePath());
        candidates += subs.entryInfoList(patterns, QDir::Files);
        ownFolder += QDir(subs.filePath(baseName)).entryInfoList(patterns, QDir::Files);
    }

    // In a folder with just this one video (a movie's own folder), every subtitle is its.
    const QFileInfoList siblings = dir.entryInfoList(QDir::Files);
    const bool onlyVideo = std::count_if(siblings.begin(), siblings.end(), isVideoFile) <= 1;

    const QString name = normalizedName(baseName);
    const std::pair<int, int> episode = episodeOf(baseName);
    QStringList exact;
    QStringList likely;
    for (const QFileInfo &subtitle : std::as_const(candidates)) {
        const QString subtitleName = normalizedName(subtitle.completeBaseName());
        const QString rest = subtitleName.startsWith(name) ? subtitleName.mid(name.size()) : QString();
        const bool languageTag = subtitleName.startsWith(name) && rest.size() <= 16
            && std::all_of(rest.begin(), rest.end(), [](QChar c) { return c.isLetter(); });

        if (subtitleName == name)
            exact.append(subtitle.absoluteFilePath());
        else if (languageTag   // "Movie.en", but not "Movie 2"
                 || (episode.first >= 0 && episodeOf(subtitle.completeBaseName()) == episode)
                 || onlyVideo)
            likely.append(subtitle.absoluteFilePath());
    }
    for (const QFileInfo &subtitle : std::as_const(ownFolder))
        likely.append(subtitle.absoluteFilePath());
    return exact + likely;
}

QVariant VideoLibrary::subtitleChoice(const QString &path) const
{
    const QJsonObject entry = m_history.value(keyFor(path)).toObject();
    if (!entry.contains(QStringLiteral("subtitle")))
        return {};
    const QString subtitle = entry.value(QStringLiteral("subtitle")).toString();
    // Ask again if the chosen file has been moved or deleted.
    if (!subtitle.isEmpty() && !QFileInfo::exists(subtitle))
        return {};
    return subtitle;
}

void VideoLibrary::setSubtitleChoice(const QString &path, const QString &subtitlePath)
{
    if (path.isEmpty())
        return;
    const QString key = keyFor(path);
    QJsonObject entry = m_history.value(key).toObject();
    entry.insert(QStringLiteral("subtitle"), subtitlePath);
    m_history.insert(key, entry);
    scheduleSave();
}

void VideoLibrary::showInFileManager(const QString &path) const
{
#ifdef Q_OS_WIN
    // Explorer's own "open folder and select" call; copes with any characters in the path.
    const std::wstring native = QDir::toNativeSeparators(path).toStdWString();
    const HRESULT init = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED | COINIT_DISABLE_OLE1DDE);
    if (PIDLIST_ABSOLUTE item = ILCreateFromPathW(native.c_str())) {
        SHOpenFolderAndSelectItems(item, 0, nullptr, 0);
        ILFree(item);
    }
    if (SUCCEEDED(init))
        CoUninitialize();
#else
    QDesktopServices::openUrl(QUrl::fromLocalFile(QFileInfo(path).absolutePath()));
#endif
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
        // The video's own folder is already listed above, as the playlist.
        QList<int> others;
        for (int i = 0; i < m_videos.size(); ++i) {
            if (m_videos[i].dir != folder && belongs(m_videos[i]))
                others.append(i);
        }
        std::shuffle(others.begin(), others.end(), *QRandomGenerator::global());
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
    // Updated in place, keeping anything else remembered about the video (its subtitle choice).
    const QString key = keyFor(path);
    QJsonObject entry = m_history.value(key).toObject();
    entry.insert(QStringLiteral("position"), position);
    entry.insert(QStringLiteral("duration"), duration);
    entry.insert(QStringLiteral("watchedAt"), now);
    entry.insert(QStringLiteral("finished"), finished);
    m_history.insert(key, entry);
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
    refreshContinueWatching();
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

QString VideoLibrary::thumbnailFile(const VideoInfo &video) const
{
    return m_dataDir + QStringLiteral("/thumbnails/") + video.cacheKey + QStringLiteral(".jpg");
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
    const QJsonObject metadata = readJson(QStringLiteral("metadata.json"));
    m_metadata.reserve(metadata.size());
    for (auto it = metadata.begin(); it != metadata.end(); ++it) {
        const QJsonObject entry = it.value().toObject();
        m_metadata.insert(it.key(), {entry.value(QLatin1String("duration")).toDouble(),
                                     entry.value(QLatin1String("w")).toInt(),
                                     entry.value(QLatin1String("h")).toInt(),
                                     entry.value(QLatin1String("year")).toInt(),
                                     entry.value(QLatin1String("thumb")).toBool(),
                                     entry.contains(QLatin1String("w"))});
    }
    m_history = readJson(QStringLiteral("history.json"));

    // Which thumbnails exist, read once (rather than a file check per video per rebuild).
    const QStringList files = QDir(m_dataDir + QStringLiteral("/thumbnails")).entryList({QStringLiteral("*.jpg")}, QDir::Files);
    m_thumbFiles.reserve(files.size());
    for (const QString &file : files)
        m_thumbFiles.insert(file.chopped(4));
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
    if (std::exchange(m_metadataChanged, false)) {
        QJsonObject metadata;
        for (auto it = m_metadata.cbegin(); it != m_metadata.cend(); ++it) {
            const VideoMeta &m = it.value();
            QJsonObject entry{{"duration", m.duration}, {"thumb", m.thumb}};
            if (m.probed) {
                entry.insert(QLatin1String("w"), m.width);
                entry.insert(QLatin1String("h"), m.height);
                entry.insert(QLatin1String("year"), m.year);
            }
            metadata.insert(it.key(), entry);
        }
        writeJson(QStringLiteral("metadata.json"), metadata);
    }
    writeJson(QStringLiteral("history.json"), m_history);
}
