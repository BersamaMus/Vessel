#include "DriveEject.h"

#include <QCoreApplication>

#ifdef Q_OS_WIN
#include <windows.h>
#include <cfgmgr32.h>
#include <initguid.h>
#include <setupapi.h>
#include <winioctl.h>

#include <string>
#include <vector>
#endif

namespace {

QString tr(const char *text)
{
    return QCoreApplication::translate("DriveEject", text);
}

#ifdef Q_OS_WIN

HANDLE openVolume(wchar_t letter, DWORD access)
{
    const std::wstring device{L'\\', L'\\', L'.', L'\\', letter, L':'};
    return CreateFileW(device.c_str(), access, FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_EXISTING, 0, nullptr);
}

bool deviceNumberOf(HANDLE handle, STORAGE_DEVICE_NUMBER &number)
{
    DWORD bytes = 0;
    return DeviceIoControl(handle, IOCTL_STORAGE_GET_DEVICE_NUMBER, nullptr, 0, &number, sizeof(number), &bytes, nullptr);
}

// The disk device holding the volume: the one whose device number matches.
DEVINST diskOf(const STORAGE_DEVICE_NUMBER &volume)
{
    const GUID *guid = volume.DeviceType == FILE_DEVICE_CD_ROM ? &GUID_DEVINTERFACE_CDROM : &GUID_DEVINTERFACE_DISK;
    HDEVINFO devices = SetupDiGetClassDevsW(guid, nullptr, nullptr, DIGCF_PRESENT | DIGCF_DEVICEINTERFACE);
    if (devices == INVALID_HANDLE_VALUE)
        return 0;

    DEVINST found = 0;
    SP_DEVICE_INTERFACE_DATA interfaceData{sizeof(interfaceData)};
    for (DWORD i = 0; !found && SetupDiEnumDeviceInterfaces(devices, nullptr, guid, i, &interfaceData); ++i) {
        DWORD size = 0;
        SetupDiGetDeviceInterfaceDetailW(devices, &interfaceData, nullptr, 0, &size, nullptr);
        if (size == 0)
            continue;
        std::vector<BYTE> buffer(size);
        auto *detail = reinterpret_cast<SP_DEVICE_INTERFACE_DETAIL_DATA_W *>(buffer.data());
        detail->cbSize = sizeof(SP_DEVICE_INTERFACE_DETAIL_DATA_W);
        SP_DEVINFO_DATA info{sizeof(info)};
        if (!SetupDiGetDeviceInterfaceDetailW(devices, &interfaceData, detail, size, nullptr, &info))
            continue;
        HANDLE disk = CreateFileW(detail->DevicePath, 0, FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_EXISTING, 0, nullptr);
        if (disk == INVALID_HANDLE_VALUE)
            continue;
        STORAGE_DEVICE_NUMBER number{};
        if (deviceNumberOf(disk, number) && number.DeviceNumber == volume.DeviceNumber
            && number.DeviceType == volume.DeviceType)
            found = info.DevInst;
        CloseHandle(disk);
    }
    SetupDiDestroyDeviceInfoList(devices);
    return found;
}

QString vetoMessage(PNP_VETO_TYPE veto)
{
    switch (veto) {
    case PNP_VetoOutstandingOpen:
    case PNP_VetoDevice:
    case PNP_VetoDriver:
    case PNP_VetoWindowsApp:
    case PNP_VetoWindowsService:
        return tr("A program is still using it. Close anything with files open on it, then try again.");
    case PNP_VetoNonDisableable:
    case PNP_VetoLegacyDevice:
        return tr("Windows won't let this drive be removed while it's in use.");
    default:
        return tr("Windows couldn't eject it. Close anything using it, then try again.");
    }
}

// A card in a reader: dismount the card and eject just the medium.
QString ejectMedia(wchar_t letter)
{
    HANDLE volume = openVolume(letter, GENERIC_READ | GENERIC_WRITE);
    if (volume == INVALID_HANDLE_VALUE)
        return tr("Couldn't reach the card.");
    DWORD bytes = 0;
    bool locked = false;
    for (int attempt = 0; attempt < 10 && !locked; ++attempt) {
        locked = DeviceIoControl(volume, FSCTL_LOCK_VOLUME, nullptr, 0, nullptr, 0, &bytes, nullptr);
        if (!locked)
            Sleep(100);
    }
    if (!locked) {
        CloseHandle(volume);
        return tr("A program is still using it. Close anything with files open on it, then try again.");
    }
    FlushFileBuffers(volume);
    DeviceIoControl(volume, FSCTL_DISMOUNT_VOLUME, nullptr, 0, nullptr, 0, &bytes, nullptr);
    PREVENT_MEDIA_REMOVAL allow{FALSE};
    DeviceIoControl(volume, IOCTL_STORAGE_MEDIA_REMOVAL, &allow, sizeof(allow), nullptr, 0, &bytes, nullptr);
    const bool ejected = DeviceIoControl(volume, IOCTL_STORAGE_EJECT_MEDIA, nullptr, 0, nullptr, 0, &bytes, nullptr);
    CloseHandle(volume);   // also unlocks it
    return ejected ? QString() : tr("Windows couldn't eject the card.");
}

#endif

} // namespace

QString ejectDrive(const QString &rootPath, const QString &kind)
{
#ifdef Q_OS_WIN
    if (rootPath.size() < 2 || rootPath.at(1) != QLatin1Char(':'))
        return tr("This drive can't be ejected here.");
    const wchar_t letter = wchar_t(rootPath.at(0).toUpper().unicode());

    if (kind == QLatin1String("card"))
        return ejectMedia(letter);

    HANDLE volume = openVolume(letter, 0);
    if (volume == INVALID_HANDLE_VALUE)
        return tr("Couldn't reach the drive.");
    STORAGE_DEVICE_NUMBER number{};
    const bool known = deviceNumberOf(volume, number);
    CloseHandle(volume);   // our own handle would veto the removal
    if (!known)
        return tr("Couldn't reach the drive.");

    const DEVINST disk = diskOf(number);
    DEVINST device = 0;
    if (!disk || CM_Get_Parent(&device, disk, 0) != CR_SUCCESS)
        return tr("Couldn't find the drive's device.");

    // Programs get a moment to close their files; a second try often succeeds.
    PNP_VETO_TYPE veto = PNP_VetoTypeUnknown;
    for (int attempt = 0; attempt < 3; ++attempt) {
        wchar_t vetoName[MAX_PATH] = {};
        veto = PNP_VetoTypeUnknown;
        if (CM_Request_Device_EjectW(device, &veto, vetoName, MAX_PATH, 0) == CR_SUCCESS && veto == PNP_VetoTypeUnknown)
            return {};
        Sleep(400);
    }
    return vetoMessage(veto);
#else
    Q_UNUSED(rootPath)
    Q_UNUSED(kind)
    return tr("Ejecting drives isn't supported here yet.");
#endif
}
