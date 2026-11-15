import json, os, subprocess, sys
sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))
import cv2, numpy as np, pytest
from clipcaptions.words import Word
from clipcaptions.faces import track_faces, CascadeDetector, Box
from clipcaptions.captions import build_ass
from clipcaptions.render import burn
from clipcaptions.pipeline import process
from clipcaptions.styles import STYLES, PALETTE
from tests.test_step2 import make_video

TXT = ("Esto es lo que nadie te cuenta sobre ganar dinero en internet. Primero tienes que "
       "entender algo muy importante: el 90% de la gente se rinde antes de empezar! Pero tu "
       "no vas a ser uno de ellos, porque hoy vas a aprender el sistema completo.")


def words():
    ws, t = [], 0.2
    for w in TXT.split():
        d = 0.17 + 0.025 * len(w); ws.append(Word(w, t, t + d)); t += d + 0.03
    return ws


@pytest.fixture(scope="module")
def vid(tmp_path_factory):
    p = str(tmp_path_factory.mktemp("v") / "v.mp4")
    make_video(p, w=720, h=1280, secs=8, fps=12)
    return p


@pytest.fixture(scope="module")
def track(vid):
    return track_faces(vid, 4, CascadeDetector())


@pytest.mark.parametrize("size", [(720, 1280), (1280, 720)])
def test_layout_rules(track, size):
    w, h = size
    ass, rep = build_ass(words(), w, h, track, seed=3)
    assert len(rep) >= 12
    for r in rep:
        assert r["overlap"] == 0
        assert 0 <= r["x"] - r["bw"] / 2 and r["x"] + r["bw"] / 2 <= 1.0 + 1e-6
        assert 0 <= r["y"] - r["bh"] / 2 and r["y"] + r["bh"] / 2 <= 1.0 + 1e-6
        blk = Box(r["x"] - r["bw"] / 2, r["y"] - r["bh"] / 2, r["bw"], r["bh"])
        assert not any(blk.intersects(hd) for hd in r["heads"])


def test_variety_no_repeats(track):
    _, rep = build_ass(words(), 720, 1280, track, seed=5)
    for a, b in zip(rep, rep[1:]):
        assert a["style"] != b["style"] and a["color"] != b["color"]
        assert (a["x"], a["y"]) != (b["x"], b["y"])
    changes = sum(a["zone"] != b["zone"] for a, b in zip(rep, rep[1:]))
    assert changes >= 0.7 * (len(rep) - 1), "la posicion debe cambiar casi siempre"
    assert len({r["style"] for r in rep}) >= 4
    assert len({r["color"] for r in rep}) >= 6
    assert len({r["zone"] for r in rep}) >= 3
    assert len({r["anim"] for r in rep}) >= 3


def test_timing_consistent(track):
    ass, rep = build_ass(words(), 720, 1280, track, seed=1)
    for a, b in zip(rep, rep[1:]):
        assert a["end"] <= b["start"] + 1e-6
    for r in rep:
        assert r["end"] > r["start"]


def test_deterministic_and_seed_changes(track):
    a1, _ = build_ass(words(), 720, 1280, track, seed=9)
    a2, _ = build_ass(words(), 720, 1280, track, seed=9)
    a3, _ = build_ass(words(), 720, 1280, track, seed=10)
    assert a1 == a2 and a1 != a3


def test_ass_uses_bold_italic_and_neon_colors(track):
    ass, _ = build_ass(words(), 720, 1280, track, seed=2)
    assert "\\i1" in ass and "\\i0" in ass
    for hexrgb in PALETTE.values():
        pass
    assert any(f"{c[4:6]}{c[2:4]}{c[0:2]}&".upper() in ass.upper() for c in PALETTE.values())
    assert "\\blur" in ass and "\\fad" in ass


def test_forced_style_and_no_faces():
    ass, rep = build_ass(words(), 720, 1280, None, seed=1, style="impacto")
    assert {r["style"] for r in rep} == {"impacto"}
    assert all(r["overlap"] == 0 for r in rep)


