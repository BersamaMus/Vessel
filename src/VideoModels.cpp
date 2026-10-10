#include "VideoModels.h"

#include "VideoLibrary.h"

#include <QCollator>
#include <QLocale>
#include <QMap>
#include <QQmlEngine>
#include <QUrl>

namespace {

QString remainingText(double position, double duration)
{
    const int minutes = qRound((duration - position) / 60.0);
    if (minutes < 1)
        return QObject::tr("Less than a minute left");
    if (minutes < 60)
        return QObject::tr("%1 min left").arg(minutes);
    return QObject::tr("%1 h %2 min left").arg(minutes / 60).arg(minutes % 60);
}

// The filter buckets a video falls in (see VideoQuery); "" when not known.
QString lengthOf(const VideoInfo &v)
{
    if (v.duration <= 0)
        return {};
    return v.duration < 5 * 60 ? QStringLiteral("short") : v.duration < 30 * 60 ? QStringLiteral("medium")
         : v.duration < 90 * 60 ? QStringLiteral("long") : QStringLiteral("feature");
}

QString progressOf(const VideoInfo &v)
{
    if (v.finished)
        return QStringLiteral("watched");
    return v.watchedAt > 0 && v.position >= kMinProgress ? QStringLiteral("started") : QStringLiteral("unwatched");
}

QString resolutionOf(const VideoInfo &v)
{
    // By width as well, so a widescreen film (1920 x 800) still counts as 1080p.
    const int w = v.width, h = v.height;
    if (w <= 0 || h <= 0)
        return {};
    return w >= 3200 || h >= 2000 ? QStringLiteral("4k") : w >= 2400 || h >= 1400 ? QStringLiteral("1440")
         : w >= 1800 || h >= 1000 ? QStringLiteral("1080") : w >= 1200 || h >= 700 ? QStringLiteral("720")
         : QStringLiteral("sd");
}

bool startsWord(const QString &key, const QString &text)
{
    return key.startsWith(text) || key.contains(QLatin1Char(' ') + text);
}

// How well a video matches search text (a searchKey), lowest first; -1 for no match.
// Best is the title starting with it, or one of its words doing so; then it anywhere
// in the title (ignoring spaces, so "spiderman" finds "Spider Man"); then each word
// somewhere in the title or its folders ("house season 2"), ranked by how many
// start a word of the title rather than only being in the folders.
int matchScore(const VideoInfo &v, const QString &query, const QString &compactQuery, const QStringList &words)
{
    if (startsWord(v.titleKey, query))
        return 0;
    if (v.titleKey.contains(query) || QString(v.titleKey).remove(QLatin1Char(' ')).contains(compactQuery))
        return 1;
    int points = 0;
    for (const QString &word : words) {
        if (startsWord(v.titleKey, word))
            points += 2;
        else if (v.titleKey.contains(word))
            points += 1;
        else if (!v.placeKey.contains(word))
            return -1;
    }
    return 1000 - points;
}

} // namespace

QString searchKey(const QString &text)
{
    // Compatibility decomposition splits "é" into "e" and an accent mark, which is dropped.
    const QString decomposed = QString(text).replace(QLatin1Char('&'), QLatin1String(" and "))
                                   .normalized(QString::NormalizationForm_KD).toLower();
    QString key;
    key.reserve(decomposed.size());
    bool space = true;   // no leading or doubled spaces
    for (const QChar c : decomposed) {
        if (c.category() == QChar::Mark_NonSpacing || c == QLatin1Char('\'') || c == QChar(0x2019))
            continue;
        if (c.isLetterOrNumber()) {
            key += c;
            space = false;
        } else if (!space) {
            key += QLatin1Char(' ');
            space = true;
        }
    }
    if (key.endsWith(QLatin1Char(' ')))
        key.chop(1);
    return key;
}

QString folderLabel(const QString &folder, const QString &rootName)
{
    if (folder.isEmpty())
        return rootName;
    // Nearest folder first ("Season 2 · House MD…"), as long names get cut off.
    const QStringList parts = folder.split(QLatin1Char('/'));
    return parts.size() == 1 ? parts.first() : parts.last() + QStringLiteral(" · ") + parts.first();
}

QString formatDuration(double seconds)
{
    if (seconds <= 0)
        return {};
    const int total = qRound(seconds);
    const int h = total / 3600, m = (total / 60) % 60, s = total % 60;
    return h > 0 ? QStringLiteral("%1:%2:%3").arg(h).arg(m, 2, 10, QLatin1Char('0')).arg(s, 2, 10, QLatin1Char('0'))
                 : QStringLiteral("%1:%2").arg(m).arg(s, 2, 10, QLatin1Char('0'));
}

