"""Motor de subtitulos dinamicos: genera un archivo ASS (libass lo quema con ffmpeg)."""
from __future__ import annotations
import hashlib
import random
from dataclasses import dataclass
from functools import lru_cache
from PIL import ImageFont

from .words import Word, group_phrases
from .faces import FaceTrack, Box
from .layout import free_anchors
from .styles import STYLES, PALETTE, StyleDef


def c6(hexrgb: str) -> str:
    r, g, b = int(hexrgb[0:2], 16), int(hexrgb[2:4], 16), int(hexrgb[4:6], 16)
    return f"&H{b:02X}{g:02X}{r:02X}&"


def darken(hexrgb: str, k: float = 0.22) -> str:
    r, g, b = (int(hexrgb[i:i + 2], 16) for i in (0, 2, 4))
    return f"{int(r * k):02X}{int(g * k):02X}{int(b * k):02X}"


def ts(t: float) -> str:
    t = max(0.0, t)
    cs = int(round(t * 100))
    return f"{cs // 360000}:{cs // 6000 % 60:02d}:{cs // 100 % 60:02d}.{cs % 100:02d}"


@lru_cache(maxsize=64)
def _font(path: str, size: int):
    return ImageFont.truetype(path, size)


def text_w(text: str, path: str, size: float) -> float:
    return _font(path, max(8, int(round(size)))).getlength(text)


@lru_cache(maxsize=16)
def height_ratio(path: str) -> float:
    """libass entiende \\fs como alto de linea (ascenso+descenso), no como tamano em.
    Para que la letra mida `size` px (em) hay que pedir fs = size * ratio."""
    asc, desc = _font(path, 1000).getmetrics()
    return (asc + desc) / 1000.0


@dataclass
class WAttr:
    italic: bool = False
    scale: float = 1.0
    color: str | None = None   # color propio (palabra clave)


@dataclass
class PhraseCtx:
    words: list
    texts: list
    style: StyleDef
    color: str
    attrs: list
    size: float
    lines: list           # lista de listas de indices
    anim: str
    hr: float = 1.2       # relacion alto-de-linea / em de la fuente
    bw: float = 0.0       # tamano normalizado del bloque
    bh: float = 0.0


def _is_key(w: str) -> bool:
    core = "".join(ch for ch in w if ch.isalnum())
    return len(core) >= 7 or any(ch.isdigit() for ch in w) or w.endswith(("!", "?"))


def _word_w(ctx_texts, attrs, st, size) -> list[float]:
    out = []
    for t, a in zip(ctx_texts, attrs):
        path = st.file_italic if (a.italic or st.italic_all) else st.file
        out.append(text_w(t, path, size) * a.scale)
    return out


def _split_lines(widths: list[float], space: float, maxw: float,
                 force2: bool = False) -> list[list[int]]:
    n = len(widths)
    total = sum(widths) + space * (n - 1)
    if n == 1 or (total <= maxw and not force2):
        return [list(range(n))]
    best, best_w = None, None
    for cut in range(1, n):
        a = sum(widths[:cut]) + space * (cut - 1)
        b = sum(widths[cut:]) + space * (n - cut - 1)
        m = max(a, b)
        if best_w is None or m < best_w:
            best, best_w = cut, m
    return [list(range(best)), list(range(best, n))]


def build_ctx(words: list[Word], st: StyleDef, color: str, anim: str,
              W: int, H: int, orientation: str, rng: random.Random) -> PhraseCtx:
    texts = [w.text.upper() if st.caps else w.text for w in words]
    attrs = [WAttr() for _ in words]
    if st.mix:
        for i, w in enumerate(words):
            if _is_key(w.text):
                attrs[i] = WAttr(italic=rng.random() < 0.5, scale=1.28,
                                 color=rng.choice([c for c in PALETTE.values() if c != color]))
            elif rng.random() < 0.3:
                attrs[i] = WAttr(italic=True)
    base = (W * 0.118) if orientation == "vertical" else (H * 0.10)
    force2 = orientation == "vertical" and len(words) >= 3 and rng.random() < 0.5
    size = base * st.size_mul
    maxw = W * (0.86 if orientation == "vertical" else 0.80)
    while True:
        widths = _word_w(texts, attrs, st, size)
        space = text_w(" ", st.file, size)
        lines = _split_lines(widths, space, maxw, force2)
        lw = max(sum(widths[i] for i in ln) + space * (len(ln) - 1) for ln in lines)
        if lw <= maxw or size < base * 0.5:
            break
        size *= 0.93
    bord = size * st.outline
    bw = (lw + 2 * bord) * 1.14 / W
    mx = max(a.scale for a in attrs)
    hr = max(height_ratio(st.file), height_ratio(st.file_italic))
    bh = (len(lines) * size * hr * mx + 2 * bord) * 1.1 / H
    return PhraseCtx(words, texts, st, color, attrs, size, lines, anim, hr, bw, bh)


