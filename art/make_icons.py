"""Generates the adaptive launcher icon from art/gg.png.

Usage (from the repository root, needs Pillow):  python3 art/make_icons.py

Writes app/src/main/res/mipmap-*/ic_launcher_foreground.png (108 dp canvases) and sets
ic_launcher_background in app/src/main/res/values/colors.xml to the source image's corner colour,
so circle/squircle launcher masks show no seam.
"""
import re
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "art" / "gg.png"
RES = ROOT / "app" / "src" / "main" / "res"
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
CANVAS_DP = 108
# An opaque, full-bleed square image goes inside the 108 dp canvas scaled to this size. Launcher masks
# keep the inner 66-72 dp visible, so the square keeps its edges and the background colour fills the rest.
CONTENT_DP = 80
# A shaped image on a transparent background (e.g. the GG sun) is cropped to its visible pixels and
# fitted into the 66 dp safe zone that no launcher mask cuts. Keep art/gg.png large and sharp; the
# script does the downscaling (LANCZOS), so the size is set here and not by shrinking the source.
SAFE_DP = 66


def fit(size, box):
    """Scales (w, h) so the longer side equals box (up or down)."""
    w, h = size
    k = box / max(w, h)
    return max(1, round(w * k)), max(1, round(h * k))


def main():
    if not SOURCE.is_file():
        sys.exit(f"Missing {SOURCE}")
    src = Image.open(SOURCE).convert("RGBA")
    corner = src.getpixel((2, 2))
    if corner[3] < 128:
        background = "#FFFFFF"
        src = src.crop(src.getchannel("A").point(lambda a: 255 if a > 8 else 0).getbbox())
        content_dp = SAFE_DP
    else:
        background = "#{:02X}{:02X}{:02X}".format(*corner[:3])
        content_dp = CONTENT_DP
    for name, scale in DENSITIES.items():
        size = round(CANVAS_DP * scale)
        content = round(content_dp * scale)
        icon = src.copy()
        icon = icon.resize(fit(icon.size, content), Image.LANCZOS)
        canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        canvas.alpha_composite(icon, ((size - icon.width) // 2, (size - icon.height) // 2))
        out = RES / f"mipmap-{name}" / "ic_launcher_foreground.png"
        out.parent.mkdir(parents=True, exist_ok=True)
        canvas.save(out, optimize=True)
        print(f"{out.relative_to(ROOT)}  {size}x{size}")
    colors = RES / "values" / "colors.xml"
    text = colors.read_text()
    text = re.sub(r'(<color name="ic_launcher_background">)[^<]*(</color>)', rf"\g<1>{background}\g<2>", text)
    colors.write_text(text)
    print(f"ic_launcher_background = {background}")


if __name__ == "__main__":
    main()
