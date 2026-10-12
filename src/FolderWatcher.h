#pragma once

#include <QHash>
#include <QObject>
#include <QStringList>

#include <functional>
#include <memory>

// Notices videos (or folders) being added, removed or renamed anywhere under some folders,
// so the library keeps up without a rescan. On Windows a folder's whole tree is watched
// through one directory handle (ReadDirectoryChangesW). Other files' changes (downloads in
// progress, thumbnails) are ignored. Elsewhere it does nothing (yet).
//
// The handle keeps a drive busy: folders on drives that get unplugged shouldn't be watched.
class FolderWatcher : public QObject
{
    Q_OBJECT

public:
    explicit FolderWatcher(QObject *parent = nullptr);
    ~FolderWatcher() override;

    // Changes to paths this says yes to are ignored (folders the library doesn't look in).
    // Called on the watching threads, with absolute '/'-separated paths. Set it first.
    void setIgnored(std::function<bool(const QString &path)> ignored) { m_ignored = std::move(ignored); }
    // Watches these folders (absolute, '/'-separated) and stops watching any others.
    void setFolders(const QStringList &folders);

signals:
    // Something changed under `folder`, one of those being watched: videos came, went or
    // changed in `dirs` (just their own files to look at again), and `trees` arrived (named
    // or renamed to; to look through, sub-folders and all). Comes in bursts while files are
    // copied; wait a moment before looking.
    void changed(const QString &folder, const QStringList &dirs, const QStringList &trees);
    // Files or folders under `folder` are gone (deleted, or renamed away), none of them a
    // video by its name: a change only if they're folders the library has videos in.
    void removed(const QString &folder, const QStringList &paths);

private:
    struct Watch;
    QHash<QString, std::shared_ptr<Watch>> m_watches;
    std::function<bool(const QString &)> m_ignored;
};