def _word_block(ctx: PhraseCtx, j: int, k: int, mode: str, entry: bool) -> str:
    """Bloque de una palabra para el evento donde la palabra activa es k."""
    st, a = ctx.style, ctx.attrs[j]
    hot = a.color or ctx.color
    active = j == k
    hidden = st.reveal and j > k
    italic = a.italic or st.italic_all
    fn = st.font_italic if italic else st.font
    s = a.scale * 100.0
    # --- color
    if st.base == "neon":
        fill = "FFFFFF" if active else ctx.color
        if a.color and not active:
            fill = a.color
    else:
        fill = hot if active else (a.color or "FFFFFF")
    tags = [f"\\fn{fn}", f"\\b{st.bold}", f"\\i{1 if italic else 0}"]
    if hidden:
        tags.append("\\alpha&HFF&")
    elif mode == "glow":
        tags += ["\\alpha&H00&", "\\1a&HFF&", f"\\3c{c6(fill if st.base != 'neon' else ctx.color)}",
                 "\\3a&H40&", f"\\bord{ctx.size * 0.2:.1f}", f"\\blur{ctx.size * 0.14:.1f}"]
    else:
        out = darken(ctx.color) if st.base == "neon" else "000000"
        tags += ["\\alpha&H00&", f"\\c{c6(fill)}", f"\\3c{c6(out)}",
                 f"\\bord{ctx.size * st.outline:.1f}", "\\blur0"]
    # --- escala / animacion
    if entry and not (st.reveal and j != k):
        if ctx.anim == "zoom":
            tags.append(f"\\fscx{s * 1.6:.0f}\\fscy{s * 1.6:.0f}\\t(0,170,\\fscx{s:.0f}\\fscy{s:.0f})")
        else:
            tags.append(f"\\fscx{s * .55:.0f}\\fscy{s * .55:.0f}"
                        f"\\t(0,110,\\fscx{s * 1.12:.0f}\\fscy{s * 1.12:.0f})"
                        f"\\t(110,190,\\fscx{s:.0f}\\fscy{s:.0f})")
    elif active and not hidden:
        tags.append(f"\\fscx{s * 1.2:.0f}\\fscy{s * 1.2:.0f}\\t(0,140,\\fscx{s:.0f}\\fscy{s:.0f})")
    else:
        tags.append(f"\\fscx{s:.0f}\\fscy{s:.0f}")
    return "{" + "".join(tags) + "}" + ctx.texts[j]


def event_text(ctx: PhraseCtx, k: int, mode: str, entry: bool, pos_tag: str,
               rot: float, fad: str) -> str:
    st = ctx.style
    head = [f"\\an5", pos_tag, f"\\fs{ctx.size * ctx.hr:.0f}", f"\\frz{rot:.1f}", "\\q2"]
    if fad:
        head.append(fad)
    if st.shadow and mode != "glow":
        head += [f"\\shad{ctx.size * st.shadow:.1f}", "\\4c&H000000&", "\\4a&H50&"]
    else:
        head.append("\\shad0")
    parts = []
    for li, ln in enumerate(ctx.lines):
        blocks = []
        for j in ln:
            blocks.append(_word_block(ctx, j, k, mode, entry))
        parts.append(" ".join(blocks))
    return "{" + "".join(head) + "}" + "\\N".join(parts)


def _choose_anchor(anchors, last_zones, rng, last_pos=None):
    zones = {}
    for a in anchors:
        zones.setdefault(a.zone, []).append(a)
    cand = [z for z in zones if z not in last_zones[-2:]] or list(zones)
    # prioriza la zona menos usada recientemente y centro/medio-bajo un poco mas
    w = []
    for z in cand:
        wt = 1.0
        if z.endswith("centro"): wt *= 1.6
        if z.startswith("baja"): wt *= 1.25
        if z.startswith("alta"): wt *= 0.8
        w.append(wt)
    zone = rng.choices(cand, weights=w)[0]
    pool = zones[zone]
    if last_pos is not None:
        far = [a for a in pool if abs(a.cx - last_pos[0]) + abs(a.cy - last_pos[1]) >= 0.07]
        if far:
            pool = far
        else:  # solo queda un hueco parecido: toma el mas lejano de todos
            pool = [max(anchors, key=lambda a: abs(a.cx - last_pos[0]) + abs(a.cy - last_pos[1]))]
    return rng.choice(pool)


