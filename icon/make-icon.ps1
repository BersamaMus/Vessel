# Makes vessel.ico, the exe's icon, from the app's mark (qml/LogoMark.qml): Qt draws it at
# each size (RenderIcon.qml), and they're packed into one .ico as PNG images.
# Run again after changing the mark:  powershell -File icon\make-icon.ps1 [-QtBin C:\Qt\6.11.3\msvc2022_64\bin]
param(
    [string]$QtBin = "C:\Qt\6.11.3\msvc2022_64\bin"
)
$ErrorActionPreference = "Stop"

$here = $PSScriptRoot
$work = Join-Path ([System.IO.Path]::GetTempPath()) "vessel-icon"
Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory $work | Out-Null

& (Join-Path $QtBin "qml.exe") (Join-Path $here "RenderIcon.qml") -- $work
$sizes = 16, 20, 24, 32, 40, 48, 64, 96, 128, 256
foreach ($size in $sizes) {
    if (-not (Test-Path (Join-Path $work "$size.png"))) { throw "The $size px image wasn't drawn." }
}

# ICO: a 6-byte header, a 16-byte entry per image, then the images (PNG data, as Windows
# Vista and later read).
$images = $sizes | ForEach-Object { , [System.IO.File]::ReadAllBytes((Join-Path $work "$_.png")) }
$stream = New-Object System.IO.MemoryStream
$writer = New-Object System.IO.BinaryWriter($stream)
$writer.Write([uint16]0)              # reserved
$writer.Write([uint16]1)              # type: icon
$writer.Write([uint16]$sizes.Count)
$offset = 6 + 16 * $sizes.Count
for ($i = 0; $i -lt $sizes.Count; $i++) {
    $size = $sizes[$i]
    $dimension = if ($size -ge 256) { 0 } else { $size }   # 0 means 256
    $writer.Write([byte]$dimension)   # width
    $writer.Write([byte]$dimension)   # height
    $writer.Write([byte]0)            # palette size
    $writer.Write([byte]0)            # reserved
    $writer.Write([uint16]1)          # colour planes
    $writer.Write([uint16]32)         # bits per pixel
    $writer.Write([uint32]$images[$i].Length)
    $writer.Write([uint32]$offset)
    $offset += $images[$i].Length
}
foreach ($image in $images) { $writer.Write($image) }
$writer.Flush()

$ico = Join-Path $here "vessel.ico"
[System.IO.File]::WriteAllBytes($ico, $stream.ToArray())
Copy-Item (Join-Path $work "256.png") (Join-Path $here "vessel.png")
Write-Host "Wrote $ico ($($sizes.Count) sizes) and vessel.png"
