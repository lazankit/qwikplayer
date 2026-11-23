# Builds both QwikPlayer apps into dist\ and (optionally) the installer QwikPlayerSetup.exe.
#   .\build.ps1             -> dist\Player, dist\Web
#   .\build.ps1 -Installer  -> also installer\Output\QwikPlayerSetup.exe
# The apps are AnyCPU .NET Framework programs: they run natively on x64 and on Windows on ARM (ARM64).
param([switch]$Installer)
$ErrorActionPreference = 'Stop'
$src = $PSScriptRoot
$dist = Join-Path $src 'dist'
$csc = "$env:WINDIR\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
$common = '/nologo', '/codepage:65001', '/target:winexe', '/platform:anycpu', '/optimize+'

# QwikPlayer: the app + bundled libmpv (x64 and ARM64) + its config (uosc, its own scripts, fonts).
# libmpv comes from shinchiro/mpv-winbuild-cmake (pinned build, downloaded on first build).
$mpvBuild = '20261007'; $mpvGit = 'eb0ee10315'
foreach ($a in @(@('x64', 'x86_64'), @('arm64', 'aarch64'))) {
  $dir = "$src\player\libmpv\$($a[0])"
  if (Test-Path "$dir\libmpv-2.dll") { continue }
  New-Item -ItemType Directory -Force $dir | Out-Null
  $archive = "$env:TEMP\mpv-dev-$($a[1]).7z"
  Invoke-WebRequest "https://github.com/shinchiro/mpv-winbuild-cmake/releases/download/$mpvBuild/mpv-dev-$($a[1])-$mpvBuild-git-$mpvGit.7z" -OutFile $archive -UseBasicParsing
  tar -xf $archive -C $dir libmpv-2.dll   # Windows' tar reads .7z
}
# Subtitle sync engine: alass 2.0.0 (GPL-3.0, with its own ffmpeg), run as a separate program.
if (!(Test-Path "$src\player\alass\bin\alass-cli.exe")) {
  $zip = "$env:TEMP\alass-windows64.zip"
  Invoke-WebRequest 'https://github.com/kaegi/alass/releases/download/v2.0.0/alass-windows64.zip' -OutFile $zip -UseBasicParsing
  Remove-Item "$env:TEMP\alass-unpack" -Recurse -Force -ErrorAction SilentlyContinue
  & "$env:WINDIR\System32\tar.exe" -xf $zip -C (New-Item -ItemType Directory -Force "$env:TEMP\alass-unpack").FullName
  Move-Item "$env:TEMP\alass-unpack\alass-windows64" "$src\player\alass"
  # alass finds ffmpeg/ffprobe next to itself, so the player needs no environment setup to run it.
  Copy-Item "$src\player\alass\ffmpeg\bin\*" "$src\player\alass\bin"
}
# WebView2 SDK (Microsoft) for QwikPlayer Web and the QwikPlayer library, downloaded from NuGet on first
# build, with the native loader for both x64 and ARM64.
$sdk = "$src\web\sdk"
if (!(Test-Path "$sdk\runtimes\win-arm64\native\WebView2Loader.dll")) {
  $nupkg = "$env:TEMP\webview2-sdk.zip"
  Invoke-WebRequest 'https://www.nuget.org/api/v2/package/Microsoft.Web.WebView2/1.0.2903.40' -OutFile $nupkg -UseBasicParsing
  Expand-Archive $nupkg "$env:TEMP\webview2-sdk" -Force
  Remove-Item $sdk -Recurse -Force -ErrorAction SilentlyContinue
  New-Item -ItemType Directory -Force "$sdk\runtimes\win-x64\native", "$sdk\runtimes\win-arm64\native" | Out-Null
  Copy-Item "$env:TEMP\webview2-sdk\lib\net462\Microsoft.Web.WebView2.Core.dll", "$env:TEMP\webview2-sdk\lib\net462\Microsoft.Web.WebView2.WinForms.dll" $sdk
  foreach ($arch in 'x64', 'arm64') {
    Copy-Item "$env:TEMP\webview2-sdk\runtimes\win-$arch\native\WebView2Loader.dll" "$sdk\runtimes\win-$arch\native"
  }
}
$p = "$dist\Player"
Remove-Item $p -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$p\libmpv\x64", "$p\libmpv\arm64" | Out-Null
robocopy "$src\player\config" "$p\config" /MIR /NFL /NDL /NJH /NJS | Out-Null
robocopy "$src\player\alass" "$p\alass" /E /NFL /NDL /NJH /NJS | Out-Null
robocopy "$src\player\library" "$p\library" /MIR /NFL /NDL /NJH /NJS | Out-Null
Copy-Item "$sdk\*" $p -Recurse -Force
Copy-Item "$src\player\libmpv\x64\libmpv-2.dll" "$p\libmpv\x64"
Copy-Item "$src\player\libmpv\arm64\libmpv-2.dll" "$p\libmpv\arm64"
Copy-Item "$src\player\launcher\qwikplayer.ico", "$src\player\LICENSE.libmpv.GPL" $p
& $csc @common "/win32icon:$src\player\launcher\qwikplayer.ico" "/out:$p\QwikPlayer.exe" `
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

if ($Installer) {
  & "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe" /Q "$src\installer\QwikPlayer.iss"
  if ($LASTEXITCODE) { throw 'Installer build failed' }
  "Installer: $src\installer\Output\QwikPlayerSetup.exe"
}
"Built: $dist"
