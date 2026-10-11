#pragma once

#include <QDateTime>
#include <QList>
#include <QString>

#include <functional>

struct mpv_handle;

// Phones connected by USB. They don't get a drive letter: Android phones speak MTP
// (iPhones PTP), which Windows exposes through Windows Portable Devices (WPD). This
// lists them, finds their videos, and reads any part of a file with MTP's
// GetPartialObject64, which lets libmpv play (and seek in) a video on the phone
// through the mtp:// stream protocol registered by registerStream().
//
// Everything here blocks on the phone: call it from worker threads, not the UI.
// Windows only; elsewhere there are no phones (yet).
namespace Phones {

struct Phone
{
    QString serial;       // identifies the phone, across connections
    QString name;         // "OPPO Find X9 Pro"
    // Its storage can be read: unlocked, and in File transfer mode on Android.
    // Otherwise it shows up with no storage at all.
    bool ready = false;
    qint64 bytesTotal = 0;
    qint64 bytesFree = 0;
};

struct File
{
    QString objectId;     // valid while it stays connected; "" for one remembered from before
    QString path;         // inside the phone: "DCIM/Camera/VID_20220102_141629.mp4"
    qint64 size = 0;
    QDateTime modified;
};

// The connected phones (not USB drives, which also show up in WPD).
QList<Phone> list();

// Every video on the phone, skipping hidden folders, apps' private data and WhatsApp's
// photos and voice notes. The camera's folder and the like are looked in first. `found` is
// called after each folder with the videos in it (often none), so they can be shown as
// they're found; returning false stops the search.
QList<File> findVideos(const QString &serial, const std::function<bool(const QList<File> &)> &found);

// What libmpv opens to play a video on a phone, and the protocol that serves it. Without
// an object id, the video is looked for by its path when it's opened.
QString streamUrl(const QString &serial, const File &file);
void registerStream(mpv_handle *mpv);

// Opens the folder a phone's file is in, in File Explorer (This PC > phone > storage > …),
// with the file selected; false if it couldn't be found there. The file is found by its
// object id, or by its path inside the phone when the id isn't known. Blocks for up to a
// few seconds in a folder of thousands of files.
bool showInExplorer(const QString &serial, const QString &objectId, const QString &path);

} // namespace Phones
