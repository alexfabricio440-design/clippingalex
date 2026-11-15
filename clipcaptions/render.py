"""Quema el ASS en el video con ffmpeg (libass)."""
from __future__ import annotations
import subprocess
from .styles import FONT_DIR


def _esc(p: str) -> str:
    return p.replace("\\", "/").replace(":", "\\:").replace("'", "\\'")


def burn(video: str, ass_path: str, out: str, crf: int = 18, preset: str = "veryfast",
         fontsdir: str = FONT_DIR, verbose: bool = False) -> str:
    vf = f"ass='{_esc(ass_path)}':fontsdir='{_esc(fontsdir)}'"
    cmd = ["ffmpeg", "-y", "-v", "verbose" if verbose else "error", "-i", video,
           "-vf", vf, "-map", "0:v:0", "-map", "0:a?", "-c:v", "libx264",
           "-preset", preset, "-crf", str(crf), "-pix_fmt", "yuv420p",
           "-c:a", "copy", "-movflags", "+faststart", out]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode:
        raise RuntimeError("ffmpeg fallo: " + r.stderr[-600:])
    return r.stderr if verbose else out