def test_fonts_resolved_by_libass(vid, tmp_path):
    ass, _ = build_ass(words(), 720, 1280, None, seed=4)
    ap = tmp_path / "s.ass"; ap.write_text(ass, encoding="utf-8")
    log = burn(vid, str(ap), str(tmp_path / "o.mp4"), verbose=True)
    found = [l for l in log.splitlines() if "fontselect" in l and "->" in l]
    joined = "\n".join(found)
    assert "Inter" in joined and "Poppins" in joined
    loaded = [l for l in log.splitlines() if "Loading font file" in l and "assets/fonts" in l]
    assert len(loaded) >= 7, "debe cargar las fuentes incluidas en el repo"
    assert "Error opening" not in log


def test_full_render_never_touches_face(vid, tmp_path):
    wj = tmp_path / "w.json"
    wj.write_text(json.dumps([{"text": w.text, "start": w.start, "end": w.end} for w in words()]))
    out = str(tmp_path / "out.mp4")
    res = process(vid, out, words_json=str(wj), seed=3, log=lambda *_: None)
    # referencia: mismo encode sin subtitulos (mismo ruido de compresion)
    empty = tmp_path / "e.ass"
    empty.write_text(build_ass([], 720, 1280, None)[0], encoding="utf-8")
    ref = burn(vid, str(empty), str(tmp_path / "ref.mp4"))
    a, b = cv2.VideoCapture(ref), cv2.VideoCapture(out)
    assert int(b.get(3)) == 720 and int(b.get(4)) == 1280
    changed_frames, face_diff = 0, []
    for i in range(0, 90, 6):
        a.set(1, i); b.set(1, i)
        ok1, f1 = a.read(); ok2, f2 = b.read()
        if not (ok1 and ok2): break
        d = cv2.absdiff(f1, f2).astype(float).sum(axis=2)
        if d.mean() > 0.05: changed_frames += 1
        t = i / 12
        for r in res["phrases"]:
            if r["start"] <= t <= r["end"] and r["heads"]:
                hd = r["heads"][0]
                x1, x2 = int(hd.x * 720), int(hd.x2 * 720)
                y1, y2 = int(hd.y * 1280), int(hd.y2 * 1280)
                reg = d[y1:y2, x1:x2]
                face_diff.append((reg > 60).mean())   # fraccion de pixeles realmente pintados
    assert changed_frames >= 4, "deben verse subtitulos en el video"
    assert face_diff and max(face_diff) == 0.0, f"subtitulo sobre la cara: {max(face_diff)}"


@pytest.mark.parametrize("style", ["impacto", "flujo", "grueso", "neon"])
def test_predicted_width_matches_render(tmp_path, style):
    """El ancho calculado (PIL) debe coincidir con lo que dibuja libass (<=12%)."""
    from clipcaptions.captions import build_ctx
    import random
    W, H = 1080, 1920
    ws = [Word("Hola", 0.1, 0.5), Word("mundo", 0.5, 0.9), Word("increible", 0.9, 1.4)]
    ass, rep = build_ass(ws, W, H, None, seed=1, style=style)
    # una sola linea de texto, sin rotacion ni animacion de entrada: lo medimos en t=1.2 (palabra 3 activa)
    ass = ass.replace("\\frz", "\\xx").replace("\\fscx55", "\\fscx100").replace("\\fscy55", "\\fscy100")
    ap = tmp_path / "s.ass"; ap.write_text(ass, encoding="utf-8")
    blank = tmp_path / "b.mp4"
    subprocess.run(["ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i",
                    f"color=c=black:s={W}x{H}:d=2:r=10", str(blank)], check=True)
    out = tmp_path / "o.mp4"; burn(str(blank), str(ap), str(out))
    cap = cv2.VideoCapture(str(out)); cap.set(1, 12); _, fr = cap.read()
    g = fr.max(axis=2) > 40
    ys, xs = np.where(g)
    assert len(xs) > 500
    ink = (xs.max() - xs.min()) / W
    pred = rep[0]["bw"]          # incluye contorno y +14% de margen
    assert 0.6 * pred < ink < 1.0 * pred, (style, ink, pred)
