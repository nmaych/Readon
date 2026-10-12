"""
Generate the Readon launcher icons from the supplied artwork.

The supplied artwork is the source of truth and is never redrawn. This script
only derives the density-specific PNGs a launcher needs, and it has to solve one
problem the artwork itself does not: the PNG sits on a white field, so its
rounded corners are white. Handed straight to a launcher as an adaptive icon,
those white corners show through as pale wedges wherever the mask crops inside
the artwork's own rounded square.

So the white field is flood-filled from the corners with the artwork's own navy
before anything is derived. The fill starts at the corners and is bounded by the
artwork's navy border, which means it can never leak into the white page *inside*
the mark -- the one place a naive colour replacement would do real damage.

  * legacy icons        -- the untouched artwork, resized;
  * round icons         -- the untouched artwork masked to a circle;
  * adaptive foreground -- the field-filled artwork scaled into the 108dp layer,
                           over a background of the same navy.

LANCZOS is the right filter here: flat colour with hard edges, being downscaled.
"""

import collections
import os

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ART = os.path.normpath(os.path.join(HERE, "..", "art"))
RES = os.path.normpath(os.path.join(HERE, "..", "app-project", "app", "src", "main", "res"))
SOURCE = os.path.join(ART, "original-icon.png")

# The adaptive layer is 108dp and the launcher may crop to the central 72dp. The
# mark spans ~73% of the artwork, so the artwork is scaled to ~0.90 of the layer:
# that puts the mark just inside the safe zone while leaving the navy background
# visible all the way to the mask edge.
FOREGROUND_SCALE = 0.90

DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
FG_DENSITIES = {k: int(round(v * 108 / 48)) for k, v in DENSITIES.items()}

SUPERSAMPLE = 4


def near(a, b, tol=28):
    return all(abs(int(x) - int(y)) <= tol for x, y in zip(a[:3], b[:3]))


def field_colour(img: Image.Image):
    """The artwork's navy field, and whether the canvas has a white margin."""
    w, h = img.size
    corner = img.getpixel((0, 0))

    # Bounding box of everything that is not the corner colour. A white field
    # makes this smaller than the canvas; a full-bleed icon makes it the canvas.
    px = img.load()
    minx, miny, maxx, maxy = w, h, -1, -1
    step = 1
    for y in range(0, h, step):
        for x in range(0, w, step):
            if not near(px[x, y], corner):
                if x < minx:
                    minx = x
                if y < miny:
                    miny = y
                if x > maxx:
                    maxx = x
                if y > maxy:
                    maxy = y

    if maxx < 0:
        return corner, None

    margin = minx > 0 or miny > 0 or maxx < w - 1 or maxy < h - 1

    # Sample the navy just inside the mark's edge, where the field is flat and
    # far from any antialiased boundary.
    midx = (minx + maxx) // 2
    probe = [
        (midx, min(miny + 4, h - 1)),
        (midx, max(maxy - 4, 0)),
        (min(minx + 4, w - 1), (miny + maxy) // 2),
        (max(maxx - 4, 0), (miny + maxy) // 2),
    ]
    samples = [img.getpixel(p) for p in probe]
    navy = collections.Counter(s[:3] for s in samples).most_common(1)[0][0]

    return navy, (minx, miny, maxx, maxy) if margin else None


def fill_field(img: Image.Image, navy) -> Image.Image:
    """
    Replace the white margin with the artwork's navy.

    Flood-filled from all four corners rather than replaced by colour: a global
    "white -> navy" swap would punch holes in the white page the letter R sits on.
    """
    out = img.copy()
    w, h = out.size
    value = (navy[0], navy[1], navy[2], 255)
    for xy in ((0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)):
        ImageDraw.floodfill(out, xy, value, thresh=40)
    return out


def legacy(img: Image.Image, px: int) -> Image.Image:
    return img.resize((px, px), Image.LANCZOS)


def round_icon(img: Image.Image, px: int) -> Image.Image:
    s = px * SUPERSAMPLE
    base = img.resize((s, s), Image.LANCZOS)
    mask = Image.new("L", (s, s), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, s - 1, s - 1], fill=255)
    out = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    out.paste(base, (0, 0), mask)
    return out.resize((px, px), Image.LANCZOS)


def foreground(filled: Image.Image, px: int) -> Image.Image:
    s = px * SUPERSAMPLE
    side = int(round(s * FOREGROUND_SCALE))
    art = filled.resize((side, side), Image.LANCZOS)
    out = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    offset = (s - side) // 2
    out.paste(art, (offset, offset), art)
    return out.resize((px, px), Image.LANCZOS)


def main():
    if not os.path.exists(SOURCE):
        raise SystemExit(f"original artwork missing: {SOURCE}")

    src = Image.open(SOURCE).convert("RGBA")
    navy, margin = field_colour(src)
    print(f"source {src.size[0]}x{src.size[1]}")
    print(f"navy field #{navy[0]:02X}{navy[1]:02X}{navy[2]:02X}")
    print(f"white margin: {'yes ' + str(margin) if margin else 'no (full bleed)'}")

    filled = fill_field(src, navy) if margin else src

    for name, px in DENSITIES.items():
        d = os.path.join(RES, f"mipmap-{name}")
        os.makedirs(d, exist_ok=True)
        # Legacy and round keep the artwork exactly as supplied, white field and
        # all: that is what "the original icon" means on those slots.
        legacy(src, px).save(os.path.join(d, "ic_launcher.png"))
        round_icon(src, px).save(os.path.join(d, "ic_launcher_round.png"))
        foreground(filled, FG_DENSITIES[name]).save(
            os.path.join(d, "ic_launcher_foreground.png")
        )

    colours = os.path.join(RES, "values", "colors.xml")
    os.makedirs(os.path.dirname(colours), exist_ok=True)
    with open(colours, "w", encoding="utf-8") as fh:
        fh.write(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            "<resources>\n"
            f'    <color name="ic_launcher_background">'
            f"#{navy[0]:02X}{navy[1]:02X}{navy[2]:02X}</color>\n"
            "</resources>\n"
        )

    legacy(src, 512).save(os.path.join(ART, "preview-legacy-512.png"))
    foreground(filled, 432).save(os.path.join(ART, "preview-foreground-432.png"))
    round_icon(src, 512).save(os.path.join(ART, "preview-round-512.png"))
    print("icons written to", RES)


if __name__ == "__main__":
    main()
