"""Renders the QwikPlayer launcher icons (brand A, "Afterburn") into ../res.

Needs Python 3 + Pillow and Microsoft Edge (headless, used only as an SVG rasteriser).
Run:  python tools/make-icons.py <folder with icon.svg and icon-maskable.svg>

Writes, for every density: mipmap-*/ic_launcher.png (legacy rounded-square icon, from icon.svg),
ic_launcher_foreground.png (white mark on transparent, 108dp canvas) and ic_launcher_background.png
(gradient, 108dp canvas) for the adaptive icon (mipmap-anydpi-v26/ic_launcher.xml).
The geometry is the brand's own (icon.svg / icon-maskable.svg); the mark is copied without the gradient tile.
"""
import re, subprocess, sys, tempfile
from pathlib import Path
from PIL import Image

HERE = Path(__file__).resolve().parent
RES = HERE.parent / 'res'
BRAND = Path(sys.argv[1]) if len(sys.argv) > 1 else HERE / 'brand-A'
EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"

DENS = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}


def shot(svg_text, size, out, tmp):
    """Rasterise at 1024 px (Edge paints large gradients reliably there), then scale down with Pillow."""
    big = 1024
    html = tmp / 'p.html'
    html.write_text('<!doctype html><html><body style="margin:0;background:transparent">' + svg_text + '</body></html>', encoding='utf-8')
    raw = tmp / 'raw.png'
    subprocess.run([EDGE, '--headless=new', '--disable-gpu', '--hide-scrollbars', '--no-first-run',
                    '--force-device-scale-factor=1', '--user-data-dir=' + str(tmp / 'profile'),
                    '--window-size=%d,%d' % (big, big + 200), '--screenshot=' + str(raw),
                    '--default-background-color=00000000', '--virtual-time-budget=8000', html.as_uri()],
                   check=True, timeout=120, capture_output=True)
    im = Image.open(raw).convert('RGBA').crop((0, 0, big, big))
    im.resize((size, size), Image.LANCZOS).save(out)


def main():
    main_svg = (BRAND / 'icon.svg').read_text(encoding='utf-8')
    mask_svg = (BRAND / 'icon-maskable.svg').read_text(encoding='utf-8')
    # foreground: maskable mark only (drop the two background rects), shadow kept
    fg = re.sub(r'<rect width="1024" height="1024"[^>]*/>', '', mask_svg)
    # background: gradient rect only
    defs = re.search(r'<defs>.*?</defs>', mask_svg, re.S).group(0)
    bg = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024" width="1024" height="1024">' + defs +
          '<rect width="1024" height="1024" fill="url(#qp-bg)"/><rect width="1024" height="1024" fill="url(#qp-glow)"/></svg>')
    with tempfile.TemporaryDirectory() as t:
        tmp = Path(t)
        for name, scale in DENS.items():
            d = RES / ('mipmap-' + name)
            d.mkdir(parents=True, exist_ok=True)
            shot(main_svg, round(48 * scale), d / 'ic_launcher.png', tmp)
            shot(fg, round(108 * scale), d / 'ic_launcher_foreground.png', tmp)
            shot(bg, round(108 * scale), d / 'ic_launcher_background.png', tmp)
            print('rendered', name)


main()
