"""
Generate Android launcher icons (mipmap-* + adaptive) from icon.png.

Output layout:
  app/src/main/res/
    mipmap-mdpi/ic_launcher.png            (48x48)
    mipmap-mdpi/ic_launcher_round.png      (48x48, circular)
    mipmap-mdpi/ic_launcher_foreground.png (108x108)
    ... (hdpi 72, xhdpi 96, xxhdpi 144, xxxhdpi 192; foreground = 1.5x base)
    mipmap-anydpi-v26/ic_launcher.xml
    mipmap-anydpi-v26/ic_launcher_round.xml
"""
import os
from PIL import Image, ImageDraw

SRC = r"D:\WorkSpace\claude\go-music-dl\android-native\icon.png"
RES = r"D:\WorkSpace\claude\go-music-dl\android-native\app\src\main\res"

# DPI buckets: base size (px) for ic_launcher (square + round)
BUCKETS = {
    "mdpi":    48,
    "hdpi":    72,
    "xhdpi":   96,
    "xxhdpi":  144,
    "xxxhdpi": 192,
}
# Adaptive icon foreground/background must be 108dp; foreground PNG = 108 * (dpi/160)
ADAPTIVE_RATIO = 108 / 48  # = 2.25, applied on top of the bucket size

# In adaptive icons, the foreground PNG is 108dp but launchers only show what
# falls inside their mask (circle/squircle/square ≈ 66dp). Most hand-crafted
# brand icons already include their own padding, so we keep the foreground at
# almost full canvas (0.92) — tiny margin to avoid hard-clipping the very edge
# without inflating the visible "halo" around the art.
SAFE_ZONE_RATIO = 0.92

def make_square(src: Image.Image, size: int) -> Image.Image:
    """Square icon with full-bleed content (legacy launchers)."""
    return src.resize((size, size), Image.LANCZOS).convert("RGBA")

def make_round(src: Image.Image, size: int) -> Image.Image:
    """Circular icon for legacy launchers requesting round."""
    base = src.resize((size, size), Image.LANCZOS).convert("RGBA")
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size, size), fill=255)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(base, (0, 0), mask)
    return out

def make_foreground(src: Image.Image, size: int) -> Image.Image:
    """Adaptive icon foreground: transparent canvas with content in the safe zone."""
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    inner = int(size * SAFE_ZONE_RATIO)
    scaled = src.resize((inner, inner), Image.LANCZOS).convert("RGBA")
    off = (size - inner) // 2
    canvas.paste(scaled, (off, off), scaled)
    return canvas

def ensure_dir(p):
    os.makedirs(p, exist_ok=True)

def main():
    src = Image.open(SRC).convert("RGBA")
    print(f"source: {src.size}")

    for bucket, base in BUCKETS.items():
        out_dir = os.path.join(RES, f"mipmap-{bucket}")
        ensure_dir(out_dir)

        sq = make_square(src, base)
        sq.save(os.path.join(out_dir, "ic_launcher.png"), optimize=True)

        rd = make_round(src, base)
        rd.save(os.path.join(out_dir, "ic_launcher_round.png"), optimize=True)

        fg_size = int(round(base * ADAPTIVE_RATIO))
        fg = make_foreground(src, fg_size)
        fg.save(os.path.join(out_dir, "ic_launcher_foreground.png"), optimize=True)

        print(f"{bucket}: square={base}px round={base}px foreground={fg_size}px")

    # Adaptive-icon descriptors (API 26+)
    anydpi = os.path.join(RES, "mipmap-anydpi-v26")
    ensure_dir(anydpi)
    xml = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/car_bg" />\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
        '    <monochrome android:drawable="@mipmap/ic_launcher_foreground" />\n'
        '</adaptive-icon>\n'
    )
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        with open(os.path.join(anydpi, name), "w", encoding="utf-8") as f:
            f.write(xml)
    print(f"wrote adaptive descriptors to {anydpi}")

if __name__ == "__main__":
    main()
