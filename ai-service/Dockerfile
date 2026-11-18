FROM python:3.13-slim

WORKDIR /app
COPY pyproject.toml ./
COPY app/ app/
RUN pip install --no-cache-dir .

# Same unprivileged-user setup as the backend image.
RUN useradd --system --uid 10001 app
USER app

# PORT comes from Render; 8000 for Compose and local runs.
EXPOSE 8000
CMD ["sh", "-c", "uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8000}"]
