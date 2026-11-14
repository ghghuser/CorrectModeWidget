"""Turns branding/icon.svg or branding/icon.png into the adaptive launcher icon. Best effort:
the workflow step ignores a failure of this script (the default icon is then kept)."""
import os, sys
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SVG = os.path.join(ROOT, "branding", "icon.svg")
PNG = os.path.join(ROOT, "branding", "icon.png")
RES = os.path.join(ROOT, "app", "src", "main", "res")
SIZE = 432  # 108 dp at xxxhdpi

if os.path.isfile(SVG):
    import cairosvg
    tmp = os.path.join(ROOT, "branding", ".icon_from_svg.png")
    cairosvg.svg2png(url=SVG, write_to=tmp, output_width=1024, output_height=1024)
    src = Image.open(tmp).convert("RGBA")
    os.remove(tmp)
elif os.path.isfile(PNG):
    src = Image.open(PNG).convert("RGBA")
else:
    print("no branding/icon.svg or icon.png - nothing to do")
    sys.exit(0)

w, h = src.size
side = min(w, h)
src = src.crop(((w - side) // 2, (h - side) // 2, (w - side) // 2 + side, (h - side) // 2 + side))
opaque = src.getchannel("A").getextrema()[0] == 255

canvas = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
if opaque:
    canvas.paste(src.resize((SIZE, SIZE), Image.LANCZOS), (0, 0))
else:
    inner = int(SIZE * 2 / 3)  # keep inside the adaptive-icon safe zone
    small = src.resize((inner, inner), Image.LANCZOS)
    canvas.paste(small, ((SIZE - inner) // 2, (SIZE - inner) // 2), small)

os.makedirs(os.path.join(RES, "drawable-nodpi"), exist_ok=True)
out = os.path.join(RES, "drawable-nodpi", "ic_launcher_user.png")
canvas.save(out)
if os.path.getsize(out) == 0:
    raise SystemExit("empty icon")

xml = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/launcher_bg" />
    <foreground android:drawable="@drawable/ic_launcher_user" />
</adaptive-icon>
"""
for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
    with open(os.path.join(RES, "mipmap-anydpi", name), "w", encoding="utf-8") as f:
        f.write(xml)
print("custom launcher icon written (opaque=%s)" % opaque)
