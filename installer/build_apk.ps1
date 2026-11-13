# Builds Vessel for Android (release, signed) into build\installer\Vessel-<version>.apk.
# Run from anywhere: powershell -File installer\build_apk.ps1
# Needs Android Studio (its JDK and the Android SDK) and android\key.properties (see the README);
# without that file the build stops, unless -AllowDebugKey is given for a test build.
param([switch]$AllowDebugKey)
$ErrorActionPreference = 'Stop'
$root = Resolve-Path "$PSScriptRoot\.."

$line = Select-String -Path "$root\CMakeLists.txt" -Pattern '^project\(\S+\s+VERSION\s+([0-9.]+)' | Select-Object -First 1
if (-not $line) { throw 'No version in CMakeLists.txt' }
$version = $line.Matches[0].Groups[1].Value

# Gradle needs a JDK; Android Studio ships one.
if (-not $env:JAVA_HOME) {
  $jbr = "$env:ProgramFiles\Android\Android Studio\jbr"
  if (-not (Test-Path "$jbr\bin\java.exe")) { throw 'No JDK: set JAVA_HOME or install Android Studio' }
  $env:JAVA_HOME = $jbr
}
# A debug-signed APK can't update installs of the release-signed app, so it must never come out
# under the name that gets published. -AllowDebugKey builds one anyway, named Vessel-<ver>-debugkey.apk.
$apkName = "Vessel-$version.apk"
if (-not (Test-Path "$root\android\key.properties")) {
  if (-not $AllowDebugKey) { throw 'No android\key.properties: the APK would be signed with the debug key. Add the file (see the README), or pass -AllowDebugKey for a test build.' }
  Write-Warning 'No android\key.properties: signing with the debug key. Do not publish this APK.'
  $apkName = "Vessel-$version-debugkey.apk"
}

Push-Location "$root\android"
try {
  Write-Host "Building Vessel $version for Android (release)..."
  # --no-daemon: a Gradle daemon left running keeps this script's window from returning.
  & .\gradlew.bat assembleRelease --console=plain --no-daemon
  if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
} finally {
  Pop-Location
}

$out = "$root\build\installer"
New-Item -ItemType Directory -Force $out | Out-Null
Copy-Item "$root\android\app\build\outputs\apk\release\app-release.apk" "$out\$apkName" -Force
Write-Host "Done: build\installer\$apkName"
