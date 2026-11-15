"""Transcripcion con Groq (Whisper) con tiempos por palabra."""
from __future__ import annotations
import os
import time
import requests
from .words import Word, clean_words

GROQ_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
DEFAULT_MODEL = "whisper-large-v3-turbo"
MAX_UPLOAD_BYTES = 24 * 1024 * 1024  # limite del plan gratuito ~25 MB


class TranscriptionError(RuntimeError):
    pass


def parse_response(data: dict) -> list[Word]:
    """Convierte la respuesta verbose_json de Groq en una lista de Word."""
    raw = data.get("words")
    if not raw:
        raise TranscriptionError(
            "La respuesta no trae tiempos por palabra. "
            "Revisa timestamp_granularities=word."
        )
    words = [
        Word(str(w.get("word", w.get("text", ""))),
             float(w["start"]), float(w["end"]))
        for w in raw
    ]
    return clean_words(words)


def transcribe(
    audio_path: str,
    api_key: str | None = None,
    language: str | None = None,
    model: str = DEFAULT_MODEL,
    retries: int = 3,
    session: requests.Session | None = None,
) -> list[Word]:
    key = api_key or os.environ.get("GROQ_API_KEY")
    if not key:
        raise TranscriptionError(
            "Falta GROQ_API_KEY. Ponla en el archivo .env o como variable de entorno."
        )
    size = os.path.getsize(audio_path)
    if size > MAX_UPLOAD_BYTES:
        raise TranscriptionError(
            f"El audio pesa {size/1e6:.1f} MB y el limite es ~24 MB. "
            "Recorta el video o usa un clip mas corto."
        )
    http = session or requests
    data = {
        "model": model,
        "response_format": "verbose_json",
        "timestamp_granularities[]": "word",
        "temperature": "0",
    }
    if language:
        data["language"] = language
    last = None
    for attempt in range(retries):
        with open(audio_path, "rb") as f:
            r = http.post(
                GROQ_URL,
                headers={"Authorization": f"Bearer {key}"},
                files={"file": (os.path.basename(audio_path), f)},
                data=data,
                timeout=180,
            )
        if r.status_code == 200:
            return parse_response(r.json())
        last = f"HTTP {r.status_code}: {r.text[:300]}"
        if r.status_code in (429, 500, 502, 503, 504):
            wait = float(r.headers.get("retry-after", 2 ** attempt))
            time.sleep(min(wait, 20))
            continue
        break
    raise TranscriptionError(f"Groq fallo. {last}")
