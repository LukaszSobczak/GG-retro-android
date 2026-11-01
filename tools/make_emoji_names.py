"""Builds app/src/main/assets/emoji_pl.tsv: Polish emoji names from Unicode CLDR annotations.

Usage (from the repository root):  python3 tools/make_emoji_names.py

Each line is "<emoji without U+FE0F>\\t<Polish name>". Data: Unicode CLDR (Unicode License v3,
see third_party/unicode-cldr/LICENSE). Used to replace emoji that have no GG 6 equivalent with
their name, e.g. "(jednorożec)".
"""
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

CLDR_RELEASE = "release-46"
URL = f"https://raw.githubusercontent.com/unicode-org/cldr/{CLDR_RELEASE}/common/annotations/pl.xml"
ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "app" / "src" / "main" / "assets" / "emoji_pl.tsv"


def main():
    with urllib.request.urlopen(URL, timeout=60) as r:
        tree = ET.fromstring(r.read())
    names = {}
    for a in tree.iter("annotation"):
        if a.get("type") != "tts":
            continue
        cp = a.get("cp", "").replace("️", "")
        name = (a.text or "").strip()
        # Only pictographs: skip plain letters/punctuation that also have annotations.
        if not cp or not name or all(ord(c) < 0x2000 for c in cp):
            continue
        names[cp] = name.replace("\t", " ").replace("\n", " ")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with OUT.open("w", encoding="utf-8", newline="\n") as f:
        f.write(f"# Polish emoji names from Unicode CLDR {CLDR_RELEASE} (Unicode License v3)\n")
        for cp in sorted(names):
            f.write(f"{cp}\t{names[cp]}\n")
    print(f"{OUT.relative_to(ROOT)}: {len(names)} names")


if __name__ == "__main__":
    main()
