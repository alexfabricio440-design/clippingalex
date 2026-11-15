"""Genera los iconos de la app (PNG). Uso: python scripts/make_icons.py"""
import os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(__file__))
FONT = os.path.join(ROOT, "assets", "fonts", "Inter-Black.otf")
OUT = os.path.join(ROOT, "web", "static")


def icon(size: int, pad: float) -> Image.Image:
    img = Image.new("RGB", (size, size))
    px = img.load()
    for y in range(size):
        for x in range(size):
            t = (x + y) / (2 * size)
            px[x, y] = (int(255 * (1 - t) + 123 * t), int(45 * (1 - t) + 44 * t), int(149 * (1 - t) + 255 * t))
    d = ImageDraw.Draw(img)
    f = ImageFont.truetype(FONT, int(size * (0.5 - pad)))
    w = d.textlength("C", font=f)
    x0 = size / 2 - w - size * 0.01
    d.text((x0 + 3, size * 0.5), "C", font=f, fill=(0, 0, 0), anchor="lm")
    d.text((x0, size * 0.5), "C", font=f, fill=(255, 255, 255), anchor="lm")
    d.text((size / 2 + size * 0.02 + 3, size * 0.5), "C", font=f, fill=(0, 0, 0), anchor="lm")
    d.text((size / 2 + size * 0.02, size * 0.5), "C", font=f, fill=(182, 255, 0), anchor="lm")
    return img


if __name__ == "__main__":
    icon(192, 0.04).save(os.path.join(OUT, "icon-192.png"))
    icon(512, 0.04).save(os.path.join(OUT, "icon-512.png"))
    icon(512, 0.12).save(os.path.join(OUT, "icon-maskable-512.png"))
    print("Iconos listos")
