"""Catalogo de estilos de subtitulos y paleta de colores brillantes."""
from __future__ import annotations
import os
from dataclasses import dataclass, field

FONT_DIR = os.path.join(os.path.dirname(os.path.dirname(__file__)), "assets", "fonts")


def _f(name: str) -> str:
    return os.path.join(FONT_DIR, name)


# Colores vivos/neon (RGB). Ninguno es gris ni apagado.
PALETTE = {
    "rosa": "FF2D95",
    "cian": "00E5FF",
    "lima": "B6FF00",
    "amarillo": "FFE600",
    "naranja": "FF7A00",
    "violeta": "B14DFF",
    "menta": "00FF9C",
    "rojo": "FF3347",
}


@dataclass(frozen=True)
class StyleDef:
    name: str
    font: str             # familia para libass (normal)
    font_italic: str      # familia para libass (cursiva)
    file: str             # archivo para medir (normal)
    file_italic: str      # archivo para medir (cursiva)
    bold: int = 1         # \b para libass
    caps: bool = False
    size_mul: float = 1.0
    outline: float = 0.07     # grosor del contorno (x tamano de letra)
    shadow: float = 0.0
    glow: bool = False
    reveal: bool = False      # las palabras aparecen al decirse
    base: str = "white"       # white | neon (color base del texto)
    italic_all: bool = False
    mix: bool = False         # palabras con tamano/cursiva variados
    anims: tuple = ("pop",)


STYLES: dict[str, StyleDef] = {s.name: s for s in [
    StyleDef("impacto", "Inter Black", "Inter Black", _f("Inter-Black.otf"),
             _f("Inter-BlackItalic.otf"), bold=0, caps=True, size_mul=1.0,
             outline=0.085, shadow=0.05, anims=("pop", "zoom", "slide_up")),
    StyleDef("flujo", "Poppins", "Poppins", _f("Poppins-Bold.ttf"),
             _f("Poppins-BoldItalic.ttf"), size_mul=0.95, outline=0.07,
             shadow=0.03, italic_all=True, anims=("slide_up", "slide_left", "pop")),
    StyleDef("neon", "Inter ExtraBold", "Inter ExtraBold", _f("Inter-ExtraBold.otf"),
             _f("Inter-ExtraBoldItalic.otf"), bold=0, caps=True, size_mul=0.95,
             outline=0.05, glow=True, base="neon", anims=("zoom", "pop")),
    StyleDef("mixto", "Poppins", "Poppins", _f("Poppins-Bold.ttf"),
             _f("Poppins-BoldItalic.ttf"), size_mul=0.95, outline=0.075,
             shadow=0.04, mix=True, anims=("pop", "slide_up", "zoom")),
    StyleDef("aparece", "Inter Black", "Inter Black", _f("Inter-Black.otf"),
             _f("Inter-BlackItalic.otf"), bold=0, caps=True, size_mul=1.05,
             outline=0.09, shadow=0.05, reveal=True, anims=("pop",)),
    StyleDef("grueso", "Poppins", "Poppins", _f("Poppins-Bold.ttf"),
             _f("Poppins-BoldItalic.ttf"), size_mul=1.0, outline=0.1,
             shadow=0.06, anims=("pop", "slide_left")),
]}
