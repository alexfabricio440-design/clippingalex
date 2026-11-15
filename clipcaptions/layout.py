"""Zonas libres para subtitulos: fuera de la cabeza y dentro del area segura."""
from __future__ import annotations
from dataclasses import dataclass
from .faces import Box

# Area segura (normalizada) para no chocar con la interfaz de TikTok/Reels/Shorts.
SAFE = {
    "vertical": Box(0.06, 0.09, 0.88, 0.70),     # hasta y=0.79: abajo queda la UI
    "horizontal": Box(0.05, 0.06, 0.90, 0.86),
}


@dataclass(frozen=True)
class Anchor:
    cx: float
    cy: float
    zone: str       # alta, media, baja + izquierda/centro/derecha
    overlap: float  # 0 = libre de cabezas


def _zone_name(cx: float, cy: float, safe: Box) -> str:
    ry = (cy - safe.y) / safe.h
    rx = (cx - safe.x) / safe.w
    v = "alta" if ry < 0.34 else "media" if ry < 0.67 else "baja"
    h = "izq" if rx < 0.34 else "centro" if rx < 0.67 else "der"
    return f"{v}-{h}"


def free_anchors(heads: list[Box], block_w: float, block_h: float,
                 orientation: str = "vertical", cols: int = 9, rows: int = 9,
                 gap: float = 0.015) -> list[Anchor]:
    """Posiciones (centro del bloque de texto) que no tocan ninguna cabeza.

    block_w / block_h: tamano del bloque de texto, normalizado (0-1).
    Si no queda ningun hueco, devuelve las de menor solape (nunca vacio).
    """
    safe = SAFE[orientation]
    res: list[Anchor] = []
    min_cx, max_cx = safe.x + block_w / 2, safe.x2 - block_w / 2
    min_cy, max_cy = safe.y + block_h / 2, safe.y2 - block_h / 2
    if max_cx < min_cx:
        min_cx = max_cx = 0.5
    if max_cy < min_cy:
        min_cy = max_cy = (safe.y + safe.y2) / 2
    for r in range(rows):
        cy = min_cy + (max_cy - min_cy) * (r / (rows - 1) if rows > 1 else 0.5)
        for c in range(cols):
            cx = min_cx + (max_cx - min_cx) * (c / (cols - 1) if cols > 1 else 0.5)
            blk = Box(cx - block_w / 2 - gap, cy - block_h / 2 - gap,
                      block_w + 2 * gap, block_h + 2 * gap)
            ov = sum(blk.overlap_area(h) for h in heads)
            res.append(Anchor(cx, cy, _zone_name(cx, cy, safe), ov))
    free = [a for a in res if a.overlap == 0]
    if free:
        return free
    best = min(a.overlap for a in res)
    return [a for a in res if a.overlap <= best * 1.05]
