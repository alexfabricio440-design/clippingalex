# ClipCaptions (clippingalex)

Subtítulos dinámicos estilo TikTok para clipping: transcripción con **Groq (Whisper)**,
detección de rostro para que el texto nunca lo tape, y render con ffmpeg.

## Estado
- [x] Paso 1: transcripción con Groq + tiempos por palabra + agrupación en frases
- [ ] Paso 2: detección de rostro y zonas libres
- [ ] Paso 3: motor de estilos (neón, cursiva, grueso, animaciones, posiciones variables)
- [ ] Paso 4: web instalable en el teléfono (PWA)

## Uso
```bash
pip install -r requirements.txt
cp .env.example .env   # pon tu GROQ_API_KEY
python -m pytest -q tests
```
Requiere `ffmpeg` instalado. La clave de Groq nunca se sube al repo (`.env` está en `.gitignore`).
