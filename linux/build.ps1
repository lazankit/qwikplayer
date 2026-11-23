# Packages QwikPlayer for Linux -> linux\out\qwikplayer-linux.tar.gz
# (the same mpv config as Windows + install.sh; runs on any CPU mpv runs on: x86_64, ARM64, ...)
$ErrorActionPreference = 'Stop'
$src = Split-Path $PSScriptRoot
$out = "$PSScriptRoot\out"
$pkg = "$out\qwikplayer"
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $pkg | Out-Null
robocopy "$src\player\config" "$pkg\config" /E /XF ziggy-windows.exe ziggy-darwin /NFL /NDL /NJH /NJS | Out-Null
Copy-Item "$PSScriptRoot\install.sh", "$PSScriptRoot\uninstall.sh" $pkg
Copy-Item "$src\docs\qwikplayer-icon.png" "$pkg\qwikplayer.png"
# Shell scripts must have Unix line endings to run.
foreach ($f in 'install.sh', 'uninstall.sh') {
  $t = [IO.File]::ReadAllText("$pkg\$f") -replace "`r`n", "`n"
  [IO.File]::WriteAllText("$pkg\$f", $t, (New-Object Text.UTF8Encoding $false))
}
@"
QwikPlayer for Linux
====================
1. Install mpv (sudo apt install mpv / dnf / pacman / flatpak).
2. In this folder run:   bash install.sh
3. Open "QwikPlayer" from your app menu, or:   qwikplayer video.mkv
Remove with:  bash uninstall.sh
https://github.com/lazankit/qwikplayer
"@ -replace "`r`n", "`n" | Set-Content "$pkg\README.txt" -NoNewline -Encoding ascii
tar -czf "$out\qwikplayer-linux.tar.gz" -C $out qwikplayer
"Linux package: $out\qwikplayer-linux.tar.gz"
