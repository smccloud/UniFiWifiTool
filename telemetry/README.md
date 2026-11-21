# Anonymous install tracking service

A small FastAPI + MySQL/MariaDB service that powers
**https://unifiwifitool.smccloud.com**.

It stores only anonymous install heartbeats:

- random install ID,
- app version,
- coarse platform (for example `linux/amd64`),
- deployment type (`docker`, `unraid`),
- first seen,
- last seen.

It does **not** store or log IP addresses, MAC addresses, SSIDs, hostnames,
controller URLs, credentials, or device names.

## Privacy design

- The heartbeat endpoint does not accept a `Request` object, so it cannot read
  or log the client IP.
- Uvicorn runs with `--no-access-log`.
- The reverse proxy must also discard access logs; see the Caddy example below.
- There are no IP columns in the database.
- The public page shows aggregate counts only. Individual install IDs are
  visible only in the authenticated admin section.
- Client tracking is **opt-in**. The main application sends nothing unless
  `TELEMETRY_ENABLED=true`.

## Deploy

1. Create DNS for `unifiwifitool.smccloud.com` pointing at the host.
2. Copy `.env.example` to `.env` and set strong passwords.
3. Start the stack:

```bash
docker compose up -d --build
```

The app binds to `127.0.0.1:8081`. Put a TLS reverse proxy in front of it.
Caddy example:

```caddyfile
unifiwifitool.smccloud.com {
    log {
        output discard
    }
    reverse_proxy 127.0.0.1:8081
}
```

The `log { output discard }` block is intentional: do not enable access logs
for this host. For nginx, use `access_log off;`.

## Pages

- `/` — public aggregate stats.
- `/api/v1/stats` — JSON stats.
- `/admin` — HTTP Basic authenticated install list, filters, and prune button.
- `/healthz` — container health check.

Set `TELEMETRY_ADMIN_USERNAME` and `TELEMETRY_ADMIN_PASSWORD` for the admin
section.

## Database

The service requires MySQL or MariaDB. `TELEMETRY_DATABASE_URL` uses the
SQLAlchemy format:

```text
mysql+pymysql://telemetry:password@db:3306/telemetry
```

Use a URL-safe database password, or percent-encode special characters. The
container runs `alembic upgrade head` before starting.

The `installs` table is defined in
[`migrations/versions/0001_initial.py`](migrations/versions/0001_initial.py).
Keep schema changes in new Alembic revisions.

Back up the MariaDB volume regularly. Retention runs automatically once per day
and can also be triggered from the admin page; installs older than
`TELEMETRY_RETENTION_DAYS` (default 90) are removed.

## Client configuration

In the main application:

```bash
TELEMETRY_ENABLED=true
TELEMETRY_URL=https://unifiwifitool.smccloud.com/api/v1/heartbeat
DEPLOYMENT_TYPE=unraid
TELEMETRY_INTERVAL_SECONDS=86400
```

`TELEMETRY_ENABLED` defaults to `false`. Documentation must keep this opt-in
behavior explicit.

## Development

```bash
python -m venv .venv
.venv/bin/pip install -r requirements-dev.txt
.venv/bin/pytest
```

The tests use SQLite for query logic; production uses MySQL/MariaDB.
