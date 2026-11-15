"""Modelo de palabras con tiempos y agrupacion en frases cortas."""
from __future__ import annotations
from dataclasses import dataclass


@dataclass
class Word:
    text: str
    start: float
    end: float

    @property
    def duration(self) -> float:
        return max(0.0, self.end - self.start)


_END_PUNCT = (".", "?", "!", "…")
_SOFT_PUNCT = (",", ";", ":", "—")


def clean_words(words: list[Word]) -> list[Word]:
    """Quita vacios, arregla tiempos invertidos y solapes."""
    out: list[Word] = []
    for w in words:
        t = w.text.strip()
        if not t:
            continue
        start, end = float(w.start), float(w.end)
        if end < start:
            end = start
        if out and start < out[-1].end:
            # evita solape: recorta el final de la anterior
            out[-1].end = max(out[-1].start, start)
        out.append(Word(t, start, end))
    return out


def group_phrases(
    words: list[Word],
    max_words: int = 3,
    max_chars: int = 22,
    gap: float = 0.55,
) -> list[list[Word]]:
    """Agrupa palabras en frases de 1-3 palabras, estilo TikTok.

    Corta en: pausas largas, puntuacion y limite de palabras/caracteres.
    """
    phrases: list[list[Word]] = []
    cur: list[Word] = []
    for w in words:
        if cur:
            pause = w.start - cur[-1].end
            chars = sum(len(x.text) for x in cur) + len(cur) + len(w.text)
            if (
                pause > gap
                or len(cur) >= max_words
                or chars > max_chars
                or cur[-1].text.endswith(_END_PUNCT)
            ):
                phrases.append(cur)
                cur = []
            elif cur[-1].text.endswith(_SOFT_PUNCT) and len(cur) >= 2:
                phrases.append(cur)
                cur = []
        cur.append(w)
    if cur:
        phrases.append(cur)
    return phrases
