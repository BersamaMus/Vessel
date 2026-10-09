#pragma once

#include <QAbstractListModel>
#include <QPointer>
#include <QtQml/qqmlregistration.h>

#include <functional>

class VideoLibrary;

// A list of library videos (a shelf, or one folder's videos).
class VideoModel : public QAbstractListModel
{
    Q_OBJECT
    QML_ELEMENT
    QML_UNCREATABLE("Get these from VideoLibrary")

    Q_PROPERTY(int count READ rowCount NOTIFY countChanged)

public:
    enum Role {
        PathRole = Qt::UserRole + 1,
        TitleRole,
        SubtitleRole,
        ThumbnailRole,
        DurationRole,
        DurationTextRole,
        ProgressRole,
        RemainingTextRole,
        FinishedRole,
    };

    using Source = std::function<QList<int>()>;

    VideoModel(VideoLibrary *library, Source source, QObject *parent = nullptr);

    int rowCount(const QModelIndex &parent = {}) const override;
    QVariant data(const QModelIndex &index, int role) const override;
    QHash<int, QByteArray> roleNames() const override;

    Q_INVOKABLE QString pathAt(int row) const;

    // Re-reads the list from the library.
    void refresh();

signals:
    void countChanged();

private:
    void onVideoChanged(int libraryIndex);

    QPointer<VideoLibrary> m_library;
    Source m_source;
    QList<int> m_rows;   // indexes into the library
};

// Sub-folders of a library folder, each with a cover thumbnail.
class FolderModel : public QAbstractListModel
{
    Q_OBJECT
    QML_ELEMENT
    QML_UNCREATABLE("Get these from VideoLibrary")

    Q_PROPERTY(int count READ rowCount NOTIFY countChanged)

public:
    enum Role {
        NameRole = Qt::UserRole + 1,
        FolderRole,
        CountRole,
        CountTextRole,
        ThumbnailRole,
    };

    FolderModel(VideoLibrary *library, const QString &parentFolder, QObject *parent = nullptr);

    int rowCount(const QModelIndex &parent = {}) const override;
    QVariant data(const QModelIndex &index, int role) const override;
    QHash<int, QByteArray> roleNames() const override;

    void refresh();

signals:
    void countChanged();

private:
    struct Entry
    {
        QString name;
        QString folder;
        int count = 0;
        int cover = -1;   // library index of the video used as the cover
    };

    QPointer<VideoLibrary> m_library;
    QString m_parentFolder;
    QList<Entry> m_entries;
};

QString formatDuration(double seconds);
