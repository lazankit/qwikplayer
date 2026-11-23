# Builds a QwikPlayer app icon (.ico, PNG-compressed entries 256/64/48/32/24/16) from the brand SVGs in shared\brand.
#   -Variant full : gradient field + white play mark (icon.svg, icon-small.svg)            -> QwikPlayer
#   -Variant web  : dark field + gradient play mark (icon-web.svg, icon-web-small.svg)     -> QwikPlayer Web
# 48 px and up use the detailed artwork, 32 px and below the simplified "small" artwork.
# The SVGs are rasterised with headless Microsoft Edge (any Chromium works: pass -Edge) and scaled down with GDI+.
#   .\make-icon.ps1 -Variant full -Out ..\player\launcher\qwikplayer.ico -Png256 ..\docs\qwikplayer-icon.png
#   .\make-icon.ps1 -Variant web  -Out ..\web\qwikplayer-web.ico        -Png256 ..\docs\qwikplayer-web-icon.png
param(
  [ValidateSet('full', 'web')][string]$Variant = 'full',
  [string]$Out = (Join-Path $PSScriptRoot '..\player\launcher\qwikplayer.ico'),
  [string]$Png256 = '',
  [string]$Edge = 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe'
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$brand = Join-Path $PSScriptRoot 'brand'
$names = if ($Variant -eq 'web') { 'icon-web', 'icon-web-small' } else { 'icon', 'icon-small' }
if (!(Test-Path $Edge)) { throw "Edge not found at $Edge (pass -Edge <path to a Chromium browser>)" }
$Out = [IO.Path]::GetFullPath($Out)
$tmp = Join-Path ([IO.Path]::GetTempPath()) ('qwikplayer-icon-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $tmp | Out-Null
New-Item -ItemType Directory -Force (Split-Path -Parent $Out) | Out-Null

# SVG -> 512x512 transparent PNG
function Render([string]$svg, [string]$png) {
  $html = Join-Path $tmp ([IO.Path]::GetFileNameWithoutExtension($svg) + '.html')
  $uri = ([Uri](Resolve-Path $svg).Path).AbsoluteUri
  [IO.File]::WriteAllText($html, "<!doctype html><meta charset=utf-8><style>html,body{margin:0;background:transparent}img{display:block;width:512px;height:512px}</style><img src=`"$uri`">")
  $ErrorActionPreference = 'Continue'   # Edge writes harmless warnings to stderr
  & $Edge --headless=new --disable-gpu --hide-scrollbars --no-first-run --force-device-scale-factor=1 `
    "--user-data-dir=$tmp\profile" --window-size=512,512 "--screenshot=$png" --default-background-color=00000000 --virtual-time-budget=3000 ([Uri]$html).AbsoluteUri | Out-Null
  $ErrorActionPreference = 'Stop'
  if (!(Test-Path $png)) { throw "Edge did not write $png" }
}
$big = Join-Path $tmp 'big.png'; $small = Join-Path $tmp 'small.png'
Render (Join-Path $brand "$($names[0]).svg") $big
Render (Join-Path $brand "$($names[1]).svg") $small

function Scale([string]$png, [int]$n) {
  $img = [System.Drawing.Image]::FromFile($png)
  $b = New-Object System.Drawing.Bitmap $n, $n, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
  $g = [System.Drawing.Graphics]::FromImage($b)
  $g.InterpolationMode = 'HighQualityBicubic'; $g.SmoothingMode = 'HighQuality'; $g.PixelOffsetMode = 'HighQuality'; $g.CompositingMode = 'SourceCopy'
  $g.DrawImage($img, (New-Object System.Drawing.Rectangle 0, 0, $n, $n), 0, 0, $img.Width, $img.Height, [System.Drawing.GraphicsUnit]::Pixel)
  $g.Dispose(); $img.Dispose()
  $ms = New-Object System.IO.MemoryStream
  $b.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png); $b.Dispose()
  return ,$ms.ToArray()
}

# ICO container with PNG-compressed entries (supported since Windows Vista).
$sizes = 256, 64, 48, 32, 24, 16
$pngs = $sizes | ForEach-Object { if ($_ -ge 48) { ,(Scale $big $_) } else { ,(Scale $small $_) } }
$fs = [System.IO.File]::Create($Out); $bw = New-Object System.IO.BinaryWriter $fs
$bw.Write([uint16]0); $bw.Write([uint16]1); $bw.Write([uint16]$sizes.Count)
$offset = 6 + 16 * $sizes.Count
for ($i = 0; $i -lt $sizes.Count; $i++) {
  $s = $sizes[$i]; $bw.Write([byte]($s % 256)); $bw.Write([byte]($s % 256)); $bw.Write([byte]0); $bw.Write([byte]0)
  $bw.Write([uint16]1); $bw.Write([uint16]32); $bw.Write([uint32]$pngs[$i].Length); $bw.Write([uint32]$offset)
  $offset += $pngs[$i].Length
}
foreach ($p in $pngs) { $bw.Write($p) }
$bw.Close()
if ($Png256) { [System.IO.File]::WriteAllBytes([IO.Path]::GetFullPath($Png256), $pngs[0]) }
Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
"icon: $Out"
