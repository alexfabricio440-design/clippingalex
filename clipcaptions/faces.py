"""Deteccion y seguimiento de rostros a lo largo del video.

Motores:
- YuNet (OpenCV FaceDetectorYN): el mejor. Necesita models/face_detection_yunet_2023mar.onnx
  (se baja con `python scripts/get_models.py`).
- Cascade (scikit-image LBP): respaldo que no necesita descargas.
"""
from __future__ import annotations
import os
from dataclasses import dataclass, field
import cv2
import numpy as np

MODEL_PATH = os.path.join(os.path.dirname(os.path.dirname(__file__)),
                          "models", "face_detection_yunet_2023mar.onnx")


@dataclass(frozen=True)
class Box:
    """Caja normalizada (0-1): x,y = esquina sup. izquierda."""
    x: float
    y: float
    w: float
    h: float

    @property
    def x2(self): return self.x + self.w
    @property
    def y2(self): return self.y + self.h
    @property
    def area(self): return max(0.0, self.w) * max(0.0, self.h)

    def intersects(self, o: "Box") -> bool:
        return self.x < o.x2 and o.x < self.x2 and self.y < o.y2 and o.y < self.y2

    def overlap_area(self, o: "Box") -> float:
        w = min(self.x2, o.x2) - max(self.x, o.x)
        h = min(self.y2, o.y2) - max(self.y, o.y)
        return max(0.0, w) * max(0.0, h)

    def union(self, o: "Box") -> "Box":
        x, y = min(self.x, o.x), min(self.y, o.y)
        return Box(x, y, max(self.x2, o.x2) - x, max(self.y2, o.y2) - y)

    def head(self) -> "Box":
        """Cara -> cabeza completa (pelo arriba, cuello abajo, aire a los lados)."""
        x = self.x - 0.25 * self.w
        y = self.y - 0.45 * self.h
        w = 1.5 * self.w
        h = 1.95 * self.h
        x1, y1 = max(0.0, x), max(0.0, y)
        x2, y2 = min(1.0, x + w), min(1.0, y + h)
        return Box(x1, y1, x2 - x1, y2 - y1)


class CascadeDetector:
    name = "cascade"

    def __init__(self):
        from skimage import data
        from skimage.feature import Cascade
        self.c = Cascade(data.lbp_frontal_face_cascade_filename())

    def detect(self, bgr: np.ndarray) -> list[tuple[float, float, float, float]]:
        h, w = bgr.shape[:2]
        s = 360.0 / max(h, w) if max(h, w) > 360 else 1.0
        g = cv2.cvtColor(cv2.resize(bgr, None, fx=s, fy=s), cv2.COLOR_BGR2GRAY)
        m = min(g.shape)
        res = self.c.detect_multi_scale(
            img=g, scale_factor=1.2, step_ratio=1,
            min_size=(max(24, int(m * 0.10)),) * 2,
            max_size=(int(m * 0.95),) * 2)
        return [(d["c"] / s, d["r"] / s, d["width"] / s, d["height"] / s) for d in res]


class YuNetDetector:
    name = "yunet"

    def __init__(self, model_path: str = MODEL_PATH):
        self.model_path = model_path
        self.det = None
        self.size = None

    def detect(self, bgr: np.ndarray):
        h, w = bgr.shape[:2]
        if self.det is None or self.size != (w, h):
            self.det = cv2.FaceDetectorYN.create(self.model_path, "", (w, h), 0.6, 0.3, 20)
            self.size = (w, h)
        _, faces = self.det.detect(bgr)
        if faces is None:
            return []
        return [(float(f[0]), float(f[1]), float(f[2]), float(f[3])) for f in faces]


def get_detector(prefer: str | None = None):
    prefer = prefer or os.environ.get("CLIPCAPTIONS_DETECTOR", "auto")
    if prefer in ("auto", "yunet") and os.path.exists(MODEL_PATH):
        try:
            return YuNetDetector()
        except Exception:
            if prefer == "yunet":
                raise
    return CascadeDetector()


@dataclass
class FaceTrack:
    """Rostros muestreados en el tiempo. samples = [(t, [Box,...]), ...]"""
    samples: list[tuple[float, list[Box]]] = field(default_factory=list)
    hold: float = 1.0  # segundos que se mantiene la ultima cara si deja de detectarse

    @property
    def has_faces(self) -> bool:
        return any(b for _, b in self.samples)

    def faces_at(self, t: float) -> list[Box]:
        """Caras en el instante t; si no hay, usa la deteccion mas cercana (<= hold)."""
        best, dist = [], None
        for ts, boxes in self.samples:
            if not boxes:
                continue
            d = abs(ts - t)
            if dist is None or d < dist:
                best, dist = boxes, d
        return best if dist is not None and dist <= self.hold else []

    def heads_between(self, t0: float, t1: float, margin: float = 0.25) -> list[Box]:
        """Zonas de cabeza que ocupan el rostro en toda la ventana [t0, t1].

        Une las posiciones de cada cara durante la ventana, asi el texto
        nunca pisa la cara aunque la persona se mueva.
        """
        raw: list[Box] = []
        times = [t0 - margin, t0, (t0 + t1) / 2, t1, t1 + margin]
        times += [ts for ts, _ in self.samples if t0 - margin <= ts <= t1 + margin]
        for t in times:
            raw += [f.head() for f in self.faces_at(t)]
        # une cajas que se tocan hasta que no quede ninguna superpuesta
        regions = raw
        changed = True
        while changed:
            changed, merged = False, []
            for b in regions:
                for i, m in enumerate(merged):
                    if m.intersects(b):
                        merged[i] = m.union(b)
                        changed = True
                        break
                else:
                    merged.append(b)
            regions = merged
        return regions


def track_faces(video_path: str, sample_fps: float = 4.0, detector=None,
                max_seconds: float | None = None) -> FaceTrack:
    det = detector or get_detector()
    cap = cv2.VideoCapture(video_path)
    if not cap.isOpened():
        raise RuntimeError(f"No pude abrir el video: {video_path}")
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    step = max(1, int(round(fps / sample_fps)))
    track = FaceTrack()
    i = 0
    while True:
        if not cap.grab():
            break
        if i % step == 0:
            ok, frame = cap.retrieve()
            if not ok:
                break
            t = i / fps
            if max_seconds and t > max_seconds:
                break
            h, w = frame.shape[:2]
            boxes = []
            for (x, y, bw, bh) in det.detect(frame):
                b = Box(max(0, x / w), max(0, y / h), bw / w, bh / h)
                if b.area > 0.0004:
                    boxes.append(b)
            track.samples.append((t, boxes))
        i += 1
    cap.release()
    return track
