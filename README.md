# UniFi WiFi Access Manager

[![Build Status](https://jenkins.smccloud.com/buildStatus/icon?job=UniFiWifiTool&style=plastic)](#)

A small, self-hosted Linux application for deciding which devices may use your
UniFi WiFi — manually or on a weekly schedule — with a web UI, audit log, and
Docker deployment.

## What it does

- Builds a local inventory of WiFi clients (MAC, label, notes, SSID, IP, signal).
  Wired clients in a full UniFi network are ignored.
- Allows, blocks, or leaves devices unmanaged through the UniFi controller.
- Supports **per-client time-based access** with custom allow and block windows
  in the same weekly schedule. Block windows win over allow windows.
- Supports **Discipline Mode**: temporarily reduce a specific device's WiFi
  speed (download and upload limits) for a chosen duration, then restore its
  previous bandwidth group automatically.
- Timezone-aware, with overnight windows and a configurable fallback state.
- Applies schedule transitions automatically in the background.
- Keeps an audit log of every login, change, block, and unblock, with configurable retention.
- Includes light, dark, and follow-system themes.
- Includes a responsive web UI for phone and tablet browsers.
- Runs as a single non-root container with SQLite in `/data`.

## Screenshots

The screenshots use demo data and a local mock UniFi controller.

| Dashboard — dark | Dashboard — light |
| :-: | :-: |
| ![Dashboard in dark mode](docs/screenshots/overview-dark.jpg) | ![Dashboard in light mode](docs/screenshots/overview-light.jpg) |

**Per-client schedule editor** — mix allow and block windows; block windows win.

![Per-client schedule editor](docs/screenshots/schedule-editor.jpg)

**Settings** — controller capabilities, audit retention, and the MIT license.

<img src="docs/screenshots/settings-retention.jpg" alt="Settings, audit retention, and license" width="640">

## Important UniFi constraint

There are two local APIs, and they do different things:

| Capability | API | Credential |
| --- | --- | --- |
| Sites, connected clients, SSID MAC filters | Official Integration API | API key (`X-API-KEY`) |
| Rich client details, **block/unblock** | Classic controller API | Local admin cookie + CSRF |

The official API does **not** expose client block/unblock. To enforce access,
the app needs a local-only UniFi admin account in addition to the API key. If
only an API key is supplied, the app runs in read-only/inventory mode and says
so in the UI.

For true "only these MACs may join" enforcement, use the per-SSID MAC filter
(Network 10+) or RADIUS MAC authentication. Client block is a per-device
denylist; it is immediate and non-disruptive, but a brand-new device can
associate before it is blocked.

This project is not affiliated with or endorsed by Ubiquiti Inc. UniFi is a
trademark of Ubiquiti Inc.

## Quick start

Docker Compose is optional. The image is designed to be run directly, including
through unRAID's Docker Manager.

```bash
docker pull smccloud/unifi-wifi-tool:1.1.2

mkdir -p /opt/unifi-wifi-tool
docker run -d \
  --name unifi-wifi-tool \
  --restart unless-stopped \
  --init \
  -p 8080:8000 \
  -v /opt/unifi-wifi-tool:/data \
  -e SECRET_KEY="$(openssl rand -base64 48)" \
  -e UNIFI_HOST='https://192.168.1.1' \
  -e UNIFI_API_KEY='...' \
  -e UNIFI_USERNAME='unifi-service' \
  -e UNIFI_PASSWORD='...' \
  smccloud/unifi-wifi-tool:1.1.2
```

To build from source instead:

```bash
git clone <this-repo> unifi-wifi-tool
cd unifi-wifi-tool
docker build -t smccloud/unifi-wifi-tool:1.1.2 .
```

Open <http://localhost:8080> (or the host/IP where the container runs). On a
fresh install with no users, the app shows a one-time setup screen at `/setup`
to create the first administrator. Set `APP_PASSWORD` (and optionally
`APP_USERNAME`) instead if you need an unattended install; it is imported as the
first admin at startup and ignored once any user exists.

- **unRAID:** see [unraid/README.md](unraid/README.md) and the template at
  [unraid/unifi-wifi-tool.xml](unraid/unifi-wifi-tool.xml).
- **Compose (optional):** `cp .env.example .env`, edit it, then
  `docker compose up -d --build`.
- **TLS/reverse proxy:** set `COOKIE_SECURE=true` and, behind a trusted proxy,
  `TRUST_PROXY=true`.

### unRAID notes

Use a bind mount for `/data`:

```
/mnt/user/appdata/unifi-wifi-tool  ->  /data
```

The template runs the container as `99:100` (unRAID `nobody:users`). Prepare the
directory with `chown -R 99:100 /mnt/user/appdata/unifi-wifi-tool` before the
first start, or adjust the UID/GID in the template's Extra Parameters.

The template's `Repository` value is a placeholder. Replace it with the registry
path of your built image before installing the template.

### Data persistence and upgrades

The SQLite database is always at `/data/unifi-wifi.db` inside the container
(plus `-wal` and `-shm` files). It survives container recreation and image
updates as long as `/data` is a bind mount or named volume:

- unRAID: `/mnt/user/appdata/unifi-wifi-tool:/data`
- plain Docker: `-v /opt/unifi-wifi-tool:/data`
- Compose: the named volume `unifi-wifi-data`

Do not change `DATABASE_URL` to a path outside `/data` unless you also persist
that path. `docker compose down` keeps volumes; `docker compose down -v`
deletes them. Removing a container in the unRAID Docker UI does **not** remove
the appdata directory.

Before upgrading, take a backup. For a consistent backup while the app is
running:

```bash
docker exec unifi-wifi-tool python -c \
  "import sqlite3; s=sqlite3.connect('/data/unifi-wifi.db'); d=sqlite3.connect('/tmp/backup.db'); s.backup(d); d.close(); s.close()"
docker cp unifi-wifi-tool:/tmp/backup.db ./unifi-wifi-$(date +%F).db
```

The Jenkins pipeline also creates a device, replaces the container, and verifies
the device is still present from the same `/data` volume. Schema changes are
additive; an existing database is migrated automatically on startup.

### Controller setup

1. **API key** — UniFi Network → **Settings → Control Plane → Integrations →
   Create New API Key**. Put it in `UNIFI_API_KEY`.
2. **Local admin** — create a dedicated UniFi admin with local-only access and
   only the permissions it needs. Put the credentials in `UNIFI_USERNAME` and
   `UNIFI_PASSWORD`. Do not use a Super Admin, and do not enable 2FA on this
   service account.
3. **Host** — set `UNIFI_HOST` to the console, e.g. `https://192.168.1.1`.
   A bare IP is accepted and `https://` is assumed.
4. **Site** — for a single-site console, leave `UNIFI_SITE_ID` empty. For
   multiple sites, copy the site UUID from
   `GET /proxy/network/integration/v1/sites`.

Prefer `UNIFI_CA_BUNDLE` for a private/self-signed certificate. Only set
`UNIFI_TLS_INSECURE=true` if you understand the risk.

### Users and roles

Users live in the app's SQLite database (`/data/unifi-wifi.db`) in a `users`
table. Passwords are stored only as salted **scrypt** hashes and can never be
read back.

- **First run:** with no users, `/setup` asks for a username, optional display
  name, and password, then creates an administrator. There are no default
  credentials.
- **Admin:** full access, including Settings -> Users and device changes.
- **Viewer:** read-only access to devices, live clients, audit, and status.
  Mutating API calls return `403`.
- **Manage users:** Settings -> Users. You can add users, change roles,
  disable an account, reset a password, sign a device out, and delete users.
  The last enabled administrator cannot be demoted, disabled, or deleted.
- **Sessions:** each account has a session version. Changing a password,
  changing a role, or disabling an account invalidates that user's existing
  sessions immediately. Your own password change keeps your current session.
- **Your account:** Settings -> Your account changes your own password.

Existing installs that still use `APP_PASSWORD` / `APP_PASSWORD_HASH` keep
working: on first start the environment account is imported as the first admin
and the setup screen is skipped. Once users exist, environment credentials no
longer grant access.

#### Password hash (optional bootstrap)

To seed the first admin without a plaintext password in the environment,
generate a hash and set `APP_PASSWORD_HASH`:

```bash
cd backend
python -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python -c "from app.auth import hash_password; print(hash_password('your-password'))"
```

Remove `APP_PASSWORD` when using `APP_PASSWORD_HASH`.

#### Recovering access

If the only administrator is lost, stop the container and run a one-off
command against the same `/data` volume to create a recovery admin:

```bash
docker run --rm -v /opt/unifi-wifi-tool:/data \
  --entrypoint python smccloud/unifi-wifi-tool:1.1.2 -c "
from app.db import init_db, get_session_factory
from app.models import User
from app.auth import hash_password
init_db()
db = get_session_factory()()
db.add(User(username='recovery', display_name='Recovery', role='admin',
            password_hash=hash_password('a-long-recovery-password')))
db.commit()
"
```

Sign in as `recovery`, fix the existing accounts, then delete the recovery user.

## Using time-based access

Open a device → **Time-based access**. Schedules are per client and can mix
allow and block windows in one weekly rule:

- **Fallback state** — what happens outside every window: blocked or allowed.
- **Allow windows** — access is allowed while any allow window is active.
- **Block windows** — access is blocked while any block window is active.
- **Block wins** over allow when windows overlap.
- Add as many windows as you need and select days (Mon–Sun).
- Windows that cross midnight are supported: Monday `22:00–07:00` ends Tuesday
  at 07:00.
- The scheduler evaluates every 30 seconds (`SCHEDULER_INTERVAL_SECONDS`).
- The timezone defaults to `APP_TIMEZONE` and can be overridden per device.

Examples:

| Goal | Fallback | Windows |
| --- | --- | --- |
| Kids' tablet only 4–8pm | Blocked | Allow every day 16:00–20:00 |
| Guest devices blocked overnight | Allowed | Block every day 22:00–07:00 |
| Office laptop blocked during work hours | Allowed | Block Mon–Fri 09:00–17:00 |
| Allowed weekdays 9–5, lunch blocked | Blocked | Allow Mon–Fri 09:00–17:00; Block Mon–Fri 12:00–13:00 |

The UI shows the effective state and the next transition. The scheduler records
each transition in the audit log.

Use the theme switch in the top bar to choose **System**, **Light**, or **Dark**.
The choice is stored in the browser and follows OS changes while set to System.

## Temporary speed limits (Discipline Mode)

Discipline Mode is a parental-control option that temporarily **reduces a
device's WiFi speed** without blocking it. This keeps the device connected for
homework, emergency contact, or other legitimate use while applying a
proportionate limit.

Open a device → **Discipline Mode**:

- Set download and upload limits in **Mbps or Kbps**. The UI converts the
  entered values to Kbps and shows the exact values that will be sent before
  anything is applied.
- Quick presets: **1 Mbps**, **768 Kbps**, **14.4 Kbps**, **28.8 Kbps**,
  **33.6 Kbps symmetrical**, and **56 Kbps down / 33.6 Kbps up**. UniFi stores
  whole Kbps, so fractional presets are rounded (for example, 33.6 Kbps becomes
  34 Kbps) and the preview shows the rounded value.
- Choose a duration (30 minutes, 1 hour, 2 hours, 4 hours, 8 hours, or a custom
  number of minutes).
- Apply the limit. The app validates the converted Kbps values, creates a
  temporary UniFi bandwidth user group, and assigns it to the device.
- The previous bandwidth group is restored automatically when the duration
  expires.
- Use **End speed limit** to restore normal speed early.
- Active limits show their expiry in the device list and device drawer.
- Starting, ending, and automatic restoration are written to the audit log.

The API uses `download_kbps` / `upload_kbps` fields because UniFi user groups
are expressed in Kbps. This feature needs the local admin credentials
(`UNIFI_USERNAME` / `UNIFI_PASSWORD`), because it uses UniFi's classic
user-group API. If the
controller does not support user groups, the UI reports that temporary speed
limits are unavailable rather than blocking the device. Automatic restoration
runs in the scheduler loop; with `SCHEDULER_ENABLED=false`, use **End speed
limit** manually.

## Anonymous install tracking (opt-in)

Anonymous install tracking is **off by default**. When enabled, the app sends a
small heartbeat once per day containing only:

- a random install ID stored in `/data`,
- the app version,
- a coarse platform label such as `linux/amd64`,
- the deployment type such as `docker` or `unraid`.

It does **not** send or store IP addresses, MAC addresses, SSIDs, device names,
controller URLs, credentials, or any other network data. The public page at
<https://unifiwifitool.smccloud.com> shows no statistics; an
authenticated admin section lists anonymous install IDs.

Enable it with:

```bash
TELEMETRY_ENABLED=true
TELEMETRY_URL=https://unifiwifitool.smccloud.com/api/v1/heartbeat
DEPLOYMENT_TYPE=unraid
```

The tracking service is a PHP 8 + MySQL/MariaDB application designed for
shared hosting; it does not require Docker. Source and deployment notes are in
[telemetry/](telemetry/README.md). The proposal and privacy requirements are
tracked in [issue #3](https://github.com/smccloud/UniFiWifiTool/issues/3).

## Audit retention

The audit log keeps login, access-change, block, and unblock history. By
default it is kept forever. Set a retention period in **Settings → Audit
retention** or with `AUDIT_RETENTION_DAYS`:

- `0` — keep every entry forever.
- `N` — delete entries older than `N` days (UTC).
- The UI setting is stored in SQLite, survives upgrades, and overrides the
  environment variable at runtime.
- Automatic cleanup runs at most once per day while the scheduler is enabled.
- **Run cleanup now** deletes immediately using the saved retention value.
- Each purge is recorded as an `audit_purged` entry, and retention changes are
  recorded as `audit_retention_changed`.
- If the scheduler is disabled, automatic cleanup does not run; use the button
  or enable the scheduler.

This is the one intentional exception to the append-only audit log: old entries
are removed only according to the configured retention policy.

### Dry run first

Set `DRY_RUN=true` to log intended block/unblock calls without sending them.
Use **Sync now** and review the audit log before switching enforcement on.

## Development

Backend (Python 3.12+):

```bash
cd backend
python -m venv .venv
.venv/bin/pip install -r requirements-dev.txt
AUTH_DISABLED=true DATABASE_URL=sqlite:///./data/dev.db SECRET_KEY=dev-secret \
  .venv/bin/uvicorn app.main:app --reload --port 8000
```

Frontend (Node 20+):

```bash
cd frontend
npm install
npm run dev        # http://localhost:5173, proxies /api to :8000
```

Tests and production build:

```bash
cd backend && .venv/bin/pytest
cd frontend && npm run build
```

## Docker build and CI

```bash
docker build -t smccloud/unifi-wifi-tool:1.1.2 .
```

The `Jenkinsfile` builds and tests the image, then publishes successful
`master` builds to Docker Hub (`smccloud/unifi-wifi-tool` by default) using the
Jenkins username/password credentials named **Docker**. Images are pushed as
`:1.1.2` (the version in `VERSION`) and `:latest`. Override the repository or
credential ID with the
`DOCKERHUB_REPOSITORY` and `DOCKERHUB_CREDENTIALS_ID` parameters; disable
publishing with `PUSH_TO_DOCKERHUB=false`.

Existing version tags are not overwritten: if the tag for `VERSION` already
exists on Docker Hub, that push is skipped and you must bump `VERSION` to
publish a new release. Docker Hub immutable-tag rejections are reported as
warnings instead of failing the build.

Docker Scout always writes a SARIF vulnerability report and an SPDX SBOM. The
scan generates VEX exceptions for disputed base-image CVEs with
[`scripts/generate-vex.sh`](scripts/generate-vex.sh).
`SCOUT_FAIL_ON_SEVERITY` controls the build gate: `critical` (default),
`critical,high`, or `none` for report-only mode. Critical-only is the default
because the current `python:3.13-slim` base image has unfixed high-severity
findings.

1. validates the Docker toolchain,
2. runs backend tests in `python:3.13-slim` and publishes JUnit results,
3. builds the frontend in `node:22-alpine`,
4. builds the multi-stage image,
5. scans the built image with Docker Scout, archives a SARIF report and SBOM,
   and enforces the configured `SCOUT_FAIL_ON_SEVERITY` threshold,
6. validates `docker-compose.yml` when Compose is installed,
7. boots the image twice with `--network none` and checks, via `docker exec`:
   - `/api/healthz`,
   - the built SPA is served,
   - status endpoint,
   - unauthenticated API returns 401,
   - wrong password returns 401,
   - login returns a CSRF token,
   - mutations without CSRF return 403,
   - a CSRF-authenticated device create returns 201,
   - the MIT license endpoint returns the license,
   - audit retention can be updated with a CSRF token,
8. creates a device in a fresh `/data` volume, replaces the container, and
   confirms the device is still present from the same database,
9. on `master`, logs into Docker Hub with the `Docker` credential and pushes
   the version and `latest` tags.

### Versioning

The application version lives in [`VERSION`](VERSION) and is currently `1.1.2`.
The backend exposes it through `/api/auth/me`, `/api/system/status`, the health
endpoint, and the UI footer/About panel. `frontend/package.json` and
`.env.example`'s `IMAGE_VERSION` mirror it, and the backend test suite fails if
they drift. Docker Hub images use the same version tag.

Run [`scripts/bump-version.sh`](scripts/bump-version.sh) to update `VERSION` and
all mirrored references. It bumps by `0.1.0` by default, commits the changed
files, and pushes the branch to `origin`. Set `BUMP_AMOUNT` to change the amount
or `PUSH_REMOTE` to use another remote.

The `DOCKER_NETWORK` parameter defaults to `default`, so build and test containers
use the Docker bridge network. Set it to `host` on restricted/nested/rootless CI
agents where bridge/veth creation is unavailable.

## Configuration highlights

| Variable | Default | Purpose |
| --- | --- | --- |
| `APP_USERNAME` / `APP_PASSWORD` | `admin` / — | Optional bootstrap admin; used only when no users exist. |
| `APP_PASSWORD_HASH` | — | Bootstrap admin as a scrypt hash instead of a plaintext password. |
| `SECRET_KEY` | required | Session signing key. |
| `COOKIE_SECURE` | `false` | Set `true` when served over HTTPS. |
| `UNIFI_HOST` | — | Console URL/IP. |
| `UNIFI_API_KEY` | — | Official Integration API. |
| `UNIFI_USERNAME` / `UNIFI_PASSWORD` | — | Local admin for block/unblock. |
| `UNIFI_SITE_ID` | first site | Site UUID for the official API. |
| `UNIFI_SITE` | `default` | Classic API site name. |
| `APP_TIMEZONE` | `UTC` | Default schedule timezone. |
| `DISCOVERY_ENABLED` | `true` | Import connected WiFi clients as unmanaged. |
| `AUTO_APPLY` | `true` | Apply desired states/schedules automatically. |
| `DRY_RUN` | `false` | Log calls without sending them. |
| `AUDIT_RETENTION_DAYS` | `0` | Days to keep audit entries; `0` = forever. UI override available. |
| `TELEMETRY_ENABLED` | `false` | Opt in to anonymous install heartbeats. |
| `TELEMETRY_URL` | tracking service | Anonymous heartbeat endpoint. |
| `TELEMETRY_INTERVAL_SECONDS` | `86400` | Heartbeat interval, at least once per day. |
| `DEPLOYMENT_TYPE` | `docker` | Coarse deployment label sent with heartbeats. |
| `SCHEDULER_ENABLED` | `true` | Background reconciliation and retention loop. |
| `SCHEDULER_INTERVAL_SECONDS` | `30` | Transition check interval. |
| `ENABLE_SSID_ALLOWLIST` | `false` | Experimental per-SSID MAC filter writes. |

Discipline Mode has no dedicated environment variables; it uses the classic
controller credentials described above.

See [.env.example](.env.example) for the full list.

## Security notes

- Put the app behind HTTPS and set `COOKIE_SECURE=true` for anything beyond a
  trusted LAN.
- User accounts are stored in SQLite with scrypt password hashes. Use a unique
  password per person and give read-only users the Viewer role.
- Complete first-run setup before exposing the app to an untrusted network;
  until then anyone who can reach the port can claim the first admin account.
- Use a dedicated local-only UniFi admin; never a Super Admin.
- API keys inherit the creating admin's role and have no per-key scopes.
- Never commit `.env`. The container stores only inventory/schedules/audit in
  SQLite; controller secrets stay in environment variables.
- Review the audit log for unexpected blocks, unblocks, and login attempts.
- One replica only: the scheduler and SQLite are not designed for horizontal
  scaling yet.
- The runtime image applies the Debian `zlib1g` security update and removes
  `pip`/`setuptools` after installing dependencies to reduce attack surface.

## Roadmap

- Strict SSID allowlist management with batched applies and blast-radius warnings.
- Notifications (email/webhook) for access changes and controller outages.
- CSV import/export.
- OIDC/SSO for external identity providers (local users and roles are implemented).
- Multiple controllers/sites.
- Prometheus metrics and certificate pinning.

See [docs/DESIGN.md](docs/DESIGN.md) for the full architecture, state machine,
failure modes, and a real-controller verification checklist.

## License

MIT. See [LICENSE](LICENSE). The license is also shown in the UI under
**Settings → About** and is served at `/LICENSE`.
