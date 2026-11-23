#!/usr/bin/env bash
# QwikPlayer for Linux: installs the QwikPlayer look, popups and gestures on top of your mpv,
# for the current user only (no root). Run:  bash install.sh
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
share="${XDG_DATA_HOME:-$HOME/.local/share}"
app="$share/qwikplayer"
bin="$HOME/.local/bin"

if ! command -v mpv >/dev/null 2>&1; then
  echo "QwikPlayer needs mpv. Install it first, for example:"
  echo "  Debian/Ubuntu:  sudo apt install mpv"
  echo "  Fedora:         sudo dnf install mpv"
  echo "  Arch:           sudo pacman -S mpv"
  echo "  Any distro:     flatpak install flathub io.mpv.Mpv"
  exit 1
fi

# Upgrade from before the rename (the app was called Oranix): move the old folder, which also holds the
# downloaded subtitles and subtitle settings, to the new name, then remove the old command, menu entry and icon.
old="$share/oranix-player"
if [ -d "$old" ] && [ ! -e "$app" ]; then
  mv "$old" "$app" || echo "Could not move $old to $app; the subtitles saved there stay where they are."
fi
rm -f "$bin/oranix-player" "$share/applications/oranix-player.desktop" "$share/icons/hicolor/256x256/apps/oranix-player.png"

mkdir -p "$app" "$bin" "$share/applications" "$share/icons/hicolor/256x256/apps"
rm -rf "$app/config"
cp -r "$here/config" "$app/config"
cp "$here/qwikplayer.png" "$share/icons/hicolor/256x256/apps/qwikplayer.png"

# Launcher: mpv with the QwikPlayer config. The Windows-only video settings in mpv.conf are overridden here.
cat > "$bin/qwikplayer" <<EOF
#!/usr/bin/env bash
exec mpv --config-dir="$app/config" --gpu-api=auto --hwdec=auto-safe --vo=gpu-next \\
  --title='\${?media-title:\${media-title} - }QwikPlayer' "\$@"
EOF
chmod +x "$bin/qwikplayer"

cat > "$share/applications/qwikplayer.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=QwikPlayer
GenericName=Video Player
Comment=Play More. Worry Less.
Exec=$bin/qwikplayer --player-operation-mode=pseudo-gui -- %F
Icon=qwikplayer
Terminal=false
Categories=AudioVideo;Video;Player;
MimeType=video/mp4;video/x-matroska;video/webm;video/x-msvideo;video/quicktime;video/mpeg;video/x-flv;video/mp2t;
StartupWMClass=mpv
EOF
command -v update-desktop-database >/dev/null && update-desktop-database "$share/applications" || true

echo "QwikPlayer installed."
echo "  Open it from your app menu, or run:  qwikplayer video.mkv"
echo "  Make it the default video player in your desktop's Default Applications settings."
case ":$PATH:" in *":$bin:"*) ;; *) echo "  (Add $bin to your PATH to use the qwikplayer command.)";; esac
