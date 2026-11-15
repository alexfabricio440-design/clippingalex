import io, os, subprocess, sys, time
sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))
import cv2, pytest
from clipcaptions.webapp import create_app
from clipcaptions.transcribe import TranscriptionError
from tests.test_step2 import make_video
from tests.test_step3 import words


def fake_tr(audio, language=None):
    return words()


@pytest.fixture(scope="module")
def media(tmp_path_factory):
    d = tmp_path_factory.mktemp("m")
    v = str(d / "v.mp4"); make_video(v, w=540, h=960, secs=4, fps=12)
    va = str(d / "va.mp4")      # vertical con audio
    subprocess.run(["ffmpeg", "-y", "-v", "error", "-i", v, "-f", "lavfi", "-i", "sine=f=300:d=4",
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", va], check=True)
    h = str(d / "h.mp4"); make_video(h, w=960, h=540, secs=3, fps=12)
    ha = str(d / "ha.mp4")
    subprocess.run(["ffmpeg", "-y", "-v", "error", "-i", h, "-f", "lavfi", "-i", "sine=f=300:d=3",
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", ha], check=True)
    rot = str(d / "rot.mp4")    # como un iPhone: guardado horizontal con rotacion de 90 grados
    subprocess.run(["ffmpeg", "-y", "-v", "error", "-display_rotation:v", "90", "-i", ha,
                    "-c", "copy", rot], check=True)
    return dict(noaudio=v, vert=va, horiz=ha, rot=rot)


def make(tmp_path, **cfg):
    c = dict(WORK_DIR=str(tmp_path / "work"), TRANSCRIBER=fake_tr)
    c.update(cfg)
    return create_app(c).test_client()


def post(c, path, name="x.mp4", **form):
    data = {k: v for k, v in form.items()}
    with open(path, "rb") as f:
        data["video"] = (io.BytesIO(f.read()), name)
    return c.post("/api/jobs", data=data, content_type="multipart/form-data")


def wait(c, jid, headers=None, timeout=90):
    t0 = time.time()
    while time.time() - t0 < timeout:
        j = c.get(f"/api/jobs/{jid}", headers=headers or {}).get_json()
        if j["status"] in ("done", "error"):
            return j
        time.sleep(0.3)
    raise AssertionError("timeout")


def dims(path):
    cap = cv2.VideoCapture(path); return int(cap.get(3)), int(cap.get(4))


def test_static_and_pwa(tmp_path):
    c = make(tmp_path)
    assert b"ClipCaptions" in c.get("/").data
    m = c.get("/static/manifest.webmanifest").get_json()
    assert m["display"] == "standalone" and len(m["icons"]) >= 3
    sw = c.get("/sw.js"); assert sw.status_code == 200 and sw.headers["Service-Worker-Allowed"] == "/"
    for n in ("icon-192.png", "icon-512.png", "icon-maskable-512.png", "app.js", "style.css"):
        assert c.get("/static/" + n).status_code == 200
    assert c.get("/static/../../etc/passwd").status_code in (404, 400)
    cfg = c.get("/api/config").get_json()
    assert cfg["styles"][0]["id"] == "auto" and "es" in cfg["languages"] and not cfg["needs_token"]
    assert c.get("/healthz").get_json()["ok"]


@pytest.mark.parametrize("key,size", [("vert", (540, 960)), ("horiz", (960, 540))])
def test_full_flow(tmp_path, media, key, size):
    c = make(tmp_path)
    r = post(c, media[key], "Mi clip!.mp4", style="neon", language="es")
    assert r.status_code == 202
    jid = r.get_json()["id"]
    j = wait(c, jid)
    assert j["status"] == "done", j
    assert j["phrases"] >= 5 and j["filename"] == "Mi clip__sub.mp4"
    v = c.get(f"/api/jobs/{jid}/video")
    assert v.status_code == 200 and v.mimetype == "video/mp4"
    part = c.get(f"/api/jobs/{jid}/video", headers={"Range": "bytes=0-99"})
    assert part.status_code == 206 and len(part.data) == 100      # el telefono puede avanzar el video
    d = c.get(f"/api/jobs/{jid}/download")
    assert "attachment" in d.headers["Content-Disposition"]
    out = tmp_path / "o.mp4"; out.write_bytes(d.data)
    assert dims(str(out)) == size
    assert j["styles"] == ["neon"]


def test_rotated_phone_video(tmp_path, media):
    c = make(tmp_path)
    jid = post(c, media["rot"], "iphone.mov".replace(".mov", ".mp4")).get_json()["id"]
    j = wait(c, jid)
    assert j["status"] == "done", j
    assert j["orientation"] == "vertical"        # 960x540 con rotacion 90 = vertical
    out = tmp_path / "o.mp4"; out.write_bytes(c.get(f"/api/jobs/{jid}/download").data)
    assert dims(str(out)) == (540, 960)


@pytest.mark.parametrize("name,form,code", [
    ("a.exe", {}, 400), ("a.mp4", {"style": "xx"}, 400), ("a.mp4", {"language": "klingon"}, 400)])
def test_validation(tmp_path, media, name, form, code):
    c = make(tmp_path)
    assert post(c, media["vert"], name, **form).status_code == code


def test_no_file_and_corrupt(tmp_path):
    c = make(tmp_path)
    assert c.post("/api/jobs", data={}, content_type="multipart/form-data").status_code == 400
    bad = tmp_path / "bad.mp4"; bad.write_bytes(b"esto no es un video" * 100)
    r = post(c, str(bad), "bad.mp4")
    assert r.status_code == 400 and "leer" in r.get_json()["error"]
    assert not any(os.scandir(tmp_path / "work")), "no deja basura en disco"


def test_limits(tmp_path, media):
    assert post(make(tmp_path / "a", MAX_SECONDS=1), media["vert"]).status_code == 400
    r = post(make(tmp_path / "b", MAX_MB=0), media["vert"])
    assert r.status_code == 413 and "pesa" in r.get_json()["error"]


def test_token_protection(tmp_path, media):
    c = make(tmp_path, APP_TOKEN="secreto123")
    assert c.get("/api/config").get_json()["needs_token"] is True
    assert post(c, media["vert"]).status_code == 401
    r = c.post("/api/jobs", data={"video": (io.BytesIO(open(media["vert"], "rb").read()), "a.mp4")},
               content_type="multipart/form-data", headers={"X-Token": "secreto123"})
    assert r.status_code == 202
    jid = r.get_json()["id"]
    assert c.get(f"/api/jobs/{jid}").status_code == 401
    assert wait(c, jid, {"X-Token": "secreto123"})["status"] == "done"
    assert c.get(f"/api/jobs/{jid}/video?token=secreto123").status_code == 200
    assert c.get(f"/api/jobs/{jid}/video?token=otro").status_code == 401
    assert c.get("/").status_code == 200       # la pagina de entrada si carga (pide el codigo)


def test_errors_are_friendly(tmp_path, media):
    def boom(audio, language=None): raise TranscriptionError("Falta GROQ_API_KEY. Ponla en .env")
    c = make(tmp_path / "a", TRANSCRIBER=boom)
    j = wait(c, post(c, media["vert"]).get_json()["id"])
    assert j["status"] == "error" and "GROQ_API_KEY" in j["error"] and "servidor" in j["error"]
    c2 = make(tmp_path / "b")
    j2 = wait(c2, post(c2, media["noaudio"]).get_json()["id"])
    assert j2["status"] == "error" and "audio" in j2["error"]
    assert c.get("/api/jobs/nope").status_code == 404


def test_cleanup_old_jobs(tmp_path, media):
    app = create_app(dict(WORK_DIR=str(tmp_path / "w"), TRANSCRIBER=fake_tr, KEEP_SECONDS=0))
    c = app.test_client()
    jid = post(c, media["vert"]).get_json()["id"]; wait(c, jid)
    time.sleep(0.05)
    post(c, media["vert"])        # un trabajo nuevo dispara la limpieza
    assert jid not in app.extensions["jobs"] and not os.path.exists(tmp_path / "w" / jid)
