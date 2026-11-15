import argparse
import os
import sys


def _load_env():
    p = os.path.join(os.path.dirname(os.path.dirname(__file__)), ".env")
    if os.path.exists(p):
        for line in open(p, encoding="utf-8"):
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                os.environ.setdefault(k.strip(), v.strip().strip('"').strip("'"))


def main():
    from .pipeline import process
    from .styles import STYLES
    _load_env()
    ap = argparse.ArgumentParser(prog="clipcaptions",
                                 description="Subtitulos dinamicos estilo TikTok para clipping")
    ap.add_argument("video")
    ap.add_argument("-o", "--out", help="video de salida (por defecto: <nombre>_sub.mp4)")
    ap.add_argument("-l", "--language", help="idioma, ej. es, en (por defecto autodetecta)")
    ap.add_argument("--style", default="auto", choices=["auto", *STYLES])
    ap.add_argument("--seed", type=int, help="semilla: mismo numero = mismo resultado")
    ap.add_argument("--words", help="JSON de palabras con tiempos (omite Groq, para pruebas)")
    a = ap.parse_args()
    out = a.out or os.path.splitext(a.video)[0] + "_sub.mp4"
    try:
        process(a.video, out, a.language, a.words, a.style, a.seed)
    except Exception as e:
        sys.exit(f"Error: {e}")


if __name__ == "__main__":
    main()
