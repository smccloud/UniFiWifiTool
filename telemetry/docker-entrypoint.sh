#!/bin/sh
set -eu

# Apply versioned SQL migrations before starting. Alembic uses
# TELEMETRY_DATABASE_URL from the environment.
alembic upgrade head

# Never enable the access log: it includes client IP addresses.
exec uvicorn app.main:app \
    --host 0.0.0.0 \
    --port 8000 \
    --no-access-log
