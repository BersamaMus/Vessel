#include "VideoLibrary.h"

#include "DriveEject.h"
#include "DriveWatcher.h"
#include "FolderWatcher.h"
#include "Thumbnailer.h"
#include "VideoFormats.h"
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
#include <QJsonArray>
#include <QJsonDocument>
#include <QLocale>
#include <QPromise>
#include <QRandomGenerator>
#include <QRegularExpression>
#include <QSaveFile>
#include <QSet>
#include <QSettings>
#include <QStandardPaths>
#include <QUuid>
#include <QtConcurrent/QtConcurrentRun>

#include <algorithm>
#include <numeric>

#ifdef Q_OS_WIN
#include <shlobj.h>
#else
#include <QDesktopServices>
#endif

// Timings of scans and library updates: QT_LOGGING_RULES="vessel.perf.debug=true".
Q_LOGGING_CATEGORY(lcPerf, "vessel.perf", QtWarningMsg)

namespace {

constexpr int kShelfSize = 20;
constexpr int kQuickPicks = 16;

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

// "Film.2018.mkv" -> "mkv", as QFileInfo::suffix() has it (after the last dot).
QString suffixOf(const QString &name)
{
    const qsizetype dot = name.lastIndexOf(QLatin1Char('.'));
    return dot < 0 ? QString() : name.mid(dot + 1);
}

// "Film.2018.mkv" -> "Film.2018", as QFileInfo::completeBaseName() has it.
QString baseNameOf(const QString &name)
{
    const qsizetype dot = name.lastIndexOf(QLatin1Char('.'));
    return dot < 0 ? name : name.left(dot);
}

// A file a scan listed (its path, name and size) that's a video.
bool isVideoEntry(const QString &path, const QString &name, qint64 size)
{
    // Empty files are unfinished downloads; they can't be played.
    return VideoFormats::isVideoSuffix(suffixOf(name)) && size > 0 && VideoFormats::looksLikeVideo(path);
}

bool isVideoFile(const QFileInfo &file)
{
    return isVideoEntry(file.filePath(), file.fileName(), file.size());
}

// A video editor's own renders, not anyone's videos: Premiere Pro's previews (hundreds of
// "Rendered - <id>.mpeg" files per project).
bool isEditorCache(const QString &folder)
{
    return folder.contains(QLatin1String("/Adobe Premiere Pro Video Previews"), Qt::CaseInsensitive);
}

// Folders, anywhere, that are never anyone's videos: Windows' own (the recycle bin, where
// deleted videos would come back, and its other "$" folders; System Volume Information),
// apps' data and caches (a user's AppData, ".cache" and other dot-folders), code's
// packages, and games' files.
bool isSkippedFolder(const QString &name)
{
    static const QSet<QString> names{
        QStringLiteral("system volume information"), QStringLiteral("appdata"), QStringLiteral("node_modules"),
        QStringLiteral("bower_components"), QStringLiteral("site-packages"), QStringLiteral("__pycache__"),
        QStringLiteral("steamapps"), QStringLiteral("epic games"), QStringLiteral("xboxgames"),
        QStringLiteral("windowsapps"), QStringLiteral("wpsystem"),
    };
    return name.startsWith(QLatin1Char('$')) || name.startsWith(QLatin1Char('.')) || names.contains(name.toLower());
}

// Folders at the top of a drive that are Windows' or apps' own.
bool isSystemFolder(const QString &name)
{
    static const QSet<QString> names{
        QStringLiteral("windows"), QStringLiteral("windows.old"), QStringLiteral("program files"),
        QStringLiteral("program files (x86)"), QStringLiteral("programdata"), QStringLiteral("recovery"),
        QStringLiteral("perflogs"), QStringLiteral("msocache"), QStringLiteral("intel"), QStringLiteral("amd"),
        QStringLiteral("nvidia"), QStringLiteral("inetpub"), QStringLiteral("onedrivetemp"),
        QStringLiteral("config.msi"), QStringLiteral("drivers"),
    };
    return names.contains(name.toLower());
}

// A file (or folder) that says its folder is a code project: its videos, and those of the
// folders inside it, are the project's (test clips, build output), not anyone's to watch.
bool isProjectFile(const QString &name)
{
    static const QSet<QString> names{
        QStringLiteral(".git"), QStringLiteral(".hg"), QStringLiteral(".svn"), QStringLiteral("package.json"),
        QStringLiteral("pubspec.yaml"), QStringLiteral("build.gradle"), QStringLiteral("build.gradle.kts"),
        QStringLiteral("settings.gradle"), QStringLiteral("cmakelists.txt"), QStringLiteral("cargo.toml"),
        QStringLiteral("go.mod"), QStringLiteral("pom.xml"), QStringLiteral("composer.json"),
        QStringLiteral("gemfile"), QStringLiteral("pyproject.toml"), QStringLiteral("setup.py"),
        QStringLiteral("requirements.txt"), QStringLiteral("makefile"),
    };
    const QString lower = name.toLower();
    return names.contains(lower) || lower.endsWith(QLatin1String(".sln")) || lower.endsWith(QLatin1String(".csproj"))
        || lower.endsWith(QLatin1String(".xcodeproj"));
}

// "C:/" or "D:/": a whole drive.
bool isDriveRoot(const QString &folder)
{
    return folder.size() == 3 && folder.endsWith(QLatin1String(":/"));
}

// Whether a path is somewhere scans don't look: in a skipped folder, or in a system folder
// at the top of a drive. Only the path's own names are looked at, not what's on disk.
bool isSkippedPath(const QString &path)
{
    const QStringList parts = path.split(QLatin1Char('/'), Qt::SkipEmptyParts);
    for (qsizetype i = 0; i < parts.size(); ++i) {
        if (i == 0 && parts[0].endsWith(QLatin1Char(':')))
            continue;   // the drive
        if (isSkippedFolder(parts[i]) || (i == 1 && parts[0].endsWith(QLatin1Char(':')) && isSystemFolder(parts[i])))
            return true;
    }
    return false;
}

// Whether `folder`, under `root`, is in a code project: one of the folders above it (but
// below `root`) has a project's files.
bool isInProject(const QString &root, const QString &folder)
{
    const QString base = withSlash(root);
    for (QString dir = QFileInfo(folder).path(); dir.startsWith(base) && dir.size() > base.size();
         dir = QFileInfo(dir).path()) {
        const QStringList names = QDir(dir).entryList(QDir::AllEntries | QDir::Hidden | QDir::System | QDir::NoDotAndDotDot);
        if (std::any_of(names.begin(), names.end(), isProjectFile))
            return true;
    }
    return false;
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

// Videos in natural path order ("Episode 2" before "Episode 10"), with a sort key made
// once per path rather than a locale comparison for every pair.
QList<VideoInfo> sortedByPath(QList<VideoInfo> videos)
{
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

// A folder's search text: where it is under `root`, and what `root` is called.
QString placeKeyOf(const QString &dir, const QString &root, const QString &sourceName)
{
    const QString inside = dir == root ? QString() : dir.mid(withSlash(root).size());
    return searchKey(inside + QLatin1Char(' ') + sourceName);
}

// One thing in a folder, as a scan sees it: what's needed to list a video, all of it read
// with the folder's listing rather than asked of each file afterwards.
struct DirEntry
{
    QString name;
    bool folder = false;
    bool link = false;   // a link to a folder, or a junction: not looked inside
    qint64 size = 0;
    QDateTime modified;
    QDateTime born;      // when it was created here (copied in); invalid if unknown
    // In OneDrive (or another cloud folder) but not on this computer.
    bool onlineOnly = false;
};

#ifdef Q_OS_WIN
QDateTime fromFileTime(const FILETIME &time)
{
    const quint64 ticks = (quint64(time.dwHighDateTime) << 32) | time.dwLowDateTime;   // 100 ns since 1601
    constexpr quint64 k1970 = 116444736000000000ULL;
    // Before 1970 (a camera with its clock unset) is as good as unknown, and would wrap
    // round to tens of thousands of years from now.
    if (ticks < k1970)
        return {};
    return QDateTime::fromMSecsSinceEpoch(qint64((ticks - k1970) / 10000));
}

// In OneDrive (or another cloud folder) but not on this computer, going by its attributes.
bool isOnlineOnly(DWORD attributes)
{
    return attributes & (FILE_ATTRIBUTE_RECALL_ON_DATA_ACCESS | FILE_ATTRIBUTE_RECALL_ON_OPEN | FILE_ATTRIBUTE_OFFLINE);
}

// The same for the file at `path` (what it points to, if it's a link).
bool isOnlineOnly(const QString &path)
{
    const DWORD attributes = GetFileAttributesW(reinterpret_cast<const wchar_t *>(QDir::toNativeSeparators(path).utf16()));
    return attributes != INVALID_FILE_ATTRIBUTES && isOnlineOnly(attributes);
}
#endif

// What's in a folder: its files (not hidden ones) and its sub-folders (hidden ones too).
// On Windows this is one bulk listing that brings each file's size, times and attributes
// with it, which is much quicker across a whole drive than QDir listing files and folders
// separately and then asking about each video.
QList<DirEntry> listFolder(const QString &dir)
{
    QList<DirEntry> entries;
#ifdef Q_OS_WIN
    // The \\?\ form lifts the 260-character limit on paths.
    QString pattern = QDir::toNativeSeparators(withSlash(dir)) + QLatin1Char('*');
    pattern = pattern.startsWith(QLatin1String("\\\\")) ? QStringLiteral("\\\\?\\UNC\\") + pattern.mid(2)
                                                        : QStringLiteral("\\\\?\\") + pattern;
    WIN32_FIND_DATAW data;
    const HANDLE find = FindFirstFileExW(reinterpret_cast<const wchar_t *>(pattern.utf16()), FindExInfoBasic, &data,
                                         FindExSearchNameMatch, nullptr, FIND_FIRST_EX_LARGE_FETCH);
    if (find == INVALID_HANDLE_VALUE)
        return entries;
    do {
        const DWORD attributes = data.dwFileAttributes;
        const bool folder = attributes & FILE_ATTRIBUTE_DIRECTORY;
        const QString name = QString::fromWCharArray(data.cFileName);
        if (folder && (name == QLatin1String(".") || name == QLatin1String("..")))
            continue;
        if (!folder && (attributes & FILE_ATTRIBUTE_HIDDEN))
            continue;
        // For a reparse point, the kind of link it is.
        const DWORD tag = (attributes & FILE_ATTRIBUTE_REPARSE_POINT) ? data.dwReserved0 : 0;
        DirEntry entry;
        entry.name = name;
        entry.folder = folder;
        if (folder) {
            entry.link = tag == IO_REPARSE_TAG_SYMLINK || tag == IO_REPARSE_TAG_MOUNT_POINT;
        } else if (tag == IO_REPARSE_TAG_SYMLINK) {
            // A link to a file: the listing describes the link, so ask about what it points to.
            const QFileInfo target(withSlash(dir) + name);
            if (!target.exists())
                continue;
            entry.size = target.size();
            entry.modified = target.lastModified();
            entry.born = target.birthTime();
            entry.onlineOnly = isOnlineOnly(target.filePath());
        } else {
            entry.size = qint64((quint64(data.nFileSizeHigh) << 32) | data.nFileSizeLow);
            entry.modified = fromFileTime(data.ftLastWriteTime);
            entry.born = fromFileTime(data.ftCreationTime);
            entry.onlineOnly = isOnlineOnly(attributes);
        }
        entries.append(entry);
    } while (FindNextFileW(find, &data));
    FindClose(find);
#else
    const QDir here(dir);
    for (const QFileInfo &file : here.entryInfoList(QDir::Files)) {
        DirEntry entry;
        entry.name = file.fileName();
        entry.size = file.size();
        entry.modified = file.lastModified();
        entry.born = file.birthTime();
        entries.append(entry);
    }
    // Links to folders aren't followed.
    for (const QFileInfo &folder :
         here.entryInfoList(QDir::Dirs | QDir::Hidden | QDir::System | QDir::NoDotAndDotDot | QDir::NoSymLinks)) {
        DirEntry entry;
        entry.name = folder.fileName();
        entry.folder = true;
        entry.link = folder.isJunction();
        entries.append(entry);
    }
#endif
    return entries;
}

// A video file in `dir` under `root` (a drive, or the Videos folder), with everything that
// can be worked out from the file system alone. `placeKey` is its folder's search text.
VideoInfo fileVideo(const QString &dir, const QString &name, qint64 size, const QDateTime &modified,
                    const QDateTime &born, bool onlineOnly, const QString &root, bool external,
                    const QString &driveId, const QString &placeKey)
{
    VideoInfo video;
    video.path = withSlash(dir) + name;
    video.key = external ? QStringLiteral("drive:%1/%2").arg(driveId, video.path.mid(withSlash(root).size()))
                         : video.path;
    const QString baseName = baseNameOf(name);
    video.title = prettyTitle(baseName);
    video.year = yearIn(baseName);
    video.dir = dir;
    video.placeKey = placeKey;
    video.root = root;
    video.type = suffixOf(name).toLower();
    video.external = external;
    video.size = size;
    video.modified = modified;
    // Creation time is when the file was copied here, so it's when it was "added".
    video.added = born.isValid() ? born : modified;
    video.cacheKey = cacheKeyOf(video.key, video.size, video.modified);
    video.titleKey = searchKey(video.title);
    video.onlineOnly = onlineOnly;
    return video;
}

// The same for one file on its own (one that's just been moved), asking the file system.
VideoInfo fileVideo(const QFileInfo &file, const QString &root, bool external, const QString &driveId,
                    const QString &placeKey)
{
    bool onlineOnly = false;
#ifdef Q_OS_WIN
    onlineOnly = isOnlineOnly(file.absoluteFilePath());
#endif
    return fileVideo(file.absolutePath(), file.fileName(), file.size(), file.lastModified(), file.birthTime(),
                     onlineOnly, root, external, driveId, placeKey);
}

// How often a scan reports what it's found so far (the first batch goes at once): each
// report relists the library, so not for every folder.
constexpr int kScanReportMs = 1000;

// Every video under `root` (on the scanning thread, keeping the UI free), a folder at a
// time: each folder's videos, then its sub-folders' in natural order, so whole folders are
// finished one after another. Reported in batches as they're found, so finished folders
// can be browsed (and get their thumbnails) while the rest is still being looked through.
// `sourceName` is what the drive (or folder) is called, for search.
//
// `start` is where to look (a folder inside `root`, after a change there, or `root` itself);
// with `recursive` false, only that folder's own files.
void scanFolder(QPromise<QList<VideoInfo>> &promise, const QString &root, const QString &start, bool recursive,
                bool external, const QString &driveId, const QString &sourceName, std::shared_ptr<std::atomic_int> found)
{
    // Part of a drive that isn't looked through: nothing there.
    if (start != root && (isSkippedPath(isDriveRoot(root) ? start : start.mid(root.size())) || isInProject(root, start)))
        return;

    QCollator collator;
    collator.setNumericMode(true);
    collator.setCaseSensitivity(Qt::CaseInsensitive);

    QList<VideoInfo> batch;
    QElapsedTimer sinceReport;
    bool reported = false;
    // Folders are written the same way throughout ("C:/Videos", "E:/"), the root included,
    // so they can be compared with it.
    const QString top = QDir::cleanPath(root);
    QStringList stack{QDir::cleanPath(start)};
    while (!stack.isEmpty()) {
        const QString dir = stack.takeLast();
        // Hidden folders are looked in too: copied Documents folders keep the old "My Pictures"
        // and "My Music" as hidden system folders, videos and all. Links to folders (and
        // junctions) aren't followed.
        const QList<DirEntry> entries = listFolder(dir);
        // A code project, and everything in it: not anyone's videos. A link to a folder named
        // like a project's doesn't make it one.
        if (dir != top && std::any_of(entries.begin(), entries.end(), [](const DirEntry &e) {
                return !e.link && isProjectFile(e.name);
            }))
            continue;

        if (!isEditorCache(dir)) {
            const QString placeKey = placeKeyOf(dir, root, sourceName);
            QList<VideoInfo> videos;
            for (const DirEntry &file : entries) {
                if (file.folder || !isVideoEntry(withSlash(dir) + file.name, file.name, file.size))
                    continue;
                videos.append(fileVideo(dir, file.name, file.size, file.modified, file.born, file.onlineOnly, root,
                                        external, driveId, placeKey));
                ++*found;
            }
            batch += sortedByPath(std::move(videos));
        }
        QStringList subfolders;
        if (recursive) {
            for (const DirEntry &folder : entries) {
                if (!folder.folder || folder.link || isSkippedFolder(folder.name)
                    || (dir == top && isDriveRoot(root) && isSystemFolder(folder.name)))
                    continue;
                subfolders.append(folder.name);
            }
        }
        std::sort(subfolders.begin(), subfolders.end(), [&](const QString &a, const QString &b) {
            return collator.compare(a, b) < 0;
        });
        // Last pushed is looked in first: the first sub-folder next.
        for (auto it = subfolders.crbegin(); it != subfolders.crend(); ++it)
            stack.append(withSlash(dir) + *it);

        if (!batch.isEmpty() && (!reported || sinceReport.elapsed() >= kScanReportMs)) {
            promise.addResult(std::exchange(batch, {}));
            reported = true;
            sinceReport.start();
        }
    }
    if (!batch.isEmpty())
        promise.addResult(batch);
}

// A video on a phone, whose root is "mtp://<serial>". `placeKeys` shares each folder's
// search text between its videos.
VideoInfo phoneVideo(const QString &root, const QString &serial, const QString &sourceName, const Phones::File &file,
                     QHash<QString, QString> &placeKeys)
{
    VideoInfo video;
    video.path = root + QLatin1Char('/') + file.path;
    video.key = QStringLiteral("phone:%1/%2").arg(serial, file.path);
    const QString name = file.path.section(QLatin1Char('/'), -1);
    const QString baseName = name.contains(QLatin1Char('.')) ? name.section(QLatin1Char('.'), 0, -2) : name;
    video.title = prettyTitle(baseName);
    video.year = yearIn(baseName);
    const QString inside = file.path.contains(QLatin1Char('/')) ? file.path.section(QLatin1Char('/'), 0, -2) : QString();
    video.dir = inside.isEmpty() ? root : root + QLatin1Char('/') + inside;
    auto place = placeKeys.find(inside);
    if (place == placeKeys.end())
        place = placeKeys.insert(inside, searchKey(inside + QLatin1Char(' ') + sourceName));
    video.placeKey = *place;
    video.root = root;
    video.type = name.section(QLatin1Char('.'), -1).toLower();
    video.external = true;
    video.phone = true;
    video.objectId = file.objectId;
    video.size = file.size;
    video.modified = file.modified;
    video.added = file.modified;
    video.cacheKey = cacheKeyOf(video.key, video.size, video.modified);
    video.titleKey = searchKey(video.title);
    return video;
}

// Every video on a phone (see scanFolder), found over MTP. Reported in batches as they're
// found (the first straight away, then about once a second): the first time after it's
// plugged in, a phone can take a minute to list everything.
void scanPhone(QPromise<QList<VideoInfo>> &promise, const QString &root, const QString &serial,
               const QString &sourceName, std::shared_ptr<std::atomic_int> found)
{
    QList<VideoInfo> batch;
    QHash<QString, QString> placeKeys;   // folder inside the phone -> its search text
    QElapsedTimer sinceReport;
    bool reported = false;
    Phones::findVideos(serial, [&](const QList<Phones::File> &files) {
        for (const Phones::File &file : files) {
            batch.append(phoneVideo(root, serial, sourceName, file, placeKeys));
            ++*found;
        }
        if (!batch.isEmpty() && (!reported || sinceReport.elapsed() >= kScanReportMs)) {
            promise.addResult(std::exchange(batch, {}));
            reported = true;
            sinceReport.start();
        }
        return true;
    });
    if (!batch.isEmpty())
        promise.addResult(batch);
}

// What was found on a phone, kept for showing straight away the next time it's plugged in
// (while it's searched again): phones/<serial>.json in the data folder.
QString phoneListFile(const QString &dataDir, const QString &serial)
{
    QString name = serial;
    name.replace(QRegularExpression(QStringLiteral("[^A-Za-z0-9_-]")), QStringLiteral("_"));
    return dataDir + QStringLiteral("/phones/") + name + QStringLiteral(".json");
}

void savePhoneList(const QString &dataDir, const QString &serial, const QList<VideoInfo> &videos)
{
    QJsonArray list;
    for (const VideoInfo &v : videos) {
        QJsonObject entry{{"path", v.path.mid(v.root.size() + 1)}, {"size", v.size}};
        if (v.modified.isValid())
            entry.insert(QLatin1String("modified"), v.modified.toMSecsSinceEpoch());
        list.append(entry);
    }
    QDir().mkpath(dataDir + QStringLiteral("/phones"));
    QSaveFile file(phoneListFile(dataDir, serial));
    if (file.open(QIODevice::WriteOnly)) {
        file.write(QJsonDocument(QJsonObject{{"videos", list}}).toJson(QJsonDocument::Compact));
        file.commit();
    }
}

// The videos found on a phone last time, without their object ids: MTP numbers a phone's
// files afresh each time it's connected.
QList<VideoInfo> loadPhoneList(const QString &dataDir, const QString &root, const QString &serial,
                               const QString &sourceName)
{
    QFile file(phoneListFile(dataDir, serial));
    if (!file.open(QIODevice::ReadOnly))
        return {};
    QList<VideoInfo> videos;
    QHash<QString, QString> placeKeys;
    const QJsonArray list = QJsonDocument::fromJson(file.readAll()).object().value(QLatin1String("videos")).toArray();
    for (const QJsonValue &value : list) {
        const QJsonObject entry = value.toObject();
        Phones::File f;
        f.path = entry.value(QLatin1String("path")).toString();
        f.size = entry.value(QLatin1String("size")).toInteger();
        if (entry.contains(QLatin1String("modified")))
            f.modified = QDateTime::fromMSecsSinceEpoch(entry.value(QLatin1String("modified")).toInteger());
        if (!f.path.isEmpty())
            videos.append(phoneVideo(root, serial, sourceName, f, placeKeys));
    }
    return sortedByPath(std::move(videos));
}

// What the last scan of one of the computer's own drives (or the Videos folder) found,
// kept so the library is there straight away at the next launch while it's looked through
// again: scans/<name>.json in the data folder.
QString scanListFile(const QString &dataDir, const QString &root)
{
    QString name = root;
    name.replace(QRegularExpression(QStringLiteral("[^A-Za-z0-9_-]")), QStringLiteral("_"));
    return dataDir + QStringLiteral("/scans/") + name + QStringLiteral(".json");
}

// `driveId` is the drive's own id (empty for the Videos folder on a network drive): a
// different disk that gets the same letter doesn't get this one's list.
void saveScanList(const QString &dataDir, const QString &root, const QString &driveId, const QList<VideoInfo> &videos)
{
    const qsizetype inside = withSlash(root).size();
    QJsonArray list;
    for (const VideoInfo &v : videos) {
        QJsonObject entry{{"path", v.path.mid(inside)}, {"size", v.size}};
        if (v.modified.isValid())
            entry.insert(QLatin1String("modified"), v.modified.toMSecsSinceEpoch());
        if (v.added != v.modified && v.added.isValid())
            entry.insert(QLatin1String("added"), v.added.toMSecsSinceEpoch());
        if (v.onlineOnly)
            entry.insert(QLatin1String("online"), true);
        list.append(entry);
    }
    QDir().mkpath(dataDir + QStringLiteral("/scans"));
    QSaveFile file(scanListFile(dataDir, root));
    if (file.open(QIODevice::WriteOnly)) {
        file.write(QJsonDocument(QJsonObject{{"root", root}, {"drive", driveId}, {"videos", list}})
                       .toJson(QJsonDocument::Compact));
        file.commit();
    }
}

// Read on a worker thread at launch, so a big library doesn't hold up the window. Videos in
// folders that have gone since are left out; the rest are marked `unconfirmed` until the
// scan finds them again.
QList<VideoInfo> loadScanList(const QString &dataDir, const QString &root, const QString &driveId,
                              const QString &sourceName)
{
    QFile file(scanListFile(dataDir, root));
    if (!file.open(QIODevice::ReadOnly))
        return {};
    const QJsonObject saved = QJsonDocument::fromJson(file.readAll()).object();
    if (saved.value(QLatin1String("root")).toString() != root)
        return {};   // another folder whose name came out the same
    if (saved.value(QLatin1String("drive")).toString() != driveId)
        return {};   // another disk that has this one's letter now
    const QString prefix = withSlash(root);
    QList<VideoInfo> videos;
    QHash<QString, QString> placeKeys;   // folder -> its search text
    QSet<QString> gone;                  // folders that aren't there now
    const QJsonArray list = saved.value(QLatin1String("videos")).toArray();
    videos.reserve(list.size());
    const auto timeOf = [](const QJsonValue &ms) {
        return ms.isUndefined() ? QDateTime() : QDateTime::fromMSecsSinceEpoch(ms.toInteger());
    };
    for (const QJsonValue &value : list) {
        const QJsonObject entry = value.toObject();
        const QString path = prefix + entry.value(QLatin1String("path")).toString();
        const qsizetype slash = path.lastIndexOf(QLatin1Char('/'));
        if (slash < prefix.size() - 1)
            continue;
        // Written as the scan writes it ("C:/" for the root of C:).
        const QString dir = slash == prefix.size() - 1 ? QDir::cleanPath(root) : path.left(slash);
        if (gone.contains(dir))
            continue;
        auto place = placeKeys.find(dir);
        if (place == placeKeys.end()) {
            if (!QFileInfo::exists(dir)) {
                gone.insert(dir);
                continue;
            }
            place = placeKeys.insert(dir, placeKeyOf(dir, root, sourceName));
        }
        const QDateTime modified = timeOf(entry.value(QLatin1String("modified")));
        const QDateTime added = entry.contains(QLatin1String("added")) ? timeOf(entry.value(QLatin1String("added")))
                                                                       : modified;
        VideoInfo video = fileVideo(dir, path.mid(slash + 1), entry.value(QLatin1String("size")).toInteger(), modified,
                                    added, entry.value(QLatin1String("online")).toBool(), root, false, QString(), *place);
        video.unconfirmed = true;
        videos.append(std::move(video));
    }
    return sortedByPath(std::move(videos));
}

bool isFinished(double position, double duration)
{
    // 95% covers a film's end credits; short clips just need to reach the end.
    return duration > 0 && (position >= duration * 0.95 || duration - position < 5);
}

} // namespace

VideoLibrary::VideoLibrary(QObject *parent)
    : QObject(parent)
    // VESSEL_DATA_DIR points a test copy at its own settings, history and cache.
    , m_dataDir(qEnvironmentVariable("VESSEL_DATA_DIR",
                                     QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation)))
    , m_pickSeed(QRandomGenerator::global()->generate())
{
    QDir().mkpath(m_dataDir + QStringLiteral("/thumbnails"));
    loadState();

    m_saveTimer.setSingleShot(true);
    m_saveTimer.setInterval(2000);
    connect(&m_saveTimer, &QTimer::timeout, this, &VideoLibrary::save);
    m_scanSaver.setMaxThreadCount(1);
    m_scanSaveTimer.setSingleShot(true);
    m_scanSaveTimer.setInterval(30000);
    connect(&m_scanSaveTimer, &QTimer::timeout, this, &VideoLibrary::saveScanLists);

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

    m_videosFolder = QDir::cleanPath(QStandardPaths::writableLocation(QStandardPaths::MoviesLocation));
    {
        // Library folders were chosen in Settings before the library was the whole computer.
        QSettings settings(m_dataDir + QStringLiteral("/settings.ini"), QSettings::IniFormat);
        settings.remove(QStringLiteral("library/folders"));
    }

    // Home's shelves are about the library, not whatever drive is plugged in.
    m_continueWatching = new VideoModel(this, [this] { return startedIn(QString()); }, this);

    m_historyModel = new HistoryModel(this, [this] {
        QList<int> rows;
        for (int i = 0; i < m_videos.size(); ++i)
            if (m_videos[i].watchedAt > 0)
                rows.append(i);
        std::sort(rows.begin(), rows.end(), [this](int a, int b) { return m_videos[a].watchedAt > m_videos[b].watchedAt; });
        return rows;
    }, this);

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

    m_folders = new FolderModel(this, QString(), FolderModel::VideoFolders, this);
    m_playlistModel = new PlaylistModel(this, this);

    // Videos added to (or removed from) the library show up without a rescan, a moment
    // after the last change (copying a film is many changes).
    m_folderWatcher = new FolderWatcher(this);
    m_folderWatcher->setIgnored(&isSkippedPath);
    connect(m_folderWatcher, &FolderWatcher::changed, this,
            [this](const QString &, const QStringList &dirs, const QStringList &trees) {
        for (const QString &dir : dirs)
            onFolderChanged(dir, false);
        for (const QString &tree : trees)
            onFolderChanged(tree, true);
    });
    connect(m_folderWatcher, &FolderWatcher::removed, this, [this](const QString &, const QStringList &paths) {
        for (const QString &path : paths) {
            const QString prefix = withSlash(path);
            const bool hadVideos = std::any_of(m_videos.cbegin(), m_videos.cend(), [&](const VideoInfo &v) {
                return v.path.startsWith(prefix, Qt::CaseInsensitive);
            });
            if (hadVideos)
                onFolderChanged(path, true);   // finds nothing there now: they leave
        }
    });
    m_rebuildTimer.setSingleShot(true);
    connect(&m_rebuildTimer, &QTimer::timeout, this, &VideoLibrary::rebuild);
    m_changeTimer.setSingleShot(true);
    m_changeTimer.setInterval(2000);
    connect(&m_changeTimer, &QTimer::timeout, this, [this] {
        const QHash<QString, bool> folders = std::exchange(m_changedFolders, {});
        for (auto it = folders.cbegin(); it != folders.cend(); ++it) {
            const QString &folder = it.key();
            // Inside a folder that's being looked through anyway.
            bool inside = false;
            for (auto other = folders.cbegin(); other != folders.cend() && !inside; ++other)
                inside = other.value() && other.key() != folder && folder.startsWith(withSlash(other.key()));
            if (inside)
                continue;
            // The library source it's in.
            int index = -1;
            for (int i = 0; i < m_sources.size() && index < 0; ++i)
                if (!m_sources[i].external && (folder == m_sources[i].path || folder.startsWith(withSlash(m_sources[i].path))))
                    index = i;
            if (index < 0)
                continue;
            if (m_sources[index].scanning)
                onFolderChanged(folder, it.value());   // after this scan, look again
            else if (folder == m_sources[index].path && it.value())
                scanSource(folder);
            else
                rescanPart(m_sources[index].path, folder, it.value());
        }
    });

    m_driveWatcher = new DriveWatcher(this);
    connect(m_driveWatcher, &DriveWatcher::drivesChanged, this, &VideoLibrary::updateLibrarySources);
    connect(m_driveWatcher, &DriveWatcher::drivesChanged, this, &VideoLibrary::onDrivesChanged);
    connect(m_driveWatcher, &DriveWatcher::drivesChanged, this, &VideoLibrary::updateWatchedFolders);
    updateLibrarySources();
    onDrivesChanged();
    // Drives already plugged in at startup aren't announced; ones plugged in later are.
    for (VideoSource &source : m_sources)
        source.announce = false;
    updateWatchedFolders();

    m_phoneTimer.setInterval(2000);
    connect(&m_phoneTimer, &QTimer::timeout, this, &VideoLibrary::pollPhones);
    m_phoneTimer.start();
    pollPhones();
}

VideoLibrary::~VideoLibrary()
{
    delete m_thumbnailer;   // waits for running grabs before we save
    m_thumbnailer = nullptr;
    save();
    saveScanLists();
    m_scanSaver.waitForDone();
}

int VideoLibrary::libraryCount() const
{
    return std::count_if(m_videos.begin(), m_videos.end(), [](const VideoInfo &v) { return !v.external; });
}

void VideoLibrary::updateLibrarySources()
{
    // The computer's own drives; elsewhere, the home folder. And the Videos folder, if it's
    // somewhere else (a network drive).
    QList<VideoSource> wanted;
    const QList<DriveInfo> internal = m_driveWatcher->internalDrives();
    for (const DriveInfo &drive : internal) {
        VideoSource source;
        source.path = drive.rootPath;
        source.name = drive.name;
        source.driveId = drive.id;
        wanted.append(source);
    }
    if (wanted.isEmpty()) {
#ifndef Q_OS_WIN
        VideoSource home;
        home.path = QDir::homePath();
        wanted.append(home);
#endif
    }
    const bool videosInside = std::any_of(wanted.begin(), wanted.end(), [&](const VideoSource &s) {
        return m_videosFolder.startsWith(withSlash(s.path), Qt::CaseInsensitive);
    });
    if (!videosInside && !m_videosFolder.isEmpty()) {
        VideoSource videos;
        videos.path = m_videosFolder;
        wanted.append(videos);
    }

    bool changed = false;
    for (int i = m_sources.size() - 1; i >= 0; --i) {
        if (m_sources[i].external)
            continue;
        const QString path = m_sources[i].path;
        if (std::any_of(wanted.begin(), wanted.end(), [&](const VideoSource &s) { return s.path == path; }))
            continue;
        m_sources.removeAt(i);
        m_scanned.remove(path);
        // Its list from last time goes too: if it comes back, it's looked through afresh.
        m_unsavedScans.remove(path);
        m_scanSaver.start([file = scanListFile(m_dataDir, path)] { QFile::remove(file); });
        changed = true;
    }
    // In order, ahead of the external drives.
    for (int position = 0; position < wanted.size(); ++position) {
        const int index = sourceIndex(wanted[position].path);
        if (index >= 0) {
            m_sources[index].name = wanted[position].name;
            continue;
        }
        m_sources.insert(std::min<qsizetype>(position, m_sources.size()), wanted[position]);
        const QString path = wanted[position].path;
        const QString driveId = wanted[position].driveId;
        scanSource(path);
        // What was there last time, listed as soon as it's read (off the UI thread) rather
        // than after looking through the whole drive. Videos the scan has found already
        // stay as it found them; when it's done, its list replaces this one.
        auto *loader = new QFutureWatcher<QList<VideoInfo>>(this);
        connect(loader, &QFutureWatcherBase::finished, this, [this, loader, path, driveId] {
            loader->deleteLater();
            const int index = sourceIndex(path);
            if (index < 0 || m_sources[index].driveId != driveId || !m_sources[index].scanning)
                return;   // gone, or scanned already
            const QList<VideoInfo> remembered = loader->result();
            if (remembered.isEmpty())
                return;
            QList<VideoInfo> &videos = m_scanned[path];
            QSet<QString> found;
            for (const VideoInfo &v : std::as_const(videos))
                found.insert(v.path);
            for (const VideoInfo &v : remembered)
                if (!found.contains(v.path))
                    videos.append(v);
            m_unsorted.insert(path);
            rebuild();
        });
        loader->setFuture(QtConcurrent::run(loadScanList, m_dataDir, path, driveId, sourceName(path)));
        changed = true;
    }
    if (changed)
        rebuild();
}

QVariantList VideoLibrary::drives() const
{
    const QLocale locale = QLocale::system();
    QVariantList list;
    for (const VideoSource &source : m_sources) {
        if (!source.external || source.phone)
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

QVariantList VideoLibrary::phones() const
{
    const QLocale locale = QLocale::system();
    const auto size = [&](qint64 bytes) { return locale.formattedDataSize(bytes, 1, QLocale::DataSizeTraditionalFormat); };
    QVariantList list;
    for (const Phones::Phone &phone : m_phones) {
        const QString path = QStringLiteral("mtp://") + phone.serial;
        const int index = phone.ready ? sourceIndex(path) : -1;
        list.append(QVariantMap{
            {"path", index >= 0 ? path : QString()},
            {"name", phone.name},
            {"ready", phone.ready},
            {"count", index >= 0 ? m_scanned.value(path).size() : 0},
            {"scanning", index >= 0 && m_sources[index].scanning},
            {"found", index >= 0 ? m_sources[index].found->load() : 0},
            {"sizeText", size(phone.bytesTotal)},
            {"freeText", size(phone.bytesFree)},
            {"usedFraction", phone.bytesTotal > 0 ? 1.0 - double(phone.bytesFree) / phone.bytesTotal : 0.0},
        });
    }
    return list;
}

void VideoLibrary::pollPhones()
{
    if (std::exchange(m_pollingPhones, true))
        return;   // the last look is still going (a phone can be slow to answer)
    auto *watcher = new QFutureWatcher<QList<Phones::Phone>>(this);
    connect(watcher, &QFutureWatcherBase::finished, this, [this, watcher] {
        watcher->deleteLater();
        m_pollingPhones = false;
        onPhonesChanged(watcher->result());
    });
    watcher->setFuture(QtConcurrent::run(&Phones::list));
}

void VideoLibrary::onPhonesChanged(const QList<Phones::Phone> &phones)
{
    // Phones there at startup aren't announced; ones connected (or unlocked) later are.
    static bool first = true;
    const bool announce = !std::exchange(first, false);

    const auto signature = [](const QList<Phones::Phone> &list) {
        QString s;
        for (const Phones::Phone &p : list)
            s += p.serial + (p.ready ? QLatin1Char('+') : QLatin1Char('-')) + p.name
                 + QString::number(p.bytesFree >> 26) + QLatin1Char('|');   // free space to the nearest 64 MB
        return s;
    };
    const bool changed = signature(phones) != signature(m_phones);
    m_phones = phones;
    if (!changed)
        return;

    const auto readyPhone = [&](const QString &serial) {
        for (const Phones::Phone &p : phones)
            if (p.serial == serial && p.ready)
                return &p;
        return static_cast<const Phones::Phone *>(nullptr);
    };
    bool relist = false;   // the list of videos changed
    for (int i = m_sources.size() - 1; i >= 0; --i) {
        const VideoSource &source = m_sources[i];
        if (!source.phone || readyPhone(source.driveId))
            continue;
        // Unplugged, or locked again.
        const QString path = source.path;
        emit driveRemoved(path, source.name, false);   // while its videos are still known
        m_sources.removeAt(i);
        m_scanned.remove(path);
        relist = true;
    }
    for (const Phones::Phone &phone : phones) {
        if (!phone.ready)
            continue;
        const QString path = QStringLiteral("mtp://") + phone.serial;
        const int index = sourceIndex(path);
        if (index >= 0) {
            m_sources[index].bytesTotal = phone.bytesTotal;
            m_sources[index].bytesFree = phone.bytesFree;
            continue;
        }
        VideoSource source;
        source.path = path;
        source.name = phone.name;
        source.external = true;
        source.phone = true;
        source.driveId = phone.serial;
        source.kind = QStringLiteral("phone");
        source.bytesTotal = phone.bytesTotal;
        source.bytesFree = phone.bytesFree;
        source.announce = announce;
        m_sources.append(source);
        // What was on it last time, while it's searched again.
        QList<VideoInfo> remembered = loadPhoneList(m_dataDir, path, phone.serial, sourceName(path));
        if (!remembered.isEmpty()) {
            m_scanned.insert(path, std::move(remembered));
            relist = true;
        }
        scanSource(path);
    }
    if (relist)
        rebuild();
    emit drivesChanged();
}

QString VideoLibrary::playbackUrl(const QString &path) const
{
    const int index = m_byPath.value(path, -1);
    if (index < 0 || !m_videos[index].phone)
        return path;
    const VideoInfo &v = m_videos[index];
    Phones::File file;
    file.objectId = v.objectId;
    file.path = v.path.mid(v.root.size() + 1);
    file.size = v.size;
    return Phones::streamUrl(v.root.mid(6), file);   // the root is "mtp://<serial>"
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

    // The videos come in batches, each shown (and given thumbnails) as it arrives; the
    // finished list then replaces what was there, dropping videos that have gone.
    auto *watcher = new QFutureWatcher<QList<VideoInfo>>(this);
    QElapsedTimer timer;
    timer.start();
    connect(watcher, &QFutureWatcherBase::resultsReadyAt, this, [this, watcher, path, timer](int begin, int end) {
        for (int i = begin; i < end; ++i)
            onVideosFound(path, watcher->resultAt(i));
        qCDebug(lcPerf) << "videos so far in" << path << ":" << watcher->future().resultCount() << "batches in"
                        << timer.elapsed() << "ms";
    });
    connect(watcher, &QFutureWatcherBase::finished, this, [this, watcher, path, timer] {
        watcher->deleteLater();
        QList<VideoInfo> videos;
        for (const QList<VideoInfo> &batch : watcher->future().results())
            videos += batch;
        qCDebug(lcPerf) << "scanned" << path << videos.size() << "videos in" << timer.elapsed() << "ms";
        onScanFinished(path, sortedByPath(std::move(videos)));
    });
    if (source.phone)
        watcher->setFuture(QtConcurrent::run(scanPhone, source.path, source.driveId, sourceName(source.path), source.found));
    else
        watcher->setFuture(QtConcurrent::run(scanFolder, source.path, source.path, true, source.external, source.driveId,
                                             sourceName(source.path), source.found));
}

void VideoLibrary::rescanPart(const QString &sourcePath, const QString &folder, bool recursive)
{
    const int index = sourceIndex(sourcePath);
    if (index < 0)
        return;
    const VideoSource &source = m_sources[index];
    auto *watcher = new QFutureWatcher<QList<VideoInfo>>(this);
    connect(watcher, &QFutureWatcherBase::finished, this, [this, watcher, sourcePath, folder, recursive] {
        watcher->deleteLater();
        if (sourceIndex(sourcePath) < 0)
            return;   // gone meanwhile
        QList<VideoInfo> found;
        for (const QList<VideoInfo> &batch : watcher->future().results())
            found += batch;
        // What's there now takes the place of what was.
        const QString prefix = withSlash(folder);
        const auto inFolder = [&](const VideoInfo &v) {
            return v.dir == folder || (recursive && v.dir.startsWith(prefix));
        };
        QList<VideoInfo> &videos = m_scanned[sourcePath];
        // Nothing came, went or changed (it was the folder's other files): left as it is.
        QHash<QString, const VideoInfo *> was;
        for (const VideoInfo &v : std::as_const(videos))
            if (inFolder(v))
                was.insert(v.path, &v);
        const bool same = was.size() == found.size() && std::all_of(found.begin(), found.end(), [&](const VideoInfo &v) {
            const VideoInfo *old = was.value(v.path);
            return old && old->cacheKey == v.cacheKey && old->onlineOnly == v.onlineOnly;
        });
        if (same)
            return;
        videos.removeIf(inFolder);
        videos += found;
        scanListChanged(sourcePath);
        m_unsorted.insert(sourcePath);
        rebuild();
    });
    watcher->setFuture(QtConcurrent::run(scanFolder, source.path, folder, recursive, source.external, source.driveId,
                                         sourceName(source.path), std::make_shared<std::atomic_int>(0)));
}

void VideoLibrary::onVideosFound(const QString &path, const QList<VideoInfo> &found)
{
    const int source = sourceIndex(path);
    if (source < 0 || found.isEmpty())
        return;   // unplugged (or taken out of the library) meanwhile
    // Each takes the place of the one known from before (the last scan, or a phone's list
    // from last time), if there was one.
    QList<VideoInfo> &videos = m_scanned[path];
    QHash<QString, qsizetype> at;
    at.reserve(videos.size());
    for (qsizetype i = 0; i < videos.size(); ++i)
        at.insert(videos[i].path, i);
    bool added = false;
    bool located = false;
    for (const VideoInfo &v : found) {
        const auto it = at.constFind(v.path);
        if (it != at.cend()) {
            // Changed since the last scan: listed afresh.
            if (videos[*it].cacheKey != v.cacheKey)
                added = true;
            videos[*it] = v;
            // What's listed only needs to know where it is on the phone now, or that it's
            // still there; then its thumbnail can be made.
            const int index = m_byPath.value(v.path, -1);
            if (index >= 0 && m_videos[index].phone && m_videos[index].objectId != v.objectId) {
                m_videos[index].objectId = v.objectId;
                located = true;
            }
            if (index >= 0 && m_videos[index].unconfirmed) {
                m_videos[index].unconfirmed = false;
                located = true;
            }
        } else {
            videos.append(v);
            added = true;
        }
    }
    if (added) {
        m_unsorted.insert(path);   // sorted once, when they're listed
        scheduleRebuild();   // lists them, and queues their thumbnails
        if (m_sources[source].external)
            emit drivesChanged();
    } else if (located) {
        queueThumbnails();   // remembered videos, found again, can have theirs made now
    }
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
        if (m_sources[index].phone)
            savePhoneList(m_dataDir, m_sources[index].driveId, videos);
        m_scanned.insert(path, std::move(videos));
        m_unsorted.remove(path);
        m_sources[index].scanning = false;
        if (!m_sources[index].external) {
            m_unsavedScans.insert(path);
            saveScanLists();   // now, not later: a whole drive's been looked through
        }
        rebuild();
        VideoSource &source = m_sources[index];
        if (source.external) {
            emit drivesChanged();
            if (std::exchange(source.announce, false))
                emit driveConnected(source.name, source.path, found);
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

void VideoLibrary::updateWatchedFolders()
{
    // Not on a drive that gets unplugged: watching it would keep it from being ejected
    // (and it's scanned again when it's plugged back in anyway).
    const QList<DriveInfo> drives = m_driveWatcher ? m_driveWatcher->drives() : QList<DriveInfo>();
    QStringList folders;
    for (const VideoSource &source : std::as_const(m_sources)) {
        if (source.external || !QFileInfo(source.path).isDir())
            continue;
        const bool removable = std::any_of(drives.begin(), drives.end(), [&](const DriveInfo &drive) {
            return withSlash(source.path).startsWith(drive.rootPath, Qt::CaseInsensitive);
        });
        if (!removable)
            folders.append(source.path);
    }
    m_folderWatcher->setFolders(folders);
}

void VideoLibrary::onFolderChanged(const QString &folder, bool recursive)
{
    m_changedFolders[folder] |= recursive;
    m_changeTimer.start();   // (again): two seconds after the last change
}

void VideoLibrary::onDrivesChanged()
{
    const QList<DriveInfo> drives = m_driveWatcher->drives();

    bool removed = false;
    for (int i = m_sources.size() - 1; i >= 0; --i) {
        const VideoSource &source = m_sources[i];
        if (!source.external || source.phone)   // phones come and go in onPhonesChanged
            continue;
        const bool present = std::any_of(drives.begin(), drives.end(), [&](const DriveInfo &d) {
            return d.rootPath == source.path && d.id == source.driveId;
        });
        if (present)
            continue;
        const QString path = source.path;
        emit driveRemoved(path, source.name, m_ejected.remove(path));   // while its videos are still known, so progress can be saved
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

void VideoLibrary::ejectDrive(const QString &path)
{
    const int index = sourceIndex(path);
    if (index < 0 || !m_sources[index].external || m_sources[index].phone)
        return;
    const QString name = m_sources[index].name;
    const QString kind = m_sources[index].kind;
    // Marked first: once ejected, the drive watcher may see it go before we hear back.
    m_ejected.insert(path);
    auto *watcher = new QFutureWatcher<QString>(this);
    connect(watcher, &QFutureWatcherBase::finished, this, [this, watcher, path, name] {
        watcher->deleteLater();
        const QString error = watcher->result();
        if (!error.isEmpty()) {
            m_ejected.remove(path);
            emit driveEjectFailed(path, name, error);
        }
        // Otherwise it leaves on the drive watcher's next look, a moment from now, as "ejected".
    });
    watcher->setFuture(QtConcurrent::run(&::ejectDrive, path, kind));
}

void VideoLibrary::scheduleRebuild()
{
    constexpr qint64 interval = 2000;   // ms
    if (m_rebuildTimer.isActive())
        return;   // these will be in it
    const qint64 since = m_sinceRebuild.isValid() ? m_sinceRebuild.elapsed() : interval;
    if (since >= interval)
        rebuild();   // the first batch in a while shows straight away
    else
        m_rebuildTimer.start(int(interval - since));
}

void VideoLibrary::rebuild()
{
    QElapsedTimer timer;
    timer.start();
    m_rebuildTimer.stop();   // this is it
    m_sinceRebuild.start();
    for (const QString &path : std::exchange(m_unsorted, {})) {
        const auto it = m_scanned.find(path);
        if (it != m_scanned.end())
            *it = sortedByPath(std::move(*it));
    }
    m_videos.clear();
    m_byPath.clear();
    m_byKey.clear();
    for (const VideoSource &source : std::as_const(m_sources)) {
        for (const VideoInfo &found : m_scanned.value(source.path)) {
            // The Videos folder on an external drive is listed under the library, not the drive.
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
            m_byKey.insert(v.key, m_videos.size());
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
            // The sub-folder it's under: a drive for "", otherwise the next folder down.
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

    // While a phone is being scanned, its videos get thumbnails as the scan finds them (the
    // phone takes turns between the two). Ones remembered from last time wait until the scan
    // finds them again: until then where they are on the phone isn't known.
    QSet<QString> scanningPhones;
    for (const VideoSource &source : std::as_const(m_sources))
        if (source.phone && source.scanning)
            scanningPhones.insert(source.path);

    QList<bool> queued(m_videos.size(), false);
    QSet<QString> pending;
    for (int i : order) {
        if (std::exchange(queued[i], true))
            continue;
        const VideoInfo &v = m_videos[i];
        if (v.phone && v.objectId.isEmpty() && scanningPhones.contains(v.root))
            continue;
        // Known from the last scan, and maybe deleted since: it waits for this one to find it.
        if (v.unconfirmed)
            continue;
        // Reading it would download it.
        if (v.onlineOnly)
            continue;
        const VideoMeta meta = m_metadata.value(v.cacheKey);
        // Looked at again when the cache is from before sizes were kept.
        if (!meta.probed || (meta.thumb && !v.hasThumbnail)) {
            m_thumbnailer->request(v.path, thumbnailFile(v), v.phone ? playbackUrl(v.path) : QString());
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

void VideoLibrary::setPhonePlaying(bool playing)
{
    if (m_phonePlaying == playing)
        return;
    m_phonePlaying = playing;
    m_thumbnailer->setPhoneHeld(playing);
    emit phonePlayingChanged();
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
    // Gone, e.g. the drive was unplugged mid-grab: don't remember it as unreadable. (A phone's
    // videos are only listed while it's connected.)
    if (index < 0 || (!ok && !m_videos[index].phone && !QFileInfo::exists(path)))
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
    m_historyModel->refresh();
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
    refreshPlaylists();
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

QStringList VideoLibrary::videoPathsIn(const QString &folder) const
{
    // The library's videos are in path order within each source, so a folder's tree is in order.
    const QString prefix = withSlash(folder);
    QStringList paths;
    for (const VideoInfo &v : m_videos) {
        const bool here = folder.isEmpty() ? !v.external : v.dir == folder || v.dir.startsWith(prefix);
        if (here)
            paths.append(v.path);
    }
    return paths;
}

VideoModel *VideoLibrary::videosAt(const QStringList &paths)
{
    auto *model = new VideoModel(this, [this, paths] {
        QList<int> rows;
        for (const QString &path : paths)
            if (const int index = m_byPath.value(path, -1); index >= 0)
                rows.append(index);
        return rows;
    });
    m_folderVideoModels.removeAll(nullptr);
    m_folderVideoModels.append(model);
    return model;   // owned by QML (no parent)
}

// --- Playlists --------------------------------------------------------------------------

Playlist *VideoLibrary::findPlaylist(const QString &id)
{
    for (Playlist &playlist : m_playlists)
        if (playlist.id == id)
            return &playlist;
    return nullptr;
}

const Playlist *VideoLibrary::findPlaylist(const QString &id) const
{
    return const_cast<VideoLibrary *>(this)->findPlaylist(id);
}

QList<int> VideoLibrary::playlistIndexes(const Playlist &playlist) const
{
    QList<int> rows;
    for (const QString &key : playlist.keys)
        if (const int index = m_byKey.value(key, -1); index >= 0)
            rows.append(index);
    return rows;
}

void VideoLibrary::refreshPlaylists()
{
    m_playlistModel->refresh();
    m_playlistVideoModels.removeAll(nullptr);
    for (const auto &model : std::as_const(m_playlistVideoModels))
        model->refresh();
}

void VideoLibrary::playlistsEdited()
{
    m_playlistsChanged = true;
    scheduleSave();
    ++m_playlistRevision;
    refreshPlaylists();
    emit playlistsChanged();
}

QString VideoLibrary::createPlaylist(const QString &name, const QStringList &paths)
{
    Playlist playlist;
    playlist.id = QUuid::createUuid().toString(QUuid::WithoutBraces);
    playlist.name = name.trimmed().isEmpty() ? tr("New playlist") : name.trimmed();
    playlist.created = playlist.updated = QDateTime::currentMSecsSinceEpoch();
    for (const QString &path : paths)
        if (const QString key = keyFor(path); !playlist.keys.contains(key))
            playlist.keys.append(key);
    m_playlists.prepend(playlist);
    playlistsEdited();
    return playlist.id;
}

void VideoLibrary::renamePlaylist(const QString &id, const QString &name)
{
    Playlist *playlist = findPlaylist(id);
    if (!playlist || name.trimmed().isEmpty() || playlist->name == name.trimmed())
        return;
    playlist->name = name.trimmed();
    playlistsEdited();
}

void VideoLibrary::deletePlaylist(const QString &id)
{
    if (m_playlists.removeIf([&](const Playlist &playlist) { return playlist.id == id; }) > 0)
        playlistsEdited();
}

QString VideoLibrary::playlistName(const QString &id) const
{
    const Playlist *playlist = findPlaylist(id);
    return playlist ? playlist->name : QString();
}

bool VideoLibrary::addToPlaylist(const QString &id, const QString &path, int at)
{
    Playlist *playlist = findPlaylist(id);
    const QString key = keyFor(path);
    if (!playlist || playlist->keys.contains(key))
        return false;
    playlist->keys.insert(at >= 0 && at <= playlist->keys.size() ? at : playlist->keys.size(), key);
    playlist->updated = QDateTime::currentMSecsSinceEpoch();
    // Most recently added to first, as in the Save to playlist list.
    const qsizetype from = playlist - m_playlists.data();
    m_playlists.move(from, 0);
    playlistsEdited();
    return true;
}

int VideoLibrary::removeFromPlaylist(const QString &id, const QString &path)
{
    Playlist *playlist = findPlaylist(id);
    const int at = playlist ? int(playlist->keys.indexOf(keyFor(path))) : -1;
    if (at < 0)
        return -1;
    playlist->keys.removeAt(at);
    playlistsEdited();
    return at;
}

void VideoLibrary::moveInPlaylist(const QString &id, const QString &path, const QString &before)
{
    Playlist *playlist = findPlaylist(id);
    const QString key = keyFor(path);
    if (!playlist || !playlist->keys.removeOne(key))
        return;
    const qsizetype at = before.isEmpty() ? -1 : playlist->keys.indexOf(keyFor(before));
    playlist->keys.insert(at >= 0 ? at : playlist->keys.size(), key);
    playlistsEdited();
}

QStringList VideoLibrary::playlistsWith(const QString &path) const
{
    const QString key = keyFor(path);
    QStringList ids;
    for (const Playlist &playlist : m_playlists)
        if (playlist.keys.contains(key))
            ids.append(playlist.id);
    return ids;
}

VideoModel *VideoLibrary::playlistVideos(const QString &id)
{
    auto *model = new VideoModel(this, [this, id] {
        const Playlist *playlist = findPlaylist(id);
        return playlist ? playlistIndexes(*playlist) : QList<int>();
    });
    m_playlistVideoModels.removeAll(nullptr);
    m_playlistVideoModels.append(model);
    return model;   // owned by QML (no parent)
}

QStringList VideoLibrary::playlistPaths(const QString &id) const
{
    QStringList paths;
    if (const Playlist *playlist = findPlaylist(id))
        for (const int index : playlistIndexes(*playlist))
            paths.append(m_videos[index].path);
    return paths;
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
    // On the computer's own drives, where it is in the user's folder ("Season 2 · Videos"),
    // not "Season 2 · Users".
    static const QString home = QDir::homePath();
    QString root = video.root;
    QString rootName = sourceName(video.root);
    if (!video.external && video.dir.startsWith(withSlash(home), Qt::CaseInsensitive)) {
        root = home;
        rootName = QDir(home).dirName();
    }
    const QString relative = video.dir.size() <= root.size() ? QString() : video.dir.mid(withSlash(root).size());
    return folderLabel(relative, rootName);
}

QString VideoLibrary::titleOf(const QString &path) const
{
    const int index = m_byPath.value(path, -1);
    return index >= 0 ? m_videos[index].title : prettyTitle(QFileInfo(path).completeBaseName());
}

QVariantMap VideoLibrary::detailsOf(const QString &path) const
{
    const int index = m_byPath.value(path, -1);
    const QLocale locale = QLocale::system();
    if (index >= 0 && m_videos[index].phone) {
        // Not a file this computer can look at: what the phone said about it.
        const VideoInfo &v = m_videos[index];
        return {
            {"title", v.title},
            {"inLibrary", true},
            {"external", true},
            {"phone", true},
            {"watched", v.finished},
            {"inHistory", v.watchedAt > 0},
            {"folder", v.dir},
            {"folderLabel", locationOf(v)},
            {"phoneFolder", (QStringList{sourceName(v.root)}
                             + v.dir.mid(v.root.size()).split(QLatin1Char('/'), Qt::SkipEmptyParts))
                                .join(QStringLiteral(" › "))},
            {"fileName", v.path.section(QLatin1Char('/'), -1)},
            {"sizeText", locale.formattedDataSize(v.size, 1, QLocale::DataSizeTraditionalFormat)},
            {"addedText", QString()},
            {"modifiedText", formatDateTime(v.modified)},
        };
    }
    const QFileInfo file(path);
    const QDateTime added = index >= 0 ? m_videos[index].added : file.birthTime();

    return {
        {"title", titleOf(path)},
        {"inLibrary", index >= 0},
        {"canManage", canManage(path)},
        {"watched", index >= 0 && m_videos[index].finished},
        {"inHistory", index >= 0 && m_videos[index].watchedAt > 0},
        {"external", index >= 0 && m_videos[index].external},
        {"folder", file.absolutePath()},
        {"folderLabel", index >= 0 ? locationOf(m_videos[index]) : QDir::toNativeSeparators(file.absolutePath())},
        {"fileName", file.fileName()},
        {"sizeText", locale.formattedDataSize(file.size(), 1, QLocale::DataSizeTraditionalFormat)},
        {"addedText", formatDateTime(added)},
        // Kept when the file is copied (unlike its creation date), so close to when it was made.
        {"modifiedText", formatDateTime(file.lastModified())},
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
    static const QStringList patterns = VideoFormats::subtitlePatterns();
    if (isPhone(path))
        return {};   // not looked for on phones (yet)
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

QString VideoLibrary::videoNameFilter() const
{
    return VideoFormats::nameFilter();
}

QString VideoLibrary::subtitleNameFilter() const
{
    return VideoFormats::subtitlePatterns().join(QLatin1Char(' '));
}

bool VideoLibrary::isSubtitleFile(const QString &path) const
{
    return VideoFormats::isSubtitleSuffix(QFileInfo(path).suffix());
}

// --- Managing files -----------------------------------------------------------------

namespace {

// Subtitle files that go with a video because they're named after it: "Movie.srt",
// "Movie.en.srt" for "Movie.mkv". They're renamed and moved along with it. One named
// after another video there with a longer name ("Movie.Extended.srt", when there's a
// "Movie.Extended.mkv") is that one's. A VobSub's .idx goes with its .sub.
QStringList sidecarSubtitles(const QFileInfo &video)
{
    const QString base = video.completeBaseName();
    const auto namedAfter = [](const QString &name, const QString &videoBase) {
        return name.compare(videoBase, Qt::CaseInsensitive) == 0
            || name.startsWith(videoBase + QLatin1Char('.'), Qt::CaseInsensitive);
    };
    const QFileInfoList files = video.dir().entryInfoList(QDir::Files);
    QStringList otherVideos;   // their base names, longer than this one's
    for (const QFileInfo &file : files)
        if (file.completeBaseName().size() > base.size() && VideoFormats::isVideoSuffix(file.suffix()))
            otherVideos.append(file.completeBaseName());

    QStringList found;
    for (const QFileInfo &file : files) {
        const QString suffix = file.suffix();
        if (!VideoFormats::isSubtitleSuffix(suffix) && suffix.compare(QLatin1String("idx"), Qt::CaseInsensitive) != 0)
            continue;
        const QString name = file.completeBaseName();
        if (!namedAfter(name, base))
            continue;
        if (std::any_of(otherVideos.cbegin(), otherVideos.cend(), [&](const QString &other) { return namedAfter(name, other); }))
            continue;
        found.append(file.absoluteFilePath());
    }
    return found;
}

// What a sidecar is called once its video is `newBase` in `newDir`: "Movie.en.srt" for
// "Movie.mkv" becomes "Film.en.srt" for "Film.mkv".
QString sidecarTarget(const QString &sidecar, const QString &oldBase, const QString &newDir, const QString &newBase)
{
    const QString name = QFileInfo(sidecar).fileName();
    return newDir + QLatin1Char('/') + newBase + name.mid(oldBase.size());
}

} // namespace

bool VideoLibrary::canManage(const QString &path) const
{
    return !path.isEmpty() && !isPhone(path) && QFileInfo(path).isFile();
}

QVariantMap VideoLibrary::renameVideo(const QString &path, const QString &name)
{
    if (!canManage(path))
        return {{"error", tr("This video can't be renamed here.")}};
    const QFileInfo old(path);
    QString wanted = name.trimmed();
    static const QRegularExpression invalid(QStringLiteral(R"([<>:"/\\|?*\x00-\x1F])"));
    if (wanted.isEmpty() || wanted == QLatin1String(".") || wanted == QLatin1String(".."))
        return {{"error", tr("Type a name for the file.")}};
    if (wanted.contains(invalid))
        return {{"error", tr("A file name can't contain any of these: < > : \" / \\ | ? *")}};
    // The extension stays unless another video extension was typed.
    if (!VideoFormats::isVideoSuffix(QFileInfo(wanted).suffix()))
        wanted += QLatin1Char('.') + old.suffix();
    const QString target = old.absolutePath() + QLatin1Char('/') + wanted;
    if (target == old.absoluteFilePath())
        return {{"path", path}};
    // A change of case only ("movie.mp4" to "Movie.mp4") is the same file on Windows.
    if (QFileInfo::exists(target) && target.compare(old.absoluteFilePath(), Qt::CaseInsensitive) != 0)
        return {{"error", tr("There's already a file called %1 in this folder.").arg(wanted)}};

    const QStringList sidecars = sidecarSubtitles(old);
    if (!QFile::rename(old.absoluteFilePath(), target))
        return {{"error", tr("Couldn't rename it. Is it open in another program?")}};
    const QString newBase = QFileInfo(target).completeBaseName();
    for (const QString &sidecar : sidecars)
        QFile::rename(sidecar, sidecarTarget(sidecar, old.completeBaseName(), old.absolutePath(), newBase));
    relocate(path, target);
    return {{"path", target}};
}

void VideoLibrary::moveVideo(const QString &path, const QString &folder)
{
    if (!canManage(path)) {
        emit videoMoved(path, QString(), tr("This video can't be moved here."));
        return;
    }
    const QFileInfo old(path);
    const QString target = QDir::cleanPath(folder) + QLatin1Char('/') + old.fileName();
    if (QFileInfo(target).absoluteFilePath() == old.absoluteFilePath()) {
        emit videoMoved(path, path, QString());
        return;
    }
    if (QFileInfo::exists(target)) {
        emit videoMoved(path, QString(), tr("There's already a file called %1 there.").arg(old.fileName()));
        return;
    }
    // To another drive it's copied, which can take a minute for a film: in the background.
    const QStringList sidecars = sidecarSubtitles(old);
    auto *watcher = new QFutureWatcher<bool>(this);
    connect(watcher, &QFutureWatcherBase::finished, this, [this, watcher, path, target] {
        watcher->deleteLater();
        if (!watcher->result()) {
            emit videoMoved(path, QString(), tr("Couldn't move it. Is it open in another program?"));
            return;
        }
        relocate(path, target);
        emit videoMoved(path, target, QString());
    });
    watcher->setFuture(QtConcurrent::run([old, target, sidecars] {
        if (!QFile::rename(old.absoluteFilePath(), target))
            return false;
        const QString dir = QFileInfo(target).absolutePath();
        for (const QString &sidecar : sidecars)
            QFile::rename(sidecar, sidecarTarget(sidecar, old.completeBaseName(), dir, old.completeBaseName()));
        return true;
    }));
}

QString VideoLibrary::deleteVideo(const QString &path)
{
    if (!canManage(path))
        return tr("This video can't be deleted here.");
    const int index = m_byPath.value(path, -1);
    if (!QFile::moveToTrash(path))
        return tr("Couldn't move it to the Recycle Bin. Is it open in another program?");
    if (index >= 0) {
        const VideoInfo v = m_videos[index];
        forgetScanned(path);
        m_history.remove(v.key);
        // It leaves its playlists too.
        bool inPlaylists = false;
        for (Playlist &playlist : m_playlists)
            inPlaylists |= playlist.keys.removeAll(v.key) > 0;
        if (inPlaylists) {
            m_playlistsChanged = true;
            ++m_playlistRevision;
            emit playlistsChanged();
        }
        scheduleSave();
        rebuild();
    }
    return {};
}

void VideoLibrary::forgetScanned(const QString &path)
{
    for (auto it = m_scanned.begin(); it != m_scanned.end(); ++it)
        if (it->removeIf([&](const VideoInfo &entry) { return entry.path == path; }) > 0)
            scanListChanged(it.key());
}

void VideoLibrary::scanListChanged(const QString &source)
{
    const int index = sourceIndex(source);
    if (index < 0 || m_sources[index].external)
        return;   // drives and phones aren't kept this way
    m_unsavedScans.insert(source);
    if (!m_scanSaveTimer.isActive())
        m_scanSaveTimer.start();   // not again: at most every half a minute while it keeps changing
}

void VideoLibrary::saveScanLists()
{
    m_scanSaveTimer.stop();
    for (const QString &path : std::exchange(m_unsavedScans, {})) {
        const int index = sourceIndex(path);
        if (index < 0 || m_sources[index].external)
            continue;
        // The list is shared with the copy written out, not copied, until it next changes.
        m_scanSaver.start([dataDir = m_dataDir, path, driveId = m_sources[index].driveId,
                           videos = m_scanned.value(path)] { saveScanList(dataDir, path, driveId, videos); });
    }
}

void VideoLibrary::relocate(const QString &oldPath, const QString &newPath)
{
    const int index = m_byPath.value(oldPath, -1);
    if (index < 0)
        return;   // not in the library: nothing to update
    const VideoInfo old = m_videos[index];
    forgetScanned(oldPath);

    // Where it is now: one of the library's sources (first, as in rebuild()) or a drive, if any.
    QString newKey = newPath;
    QString newCacheKey;
    for (const VideoSource &source : std::as_const(m_sources)) {
        if (source.phone || !newPath.startsWith(withSlash(source.path)))
            continue;
        const QFileInfo file(newPath);
        const VideoInfo moved = fileVideo(file, source.path, source.external, source.driveId,
                                          placeKeyOf(file.absolutePath(), source.path, sourceName(source.path)));
        newKey = moved.key;
        newCacheKey = moved.cacheKey;
        QList<VideoInfo> &list = m_scanned[source.path];
        list.append(moved);
        list = sortedByPath(std::move(list));
        scanListChanged(source.path);
        break;
    }

    // Its history (progress, subtitle and audio choices) goes with it.
    if (newKey != old.key && m_history.contains(old.key)) {
        QJsonObject entry = m_history.take(old.key).toObject();
        // A subtitle file named after it was renamed or moved with it.
        const QString subtitle = entry.value(QStringLiteral("subtitle")).toString();
        if (!subtitle.isEmpty() && !QFileInfo::exists(subtitle)) {
            const QFileInfo was(old.path), now(newPath);
            const QString followed = sidecarTarget(subtitle, was.completeBaseName(), now.absolutePath(), now.completeBaseName());
            if (QFileInfo::exists(followed))
                entry.insert(QStringLiteral("subtitle"), followed);
        }
        m_history.insert(newKey, entry);
    }
    // Its places in playlists.
    if (newKey != old.key) {
        for (Playlist &playlist : m_playlists) {
            const qsizetype at = playlist.keys.indexOf(old.key);
            if (at >= 0) {
                // Moved over a copy that's in the playlist already: just the one is kept.
                if (playlist.keys.contains(newKey))
                    playlist.keys.removeAt(at);
                else
                    playlist.keys[at] = newKey;
                m_playlistsChanged = true;
            }
        }
    }
    // And its thumbnail and details, so they needn't be made again.
    if (!newCacheKey.isEmpty() && newCacheKey != old.cacheKey) {
        if (m_metadata.contains(old.cacheKey)) {
            m_metadata.insert(newCacheKey, m_metadata.take(old.cacheKey));
            m_metadataChanged = true;
        }
        if (m_thumbFiles.remove(old.cacheKey)) {
            VideoInfo probe;
            probe.cacheKey = old.cacheKey;
            const QString from = thumbnailFile(probe);
            probe.cacheKey = newCacheKey;
            if (QFile::rename(from, thumbnailFile(probe)))
                m_thumbFiles.insert(newCacheKey);
        }
    }
    scheduleSave();
    rebuild();
}

// --- Watch history ----------------------------------------------------------------------

void VideoLibrary::editHistory(const QString &path, const std::function<void(QJsonObject &)> &change)
{
    const QString key = keyFor(path);
    QJsonObject entry = m_history.value(key).toObject();
    change(entry);
    if (entry.isEmpty())
        m_history.remove(key);
    else
        m_history.insert(key, entry);
    scheduleSave();

    const int index = m_byPath.value(path, -1);
    if (index < 0)
        return;
    VideoInfo &v = m_videos[index];
    v.position = entry.value(QStringLiteral("position")).toDouble();
    v.watchedAt = entry.value(QStringLiteral("watchedAt")).toInteger();
    v.finished = entry.value(QStringLiteral("finished")).toBool();
    emit videoChanged(index);
    refreshContinueWatching();
}

void VideoLibrary::setWatched(const QString &path, bool watched)
{
    const int index = m_byPath.value(path, -1);
    const double duration = index >= 0 ? m_videos[index].duration : 0;
    editHistory(path, [&](QJsonObject &entry) {
        if (watched) {
            entry.insert(QStringLiteral("position"), 0);
            entry.insert(QStringLiteral("finished"), true);
            entry.insert(QStringLiteral("watchedAt"), QDateTime::currentMSecsSinceEpoch());
            if (duration > 0)
                entry.insert(QStringLiteral("duration"), duration);
        } else {
            for (const char *field : {"position", "finished", "watchedAt", "duration"})
                entry.remove(QLatin1String(field));
        }
    });
}

void VideoLibrary::clearHistory()
{
    for (auto it = m_history.begin(); it != m_history.end();) {
        QJsonObject entry = it.value().toObject();
        for (const char *field : {"position", "finished", "watchedAt", "duration"})
            entry.remove(QLatin1String(field));
        if (entry.isEmpty()) {
            it = m_history.erase(it);
        } else {
            *it = entry;
            ++it;
        }
    }
    scheduleSave();
    for (int i = 0; i < m_videos.size(); ++i) {
        VideoInfo &v = m_videos[i];
        if (v.watchedAt == 0)
            continue;
        v.position = 0;
        v.watchedAt = 0;
        v.finished = false;
        emit videoChanged(i);
    }
    refreshContinueWatching();
}

QVariantMap VideoLibrary::historyEntry(const QString &path) const
{
    const QJsonObject entry = m_history.value(keyFor(path)).toObject();
    QVariantMap saved;
    for (const char *field : {"position", "finished", "watchedAt", "duration"}) {
        const QString name = QLatin1String(field);
        if (entry.contains(name))
            saved.insert(name, entry.value(name).toVariant());
    }
    return saved;
}

void VideoLibrary::restoreHistory(const QString &path, const QVariantMap &saved)
{
    editHistory(path, [&](QJsonObject &entry) {
        for (const char *field : {"position", "finished", "watchedAt", "duration"})
            entry.remove(QLatin1String(field));
        for (auto it = saved.begin(); it != saved.end(); ++it)
            entry.insert(it.key(), QJsonValue::fromVariant(it.value()));
    });
}

int VideoLibrary::audioChoice(const QString &path) const
{
    return m_history.value(keyFor(path)).toObject().value(QStringLiteral("audio")).toInt();
}

void VideoLibrary::setAudioChoice(const QString &path, int track)
{
    if (path.isEmpty())
        return;
    const QString key = keyFor(path);
    QJsonObject entry = m_history.value(key).toObject();
    entry.insert(QStringLiteral("audio"), track);
    m_history.insert(key, entry);
    scheduleSave();
}

void VideoLibrary::showInFileManager(const QString &path)
{
    if (isPhone(path)) {
        const int index = m_byPath.value(path, -1);
        if (index < 0)
            return;
        const VideoInfo &v = m_videos[index];
        const QString serial = v.root.mid(6);   // the root is "mtp://<serial>"
        const QString inside = v.path.mid(v.root.size() + 1);
        auto *watcher = new QFutureWatcher<bool>(this);
        connect(watcher, &QFutureWatcherBase::finished, this, [this, watcher, path] {
            watcher->deleteLater();
            emit phoneFileShown(path, watcher->result());
        });
        watcher->setFuture(QtConcurrent::run(&Phones::showInExplorer, serial, v.objectId, inside));
        return;
    }
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

    const QJsonArray playlists = readJson(QStringLiteral("playlists.json")).value(QLatin1String("playlists")).toArray();
    for (const QJsonValue &value : playlists) {
        const QJsonObject entry = value.toObject();
        Playlist playlist;
        playlist.id = entry.value(QLatin1String("id")).toString();
        playlist.name = entry.value(QLatin1String("name")).toString();
        playlist.created = entry.value(QLatin1String("created")).toInteger();
        playlist.updated = entry.value(QLatin1String("updated")).toInteger();
        for (const QJsonValue &key : entry.value(QLatin1String("videos")).toArray())
            playlist.keys.append(key.toString());
        if (!playlist.id.isEmpty())
            m_playlists.append(playlist);
    }

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
    if (std::exchange(m_playlistsChanged, false)) {
        QJsonArray playlists;
        for (const Playlist &playlist : std::as_const(m_playlists))
            playlists.append(QJsonObject{{"id", playlist.id}, {"name", playlist.name},
                                         {"created", playlist.created}, {"updated", playlist.updated},
                                         {"videos", QJsonArray::fromStringList(playlist.keys)}});
        writeJson(QStringLiteral("playlists.json"), QJsonObject{{"playlists", playlists}});
    }
}