// ---------------------------------------------------------------------------
// VideoModel
// ---------------------------------------------------------------------------

VideoModel::VideoModel(VideoLibrary *library, Source source, QObject *parent)
    : QAbstractListModel(parent)
{
    attach(library, std::move(source));
}

VideoModel::VideoModel(QObject *parent)
    : QAbstractListModel(parent)
{
}

void VideoModel::attach(VideoLibrary *library, Source source)
{
    m_library = library;
    m_source = std::move(source);
    connect(library, &VideoLibrary::videoChanged, this, &VideoModel::onVideoChanged);
    connect(library, &VideoLibrary::thumbnailsQueued, this, [this] {
        if (!m_rows.isEmpty())
            emit dataChanged(index(0), index(m_rows.size() - 1), {ThumbnailPendingRole});
    });
    refresh();
}

void VideoModel::refresh()
{
    if (!m_library || !m_source)
        return;
    QList<int> rows = m_source();
    if (rows == m_rows)
        return;
    const bool countChanges = rows.size() != m_rows.size();
    beginResetModel();
    m_rows = std::move(rows);
    endResetModel();
    if (countChanges)
        emit countChanged();
}

void VideoModel::onVideoChanged(int libraryIndex)
{
    for (int row = 0; row < m_rows.size(); ++row)
        if (m_rows[row] == libraryIndex)
            emit dataChanged(index(row), index(row));
}

int VideoModel::rowCount(const QModelIndex &parent) const
{
    return parent.isValid() ? 0 : m_rows.size();
}

QVariant VideoModel::data(const QModelIndex &index, int role) const
{
    if (!m_library || !index.isValid() || index.row() >= m_rows.size())
        return {};
    const int libraryIndex = m_rows[index.row()];
    const VideoInfo &v = m_library->video(libraryIndex);
    const bool started = v.watchedAt > 0 && v.duration > 0 && !v.finished;

    switch (role) {
    case PathRole:
        return v.path;
    case TitleRole:
        return v.title;
    case SubtitleRole:
        return m_library->locationOf(v);
    case ThumbnailRole:
        return m_library->thumbnailUrl(libraryIndex);
    case DurationRole:
        return v.duration;
    case DurationTextRole:
        return formatDuration(v.duration);
    case ProgressRole:
        return started ? qBound(0.0, v.position / v.duration, 1.0) : 0.0;
    case RemainingTextRole:
        return started ? remainingText(v.position, v.duration) : QString();
    case FinishedRole:
        return v.finished;
    case DetailRole:
        return detailOf(v);
    case ThumbnailPendingRole:
        return !v.hasThumbnail && m_library->thumbnailPending(v.path);
    default:
        return {};
    }
}

QHash<int, QByteArray> VideoModel::roleNames() const
{
    return {
        {PathRole, "path"},
        {TitleRole, "title"},
        {SubtitleRole, "subtitle"},
        {ThumbnailRole, "thumbnail"},
        {DurationRole, "duration"},
        {DurationTextRole, "durationText"},
        {ProgressRole, "progress"},
        {RemainingTextRole, "remainingText"},
        {FinishedRole, "finished"},
        {DetailRole, "detail"},
        {ThumbnailPendingRole, "thumbnailPending"},
    };
}

QString VideoModel::pathAt(int row) const
{
    if (!m_library || row < 0 || row >= m_rows.size())
        return {};
    return m_library->video(m_rows[row]).path;
}

int VideoModel::indexOf(const QString &path) const
{
    for (int row = 0; row < m_rows.size(); ++row)
        if (m_library && m_library->video(m_rows[row]).path == path)
            return row;
    return -1;
}

// ---------------------------------------------------------------------------
// VideoQuery
// ---------------------------------------------------------------------------

VideoQuery::VideoQuery(QObject *parent)
    : VideoModel(parent)
{
}

void VideoQuery::componentComplete()
{
    auto *library = qmlEngine(this)->singletonInstance<VideoLibrary *>("BetterVideoPlayer", "VideoLibrary");
    m_complete = true;
    attach(library, [this] { return find(); });
    library->track(this);
}

void VideoQuery::set(QString &member, const QString &value)
{
    if (member == value)
        return;
    member = value;
    emit queryChanged();
    update();
}

void VideoQuery::setEverywhere(bool everywhere)
{
    if (m_everywhere == everywhere)
        return;
    m_everywhere = everywhere;
    emit queryChanged();
    update();
}

