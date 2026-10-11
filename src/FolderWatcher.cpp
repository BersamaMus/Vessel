#include "FolderWatcher.h"

#include "VideoFormats.h"

#include <QCoreApplication>
#include <QDir>
#include <QFileInfo>
#include <QPointer>

#include <thread>

#ifdef Q_OS_WIN
#include <windows.h>

struct FolderWatcher::Watch
{
    HANDLE stop = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    std::thread thread;

    ~Watch()
    {
        SetEvent(stop);
        if (thread.joinable())
            thread.join();
        CloseHandle(stop);
    }
};

namespace {

bool isVideoName(const QString &relativePath)
{
    const QString name = relativePath.section(QLatin1Char('\\'), -1);
    const qsizetype dot = name.lastIndexOf(QLatin1Char('.'));
    return dot > 0 && VideoFormats::isVideoSuffix(name.mid(dot + 1));
}

constexpr int kMaxGone = 64;   // more removed at once than this: just look

} // namespace

FolderWatcher::FolderWatcher(QObject *parent)
    : QObject(parent)
{
}

FolderWatcher::~FolderWatcher() = default;   // each Watch stops its thread

void FolderWatcher::setFolders(const QStringList &folders)
{
    for (auto it = m_watches.begin(); it != m_watches.end();)
        it = folders.contains(it.key()) ? std::next(it) : m_watches.erase(it);

    for (const QString &folder : folders) {
        if (m_watches.contains(folder))
            continue;
        auto watch = std::make_shared<Watch>();
        const std::wstring path = QDir::toNativeSeparators(folder).toStdWString();
        const HANDLE stop = watch->stop;
        QPointer<FolderWatcher> self = this;
        watch->thread = std::thread([self, folder, path, stop] {
            // Shared with everyone, so the folder can still be renamed or deleted.
            HANDLE dir = CreateFileW(path.c_str(), FILE_LIST_DIRECTORY,
                                     FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr, OPEN_EXISTING,
                                     FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OVERLAPPED, nullptr);
            if (dir == INVALID_HANDLE_VALUE)
                return;
            OVERLAPPED overlapped{};
            overlapped.hEvent = CreateEventW(nullptr, TRUE, FALSE, nullptr);
            alignas(DWORD) char buffer[32 * 1024];
            const DWORD filter = FILE_NOTIFY_CHANGE_FILE_NAME | FILE_NOTIFY_CHANGE_DIR_NAME | FILE_NOTIFY_CHANGE_SIZE;
            while (true) {
                ResetEvent(overlapped.hEvent);
                if (!ReadDirectoryChangesW(dir, buffer, sizeof buffer, TRUE, filter, nullptr, &overlapped, nullptr))
                    break;
                const HANDLE waits[] = {overlapped.hEvent, stop};
                if (WaitForMultipleObjects(2, waits, FALSE, INFINITE) != WAIT_OBJECT_0) {
                    CancelIoEx(dir, &overlapped);
                    DWORD ignored = 0;
                    GetOverlappedResult(dir, &overlapped, &ignored, TRUE);
                    break;
                }
                DWORD bytes = 0;
                if (!GetOverlappedResult(dir, &overlapped, &bytes, FALSE))
                    break;
                // Worth a look: a video file changing, or a folder arriving (named or renamed
                // to). Whether something gone was a folder ("Show.S01" as much as "Show")
                // can't be told from here any more: the library knows what was in it.
                // No details (too many changes at once for the buffer): look anyway.
                bool relevant = bytes == 0;
                QStringList gone;
                for (DWORD offset = 0; bytes > 0 && !relevant;) {
                    const auto *info = reinterpret_cast<const FILE_NOTIFY_INFORMATION *>(buffer + offset);
                    const QString name = QString::fromWCharArray(info->FileName, int(info->FileNameLength / sizeof(WCHAR)));
                    if (isVideoName(name)) {
                        relevant = true;
                    } else if (info->Action == FILE_ACTION_ADDED || info->Action == FILE_ACTION_RENAMED_NEW_NAME) {
                        relevant = QFileInfo(folder + QLatin1Char('/') + QDir::fromNativeSeparators(name)).isDir();
                    } else if (info->Action == FILE_ACTION_REMOVED || info->Action == FILE_ACTION_RENAMED_OLD_NAME) {
                        gone.append(folder + QLatin1Char('/') + QDir::fromNativeSeparators(name));
                        relevant = gone.size() > kMaxGone;
                    }
                    if (info->NextEntryOffset == 0)
                        break;
                    offset += info->NextEntryOffset;
                }
                if (relevant)
                    gone.clear();
                if (relevant || !gone.isEmpty())
                    QMetaObject::invokeMethod(qApp, [self, folder, gone] {
                        if (!self)
                            return;
                        if (gone.isEmpty())
                            emit self->changed(folder);
                        else
                            emit self->removed(folder, gone);
                    }, Qt::QueuedConnection);
            }
            CloseHandle(overlapped.hEvent);
            CloseHandle(dir);
        });
        m_watches.insert(folder, watch);
    }
}

#else

struct FolderWatcher::Watch
{
};

FolderWatcher::FolderWatcher(QObject *parent)
    : QObject(parent)
{
}

FolderWatcher::~FolderWatcher() = default;

void FolderWatcher::setFolders(const QStringList &)
{
}

#endif
