#include "DriveWatcher.h"

#include <QDir>
#include <QStorageInfo>

#ifdef Q_OS_WIN
#include <windows.h>
#include <winioctl.h>
#endif

namespace {

#ifdef Q_OS_WIN

std::wstring rootOf(wchar_t letter)
{
    return std::wstring{letter, L':', L'\\'};
}

// "usb" or "card" for drives on a USB or SD bus, "" for internal ones.
// External hard drives report DRIVE_FIXED, so the bus is what tells them apart.
QString busKind(wchar_t letter)
{
    const std::wstring device{L'\\', L'\\', L'.', L'\\', letter, L':'};
    HANDLE handle = CreateFileW(device.c_str(), 0, FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr,
                                OPEN_EXISTING, 0, nullptr);
    if (handle == INVALID_HANDLE_VALUE)
        return {};

    STORAGE_PROPERTY_QUERY query{};
    query.PropertyId = StorageDeviceProperty;
    query.QueryType = PropertyStandardQuery;
    alignas(STORAGE_DEVICE_DESCRIPTOR) BYTE buffer[1024] = {};
    DWORD bytes = 0;
    QString kind;
    if (DeviceIoControl(handle, IOCTL_STORAGE_QUERY_PROPERTY, &query, sizeof(query),
                        buffer, sizeof(buffer), &bytes, nullptr)) {
        switch (reinterpret_cast<const STORAGE_DEVICE_DESCRIPTOR *>(buffer)->BusType) {
        case BusTypeUsb:
        case BusType1394:
            kind = QStringLiteral("usb");
            break;
        case BusTypeSd:
        case BusTypeMmc:
            kind = QStringLiteral("card");
            break;
        default:
            break;
        }
    }
    CloseHandle(handle);
    return kind;
}

#endif

} // namespace

DriveWatcher::DriveWatcher(QObject *parent)
    : QObject(parent)
{
    m_timer.setInterval(1000);
    connect(&m_timer, &QTimer::timeout, this, &DriveWatcher::poll);
    m_timer.start();
    poll();
}

void DriveWatcher::poll()
{
    // The signature is cheap; the full look only runs when it changes.
    const QString current = signature();
    if (current == m_signature)
        return;
    m_signature = current;
    m_drives = enumerate();
    emit drivesChanged();
}

QString DriveWatcher::signature() const
{
    QString result;
#ifdef Q_OS_WIN
    const DWORD letters = GetLogicalDrives();
    result = QString::number(letters);
    for (wchar_t letter = L'A'; letter <= L'Z'; ++letter) {
        if (!(letters & (1u << (letter - L'A'))))
            continue;
        const std::wstring root = rootOf(letter);
        // A card reader keeps its letter when the card comes out, so also note the media's serial.
        if (GetDriveTypeW(root.c_str()) == DRIVE_REMOVABLE) {
            DWORD serial = 0;
            const bool ready = GetVolumeInformationW(root.c_str(), nullptr, 0, &serial, nullptr, nullptr, nullptr, 0);
            result += QLatin1Char('|') + QChar(letter) + QString::number(ready ? serial : 0);
        }
    }
#else
    for (const QStorageInfo &volume : QStorageInfo::mountedVolumes())
        result += volume.rootPath() + QLatin1Char('|');
#endif
    return result;
}

QList<DriveInfo> DriveWatcher::enumerate() const
{
    QList<DriveInfo> drives;
    const QString systemRoot = QStorageInfo::root().rootPath();

    const auto add = [&](const QStorageInfo &volume, const QString &kind) {
        if (!volume.isValid() || !volume.isReady() || volume.rootPath() == systemRoot)
            return;
        DriveInfo drive;
        drive.rootPath = volume.rootPath();
        const QString label = volume.name();
        const QString fallback = kind == QLatin1String("card") ? tr("Memory card") : tr("USB drive");
#ifdef Q_OS_WIN
        drive.name = QStringLiteral("%1 (%2)").arg(label.isEmpty() ? fallback : label, drive.rootPath.left(2));
#else
        drive.name = label.isEmpty() ? fallback : label;
#endif
        // The volume GUID path on Windows, the device node elsewhere.
        drive.id = QString::fromUtf8(volume.device());
        drive.kind = kind;
        drive.bytesTotal = volume.bytesTotal();
        drive.bytesFree = volume.bytesAvailable();
        drives.append(drive);
    };

#ifdef Q_OS_WIN
    const DWORD letters = GetLogicalDrives();
    for (wchar_t letter = L'A'; letter <= L'Z'; ++letter) {
        if (!(letters & (1u << (letter - L'A'))))
            continue;
        const std::wstring root = rootOf(letter);
        // Local drives only: network drives can stall, and they're a separate feature.
        const UINT type = GetDriveTypeW(root.c_str());
        if (type != DRIVE_REMOVABLE && type != DRIVE_FIXED)
            continue;
        QString kind = busKind(letter);
        if (kind.isEmpty() && type == DRIVE_REMOVABLE)
            kind = QStringLiteral("usb");
        if (!kind.isEmpty())   // empty means an internal disk
            add(QStorageInfo(QString::fromWCharArray(root.c_str())), kind);
    }
#else
    for (const QStorageInfo &volume : QStorageInfo::mountedVolumes()) {
        const QString root = volume.rootPath();
        if (root.startsWith(QLatin1String("/media/")) || root.startsWith(QLatin1String("/run/media/"))
            || root.startsWith(QLatin1String("/Volumes/")))
            add(volume, QStringLiteral("usb"));
    }
#endif
    return drives;
}
