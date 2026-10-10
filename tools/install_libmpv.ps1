param([string]$Destination = "E:\mc\runtime\libmpv-20261009")
$ErrorActionPreference = "Stop"
$archiveUrl = "https://github.com/shinchiro/mpv-winbuild-cmake/releases/download/20261009/mpv-dev-x86_64-20261009-git-36abaa32d0.7z"
$expectedHash = "8a15fc07996a33235778256157151c42cbaceee976f0a98860f55047856935b2"
$extractor = Get-Command 7z -ErrorAction Stop
$target = [System.IO.Path]::GetFullPath($Destination)
New-Item -ItemType Directory -Path $target -Force | Out-Null
$archivePath = Join-Path $target "mpv-dev-20261009.7z"
if (!(Test-Path -LiteralPath $archivePath)) { Invoke-WebRequest -Uri $archiveUrl -OutFile $archivePath }
if ((Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedHash) { throw "libmpv archive checksum mismatch" }
& $extractor.Source x $archivePath "-o$target" -y
if ($LASTEXITCODE -ne 0) { throw "libmpv extraction failed" }
Write-Output "Set mpvLibraryPath to $(Join-Path $target 'libmpv-2.dll') in cinemar2-client.json. See THIRD_PARTY.md."
