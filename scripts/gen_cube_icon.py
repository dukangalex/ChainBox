#!/usr/bin/env python3
"""Generate a centered isometric Rubik's cube launcher icon.

Visible faces (corner toward viewer):
  正面 left  = x=0  sky-blue  #0EA5E9
  顶   top   = y=3  amber     #FBBF24
  侧面 right = x=3  rose      #F43F5E

Earlier builds drew z=0 as "front", which sits on the RIGHT half of the
icon; the left vertical face was never painted, so the 正面 looked like
the yellow top. Always draw x=0 as 正面.
"""
from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"

PLASTIC = (17, 24, 39, 255)
SKY = (14, 165, 233, 255)       # #0EA5E9 天蓝正面（比 sky-400 更深，小图标可辨）
AMBER = (251, 191, 36, 255)     # #FBBF24 橙黄顶
ROSE = (244, 63, 94, 255)       # #F43F5E 玫红侧面

# Adaptive-icon viewport 108dp, safe zone ~21-87. Scale 10 keeps the
# cube inside that box and centered at (54, 54).
SCALE = 10.0
CX = 54.0
CY = 54.0


def iso(x: float, y: float, z: float) -> tuple[float, float]:
    return CX + (x - z) * SCALE, CY + (x + z) * (SCALE * 0.5) - y * SCALE


def path(pts: list[tuple[float, float]]) -> str:
    bits = [f"M{pts[0][0]:.1f},{pts[0][1]:.1f}"]
    for x, y in pts[1:]:
        bits.append(f"L{x:.1f},{y:.1f}")
    bits.append("Z")
    return " ".join(bits)


def sticker_quad(face: str, i: int, j: int) -> list[tuple[float, float]]:
    g = 0.08
    a, b = i + g, j + g
    c, d = i + 1 - g, j + 1 - g
    if face == "front":
        # x=0 left vertical: i → z, j → y
        return [iso(0, b, a), iso(0, b, c), iso(0, d, c), iso(0, d, a)]
    if face == "top":
        return [iso(a, 3, b), iso(c, 3, b), iso(c, 3, d), iso(a, 3, d)]
    return [iso(3, b, a), iso(3, b, c), iso(3, d, c), iso(3, d, a)]


def face_outline(face: str) -> list[tuple[float, float]]:
    if face == "front":
        return [iso(0, 0, 0), iso(0, 0, 3), iso(0, 3, 3), iso(0, 3, 0)]
    if face == "top":
        return [iso(0, 3, 0), iso(3, 3, 0), iso(3, 3, 3), iso(0, 3, 3)]
    return [iso(3, 0, 0), iso(3, 0, 3), iso(3, 3, 3), iso(3, 3, 0)]


def write_vector() -> None:
    parts = [
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    android:width="108dp"',
        '    android:height="108dp"',
        '    android:viewportWidth="108"',
        '    android:viewportHeight="108">',
        "    <!-- left=sky 正面, top=amber, right=rose; adaptive safe zone -->",
    ]
    for face, color in (("front", "#111827"), ("right", "#020617"), ("top", "#1F2937")):
        parts.append(
            f'    <path android:fillColor="{color}" android:pathData="{path(face_outline(face))}" />'
        )
    colors = {"front": "#0EA5E9", "top": "#FBBF24", "right": "#F43F5E"}
    for face in ("front", "top", "right"):
        for i in range(3):
            for j in range(3):
                parts.append(
                    f'    <path android:fillColor="{colors[face]}" '
                    f'android:pathData="{path(sticker_quad(face, i, j))}" />'
                )
    parts.append("</vector>")
    (RES / "drawable/ic_launcher_foreground.xml").write_text("\n".join(parts) + "\n", encoding="utf-8")

    mono = [
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    android:width="108dp"',
        '    android:height="108dp"',
        '    android:viewportWidth="108"',
        '    android:viewportHeight="108">',
        "    <!-- cube silhouette -->",
    ]
    for face in ("front", "right", "top"):
        mono.append(
            f'    <path android:fillColor="#FFFFFF" android:pathData="{path(face_outline(face))}" />'
        )
    mono.append("</vector>")
    (RES / "drawable/ic_launcher_monochrome.xml").write_text("\n".join(mono) + "\n", encoding="utf-8")
    write_qs_tile()
    write_qs_brand()


def qs_iso(x: float, y: float, z: float) -> tuple[float, float]:
    return 12.0 + (x - z) * 2.4, 12.0 + (x + z) * 1.2 - y * 2.4


def write_qs_tile() -> None:
    """White cube silhouette for QS tile + notification small icons."""
    def face(f: str) -> list[tuple[float, float]]:
        if f == "front":
            return [qs_iso(0, 0, 0), qs_iso(0, 0, 3), qs_iso(0, 3, 3), qs_iso(0, 3, 0)]
        if f == "top":
            return [qs_iso(0, 3, 0), qs_iso(3, 3, 0), qs_iso(3, 3, 3), qs_iso(0, 3, 3)]
        return [qs_iso(3, 0, 0), qs_iso(3, 0, 3), qs_iso(3, 3, 3), qs_iso(3, 3, 0)]

    parts = [
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    android:width="24dp"',
        '    android:height="24dp"',
        '    android:viewportWidth="24"',
        '    android:viewportHeight="24">',
        "    <!-- AngelaBox cube, tinted by the system -->",
    ]
    for f in ("front", "right", "top"):
        parts.append(
            f'    <path android:fillColor="#FFFFFFFF" android:pathData="{path(face(f))}" />'
        )
    parts.append("</vector>")
    (RES / "drawable/ic_qs_tile.xml").write_text("\n".join(parts) + "\n", encoding="utf-8")