void VideoQuery::setSort(const QString &sort)
{
    set(m_sort, sort);
    // The rows may be the same, but what they show under the title isn't.
    if (rowCount() > 0)
        emit dataChanged(index(0), index(rowCount() - 1), {DetailRole});
}

void VideoQuery::update()
{
    if (m_complete)
        refresh();
}

QList<int> VideoQuery::find()
{
    const VideoLibrary *lib = library();
    const QString prefix = m_folder.endsWith(QLatin1Char('/')) ? m_folder : m_folder + QLatin1Char('/');
    const QString query = searchKey(m_text);
    const QString compactQuery = QString(query).remove(QLatin1Char(' '));
    const QStringList words = query.split(QLatin1Char(' '), Qt::SkipEmptyParts);

    struct Hit
    {
        int index;
        int score;
    };
    QList<Hit> hits;
    QStringList types;
    for (int i = 0; i < lib->count(); ++i) {
        const VideoInfo &v = lib->video(i);
        const bool inScope = m_folder.isEmpty() ? m_everywhere || !v.external
                                                : v.dir == m_folder || v.dir.startsWith(prefix);
        if (!inScope)
            continue;
        if (!types.contains(v.type))
            types.append(v.type);
        if ((!m_type.isEmpty() && v.type != m_type)
            || (!m_length.isEmpty() && lengthOf(v) != m_length)
            || (!m_progress.isEmpty() && progressOf(v) != m_progress)
            || (!m_resolution.isEmpty() && resolutionOf(v) != m_resolution))
            continue;
        const int score = query.isEmpty() ? 0 : matchScore(v, query, compactQuery, words);
        if (score >= 0)
            hits.append({i, score});
    }

    types.sort();
    if (types != m_types) {
        m_types = types;
        emit typesChanged();
    }

    // Stable sorts, so ties stay in folder order.
    const auto by = [&](auto key) {
        std::stable_sort(hits.begin(), hits.end(), [&](const Hit &a, const Hit &b) {
            return key(lib->video(a.index)) > key(lib->video(b.index));
        });
    };
    if (m_sort == QLatin1String("name")) {
        QCollator collator;
        collator.setNumericMode(true);
        collator.setCaseSensitivity(Qt::CaseInsensitive);
        std::stable_sort(hits.begin(), hits.end(), [&](const Hit &a, const Hit &b) {
            return collator.compare(lib->video(a.index).title, lib->video(b.index).title) < 0;
        });
    } else if (m_sort == QLatin1String("added")) {
        by([](const VideoInfo &v) { return v.added; });
    } else if (m_sort == QLatin1String("watched")) {
        by([](const VideoInfo &v) { return v.watchedAt; });   // never watched (0) last
    } else if (m_sort == QLatin1String("year")) {
        by([](const VideoInfo &v) { return v.year; });        // unknown (0) last
    } else if (m_sort == QLatin1String("duration")) {
        by([](const VideoInfo &v) { return v.duration; });
    } else if (m_sort == QLatin1String("size")) {
        by([](const VideoInfo &v) { return v.size; });
    } else {   // relevance
        std::stable_sort(hits.begin(), hits.end(), [](const Hit &a, const Hit &b) { return a.score < b.score; });
    }

    QList<int> rows;
    rows.reserve(hits.size());
    for (const Hit &hit : std::as_const(hits))
        rows.append(hit.index);
    return rows;
}

QString VideoQuery::detailOf(const VideoInfo &v) const
{
    const QLocale locale = QLocale::system();
    if (m_sort == QLatin1String("year"))
        return v.year > 0 ? QString::number(v.year) : QString();
    if (m_sort == QLatin1String("added"))
        return tr("Added %1").arg(locale.toString(v.added.date(), QStringLiteral("d MMM yyyy")));
    if (m_sort == QLatin1String("size"))
        return locale.formattedDataSize(v.size, 1, QLocale::DataSizeTraditionalFormat);
    if (m_sort == QLatin1String("watched") && v.watchedAt > 0) {
        const QDate day = QDateTime::fromMSecsSinceEpoch(v.watchedAt).date();
        const qint64 daysAgo = day.daysTo(QDate::currentDate());
        return daysAgo <= 0 ? tr("Watched today")
             : daysAgo == 1 ? tr("Watched yesterday")
             : daysAgo < 7 ? tr("Watched %1 days ago").arg(daysAgo)
             : tr("Watched %1").arg(locale.toString(day, QStringLiteral("d MMM yyyy")));
    }
    return {};
}

