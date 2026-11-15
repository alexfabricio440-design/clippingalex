"""Descarga el modelo YuNet (detector de rostros, ~230 KB). Ejecutar una vez."""
import os, sys, urllib.request

URL = ("https://github.com/opencv/opencv_zoo/raw/main/models/"
       "face_detection_yunet/face_detection_yunet_2023mar.onnx")
DEST = os.path.join(os.path.dirname(os.path.dirname(__file__)), "models",
                    "face_detection_yunet_2023mar.onnx")

if __name__ == "__main__":
    os.makedirs(os.path.dirname(DEST), exist_ok=True)
    print("Descargando YuNet...")
    urllib.request.urlretrieve(URL, DEST)
    size = os.path.getsize(DEST)
    if size < 100_000:
        os.remove(DEST)
        sys.exit("La descarga fallo (archivo muy chico). Reintenta.")
    print(f"Listo: {DEST} ({size/1024:.0f} KB)")
