"""Iconos del launcher de Android. Uso: python scripts/make_android_icons.py"""
import os, sys
sys.path.insert(0, os.path.dirname(__file__))
from make_icons import icon, ROOT

SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
for d, px in SIZES.items():
    out = os.path.join(ROOT, "android", "app", "src", "main", "res", f"mipmap-{d}")
    os.makedirs(out, exist_ok=True)
    icon(px * 2, 0.04).resize((px, px)).save(os.path.join(out, "ic_launcher.png"))
print("Iconos Android listos")