// ---------------------------------------------------------------------------
// FolderModel
// ---------------------------------------------------------------------------

FolderModel::FolderModel(VideoLibrary *library, const QString &parentFolder, Mode mode, QObject *parent)
    : QAbstractListModel(parent)
    , m_library(library)
    , m_parentFolder(parentFolder)
    , m_mode(mode)
{
    // Covers fill in as thumbnails are made.
    const auto coversChanged = [this] {
        if (!m_entries.isEmpty())
            emit dataChanged(index(0), index(m_entries.size() - 1), {ThumbnailRole, ThumbnailPendingRole});
    };
    connect(library, &VideoLibrary::videoChanged, this, coversChanged);
    connect(library, &VideoLibrary::thumbnailsQueued, this, coversChanged);
    refresh();
}

void FolderModel::refresh()
{
    if (!m_library)
        return;

    // Extras and samples make poor covers; judged by the path inside the folder.
    const auto isExtra = [](const VideoInfo &v, const QString &folder) {
        const QString inside = v.path.mid(folder.size());
        return inside.contains(QLatin1String("extra"), Qt::CaseInsensitive)
            || inside.contains(QLatin1String("sample"), Qt::CaseInsensitive);
    };
    QMap<QString, Entry> byFolder;
    const auto add = [&](const QString &folder, const QString &name, int index) {
        Entry &entry = byFolder[folder];
        entry.name = name;
        entry.folder = folder;
        ++entry.count;
        // The first video in natural order, but not an extra or a sample if possible.
        if (entry.cover < 0 || (isExtra(m_library->video(entry.cover), folder) && !isExtra(m_library->video(index), folder)))
            entry.cover = index;
    };

    if (m_mode == Children && m_parentFolder.isEmpty()) {
        // The library as a whole: one entry per library folder.
        for (int i = 0; i < m_library->count(); ++i) {
            const VideoInfo &v = m_library->video(i);
            if (!v.external)
                add(v.root, m_library->folderName(v.root), i);
        }
    } else {
        const QStringList parents = m_mode == TopFolders ? m_library->libraryRoots() : QStringList{m_parentFolder};
        for (const QString &parent : parents) {
            const QString prefix = parent.endsWith(QLatin1Char('/')) ? parent : parent + QLatin1Char('/');
            for (int i = 0; i < m_library->count(); ++i) {
                const VideoInfo &v = m_library->video(i);
                const QString name = v.dir.mid(prefix.size()).section(QLatin1Char('/'), 0, 0);
                if (!v.dir.startsWith(prefix) || name.isEmpty())
                    continue;   // outside this folder, or directly in it
                add(prefix + name, name, i);
            }
        }
    }

    QList<Entry> entries = byFolder.values();
    QCollator collator;
    collator.setNumericMode(true);
    collator.setCaseSensitivity(Qt::CaseInsensitive);
    std::sort(entries.begin(), entries.end(), [&](const Entry &a, const Entry &b) {
        return collator.compare(a.name, b.name) < 0;
    });

    const bool countChanges = entries.size() != m_entries.size();
    beginResetModel();
    m_entries = std::move(entries);
    endResetModel();
    if (countChanges)
        emit countChanged();
}

int FolderModel::rowCount(const QModelIndex &parent) const
{
    return parent.isValid() ? 0 : m_entries.size();
}

QVariant FolderModel::data(const QModelIndex &index, int role) const
{
    if (!m_library || !index.isValid() || index.row() >= m_entries.size())
        return {};
    const Entry &entry = m_entries[index.row()];

    switch (role) {
    case NameRole:
        return entry.name;
    case FolderRole:
        return entry.folder;
    case CountRole:
        return entry.count;
    case CountTextRole:
        return entry.count == 1 ? tr("1 video") : tr("%1 videos").arg(entry.count);
    case ThumbnailRole:
        return entry.cover >= 0 ? m_library->thumbnailUrl(entry.cover) : QUrl();
    case ThumbnailPendingRole: {
        if (entry.cover < 0)
            return false;
        const VideoInfo &cover = m_library->video(entry.cover);
        return !cover.hasThumbnail && m_library->thumbnailPending(cover.path);
    }
    default:
        return {};
    }
}

QHash<int, QByteArray> FolderModel::roleNames() const
{
    return {
        {NameRole, "name"},
        {FolderRole, "folder"},
        {CountRole, "count"},
        {CountTextRole, "countText"},
        {ThumbnailRole, "thumbnail"},
        {ThumbnailPendingRole, "thumbnailPending"},
    };
}