def write_qs_brand() -> None:
    """Full-color cube for the quick-settings tile (not status-bar small icons)."""
    def face(f: str) -> list[tuple[float, float]]:
        if f == "front":
            return [qs_iso(0, 0, 0), qs_iso(0, 0, 3), qs_iso(0, 3, 3), qs_iso(0, 3, 0)]
        if f == "top":
            return [qs_iso(0, 3, 0), qs_iso(3, 3, 0), qs_iso(3, 3, 3), qs_iso(0, 3, 3)]
        return [qs_iso(3, 0, 0), qs_iso(3, 0, 3), qs_iso(3, 3, 3), qs_iso(3, 3, 0)]

    colors = {"front": "#0EA5E9", "right": "#F43F5E", "top": "#FBBF24"}
    parts = [
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    android:width="24dp"',
        '    android:height="24dp"',
        '    android:viewportWidth="24"',
        '    android:viewportHeight="24">',
        "    <!-- AngelaBox cube for QS: left=sky 正面, right=rose, top=amber -->",
    ]
    for f in ("front", "right", "top"):
        parts.append(
            f'    <path android:fillColor="{colors[f]}" android:pathData="{path(face(f))}" />'
        )
    parts.append("</vector>")
    (RES / "drawable/ic_qs_brand.xml").write_text("\n".join(parts) + "\n", encoding="utf-8")


def project_px(x: float, y: float, z: float, cx: float, cy: float, s: float) -> tuple[float, float]:
    return cx + (x - z) * s, cy + (x + z) * (s * 0.5) - y * s


def draw_cube(size: int) -> Image.Image:
    img = Image.new("RGBA", (size, size), (255, 255, 255, 255))
    draw = ImageDraw.Draw(img)
    s = size * 0.118
    cx = size * 0.50
    cy = size * 0.50

    def p(x, y, z):
        return project_px(x, y, z, cx, cy, s)

    def poly(pts, fill, outline=None):
        draw.polygon(pts, fill=fill, outline=outline)

    def sticker(face, i, j, fill):
        g = 0.10
        a, b = i + g, j + g
        c, d = i + 1 - g, j + 1 - g
        if face == "front":
            pts = [p(0, b, a), p(0, b, c), p(0, d, c), p(0, d, a)]
        elif face == "top":
            pts = [p(a, 3, b), p(c, 3, b), p(c, 3, d), p(a, 3, d)]
        else:
            pts = [p(3, b, a), p(3, b, c), p(3, d, c), p(3, d, a)]
        poly(pts, fill)

    poly([p(0, 0, 0), p(0, 0, 3), p(0, 3, 3), p(0, 3, 0)], PLASTIC)
    poly([p(3, 0, 0), p(3, 0, 3), p(3, 3, 3), p(3, 3, 0)], (2, 6, 23, 255))
    poly([p(0, 3, 0), p(3, 3, 0), p(3, 3, 3), p(0, 3, 3)], (31, 41, 55, 255))

    for i in range(3):
        for j in range(3):
            sticker("front", i, j, SKY)
            sticker("top", i, j, AMBER)
            sticker("right", i, j, ROSE)
    return img


def round_mask(img: Image.Image) -> Image.Image:
    size = img.size[0]
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    mask = Image.new("L", img.size, 0)
    d = ImageDraw.Draw(mask)
    d.ellipse((0, 0, size - 1, size - 1), fill=255)
    out.paste(img, (0, 0))
    out.putalpha(mask)
    return out


def save_resized(master: Image.Image, path: Path, size: int, rounded: bool) -> None:
    im = master.resize((size, size), Image.Resampling.LANCZOS)
    if rounded:
        im = round_mask(im)
    path.parent.mkdir(parents=True, exist_ok=True)
    im.save(path, "PNG")


def main() -> None:
    write_vector()
    master = draw_cube(1024)
    sizes = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }
    for folder, px in sizes.items():
        save_resized(master, RES / folder / "ic_launcher.png", px, False)
        save_resized(master, RES / folder / "ic_launcher_round.png", px, True)
    play = master.resize((512, 512), Image.Resampling.LANCZOS)
    play.save(ROOT / "app/src/main/ic_launcher-playstore.png", "PNG")
    other = ROOT / "app/src/other/play/listings/en-US/graphics/icon/ic_launcher-playstore.png"
    other.parent.mkdir(parents=True, exist_ok=True)
    play.save(other, "PNG")
    hd = Path("/workspace/artifacts")
    hd.mkdir(parents=True, exist_ok=True)
    master.save(hd / "AngelaBox-icon-1024.png", "PNG")
    master.resize((512, 512), Image.Resampling.LANCZOS).save(hd / "AngelaBox-icon-512.png", "PNG")
    bbox_pts = [
        iso(0, 0, 0), iso(0, 0, 3), iso(3, 0, 0), iso(3, 0, 3),
        iso(0, 3, 0), iso(3, 3, 3),
    ]
    xs = [p[0] for p in bbox_pts]
    ys = [p[1] for p in bbox_pts]
    print(
        f"vector bbox x={min(xs):.1f}-{max(xs):.1f} y={min(ys):.1f}-{max(ys):.1f} "
        f"cx={(min(xs)+max(xs))/2:.1f} cy={(min(ys)+max(ys))/2:.1f}"
    )
    print("cube icons written")


if __name__ == "__main__":
    main()
