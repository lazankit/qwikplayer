# Draws docs/social-preview.png (1280x640), the image GitHub shows when the repo link is shared.
# Upload it in the repo's Settings > General > Social preview.
# Composed from the brand assets only (no app screenshot, so it never shows stale UI): shared\brand\wordmark-dark.svg
# (rendered with headless Edge), docs\qwikplayer-icon.png and docs\qwikplayer-web-icon.png, tagline and platform line in Poppins.
param([string]$Edge = 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe')
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$root = Split-Path $PSScriptRoot
$fonts = New-Object System.Drawing.Text.PrivateFontCollection
$fonts.AddFontFile("$root\player\config\fonts\Poppins-SemiBold.ttf")
$fonts.AddFontFile("$root\player\config\fonts\Poppins-Regular.ttf")
$semi = $fonts.Families | Where-Object { $_.Name -eq 'Poppins SemiBold' } | Select-Object -First 1
$reg = $fonts.Families | Where-Object { $_.Name -eq 'Poppins' } | Select-Object -First 1
if (!$reg) { $reg = $fonts.Families[0] }
if (!$semi) { $semi = $reg }
function C($hex, [int]$a = 255) { $c = [System.Drawing.ColorTranslator]::FromHtml($hex); [System.Drawing.Color]::FromArgb($a, $c.R, $c.G, $c.B) }

# Wordmark SVG -> PNG (transparent), 1489x360 natural size
$tmp = Join-Path ([IO.Path]::GetTempPath()) ('qwikplayer-card-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $tmp | Out-Null
$wmSvg = ([Uri]"$PSScriptRoot\brand\wordmark-dark.svg").AbsoluteUri
$html = "$tmp\wm.html"; $wmPng = "$tmp\wm.png"
[IO.File]::WriteAllText($html, "<!doctype html><meta charset=utf-8><style>html,body{margin:0;background:transparent}img{display:block;width:1489px;height:360.3px}</style><img src=`"$wmSvg`">")
$ErrorActionPreference = 'Continue'   # Edge writes harmless warnings to stderr
& $Edge --headless=new --disable-gpu --hide-scrollbars --no-first-run --force-device-scale-factor=1 "--user-data-dir=$tmp\profile" `
  --window-size=1600,600 "--screenshot=$wmPng" --default-background-color=00000000 --virtual-time-budget=3000 ([Uri]$html).AbsoluteUri | Out-Null
$ErrorActionPreference = 'Stop'
if (!(Test-Path $wmPng)) { throw 'Edge did not render the wordmark' }

$W, $H = 1280, 640
$b = New-Object System.Drawing.Bitmap $W, $H
$g = [System.Drawing.Graphics]::FromImage($b)
$g.SmoothingMode = 'AntiAlias'; $g.TextRenderingHint = 'AntiAliasGridFit'; $g.InterpolationMode = 'HighQualityBicubic'; $g.PixelOffsetMode = 'HighQuality'
$g.Clear((C '#0D0B14'))

function Glow($cx, $cy, $rx, $ry, $hex, $alpha) {
  $p = New-Object System.Drawing.Drawing2D.GraphicsPath
  $p.AddEllipse($cx - $rx, $cy - $ry, 2 * $rx, 2 * $ry)
  $pg = New-Object System.Drawing.Drawing2D.PathGradientBrush $p
  $pg.CenterColor = C $hex $alpha
  $pg.SurroundColors = @((C $hex 0))
  $g.FillPath($pg, $p)
}
Glow 1020 330 520 420 '#7438FF' 85      # Volt behind the icons
Glow 1180 560 360 240 '#FF2D87' 45      # Pulse low right
Glow 60 -40 420 260 '#FF8A2B' 28        # Ember top left

# Icons, large, on the right: the player and the web app (same family)
$p = [System.Drawing.Image]::FromFile("$root\docs\qwikplayer-icon.png")
$w = [System.Drawing.Image]::FromFile("$root\docs\qwikplayer-web-icon.png")
$g.DrawImage($w, 985, 290, 210, 210)
$g.DrawImage($p, 720, 105, 300, 300)

# Wordmark (source art is 1489x360.3 with padding): draw at 640 px wide
$wm = [System.Drawing.Image]::FromFile($wmPng)
$ww = 640; $wh = [int](360.3 * $ww / 1489)
$g.DrawImage($wm, (New-Object System.Drawing.Rectangle 72, 170, $ww, $wh), 0, 0, 1489, 361, [System.Drawing.GraphicsUnit]::Pixel)

$fTag = New-Object System.Drawing.Font $semi, 34, ([System.Drawing.FontStyle]::Regular), ([System.Drawing.GraphicsUnit]::Pixel)
$g.DrawString('Play More. Worry Less.', $fTag, (New-Object System.Drawing.SolidBrush (C '#F5F2FB')), 100, 345)
# thin brand gradient rule
$rule = New-Object System.Drawing.Drawing2D.LinearGradientBrush (New-Object System.Drawing.Rectangle 100, 410, 360, 5), (C '#7438FF'), (C '#FF8A2B'), 0.0
$cb = New-Object System.Drawing.Drawing2D.ColorBlend 3
$cb.Colors = @((C '#7438FF'), (C '#FF2D87'), (C '#FF8A2B')); $cb.Positions = @(0.0, 0.55, 1.0); $rule.InterpolationColors = $cb
$g.FillRectangle($rule, 100, 410, 360, 5)
$fSub = New-Object System.Drawing.Font $reg, 22, ([System.Drawing.FontStyle]::Regular), ([System.Drawing.GraphicsUnit]::Pixel)
$g.DrawString(('Windows  {0}  Linux  {0}  Android  {0}  Chrome  {0}  Open source (MIT)' -f [char]0xB7), $fSub, (New-Object System.Drawing.SolidBrush (C '#A8A1BC')), 100, 440)

$b.Save("$root\docs\social-preview.png", [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $b.Dispose(); $p.Dispose(); $w.Dispose(); $wm.Dispose()
Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
"docs\social-preview.png"
