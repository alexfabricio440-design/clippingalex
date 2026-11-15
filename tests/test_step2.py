import os, subprocess, sys
sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))
import cv2, numpy as np, pytest
from skimage import data
from clipcaptions.faces import Box, FaceTrack, CascadeDetector, track_faces
from clipcaptions.layout import free_anchors, SAFE


def make_video(path, w=600, h=960, secs=3, fps=12, move=True):
    """Video vertical: el astronauta (cara real) se mueve un poco sobre fondo."""
    face = cv2.cvtColor(data.astronaut(), cv2.COLOR_RGB2BGR)
    out = cv2.VideoWriter(path, cv2.VideoWriter_fourcc(*"mp4v"), fps, (w, h))
    for i in range(secs * fps):
        f = np.full((h, w, 3), (40, 30, 30), np.uint8)
        ox = int((i / (secs * fps)) * 20) if move else 0
        f[200:200 + 512, 14 + ox:14 + ox + 512] = face
        out.write(f)
    out.release()
    return path


def test_box_geometry():
    a, b = Box(0.1, 0.1, 0.2, 0.2), Box(0.25, 0.25, 0.2, 0.2)
    assert a.intersects(b) and a.overlap_area(b) == pytest.approx(0.0025)
    assert not a.intersects(Box(0.5, 0.5, 0.1, 0.1))
    hd = Box(0.4, 0.4, 0.2, 0.2).head()
    assert hd.x < 0.4 and hd.y < 0.4 and hd.y2 > 0.6


def test_hold_and_gaps():
    t = FaceTrack(samples=[(0.0, [Box(.4, .3, .2, .1)]), (0.25, []), (3.0, [])])
    assert t.faces_at(0.5)           # dentro de hold
    assert not t.faces_at(2.5)       # demasiado lejos
    assert t.has_faces


def test_heads_union_covers_movement():
    t = FaceTrack(samples=[(0.0, [Box(.2, .3, .2, .1)]), (1.0, [Box(.6, .3, .2, .1)])])
    r = t.heads_between(0.0, 1.0)
    # ambas posiciones de la cara quedan cubiertas por alguna zona
    for cx in (0.3, 0.7):
        assert any(z.x < cx < z.x2 for z in r)


def test_real_face_tracking(tmp_path):
    v = make_video(str(tmp_path / "v.mp4"))
    tr = track_faces(v, sample_fps=4, detector=CascadeDetector())
    found = [b for _, bs in tr.samples for b in bs]
    assert len(found) >= len(tr.samples) * 0.8, "debe detectar la cara en casi todos los cuadros"
    # la cara real esta en x 190-290 / 540 y y 262-372 / 960
    b = found[0]
    assert 0.25 < b.x + b.w / 2 < 0.55 and 0.25 < b.y + b.h / 2 < 0.45


@pytest.mark.parametrize("orient", ["vertical", "horizontal"])
def test_anchors_never_touch_head(orient):
    heads = [Box(0.30, 0.20, 0.35, 0.30)]
    for bw, bh in [(0.5, 0.07), (0.8, 0.12), (0.3, 0.05)]:
        an = free_anchors(heads, bw, bh, orient)
        assert an
        for a in an:
            if a.overlap == 0:
                blk = Box(a.cx - bw / 2, a.cy - bh / 2, bw, bh)
                assert not any(blk.intersects(h) for h in heads)
            s = SAFE[orient]
            assert s.x - 1e-6 <= a.cx - bw / 2 and a.cx + bw / 2 <= s.x2 + 1e-6


def test_anchors_have_variety():
    an = free_anchors([Box(0.30, 0.35, 0.40, 0.25)], 0.5, 0.07, "vertical")
    assert len({a.zone for a in an}) >= 3   # arriba, abajo, etc.


def test_huge_face_still_returns_least_overlap():
    an = free_anchors([Box(0.0, 0.0, 1.0, 1.0)], 0.5, 0.1, "vertical")
    assert an and all(a.overlap > 0 for a in an)


def test_end_to_end_subtitles_avoid_real_face(tmp_path):
    v = make_video(str(tmp_path / "v.mp4"))
    tr = track_faces(v, 4, CascadeDetector())
    heads = tr.heads_between(0.5, 1.5)
    assert heads
    for a in free_anchors(heads, 0.6, 0.08, "vertical"):
        blk = Box(a.cx - .3, a.cy - .04, .6, .08)
        assert a.overlap == 0 and not any(blk.intersects(h) for h in heads)
