#pragma once

#include <QAbstractListModel>
#include <QDateTime>
#include <QPointer>
#include <QQmlParserStatus>
#include <QStringList>
#include <QtQml/qqmlregistration.h>

#include <functional>

class VideoLibrary;
struct VideoInfo;

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
        DetailRole,   // what the list is sorted by, where it isn't shown already ("1994", "1.2 GB")
        ThumbnailPendingRole,   // the thumbnail is still to be made
    };

    using Source = std::function<QList<int>()>;

    VideoModel(VideoLibrary *library, Source source, QObject *parent = nullptr);

    int rowCount(const QModelIndex &parent = {}) const override;
    QVariant data(const QModelIndex &index, int role) const override;
    QHash<int, QByteArray> roleNames() const override;

    Q_INVOKABLE QString pathAt(int row) const;
    Q_INVOKABLE int indexOf(const QString &path) const;

    // Re-reads the list from the library.
    void refresh();

signals:
    void countChanged();

protected:
    // For a subclass that connects to the library later, with attach().
    explicit VideoModel(QObject *parent = nullptr);
    void attach(VideoLibrary *library, Source source);
    VideoLibrary *library() const { return m_library; }
    virtual QString detailOf(const VideoInfo &) const { return {}; }

private:
    void onVideoChanged(int libraryIndex);

    QPointer<VideoLibrary> m_library;
    Source m_source;
    QList<int> m_rows;          // indexes into the library
    QHash<int, int> m_rowOf;    // and back, so one video's change is found at once
};

// The videos in a folder and its sub-folders (or the library, or every drive),
// narrowed down by search text and filters, in the chosen order. Behind the
// Library's sort and filter bar, and Search:
//
//     VideoQuery { folder: page.folder; sort: "year"; length: "long" }
class VideoQuery : public VideoModel, public QQmlParserStatus
{
    Q_OBJECT
    QML_ELEMENT
    Q_INTERFACES(QQmlParserStatus)

    // An absolute folder, or "" for the library as a whole.
    Q_PROPERTY(QString folder READ folder WRITE setFolder NOTIFY queryChanged)
    // With folder "": "library" (the default), "drives" (every connected drive) or "phones".
    Q_PROPERTY(QString source READ source WRITE setSource NOTIFY queryChanged)
    // Matched against titles, then folder names; "" matches everything.
    Q_PROPERTY(QString text READ text WRITE setText NOTIFY queryChanged)
    // "relevance" (best search match first), "name", "added", "watched", "year", "duration" or "size".
    Q_PROPERTY(QString sort READ sort WRITE setSort NOTIFY queryChanged)
    // Filters; "" for any. length: "short" (under 5 min), "medium" (5-30), "long" (30-90),
    // "feature" (90+). progress: "unwatched", "started", "watched". type: a file
    // extension ("mp4"). resolution: "4k", "1440", "1080", "720", "sd".
    Q_PROPERTY(QString length READ length WRITE setLength NOTIFY queryChanged)
    Q_PROPERTY(QString progress READ progress WRITE setProgress NOTIFY queryChanged)
    Q_PROPERTY(QString type READ type WRITE setType NOTIFY queryChanged)
    Q_PROPERTY(QString resolution READ resolution WRITE setResolution NOTIFY queryChanged)
    // The file types in the folder (whatever the filters), for the type filter: "avi", "mp4".
    Q_PROPERTY(QStringList types READ types NOTIFY typesChanged)

public:
    explicit VideoQuery(QObject *parent = nullptr);

    QString folder() const { return m_folder; }
    QString source() const { return m_source; }
    QString text() const { return m_text; }
    QString sort() const { return m_sort; }
    QString length() const { return m_length; }
    QString progress() const { return m_progress; }
    QString type() const { return m_type; }
    QString resolution() const { return m_resolution; }
    QStringList types() const { return m_types; }

    void setFolder(const QString &folder) { set(m_folder, folder); }
    void setSource(const QString &source) { set(m_source, source); }
    void setText(const QString &text) { set(m_text, text); }
    void setSort(const QString &sort);
    void setLength(const QString &length) { set(m_length, length); }
    void setProgress(const QString &progress) { set(m_progress, progress); }
    void setType(const QString &type) { set(m_type, type); }
    void setResolution(const QString &resolution) { set(m_resolution, resolution); }

    void classBegin() override {}
    void componentComplete() override;

signals:
    void queryChanged();
    void typesChanged();

protected:
    QString detailOf(const VideoInfo &video) const override;

private:
    void set(QString &member, const QString &value);
    void update();
    QList<int> find();

    bool m_complete = false;
    QString m_folder;
    QString m_source = QStringLiteral("library");
    QString m_text;
    QString m_sort = QStringLiteral("name");
    QString m_length;
    QString m_progress;
    QString m_type;
    QString m_resolution;
    QStringList m_types;
};

// Folders, each with a cover thumbnail: the sub-folders of a folder (for "",
// the library folders themselves), or the top folders of every library folder.
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
        ThumbnailPendingRole,   // the cover is still to be made
    };

    enum Mode { Children, TopFolders };

    FolderModel(VideoLibrary *library, const QString &parentFolder, Mode mode, QObject *parent = nullptr);

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
    Mode m_mode = Children;
    QList<Entry> m_entries;
};

QString formatDuration(double seconds);
// "2 Jan 2022, 2:16 PM" in local time (the clock as the system shows it: 12 or 24 hours).
QString formatDateTime(const QDateTime &time);
// Text as search compares it, as Blimp does: lowercase, without accents ("é" is
// "e"), apostrophes dropped ("Don't" is "dont"), other punctuation as single
// spaces, and "&" as "and".
QString searchKey(const QString &text);
// "Season 2 · House MD…" for a folder relative to the library root.
QString folderLabel(const QString &folder, const QString &rootName);
