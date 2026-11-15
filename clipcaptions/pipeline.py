"""Flujo completo: video -> transcripcion -> rostros -> ASS -> video con subtitulos."""
from __future__ import annotations
import json
import os
import tempfile
from .audio import probe, extract_audio
from .captions import build_ass
from .faces import track_faces
from .render import burn
from .transcribe import transcribe
from .words import Word, clean_words


def load_words(path: str) -> list[Word]:
    """Lee palabras de un JSON: [{"text":..,"start":..,"end":..}] (para pruebas sin Groq)."""
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    data = data.get("words", data) if isinstance(data, dict) else data
    return clean_words([Word(d.get("text", d.get("word", "")), d["start"], d["end"]) for d in data])


def process(video: str, out: str, language: str | None = None, words_json: str | None = None,
            style: str = "auto", seed: int | None = None, api_key: str | None = None,
            keep_ass: str | None = None, log=print, progress=None,
            transcriber=None) -> dict:
    prog = progress or (lambda pct, msg: None)
    prog(5, "Leyendo el video")
    info = probe(video)
    log(f"Video {info.width}x{info.height} ({info.orientation}), {info.duration:.1f}s")
    if words_json:
        words = load_words(words_json)
    else:
        if not info.has_audio:
            raise RuntimeError("El video no tiene audio para transcribir.")
        with tempfile.TemporaryDirectory() as td:
            log("Transcribiendo con Groq...")
            prog(12, "Transcribiendo el audio")
            words = (transcriber or transcribe)(
                extract_audio(video, os.path.join(td, "a.flac")), language=language,
                **({} if transcriber else {"api_key": api_key}))
    log(f"{len(words)} palabras. Buscando rostros...")
    prog(40, "Buscando rostros")
    track = track_faces(video)
    log("Rostros detectados." if track.has_faces else "No se detectaron rostros.")
    prog(60, "Diseñando subtítulos")
    ass, report = build_ass(words, info.width, info.height, track, seed=seed, style=style)
    with tempfile.TemporaryDirectory() as td:
        ass_path = keep_ass or os.path.join(td, "subs.ass")
        with open(ass_path, "w", encoding="utf-8") as f:
            f.write(ass)
        log("Renderizando...")
        prog(70, "Renderizando el video")
        burn(video, ass_path, out)
    prog(100, "Listo")
    log(f"Listo: {out}")
    return {"info": info, "phrases": report}
