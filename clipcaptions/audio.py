"""Extraccion de audio con ffmpeg y datos basicos del video."""
from __future__ import annotations
import json
import subprocess
from dataclasses import dataclass


@dataclass
class VideoInfo:
    width: int
    height: int
    duration: float
    fps: float
    has_audio: bool

    @property
    def orientation(self) -> str:
        return "vertical" if self.height >= self.width else "horizontal"


def probe(path: str) -> VideoInfo:
    r = subprocess.run(
        ["ffprobe", "-v", "error", "-print_format", "json",
         "-show_streams", "-show_format", path],
        capture_output=True, text=True, check=True,
    )
    d = json.loads(r.stdout)
    v = next(s for s in d["streams"] if s["codec_type"] == "video")
    num, den = (v.get("avg_frame_rate") or "30/1").split("/")
    fps = float(num) / float(den) if float(den) else 30.0
    dur = float(d.get("format", {}).get("duration") or v.get("duration") or 0)
    rot = 0
    for sd in v.get("side_data_list", []) or []:
        rot = int(sd.get("rotation", rot) or 0)
    w, h = int(v["width"]), int(v["height"])
    if abs(rot) in (90, 270):
        w, h = h, w
    return VideoInfo(w, h, dur, fps or 30.0,
                     any(s["codec_type"] == "audio" for s in d["streams"]))


def extract_audio(video_path: str, out_path: str) -> str:
    """Audio mono 16 kHz FLAC: liviano y suficiente para Whisper."""
    subprocess.run(
        ["ffmpeg", "-y", "-v", "error", "-i", video_path, "-vn",
         "-ac", "1", "-ar", "16000", "-c:a", "flac", out_path],
        check=True,
    )
    return out_path
