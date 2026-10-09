#pragma once

#include <QList>
#include <QObject>
#include <QTimer>

struct DriveInfo
{
    QString rootPath;   // "E:/"
    QString name;       // "SANDISK (E:)"
    QString id;         // stable across plug-ins and drive letter changes
    QString kind;       // "usb" or "card"
    qint64 bytesTotal = 0;
    qint64 bytesFree = 0;
};

// Notices USB drives, external hard drives and memory cards being plugged in
// and removed. Polls cheap system calls rather than hooking device messages,
// which also catches cards put into a reader that stays connected.
class DriveWatcher : public QObject
{
    Q_OBJECT

public:
    explicit DriveWatcher(QObject *parent = nullptr);

    QList<DriveInfo> drives() const { return m_drives; }

signals:
    void drivesChanged();

private:
    void poll();
    QString signature() const;
    QList<DriveInfo> enumerate() const;

    QTimer m_timer;
    QString m_signature;
    QList<DriveInfo> m_drives;
};
