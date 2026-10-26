FROM python:3.12-slim
RUN pip install --no-cache-dir fastapi==0.115.6 uvicorn==0.34.0 nudenet==3.4.2
# NudeNet trae su modelo (320n.onnx, ~12 MB) dentro del paquete; se carga al primer análisis.
WORKDIR /app
COPY app.py screen.py ./
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000", "--proxy-headers"]