def build_ass(words: list[Word], width: int, height: int, track: FaceTrack | None = None,
              seed: int | None = None, style: str = "auto",
              max_words: int = 3) -> tuple[str, list[dict]]:
    orientation = "vertical" if height >= width else "horizontal"
    if seed is None:
        seed = int(hashlib.md5("|".join(w.text for w in words).encode()).hexdigest()[:8], 16)
    rng = random.Random(seed)
    phrases = group_phrases(words, max_words=max_words)
    colors = list(PALETTE.values())
    names = list(STYLES)
    last_styles, last_colors, last_zones = [], [], []
    prev_actual_end = 0.0
    last_pos = None
    lines, report = [], []
    for pi, ph in enumerate(phrases):
        nxt_start = (phrases[pi + 1][0].start - 0.04) if pi + 1 < len(phrases) else 1e9
        start = max(ph[0].start - 0.04, prev_actual_end)
        end = min(ph[-1].end + 0.14, nxt_start)
        if end - start < 0.2:
            end = min(start + 0.2, max(nxt_start, start + 0.05))
        prev_actual_end = end
        # estilo, color, animacion sin repetirse
        if style != "auto" and style in STYLES:
            st = STYLES[style]
        else:
            opts = [n for n in names if n not in last_styles[-2:]] or names
            st = STYLES[rng.choice(opts)]
        copts = [c for c in colors if c not in last_colors[-3:]]
        color = rng.choice(copts)
        anim = rng.choice(st.anims)
        ctx = build_ctx(ph, st, color, anim, width, height, orientation, rng)
        heads = track.heads_between(start, end) if track else []
        anchors = free_anchors(heads, ctx.bw, ctx.bh, orientation)
        an = _choose_anchor(anchors, last_zones, rng, last_pos)
        x, y = an.cx * width, an.cy * height
        rot = rng.uniform(-2.8, 2.8) if rng.random() < 0.6 else 0.0
        # eventos: uno por palabra activa
        times = [start] + [w.start for w in ph[1:]] + [end]
        for k in range(len(ph)):
            t0, t1 = times[k], times[k + 1]
            if t1 - t0 < 0.03:
                continue
            entry = k == 0
            if entry and anim == "slide_up":
                pos = f"\\move({x:.0f},{y + height * .035:.0f},{x:.0f},{y:.0f},0,170)"
            elif entry and anim == "slide_left":
                pos = f"\\move({x - width * .09:.0f},{y:.0f},{x:.0f},{y:.0f},0,170)"
            else:
                pos = f"\\pos({x:.0f},{y:.0f})"
            fad = ""
            if entry: fad = "\\fad(60,0)"
            if k == len(ph) - 1: fad = "\\fad(%d,90)" % (60 if entry else 0)
            if st.glow:
                lines.append(f"Dialogue: 0,{ts(t0)},{ts(t1)},Default,,0,0,0,,"
                             + event_text(ctx, k, "glow", entry, pos, rot, fad))
            lines.append(f"Dialogue: 1,{ts(t0)},{ts(t1)},Default,,0,0,0,,"
                         + event_text(ctx, k, "main", entry, pos, rot, fad))
        report.append(dict(start=start, end=end, text=" ".join(ctx.texts), style=st.name,
                           zone=an.zone, color=color, anim=anim, x=an.cx, y=an.cy,
                           bw=ctx.bw, bh=ctx.bh, overlap=an.overlap, heads=heads))
        last_pos = (an.cx, an.cy)
        last_styles.append(st.name); last_colors.append(color); last_zones.append(an.zone)
    header = (
        "[Script Info]\nScriptType: v4.00+\n"
        f"PlayResX: {width}\nPlayResY: {height}\nWrapStyle: 2\nScaledBorderAndShadow: yes\n\n"
        "[V4+ Styles]\n"
        "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, "
        "BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, "
        "BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n"
        "Style: Default,Poppins,80,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,"
        "-1,0,0,0,100,100,0,0,1,6,0,5,10,10,10,1\n\n"
        "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
    )
    return header + "\n".join(lines) + "\n", report
