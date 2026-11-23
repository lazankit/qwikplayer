# Live editing: pushes edited files from this folder straight into the installed apps.
# Running apps pick them up instantly - QwikPlayer Web reloads the page, QwikPlayer restarts
# the video at the same second. (Only the .cs programs need build.ps1 + a reinstall.)
#   .\live.ps1            -> push player config + web player script + start page
# The install folder comes from the installer's own record (a copy upgraded from the old name, Oranix,
# stays in the folder it was first installed to); without one it is %USERPROFILE%\QwikPlayer.
param([string]$InstallDir)
$src = $PSScriptRoot
if (!$InstallDir) {
  $key = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\{6B1E8F3A-2C4D-4E7A-9B5F-3A8D1C2E7F90}_is1'
  $InstallDir = (Get-ItemProperty $key -ErrorAction SilentlyContinue).InstallLocation
  if (!$InstallDir) { $InstallDir = "$env:USERPROFILE\QwikPlayer" }
}
$InstallDir = $InstallDir.TrimEnd('\')
if (!(Test-Path $InstallDir)) { throw "QwikPlayer isn't installed at $InstallDir" }
# Pushing the renamed scripts into a copy from before the rename would leave it half old, half new.
if (!(Test-Path "$InstallDir\Player\QwikPlayer.exe")) {
  throw "The copy in $InstallDir is from before the rename to QwikPlayer. Run .\build.ps1 -Installer and install installer\Output\QwikPlayerSetup.exe first."
}
robocopy "$src\player\config" "$InstallDir\Player\config" /MIR /NFL /NDL /NJH /NJS | Out-Null
robocopy "$src\player\library" "$InstallDir\Player\library" /MIR /NFL /NDL /NJH /NJS | Out-Null
foreach ($f in 'qwik-player.js', 'start.html') {
  $from = "$src\web\$f"; $to = "$InstallDir\Web\$f"
  if ((Get-FileHash $from).Hash -ne (Get-FileHash $to -ErrorAction SilentlyContinue).Hash) { Copy-Item $from $to -Force; "updated Web\$f" }
}
"Live update pushed to $InstallDir"
