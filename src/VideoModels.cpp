#include "VideoModels.h"

#include "VideoLibrary.h"

#include <QCollator>
#include <QMap>
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

} // namespace

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
    , m_library(library)
    , m_source(std::move(source))
{
    connect(library, &VideoLibrary::videoChanged, this, &VideoModel::onVideoChanged);
    refresh();
}

void VideoModel::refresh()
{
    if (!m_library)
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
        return folderLabel(v.folder, m_library->folderName(QString()));
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
// FolderModel
// ---------------------------------------------------------------------------

FolderModel::FolderModel(VideoLibrary *library, const QString &parentFolder, QObject *parent)
    : QAbstractListModel(parent)
    , m_library(library)
    , m_parentFolder(parentFolder)
{
    // Covers fill in as thumbnails are made.
    connect(library, &VideoLibrary::videoChanged, this, [this] {
        if (!m_entries.isEmpty())
            emit dataChanged(index(0), index(m_entries.size() - 1), {ThumbnailRole});
    });
    refresh();
}

void FolderModel::refresh()
{
    if (!m_library)
        return;

    const QString prefix = m_parentFolder.isEmpty() ? QString() : m_parentFolder + QLatin1Char('/');
    const auto isExtra = [](const VideoInfo &v) {
        return v.folder.contains(QLatin1String("extra"), Qt::CaseInsensitive)
            || v.path.contains(QLatin1String("sample"), Qt::CaseInsensitive);
    };
    QMap<QString, Entry> byName;
    for (int i = 0; i < m_library->count(); ++i) {
        const VideoInfo &v = m_library->video(i);
        if (!v.folder.startsWith(prefix) || v.folder.size() == prefix.size())
            continue;   // outside this folder, or directly in it
        const QString name = v.folder.mid(prefix.size()).section(QLatin1Char('/'), 0, 0);
        Entry &entry = byName[name];
        entry.name = name;
        entry.folder = prefix + name;
        ++entry.count;
        // The first video in natural order, but not an extra or a sample if possible.
        if (entry.cover < 0 || (isExtra(m_library->video(entry.cover)) && !isExtra(v)))
            entry.cover = i;
    }

    QList<Entry> entries = byName.values();
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
    };
}
