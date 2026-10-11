#pragma once

#include <QHash>
#include <QObject>
#include <QStringList>

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

    // Watches these folders (absolute, '/'-separated) and stops watching any others.
    void setFolders(const QStringList &folders);

signals:
    // Something changed under `folder`, one of those being watched. Comes in bursts while
    // files are copied; wait a moment before looking.
    void changed(const QString &folder);
    // Files or folders under `folder` are gone (deleted, or renamed away), none of them a
    // video by its name: a change only if they're folders the library has videos in.
    void removed(const QString &folder, const QStringList &paths);

private:
    struct Watch;
    QHash<QString, std::shared_ptr<Watch>> m_watches;
};
