#pragma once

#include <QList>
#include <QObject>
#include <QTimer>

struct DriveInfo
{
    QString rootPath;   // "E:/"
    QString name;       // "SANDISK (E:)"
    QString id;         // stable across plug-ins and drive letter changes
    QString kind;       // "usb" or "card"; "internal" for the computer's own disks
    qint64 bytesTotal = 0;
    qint64 bytesFree = 0;
};

// Notices USB drives, external hard drives and memory cards being plugged in
// and removed. Polls cheap system calls rather than hooking device messages,
// which also catches cards put into a reader that stays connected. Also lists
// the computer's own disks (C: and any other built-in drive): the library.
class DriveWatcher : public QObject
{
    Q_OBJECT

public:
    explicit DriveWatcher(QObject *parent = nullptr);

    // Drives that get plugged in and out.
    QList<DriveInfo> drives() const { return m_drives; }
    // The computer's own disks, the system drive first.
    QList<DriveInfo> internalDrives() const { return m_internal; }

signals:
    void drivesChanged();

private:
    void poll();
    QString signature() const;
    void enumerate();

    QTimer m_timer;
    QString m_signature;
    QList<DriveInfo> m_drives;
    QList<DriveInfo> m_internal;
};
