# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Frontend build
# ---------------------------------------------------------------------------
FROM node:22-alpine AS frontend

WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# ---------------------------------------------------------------------------
# Runtime
# ---------------------------------------------------------------------------
FROM python:3.13-alpine AS runtime

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /app

# Apply the available Alpine security updates to the base packages.
RUN apk upgrade --no-cache

RUN addgroup -S app \
    && adduser -S -G app -h /app -H -s /sbin/nologin app

COPY backend/requirements.txt ./requirements.txt
# pip and setuptools are build-time tools; the application does not use them at
# runtime. Removing them also removes the stale vendored SBOM that Docker Scout
# otherwise treats as installed packages.
RUN python -m pip install --no-cache-dir -r requirements.txt \
    && python -m pip uninstall -y pip setuptools \
    && rm -rf /usr/local/lib/python3.13/site-packages/pip* \
              /usr/local/lib/python3.13/site-packages/setuptools* \
              /usr/local/lib/python3.13/site-packages/pkg_resources \
              /usr/local/lib/python3.13/site-packages/_distutils_hack \
              /usr/local/lib/python3.13/site-packages/distutils-precedence.pth \
              /usr/local/bin/pip*

COPY backend/app ./app
COPY VERSION ./VERSION
COPY LICENSE ./LICENSE
COPY --from=frontend /frontend/dist ./static

RUN mkdir -p /data \
    && chown -R app:app /app /data

# The database lives here. Declaring the volume also protects data when the
# image is run directly with `docker run` (Docker creates an anonymous volume).
VOLUME ["/data"]

USER app

ENV DATABASE_URL=sqlite:////data/unifi-wifi.db \
    STATIC_DIR=/app/static \
    LICENSE_FILE=/app/LICENSE

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/api/healthz', timeout=4)"]

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
