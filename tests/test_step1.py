import os, subprocess, sys
sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))
import pytest
from clipcaptions.words import Word, clean_words, group_phrases
from clipcaptions.transcribe import parse_response, transcribe, TranscriptionError
from clipcaptions.audio import probe, extract_audio


class FakeResp:
    def __init__(self, code, js=None, text="", headers=None):
        self.status_code, self._js, self.text = code, js or {}, text
        self.headers = headers or {}
    def json(self): return self._js


class FakeSession:
    def __init__(self, resps): self.resps, self.calls = resps, 0
    def post(self, *a, **k):
        r = self.resps[min(self.calls, len(self.resps) - 1)]
        self.calls += 1
        return r


SAMPLE = {"text": "hola mundo", "words": [
    {"word": "Hola", "start": 0.1, "end": 0.5},
    {"word": "mundo.", "start": 0.5, "end": 1.0},
    {"word": " ", "start": 1.0, "end": 1.1},
    {"word": "otra", "start": 2.5, "end": 2.9}]}


def test_parse_drops_empty_and_orders():
    w = parse_response(SAMPLE)
    assert [x.text for x in w] == ["Hola", "mundo.", "otra"]


def test_parse_without_words_fails():
    with pytest.raises(TranscriptionError):
        parse_response({"text": "x"})


def test_overlap_fixed():
    w = clean_words([Word("a", 0, 1.0), Word("b", 0.8, 1.5)])
    assert w[0].end <= w[1].start


def test_group_phrases_rules():
    words = parse_response(SAMPLE)
    ph = group_phrases(words)
    assert [[w.text for w in p] for p in ph] == [["Hola", "mundo."], ["otra"]]
    long = [Word(f"p{i}", i * 0.3, i * 0.3 + 0.25) for i in range(10)]
    assert all(len(p) <= 3 for p in group_phrases(long))


def test_transcribe_ok_and_retry(tmp_path, monkeypatch):
    a = tmp_path / "a.flac"; a.write_bytes(b"x" * 100)
    monkeypatch.setattr("time.sleep", lambda s: None)
    s = FakeSession([FakeResp(429, headers={"retry-after": "0"}), FakeResp(200, SAMPLE)])
    assert len(transcribe(str(a), api_key="k", session=s)) == 3
    assert s.calls == 2


def test_transcribe_no_key(tmp_path, monkeypatch):
    monkeypatch.delenv("GROQ_API_KEY", raising=False)
    a = tmp_path / "a.flac"; a.write_bytes(b"x")
    with pytest.raises(TranscriptionError, match="GROQ_API_KEY"):
        transcribe(str(a))


def test_transcribe_bad_key(tmp_path):
    a = tmp_path / "a.flac"; a.write_bytes(b"x")
    with pytest.raises(TranscriptionError, match="401"):
        transcribe(str(a), api_key="k", session=FakeSession([FakeResp(401, text="bad")]))


def test_probe_and_extract(tmp_path):
    v = tmp_path / "v.mp4"
    subprocess.run(["ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i",
                    "testsrc=s=360x640:d=2", "-f", "lavfi", "-i",
                    "sine=f=440:d=2", "-shortest", str(v)], check=True)
    info = probe(str(v))
    assert (info.width, info.height, info.orientation) == (360, 640, "vertical")
    assert info.has_audio and 1.5 < info.duration < 2.5
    out = extract_audio(str(v), str(tmp_path / "a.flac"))
    assert os.path.getsize(out) > 1000
