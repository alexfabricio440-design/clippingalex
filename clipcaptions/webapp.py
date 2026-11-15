"""Servidor web (Flask) para usar ClipCaptions desde el telefono."""
from __future__ import annotations
import hmac
import os
import shutil
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor

from flask import Flask, jsonify, request, send_file, send_from_directory

from .audio import probe
from .pipeline import process
from .styles import STYLES

WEB_DIR = os.path.join(os.path.dirname(os.path.dirname(__file__)), "web")
ALLOWED_EXT = {".mp4", ".mov", ".m4v", ".webm", ".mkv"}
LANGS = {"": "Detectar automático", "es": "Español", "en": "English", "pt": "Português",
         "fr": "Français", "it": "Italiano", "de": "Deutsch"}
STYLE_INFO = {
    "auto": ("Automático", "Mezcla todos los estilos sin repetir"),
    "impacto": ("Impacto", "Mayúsculas gruesas con pop"),
    "flujo": ("Flujo", "Cursiva que se desliza"),
    "neon": ("Neón", "Brillo de color alrededor"),
    "mixto": ("Mixto", "Palabras clave grandes y en cursiva"),
    "aparece": ("Aparece", "Las palabras entran al decirse"),
    "grueso": ("Grueso", "Letra gorda con borde fuerte"),
}


