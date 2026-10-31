"""Generates the adaptive launcher icon from art/ic_launcher_source.png.

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
SOURCE = ROOT / "art" / "ic_launcher_source.png"
RES = ROOT / "app" / "src" / "main" / "res"
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
CANVAS_DP = 108
# The whole image goes inside the 108 dp canvas scaled to this size. Launcher masks keep the inner
# 66-72 dp visible, so a full-bleed square icon keeps its edges and the background colour fills the rest.
CONTENT_DP = 80


def main():
    if not SOURCE.is_file():
        sys.exit(f"Missing {SOURCE}")
    src = Image.open(SOURCE).convert("RGBA")
    corner = src.getpixel((2, 2))
    if corner[3] < 128:
        background = "#FFFFFF"
    else:
        background = "#{:02X}{:02X}{:02X}".format(*corner[:3])
    for name, scale in DENSITIES.items():
        size = round(CANVAS_DP * scale)
        content = round(CONTENT_DP * scale)
        icon = src.copy()
        icon.thumbnail((content, content), Image.LANCZOS)
        canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        canvas.paste(icon, ((size - icon.width) // 2, (size - icon.height) // 2), icon)
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
