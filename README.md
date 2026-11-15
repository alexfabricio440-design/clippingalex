# ClipCaptions (clippingalex)

Subtítulos dinámicos estilo TikTok para clipping: transcripción con **Groq (Whisper)**,
detección de rostro para que el texto nunca lo tape, y render con ffmpeg.

## Estado
- [x] Paso 1: transcripción con Groq + tiempos por palabra + agrupación en frases
- [x] Paso 2: detección de rostro (YuNet o respaldo LBP) y zonas libres sin tapar la cara
- [x] Paso 3: motor de estilos (6 estilos, 8 colores vivos, cursiva/grueso, animaciones, posiciones variables)
- [x] Paso 4: web instalable en el teléfono (PWA) + Docker

## Uso
```bash
pip install -r requirements.txt
cp .env.example .env   # pon tu GROQ_API_KEY
python -m pytest -q tests
```
Requiere `ffmpeg` instalado. La clave de Groq nunca se sube al repo (`.env` está en `.gitignore`).

## Detector de rostros
Mejor calidad con YuNet: `python scripts/get_models.py` (una vez). Sin ese archivo se usa el
detector de respaldo automáticamente. Forzar uno: `CLIPCAPTIONS_DETECTOR=yunet` o `cascade`.

## Usar (línea de comandos)
```bash
python scripts/get_models.py                      # una vez (detector de rostros)
python -m clipcaptions mi_video.mp4               # crea mi_video_sub.mp4
python -m clipcaptions mi_video.mp4 -l es --style auto --seed 7
python -m clipcaptions mi_video.mp4 --words palabras.json   # sin Groq, para pruebas
```
Estilos: `impacto`, `flujo`, `neon`, `mixto`, `aparece`, `grueso` (o `auto`, que los alterna sin repetir).
Las fuentes (Inter y Poppins, licencia OFL) vienen en `assets/fonts`, no hay que instalar nada.

## App web para el teléfono (PWA)
```bash
python -m clipcaptions.webapp        # abre http://localhost:8000
# o con Docker:
cp .env.example .env                 # pon GROQ_API_KEY
docker compose up --build
```
Desde el teléfono: abre la dirección del servidor, sube el video, elige estilo y descarga.
Para **instalarla como app** (menú del navegador → *Agregar a pantalla de inicio*) el navegador exige **HTTPS**:
- En tu PC: `cloudflared tunnel --url http://localhost:8000` te da una dirección https temporal.
- En la nube: cualquier servicio que corra Docker (Render, Railway, Fly.io) con las variables `GROQ_API_KEY` y `APP_TOKEN`.

Si la app queda en internet **define `APP_TOKEN`**: sin él cualquiera podría usar tu clave de Groq.
Los videos subidos y resultados se borran solos a las 2 horas.

## Pruebas
`python -m pytest -q tests` (42 pruebas: transcripción, rostros, estilos, render y API web).
