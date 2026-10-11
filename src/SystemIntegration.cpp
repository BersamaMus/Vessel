#include "SystemIntegration.h"

#include "VideoFormats.h"

#include <QCoreApplication>
#include <QDir>
#include <QFileInfo>
#include <QStringList>

#ifdef Q_OS_WIN
#include <windows.h>
#include <shlobj.h>
#endif

namespace SystemIntegration {

#ifdef Q_OS_WIN

namespace {

const wchar_t *const kProgId = L"Vessel.Video";
const wchar_t *const kCapabilities = L"Software\\Vessel\\Capabilities";

void setString(const std::wstring &key, const wchar_t *name, const std::wstring &value)
{
    RegSetKeyValueW(HKEY_CURRENT_USER, key.c_str(), name, REG_SZ, value.c_str(),
                    DWORD((value.size() + 1) * sizeof(wchar_t)));
}

std::wstring getString(const std::wstring &key, const wchar_t *name)
{
    wchar_t buffer[2048];
    DWORD size = sizeof buffer;
    if (RegGetValueW(HKEY_CURRENT_USER, key.c_str(), name, RRF_RT_REG_SZ, nullptr, buffer, &size) != ERROR_SUCCESS)
        return {};
    return buffer;
}

} // namespace

void registerFileTypes()
{
    const std::wstring exe = QDir::toNativeSeparators(QCoreApplication::applicationFilePath()).toStdWString();
    const std::wstring command = L"\"" + exe + L"\" \"%1\"";
    const std::wstring progId = std::wstring(L"Software\\Classes\\") + kProgId;
    // Already registered, by this copy: nothing to do (the usual case).
    if (getString(progId + L"\\shell\\open\\command", nullptr) == command
        && !getString(kCapabilities, L"ApplicationName").empty())
        return;

    const std::wstring exeName = QFileInfo(QCoreApplication::applicationFilePath()).fileName().toStdWString();
    const std::wstring application = L"Software\\Classes\\Applications\\" + exeName;

    // What a video file opened with Vessel is.
    setString(progId, nullptr, L"Video");
    setString(progId + L"\\DefaultIcon", nullptr, L"\"" + exe + L"\",0");
    setString(progId + L"\\shell\\open\\command", nullptr, command);

    // Vessel itself, as "Open with" lists it.
    setString(application, L"FriendlyAppName", L"Vessel");
    setString(application + L"\\shell\\open\\command", nullptr, command);

    // As Settings > Default apps lists it.
    setString(kCapabilities, L"ApplicationName", L"Vessel");
    setString(kCapabilities, L"ApplicationDescription",
              L"Plays the videos on this computer, and on the drives and phones you plug in.");
    setString(L"Software\\RegisteredApplications", L"Vessel", kCapabilities);

    for (const QString &pattern : VideoFormats::nameFilter().split(QLatin1Char(' '))) {
        const std::wstring extension = pattern.mid(1).toStdWString();   // "*.mp4" -> ".mp4"
        setString(application + L"\\SupportedTypes", extension.c_str(), L"");
        setString(L"Software\\Classes\\" + extension + L"\\OpenWithProgids", kProgId, L"");
        setString(std::wstring(kCapabilities) + L"\\FileAssociations", extension.c_str(), kProgId);
    }

    // Explorer picks it up now rather than after a restart.
    SHChangeNotify(SHCNE_ASSOCCHANGED, SHCNF_IDLIST, nullptr, nullptr);
}

void unregisterFileTypes()
{
    const std::wstring exeName = QFileInfo(QCoreApplication::applicationFilePath()).fileName().toStdWString();
    RegDeleteTreeW(HKEY_CURRENT_USER, (std::wstring(L"Software\\Classes\\") + kProgId).c_str());
    RegDeleteTreeW(HKEY_CURRENT_USER, (L"Software\\Classes\\Applications\\" + exeName).c_str());
    RegDeleteTreeW(HKEY_CURRENT_USER, L"Software\\Vessel");
    RegDeleteKeyValueW(HKEY_CURRENT_USER, L"Software\\RegisteredApplications", L"Vessel");
    for (const QString &pattern : VideoFormats::nameFilter().split(QLatin1Char(' '))) {
        const std::wstring extension = pattern.mid(1).toStdWString();
        RegDeleteKeyValueW(HKEY_CURRENT_USER, (L"Software\\Classes\\" + extension + L"\\OpenWithProgids").c_str(), kProgId);
    }
    SHChangeNotify(SHCNE_ASSOCCHANGED, SHCNF_IDLIST, nullptr, nullptr);
}

#else

void registerFileTypes()
{
}

void unregisterFileTypes()
{
}

#endif

} // namespace SystemIntegration
