"use strict";
const $ = (id) => document.getElementById(id);
let token = localStorage.getItem("cc_token") || "";
let style = "auto", file = null, cfg = null, jobId = null;

const api = (path, opts = {}) =>
  fetch(path, { ...opts, headers: { ...(opts.headers || {}), "X-Token": token } });
const show = (id, on) => $(id).classList.toggle("hidden", !on);
const fmt = (b) => (b > 1e6 ? (b / 1e6).toFixed(1) + " MB" : Math.round(b / 1e3) + " KB");

function showError(msg) { $("err").textContent = msg || ""; show("err", !!msg); }

async function init() {
  if (window.AndroidBridge) {  // dentro de la app de Android
    $("installHint").hidden = true; $("srv").hidden = false;
    $("chg").onclick = (e) => { e.preventDefault(); AndroidBridge.changeServer(); };
  }
  if ("serviceWorker" in navigator) navigator.serviceWorker.register("/sw.js").catch(() => {});
  cfg = await (await fetch("/api/config")).json();
  if (cfg.needs_token && !(await tokenOk())) { show("gate", true); show("form", false); return; }
  buildForm();
}
async function tokenOk() {
  if (!token) return false;
  const r = await api("/api/jobs/_check");
  return r.status !== 401;
}
$("tokenBtn").onclick = async () => {
  token = $("token").value.trim(); localStorage.setItem("cc_token", token);
  if (await tokenOk()) { show("gate", false); show("form", true); buildForm(); }
  else { $("token").value = ""; $("token").placeholder = "Código incorrecto"; }
};

function buildForm() {
  const box = $("styles"); box.innerHTML = "";
  cfg.styles.forEach((s) => {
    const b = document.createElement("button");
    b.className = "chip" + (s.id === style ? " on" : ""); b.textContent = s.label; b.type = "button";
    b.onclick = () => { style = s.id; [...box.children].forEach((c) => c.classList.remove("on"));
      b.classList.add("on"); $("styleDesc").textContent = s.desc; };
    box.appendChild(b);
  });
  $("styleDesc").textContent = cfg.styles[0].desc;
  const sel = $("lang"); sel.innerHTML = "";
  Object.entries(cfg.languages).forEach(([k, v]) => sel.add(new Option(v, k)));
  sel.value = localStorage.getItem("cc_lang") || "";
}

$("file").onchange = (e) => {
  file = e.target.files[0] || null; showError("");
  $("drop").classList.toggle("has", !!file);
  $("dropText").innerHTML = file ? `<b>${file.name.replace(/[<>&]/g, "")}</b><br><small>${fmt(file.size)} · toca para cambiar</small>`
    : "<b>Elige tu video</b>";
  $("go").disabled = !file;
};

$("go").onclick = () => {
  if (!file) return;
  if (file.size > cfg.max_mb * 1048576) return showError(`El video pesa ${fmt(file.size)}; el máximo es ${cfg.max_mb} MB.`);
  localStorage.setItem("cc_lang", $("lang").value);
  showError(""); show("form", false); show("progress", true); setProg(2, "Subiendo el video…");
  const fd = new FormData();
  fd.append("style", style); fd.append("language", $("lang").value); fd.append("video", file);
  const x = new XMLHttpRequest();
  x.open("POST", "/api/jobs"); x.setRequestHeader("X-Token", token);
  x.upload.onprogress = (e) => { if (e.lengthComputable) setProg(2 + 8 * e.loaded / e.total, `Subiendo… ${Math.round(100 * e.loaded / e.total)}%`); };
  x.onload = () => {
    let j = {}; try { j = JSON.parse(x.responseText); } catch (_) {}
    if (x.status === 202) { jobId = j.id; poll(); } else fail(j.error || "No se pudo subir el video");
  };
  x.onerror = () => fail("Sin conexión con el servidor");
  x.send(fd);
};

function setProg(p, msg) { $("barFill").style.width = p + "%"; if (msg) $("progMsg").textContent = msg; }
function fail(msg) { show("progress", false); show("form", true); showError(msg); }

async function poll() {
  try {
    const r = await api("/api/jobs/" + jobId);
    const j = await r.json();
    if (!r.ok) return fail(j.error || "Se perdió el trabajo");
    if (j.status === "error") return fail(j.error);
    if (j.status === "done") return done(j);
    setProg(Math.max(10, j.progress), j.message + "…");
  } catch (_) { /* reintenta */ }
  setTimeout(poll, 1000);
}

function done(j) {
  show("progress", false); show("result", true);
  const q = "?token=" + encodeURIComponent(token);
  const v = $("player"); v.src = `/api/jobs/${jobId}/video${q}`; v.play().catch(() => {});
  $("dl").href = `/api/jobs/${jobId}/download${q}`;
  $("resInfo").textContent = `${j.phrases} frases · estilos: ${(j.styles || []).join(", ")}`;
  if (navigator.canShare) {
    show("share", true);
    $("share").onclick = async () => {
      try {
        const blob = await (await fetch(`/api/jobs/${jobId}/video${q}`)).blob();
        const f = new File([blob], j.filename, { type: "video/mp4" });
        if (navigator.canShare({ files: [f] })) await navigator.share({ files: [f] });
      } catch (_) {}
    };
  }
}

$("again").onclick = () => {
  $("player").pause(); $("player").removeAttribute("src");
  show("result", false); show("form", true); file = null; $("file").value = "";
  $("go").disabled = true; $("drop").classList.remove("has");
  $("dropText").innerHTML = "<b>Elige tu video</b>";
};
init();
