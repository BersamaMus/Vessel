; Vessel's Windows installer (Inno Setup). Build it with build_installer.ps1, which builds
; the app, gathers what it needs in build\package and passes in the version from
; CMakeLists.txt.

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif

[Setup]
; Identifies Vessel to Windows across versions, so updates replace it. Never change it.
AppId={{14511018-0484-46FB-905B-0B525556D64C}
AppName=Vessel
AppVersion={#AppVersion}
AppVerName=Vessel {#AppVersion}
AppPublisher=Vessel
VersionInfoVersion={#AppVersion}
DefaultDirName={autopf}\Vessel
DefaultGroupName=Vessel
DisableProgramGroupPage=yes
; Installs for just this user without asking for admin, unless they choose everyone.
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
OutputDir=..\build\installer
OutputBaseFilename=Vessel-Setup-{#AppVersion}
SetupIconFile=..\icon\vessel.ico
UninstallDisplayIcon={app}\VesselPlayer.exe
UninstallDisplayName=Vessel
WizardStyle=modern
Compression=lzma2
SolidCompression=yes
; Closes a running Vessel (and its thumbnail workers) before updating or removing it.
CloseApplications=yes

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
; The exe, libmpv, Qt and the Visual C++ runtime: everything build\package holds.
Source: "..\build\package\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[InstallDelete]
; Qt's QML modules are replaced whole on an update, so ones an older version had don't linger.
Type: filesandordirs; Name: "{app}\qml"

[Icons]
Name: "{autoprograms}\Vessel"; Filename: "{app}\VesselPlayer.exe"
Name: "{autodesktop}\Vessel"; Filename: "{app}\VesselPlayer.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\VesselPlayer.exe"; Description: "{cm:LaunchProgram,Vessel}"; Flags: nowait postinstall skipifsilent

[UninstallRun]
; Vessel registers itself for "Open with" and Default apps when it runs: that goes too.
; (The library, history and thumbnails in %LOCALAPPDATA%\Vessel stay, for a reinstall.)
Filename: "{app}\VesselPlayer.exe"; Parameters: "--unregister"; Flags: runhidden waituntilterminated; RunOnceId: "Unregister"
