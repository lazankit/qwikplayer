# Contributing to QwikPlayer

Thanks for helping! QwikPlayer is small on purpose — a few plain files per app, no frameworks — so most changes are quick to make and easy to review.

## Ways to help

- **Report a bug** or **suggest a feature** with the [issue templates](https://github.com/lazankit/qwikplayer/issues/new/choose).
- **Pick up an issue** labelled [`good first issue`](https://github.com/lazankit/qwikplayer/labels/good%20first%20issue) — comment on it so nobody doubles up.
- **Improve the docs** — if something in the README confused you, it'll confuse the next person too.

## Where things live

| You want to change… | Edit |
|---|---|
| Player look, keys, gestures, popups | `player/config/` (`input.conf`, `script-opts/uosc.conf`, `scripts/*.lua`) |
| Player launcher (window icon, live restart) | `player/launcher/QwikPlayer.cs` |
| The web player bar (Windows **and** Android) | `web/qwik-player.js` |
| QwikPlayer Web start page | `web/start.html` |
| QwikPlayer Web window, toolbar, ad shield (Windows) | `web/QwikBrowse.cs` |
| QwikPlayer Web for Android | `android/src/com/qwikplayer/web/MainActivity.java` |
| QwikPlayer for Chrome (manifest; the blocklist is built from `web/QwikBrowse.cs`) | `chrome/manifest.json`, `chrome/build.ps1` |
| QwikPlayer for Linux | `linux/install.sh` |
| Installer | `installer/QwikPlayer.iss` |

## Try your change

```powershell
.\build.ps1 -Installer    # build both Windows apps + installer
.\live.ps1                # or: push config/script/start-page edits into an installed copy instantly
.\android\build.ps1       # Android APK
.\chrome\build.ps1        # Chrome extension (.\chrome\test-extension.ps1 checks it on YouTube in headless Edge)
.\linux\build.ps1         # Linux package
```

Changes to `player/config`, `web/qwik-player.js` and `web/start.html` show up live in running apps after `.\live.ps1` — no rebuild needed.

## Pull requests

1. Fork, then branch from `main` (`fix/subtitle-popup`, `feat/pip`, …).
2. Keep the change focused; match the style of the code around it (comments explain *why*, names say *what*).
3. Test on Windows (and Android if you touched `web/` or `android/`). Mention what you tested in the PR.
4. By contributing you agree your work is released under the [MIT License](LICENSE).

## Ground rules

Be kind — see the [Code of Conduct](CODE_OF_CONDUCT.md). Features that help people pirate content or get around DRM won't be accepted.
