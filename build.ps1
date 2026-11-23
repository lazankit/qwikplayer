# Builds both QwikPlayer apps into dist\ and (optionally) the installer QwikPlayerSetup.exe.
#   .\build.ps1             -> dist\Player, dist\Web
#   .\build.ps1 -Installer  -> also installer\Output\QwikPlayerSetup.exe
#   .\build.ps1 -Android    -> also the two Android apps (needs the Android SDK; never part of the default build)
# The apps are AnyCPU .NET Framework programs: they run natively on x64 and on Windows on ARM (ARM64).
param([switch]$Installer, [switch]$Android)
$ErrorActionPreference = 'Stop'
$src = $PSScriptRoot
$dist = Join-Path $src 'dist'
$csc = "$env:WINDIR\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
$common = '/nologo', '/codepage:65001', '/target:winexe', '/platform:anycpu', '/optimize+'
$tar = "$env:WINDIR\System32\tar.exe"

# Downloaded build inputs are pinned by SHA-256 (of the files they unpack to) and the build fails on a
# mismatch, whether the file was cached or just downloaded. When a pinned version below is bumped, update
# the hashes with (Get-FileHash <file> -Algorithm SHA256).Hash.
$hashes = @{
  'player\libmpv\x64\libmpv-2.dll'   = '872827614ed0adfca11e68def5273bcfcaea6acf38bbf1950c35980b59f43a5f'
  'player\libmpv\arm64\libmpv-2.dll' = '8f0b92e4e546030da04592f8ecb038bddb2b87218abec1fa4f3395b2be3ea145'
  'player\alass\bin\alass-cli.exe'   = '9bc1f13a923590047603858149414d49d718a0629738cccf1bb35a2a6cee3f60'
  'player\alass\bin\ffmpeg.exe'      = '72670d33859812b2cd54b6307737f19d12f82737af432505a595d03d93f6092f'
  'player\alass\bin\ffprobe.exe'     = 'b475037b5471cf140d5a36efa925c5ad66baf58bdea5f220d1df5883a5c14fc8'
  'web\sdk\Microsoft.Web.WebView2.Core.dll'     = '88a3b62f45225a811cdb85df6dfd95c2bff9a0e43e3b04f813b125eaca56cc9f'
  'web\sdk\Microsoft.Web.WebView2.WinForms.dll' = '29fc954c3fde1749817d158f3dd5ebe9efd3b3ce9708d86092fb1e9f023a1cb0'
  'web\sdk\runtimes\win-x64\native\WebView2Loader.dll'   = '462b36fd1be6ca9f7563466a89e57c41ef4a4def3e0a84fa885d203aea4a3aaf'
  'web\sdk\runtimes\win-arm64\native\WebView2Loader.dll' = 'cb561ae4537e39bba5dd8af1f648f189d9562b1003fd110a9e889ace527fb4e8'
}
function Assert-Pinned([string]$prefix) {
  foreach ($k in @($hashes.Keys | Where-Object { $_.StartsWith($prefix) })) {
    $f = Join-Path $src $k
    if (!(Test-Path $f)) { throw "Missing downloaded file: $f" }
    $h = (Get-FileHash $f -Algorithm SHA256).Hash
    if ($h -ne $hashes[$k].ToUpperInvariant()) {
      throw "SHA-256 mismatch for $k (got $h). Delete its folder and rebuild to download it again; if the pinned version was bumped on purpose, update `$hashes in build.ps1."
    }
  }
}
# Each download is unpacked in its own fresh folder, removed again after the checks.
$work = Join-Path $env:TEMP ('qwikplayer-build-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $work | Out-Null

# QwikPlayer: the app + bundled libmpv (x64 and ARM64) + its config (uosc, its own scripts, fonts).
# libmpv comes from shinchiro/mpv-winbuild-cmake (pinned build, downloaded on first build).
$mpvBuild = '20261007'; $mpvGit = 'eb0ee10315'
foreach ($a in @(@('x64', 'x86_64'), @('arm64', 'aarch64'))) {
  $dir = "$src\player\libmpv\$($a[0])"
  if (Test-Path "$dir\libmpv-2.dll") { continue }
  New-Item -ItemType Directory -Force $dir | Out-Null
  $archive = "$work\mpv-dev-$($a[1]).7z"
  Invoke-WebRequest "https://github.com/shinchiro/mpv-winbuild-cmake/releases/download/$mpvBuild/mpv-dev-$($a[1])-$mpvBuild-git-$mpvGit.7z" -OutFile $archive -UseBasicParsing
  & $tar -xf $archive -C $dir libmpv-2.dll   # Windows' tar reads .7z
}
Assert-Pinned 'player\libmpv'
# Subtitle sync engine: alass 2.0.0 (GPL-3.0, with its own ffmpeg), run as a separate program.
if (!(Test-Path "$src\player\alass\bin\alass-cli.exe")) {
  $zip = "$work\alass-windows64.zip"
  Invoke-WebRequest 'https://github.com/kaegi/alass/releases/download/v2.0.0/alass-windows64.zip' -OutFile $zip -UseBasicParsing
  $unpack = (New-Item -ItemType Directory -Force "$work\alass-unpack").FullName
  & $tar -xf $zip -C $unpack
  Move-Item "$unpack\alass-windows64" "$src\player\alass"
  # alass finds ffmpeg/ffprobe next to itself, so the player needs no environment setup to run it. This is
  # the only copy of ffmpeg that ships: subtitle sync, thumbnails and intro detection all use alass\bin.
  # Moved, not copied (the ffmpeg folder keeps only its licence and readme).
  Move-Item "$src\player\alass\ffmpeg\bin\*" "$src\player\alass\bin"
}
Assert-Pinned 'player\alass'
# WebView2 SDK (Microsoft) for QwikPlayer Web and the QwikPlayer library, downloaded from NuGet on first
# build, with the native loader for both x64 and ARM64.
$sdk = "$src\web\sdk"
if (!(Test-Path "$sdk\runtimes\win-arm64\native\WebView2Loader.dll")) {
  $nupkg = "$work\webview2-sdk.zip"
  Invoke-WebRequest 'https://www.nuget.org/api/v2/package/Microsoft.Web.WebView2/1.0.2903.40' -OutFile $nupkg -UseBasicParsing
  Expand-Archive $nupkg "$work\webview2-sdk" -Force
  Remove-Item $sdk -Recurse -Force -ErrorAction SilentlyContinue
  New-Item -ItemType Directory -Force "$sdk\runtimes\win-x64\native", "$sdk\runtimes\win-arm64\native" | Out-Null
  Copy-Item "$work\webview2-sdk\lib\net462\Microsoft.Web.WebView2.Core.dll", "$work\webview2-sdk\lib\net462\Microsoft.Web.WebView2.WinForms.dll" $sdk
  foreach ($arch in 'x64', 'arm64') {
    Copy-Item "$work\webview2-sdk\runtimes\win-$arch\native\WebView2Loader.dll" "$sdk\runtimes\win-$arch\native"
  }
}
Assert-Pinned 'web\sdk'
Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
$p = "$dist\Player"
Remove-Item $p -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$p\libmpv\x64", "$p\libmpv\arm64" | Out-Null
# uosc's helper for Linux and macOS is not shipped on Windows.
robocopy "$src\player\config" "$p\config" /MIR /XF ziggy-darwin ziggy-linux /NFL /NDL /NJH /NJS | Out-Null
# One ffmpeg only: alass\bin has it. (An older checkout may still hold a second copy in alass\ffmpeg\bin.)
robocopy "$src\player\alass" "$p\alass" /E /XD "$src\player\alass\ffmpeg\bin" /NFL /NDL /NJH /NJS | Out-Null
robocopy "$src\player\library" "$p\library" /MIR /NFL /NDL /NJH /NJS | Out-Null
Copy-Item "$sdk\*" $p -Recurse -Force
Copy-Item "$src\player\libmpv\x64\libmpv-2.dll" "$p\libmpv\x64"
Copy-Item "$src\player\libmpv\arm64\libmpv-2.dll" "$p\libmpv\arm64"
Copy-Item "$src\player\launcher\qwikplayer.ico", "$src\player\LICENSE.libmpv.GPL" $p
# The manifest (per-monitor-v2 DPI, UTF-8 code page, long paths) is for the Player only, not QwikPlayer Web.
& $csc @common "/win32icon:$src\player\launcher\qwikplayer.ico" "/win32manifest:$src\player\launcher\app.manifest" "/out:$p\QwikPlayer.exe" `
  "/r:$p\Microsoft.Web.WebView2.Core.dll" "/r:$p\Microsoft.Web.WebView2.WinForms.dll" `
  /r:System.Drawing.dll /r:System.Windows.Forms.dll /r:System.Web.Extensions.dll "$src\player\launcher\QwikPlayer.cs" "$src\player\launcher\Library.cs" "$src\player\launcher\Intros.cs"
if ($LASTEXITCODE) { throw 'QwikPlayer build failed' }

# QwikPlayer Web: browser + live files.
$w = "$dist\Web"
Remove-Item $w -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $w | Out-Null
Copy-Item "$sdk\*" $w -Recurse
Copy-Item "$src\web\qwik-player.js", "$src\web\start.html", "$src\web\qwikplayer-web.ico" $w
& $csc @common "/win32icon:$src\web\qwikplayer-web.ico" "/out:$w\QwikPlayer Web.exe" `
  "/r:$w\Microsoft.Web.WebView2.Core.dll" "/r:$w\Microsoft.Web.WebView2.WinForms.dll" `
  /r:System.Windows.Forms.dll /r:System.Drawing.dll /r:System.Web.Extensions.dll "$src\web\QwikBrowse.cs"
if ($LASTEXITCODE) { throw 'QwikPlayer Web build failed' }

if ($Android) {
  # Optional: needs the Android SDK, so it is never part of the default build.
  foreach ($d in 'android-player', 'android') {
    $script = "$src\$d\build.ps1"
    if (!(Test-Path $script)) { "Skipping $d (no build.ps1)"; continue }
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $script
    if ($LASTEXITCODE) { throw "$d build failed" }
  }
}

if ($Installer) {
  & "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe" /Q "$src\installer\QwikPlayer.iss"
  if ($LASTEXITCODE) { throw 'Installer build failed' }
  "Installer: $src\installer\Output\QwikPlayerSetup.exe"
}
"Built: $dist"
