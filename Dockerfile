FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY . .
# Detector de rostros mejorado (si falla la descarga, la app usa el de respaldo)
RUN python scripts/get_models.py || echo "YuNet no descargado: se usara el detector de respaldo"
ENV PORT=8000 CLIPCAPTIONS_WORK=/data
VOLUME /data
EXPOSE 8000
# 1 proceso (los trabajos viven en memoria) + varios hilos
CMD ["sh", "-c", "gunicorn -w 1 --threads 8 --timeout 600 -b 0.0.0.0:${PORT} 'clipcaptions.webapp:create_app()'"]