def create_app(config: dict | None = None) -> Flask:
    cfg = {
        "WORK_DIR": os.environ.get("CLIPCAPTIONS_WORK", os.path.join(os.getcwd(), "work")),
        "MAX_MB": int(os.environ.get("CLIPCAPTIONS_MAX_MB", "500")),
        "MAX_SECONDS": int(os.environ.get("CLIPCAPTIONS_MAX_SECONDS", "360")),
        "APP_TOKEN": os.environ.get("APP_TOKEN", ""),
        "KEEP_SECONDS": 2 * 3600,
        "WORKERS": 1,
        "TRANSCRIBER": None,   # para pruebas: callable(audio, language=None) -> [Word]
    }
    cfg.update(config or {})
    os.makedirs(cfg["WORK_DIR"], exist_ok=True)
    app = Flask(__name__, static_folder=None)
    app.config["MAX_CONTENT_LENGTH"] = cfg["MAX_MB"] * 1024 * 1024
    jobs: dict[str, dict] = {}
    lock = threading.Lock()
    pool = ThreadPoolExecutor(max_workers=cfg["WORKERS"])
    app.extensions["jobs"] = jobs

    # ---------- seguridad: codigo de acceso opcional ----------
    def _authorized() -> bool:
        tok = cfg["APP_TOKEN"]
        if not tok:
            return True
        given = request.headers.get("X-Token") or request.args.get("token") or ""
        return hmac.compare_digest(given.encode(), tok.encode())

    @app.before_request
    def _guard():
        if request.path.startswith("/api/") and request.path != "/api/config" and not _authorized():
            return jsonify(error="Código de acceso incorrecto"), 401

    @app.errorhandler(413)
    def _too_big(_):
        return jsonify(error=f"El video pesa demasiado (máximo {cfg['MAX_MB']} MB)"), 413

    # ---------- limpieza de trabajos viejos ----------
    def _cleanup():
        now = time.time()
        with lock:
            old = [k for k, j in jobs.items()
                   if now - j["created"] > cfg["KEEP_SECONDS"] and j["status"] in ("done", "error")]
            for k in old:
                jobs.pop(k, None)
        for k in old:
            shutil.rmtree(os.path.join(cfg["WORK_DIR"], k), ignore_errors=True)

    # ---------- trabajo en segundo plano ----------
    def _run(jid: str, src: str, out: str, language, style, seed):
        job = jobs[jid]
        job["status"] = "running"

        def progress(pct, msg):
            job["progress"], job["message"] = pct, msg
        try:
            res = process(src, out, language=language or None, style=style, seed=seed,
                          log=lambda *_: None, progress=progress,
                          transcriber=cfg["TRANSCRIBER"])
            job.update(status="done", progress=100, message="Listo",
                       phrases=len(res["phrases"]),
                       styles=sorted({p["style"] for p in res["phrases"]}))
        except Exception as e:  # el usuario ve un mensaje claro
            job.update(status="error", error=_friendly(e))
        finally:
            try:
                os.remove(src)
            except OSError:
                pass

    def _friendly(e: Exception) -> str:
        m = str(e)
        if "GROQ_API_KEY" in m:
            return "Falta la clave de Groq en el servidor (GROQ_API_KEY)."
        if "no tiene audio" in m.lower():
            return "El video no tiene audio para transcribir."
        return m[:300] or "Error inesperado"

    # ---------- rutas ----------
    @app.get("/")
    def index():
        return send_from_directory(os.path.join(WEB_DIR, "static"), "index.html")

    @app.get("/static/<path:name>")
    def static_files(name):
        resp = send_from_directory(os.path.join(WEB_DIR, "static"), name)
        if name == "sw.js":
            resp.headers["Service-Worker-Allowed"] = "/"
            resp.headers["Cache-Control"] = "no-cache"
        return resp

    @app.get("/sw.js")
    def sw():
        return static_files("sw.js")

    @app.get("/api/config")
    def config():
        return jsonify(needs_token=bool(cfg["APP_TOKEN"]), max_mb=cfg["MAX_MB"],
                       max_seconds=cfg["MAX_SECONDS"],
                       languages=LANGS,
                       styles=[{"id": k, "label": v[0], "desc": v[1]} for k, v in STYLE_INFO.items()])

    @app.post("/api/jobs")
    def create_job():
        f = request.files.get("video")
        if not f or not f.filename:
            return jsonify(error="Sube un video"), 400
        ext = os.path.splitext(f.filename)[1].lower()
        if ext not in ALLOWED_EXT:
            return jsonify(error="Formato no soportado. Usa MP4, MOV, M4V, WEBM o MKV."), 400
        style = request.form.get("style", "auto")
        if style != "auto" and style not in STYLES:
            return jsonify(error="Estilo desconocido"), 400
        language = request.form.get("language", "")
        if language not in LANGS:
            return jsonify(error="Idioma no soportado"), 400
        seed = request.form.get("seed")
        seed = int(seed) if seed and seed.isdigit() else None
        _cleanup()
        jid = uuid.uuid4().hex[:12]
        d = os.path.join(cfg["WORK_DIR"], jid)
        os.makedirs(d, exist_ok=True)
        src, out = os.path.join(d, "in" + ext), os.path.join(d, "out.mp4")
        f.save(src)
        try:
            info = probe(src)
        except Exception:
            shutil.rmtree(d, ignore_errors=True)
            return jsonify(error="No pude leer ese video. ¿Está dañado?"), 400
        if info.duration > cfg["MAX_SECONDS"]:
            shutil.rmtree(d, ignore_errors=True)
            return jsonify(error=f"Video demasiado largo (máximo {cfg['MAX_SECONDS'] // 60} min)."), 400
        name = os.path.splitext(os.path.basename(f.filename))[0][:60] or "video"
        safe = "".join(c if c.isalnum() or c in "-_ " else "_" for c in name).strip() or "video"
        with lock:
            jobs[jid] = dict(id=jid, status="queued", progress=0, message="En cola",
                             created=time.time(), out=out, filename=f"{safe}_sub.mp4",
                             orientation=info.orientation, duration=round(info.duration, 1))
        pool.submit(_run, jid, src, out, language, style, seed)
        return jsonify(id=jid), 202

    def _public(j):
        return {k: j[k] for k in ("id", "status", "progress", "message", "orientation", "duration",
                                   "filename") if k in j} | {
            k: j[k] for k in ("error", "phrases", "styles") if k in j}

    @app.get("/api/jobs/<jid>")
    def job_status(jid):
        j = jobs.get(jid)
        if not j:
            return jsonify(error="No existe o ya expiró"), 404
        return jsonify(_public(j))

    def _file(jid, attach):
        j = jobs.get(jid)
        if not j or j["status"] != "done" or not os.path.exists(j["out"]):
            return jsonify(error="Todavía no está listo"), 404
        return send_file(j["out"], mimetype="video/mp4", as_attachment=attach,
                         download_name=j["filename"], conditional=True)

    @app.get("/api/jobs/<jid>/video")
    def job_video(jid):
        return _file(jid, False)

    @app.get("/api/jobs/<jid>/download")
    def job_download(jid):
        return _file(jid, True)

    @app.get("/healthz")
    def health():
        return jsonify(ok=True)

    return app


def main():
    from .__main__ import _load_env
    _load_env()
    host = os.environ.get("HOST", "0.0.0.0")
    port = int(os.environ.get("PORT", "8000"))
    app = create_app()
    print(f"ClipCaptions en http://{host}:{port}")
    app.run(host=host, port=port, threaded=True)


if __name__ == "__main__":
    main()
