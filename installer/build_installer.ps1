# Builds Vessel (Release) and packs it into build\installer\Vessel-Setup-<version>.exe.
# Run from anywhere: powershell -File installer\build_installer.ps1
# Needs Visual Studio (C++), Qt (see CMakePresets.json) and Inno Setup
# (winget install JRSoftware.InnoSetup).
$ErrorActionPreference = 'Stop'
$root = Resolve-Path "$PSScriptRoot\.."

# The version as CMakeLists.txt has it: project(Vessel VERSION 1.0.0 ...).
$line = Select-String -Path "$root\CMakeLists.txt" -Pattern '^project\(\S+\s+VERSION\s+([0-9.]+)' | Select-Object -First 1
if (-not $line) { throw 'No version in CMakeLists.txt' }
$version = $line.Matches[0].Groups[1].Value

$vs = Get-ChildItem "$env:ProgramFiles\Microsoft Visual Studio\*\*\VC\Auxiliary\Build\vcvars64.bat" |
  Sort-Object FullName -Descending | Select-Object -First 1
if (-not $vs) { throw 'Visual Studio (C++) not found' }
$vcRoot = Resolve-Path "$($vs.Directory)\..\.."

$iscc = @(
  "$env:ProgramFiles\Inno Setup 7\ISCC.exe",
  "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe",
  "$env:LOCALAPPDATA\Programs\Inno Setup 7\ISCC.exe",
  "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe"
) | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $iscc) { throw 'Inno Setup not found. Install it with: winget install JRSoftware.InnoSetup' }

# Qt's folder, from the presets.
$presets = Get-Content "$root\CMakePresets.json" -Raw | ConvertFrom-Json
$qt = ($presets.configurePresets | Where-Object { $_.name -eq 'msvc-base' }).cacheVariables.CMAKE_PREFIX_PATH
$windeployqt = Join-Path $qt 'bin\windeployqt.exe'
if (-not (Test-Path $windeployqt)) { throw "windeployqt not found at $windeployqt" }

Push-Location $root
try {
  Write-Host "Building Vessel $version (Release)..."
  cmd /c "`"$($vs.FullName)`" >nul && cmake --preset release && cmake --build --preset release"
  if ($LASTEXITCODE -ne 0) { throw 'Build failed' }

  # Only what the app needs, not the build folder's objects and caches.
  Write-Host 'Gathering the files...'
  $package = "$root\build\package"
  if (Test-Path $package) { Remove-Item $package -Recurse -Force }
  New-Item -ItemType Directory $package | Out-Null
  Copy-Item "$root\build\release\VesselPlayer.exe" $package
  Copy-Item "$root\third_party\mpv\libmpv-2.dll" $package
  & $windeployqt --release --no-translations --no-opengl-sw --no-compiler-runtime --skip-plugin-types qmltooling `
    --qmldir "$root\qml" --verbose 0 "$package\VesselPlayer.exe"
  if ($LASTEXITCODE -ne 0) { throw 'windeployqt failed' }

  # The Visual C++ runtime, next to the exe: no separate redistributable to install.
  $crt = Get-ChildItem "$vcRoot\Redist\MSVC\*\x64\Microsoft.VC*.CRT" -Directory |
    Sort-Object FullName -Descending | Select-Object -First 1
  if (-not $crt) { throw 'Visual C++ runtime (Redist) not found' }
  Copy-Item "$($crt.FullName)\*.dll" $package

  Write-Host 'Packing the installer...'
  & $iscc "/DAppVersion=$version" "$PSScriptRoot\vessel.iss"
  if ($LASTEXITCODE -ne 0) { throw 'Inno Setup failed' }
  Write-Host "Done: build\installer\Vessel-Setup-$version.exe"
} finally {
  Pop-Location
}
