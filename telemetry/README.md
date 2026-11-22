# Anonymous install tracking service (PHP + MySQL)

This directory contains the shared-hosting service for
**https://unifiwifitool.smccloud.com**. It runs on **PHP 8.2+ and
MySQL/MariaDB**. It does **not** require Docker, Compose, Python, or Node.

The service stores only anonymous install data:

- random install ID,
- app version,
- coarse platform (`linux/amd64`, `linux/arm64`),
- deployment type (`docker`, `unraid`),
- first and last seen timestamps.

No IP addresses, MAC addresses, SSIDs, hostnames, controller URLs,
credentials, or device names are collected or stored.

## Privacy design

- The heartbeat handler never reads `REMOTE_ADDR` or request headers.
- There are no IP columns in the database.
- The public page shows aggregate counts only.
- Individual install IDs are visible only behind HTTP Basic authentication.
- Client tracking is **opt-in**: the main application sends nothing unless
  `TELEMETRY_ENABLED=true`.
- Application errors are logged with a generic message, not request metadata.

### Shared-host access logs

The script cannot control the hosting provider's HTTP access logs. Ask the host
whether access logs are enabled for this domain and whether they can be
disabled or shortened. If they cannot, the application itself still does not
store IPs, but the provider's access log may retain them outside the app.

## Files

```text
telemetry/
  public/                 document root
    index.php             front controller / API / pages
    .htaccess             rewrite non-file requests to index.php
    assets/style.css
  src/
    bootstrap.php
    database.php
    auth.php
    stats.php
    views.php
  db/migrations/001_initial.sql
  config.example.php      copy to config.php, outside public/
```

## Deploy on shared hosting

1. Upload this `telemetry/` directory so that `telemetry/public` is the
   document root for `unifiwifitool.smccloud.com`.
   - If the host will not let you change the document root, place the contents
     of `public/` in the domain root and keep `src/` and `config.php` outside
     the web root.
2. Copy the configuration:

   ```bash
   cp config.example.php config.php
   ```

   Edit `config.php` with the MySQL/MariaDB host, database, user, password,
   admin credentials, and retention period. Keep it outside `public/`.
3. Import the schema with phpMyAdmin or the MySQL CLI:

   ```bash
   mysql -u USER -p DATABASE < db/migrations/001_initial.sql
   ```

   The database needs `installs` and `settings` tables. There is intentionally
   no IP column.
4. Ensure PHP has the `pdo_mysql` extension enabled.
5. Enable HTTPS for the domain.
6. Visit `/admin` and sign in with the Basic-auth credentials from
   `config.php`.
7. Send a test heartbeat:

   ```bash
   curl -i -X POST https://unifiwifitool.smccloud.com/api/v1/heartbeat \
     -H 'Content-Type: application/json' \
     -d '{"install_id":"11111111-1111-1111-1111-111111111111","app_version":"0.8.0","platform":"linux/amd64","deployment":"unraid","schema_version":1}'
   ```

   A valid request returns `204 No Content`. Then open `/` and `/admin` to
   verify the install appears.

## Routes

- `/` — public aggregate stats.
- `/api/v1/stats` — JSON aggregate stats.
- `/api/v1/heartbeat` — anonymous heartbeat receiver.
- `/admin` — HTTP Basic authenticated install list and filters.
- `/admin/prune` — authenticated retention prune action.
- `/healthz` — health check.

## Retention

`001_initial.sql` also creates the `settings` table used to throttle automatic
pruning. The service prunes installs older than `retention_days` at most once
per day, and the admin page can trigger pruning manually.

## Client configuration

In the main application:

```bash
TELEMETRY_ENABLED=true
TELEMETRY_URL=https://unifiwifitool.smccloud.com/api/v1/heartbeat
DEPLOYMENT_TYPE=unraid
TELEMETRY_INTERVAL_SECONDS=86400
```

`TELEMETRY_ENABLED` defaults to `false`.

## Local checks

Use the PHP binary available on the host:

```bash
find . -name '*.php' -print0 | xargs -0 -n1 php -l
```

No database is required for syntax checks. There is no Docker deployment for
this service.
