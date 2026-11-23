#!/usr/bin/env bash
# Removes QwikPlayer for Linux (mpv itself is left alone).
share="${XDG_DATA_HOME:-$HOME/.local/share}"
rm -rf "$share/qwikplayer"
rm -f "$HOME/.local/bin/qwikplayer" "$share/applications/qwikplayer.desktop" "$share/icons/hicolor/256x256/apps/qwikplayer.png"
# A copy from before the rename (the app was called Oranix), if one is still there
rm -rf "$share/oranix-player"
rm -f "$HOME/.local/bin/oranix-player" "$share/applications/oranix-player.desktop" "$share/icons/hicolor/256x256/apps/oranix-player.png"
command -v update-desktop-database >/dev/null && update-desktop-database "$share/applications" || true
echo "QwikPlayer removed."
