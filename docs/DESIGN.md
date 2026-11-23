# UniFi WiFi Access Manager — Design

Status: initial scaffold (v0.1). This document explains the constraints that
shape the product and the architecture of the current implementation.

## 1. Problem

Small UniFi networks often need a simple answer to: *which devices are allowed
to use the WiFi, and when?* UniFi's own UI is powerful but awkward for
day-to-day device decisions, particularly:

- blocking or unblocking a device quickly,
- keeping a curated inventory with human-friendly labels and notes,
- allowing a device only during certain hours,
- keeping an audit trail of who changed access and when.

This application is a single-container web app for those tasks.

## 2. Goals and non-goals

### Goals

- Manage a local inventory of WiFi devices keyed by MAC address.
- Allow, block, or leave devices unmanaged.
- Support weekly, timezone-aware access schedules, including overnight windows.
- Support temporary per-device WiFi speed limits for parental control.
- Apply access changes automatically in the background and record every action.
- Run as one non-root Docker container with SQLite on a volume.
- Work with UniFi OS consoles (UDM, Cloud Gateway, etc.) and current Network
  application versions.
- Be safe by default: authentication, CSRF protection, TLS verification, audit
  log, dry-run mode, and explicit warnings for destructive operations.

### Non-goals (v1)

- Replacing UniFi's firewall, RADIUS, or guest portal configuration.
- Managing wired switch ports.
- Multi-controller / multi-tenant orchestration.
- A full identity provider. Local admin/viewer accounts are supported; OIDC is planned.
- Guaranteeing pre-association enforcement for unknown devices (see §4).

## 3. UniFi API constraints

There are two relevant local APIs on a UniFi OS console.

| Surface | Path | Auth | Used for |
| --- | --- | --- | --- |
| Official Integration API | `/proxy/network/integration/v1` | `X-API-KEY` | Sites, connected clients, WiFi broadcasts, per-SSID MAC filtering (Network 10+) |
| Classic controller API | `/proxy/network/api/s/{site}` | `TOKEN` cookie + `X-CSRF-Token` | Rich client inventory (`/stat/sta`) and client block/unblock (`/cmd/stamgr`) |

Critical consequence: **client block/unblock is not exposed by the official
API**. It requires a local admin session. The app therefore treats the API key
and local admin credentials as separate capabilities:

- API key only → read-only inventory, controller health, SSID allowlist reads.
- API key + local admin → full block/unblock and schedule enforcement.
- Local admin only → inventory and block/unblock, but no official API features.

Capabilities are detected at runtime and surfaced in the UI, rather than
failing startup. A missing capability produces a clear warning and disables the
relevant actions.

Network 10.0+ additionally exposes a per-SSID `clientFilteringPolicy` with an
`ALLOW`/`BLOCK` MAC list (up to 512 entries). This is the path to true
pre-association allowlisting. It is implemented as an experimental,
opt-in write path because editing an SSID triggers a reprovision and can briefly
disconnect clients.

## 4. Access model

Each device has one access mode:

| Mode | Meaning |
| --- | --- |
| `allowed` | Not blocked; expected to be on the network. |
| `blocked` | Blocked at the controller with `block-sta`. |
| `scheduled` | Computed from a weekly schedule. |
| `ignored` | Unmanaged. Discovery can update observations but never changes access. |

A schedule has:

- `default_access`: the state outside every window (`allowed` or `blocked`),
- an IANA timezone,
- one or more weekly windows, each with days `0..6` (Monday = 0), `HH:MM`
  start/end times, and an `action` of `allow` or `block`.

Evaluation:

1. If any active window has action `block`, the result is `blocked`.
2. Otherwise, if any active window has action `allow`, the result is `allowed`.
3. Otherwise, the result is `default_access`.

Block windows therefore take priority over allow windows when they overlap.
A single schedule can express rules such as "allowed weekdays 09:00–17:00, but
blocked 12:00–13:00 for lunch" or "allowed outside 22:00–07:00".

Rules:

- Windows are end-exclusive: `09:00–17:00` is active at `09:00` and inactive at
  `17:00`.
- If `end <= start`, the window crosses midnight and belongs to its start day.
  A Monday `22:00–07:00` window covers Tuesday 06:59.
- Evaluation uses the configured timezone. The next transition is computed by
  checking window boundaries over the next eight days, comparing the effective
  state at each boundary.
- A corrupt or missing schedule fails closed to `blocked`.
- Legacy schedules that used `kind` (`allow_during` / `block_during`) and a
  single window action are converted on read to `default_access` plus per-window
  actions. Existing rows are migrated with an additive `schedule_default`
  column.
- Manual `allowed`/`blocked` modes override any stored schedule.
- Schedules are evaluated by the background loop (default every 30 seconds)
  and applied only to explicitly managed devices.
- Anonymous install tracking is opt-in. When enabled, the app stores a random
  install ID locally and sends only that ID, the app version, a coarse platform
  label, and the deployment type to the tracking service once per day. No IP or
  network/device data is included.

### Why block/unblock rather than a true allowlist by default?

`block-sta` is per-device and non-disruptive. It is a denylist, not a
pre-association ACL: a never-seen device can associate before an operator
blocks it. For strict "only these MACs may join", use the SSID MAC filter
(Network 10+) or RADIUS MAC authentication. The data model and UI are designed
so both mechanisms can coexist.

## 5. Architecture

```
                        ┌──────────────────────────────┐
                        │  Browser (React SPA)         │
                        └──────────────┬───────────────┘
                                       │ HTTPS / JSON + session cookie
                        ┌──────────────▼───────────────┐
                        │  FastAPI                     │
                        │  · auth + CSRF               │
                        │  · devices / clients / audit │
                        │  · static SPA                │
                        └───────┬──────────────┬───────┘
                                │              │
                    ┌───────────▼───┐   ┌──────▼─────────────┐
                    │ SQLite        │   │ UniFiService       │
                    │ devices       │   │ capability detect  │
                    │ schedules     │   └───┬────────────┬───┘
                    │ audit         │       │            │
                    │ settings      │  ┌────▼─────┐  ┌───▼──────────┐
                    └───────────────┘  │Integration│  │Classic       │
                                       │API client │  │API client    │
                                       │X-API-KEY  │  │cookie+CSRF   │
                                       └───────────┘  └──────────────┘
                                ▲
                                │ every N seconds
                    ┌───────────┴───────────┐
                    │ reconcile / scheduler │
                    └───────────────────────┘
```

### Backend modules

| Module | Responsibility |
| --- | --- |
| `app/config.py` | Environment configuration, validation, TLS settings. |
| `app/db.py` | SQLAlchemy engine/session, UTC datetime handling, SQLite pragmas. |
| `app/models.py` | `Device`, `User`, `AuditLog`, `Setting`. |
| `app/schedule.py` | Pure schedule validation, evaluation, next-transition math. |
| `app/unifi/integration.py` | Official API client with pagination, 429/5xx retries. |
| `app/unifi/classic.py` | Login, CSRF rotation, `/stat/sta`, `/cmd/stamgr`. |
| `app/unifi/service.py` | Capability detection and high-level operations. |
| `app/reconcile.py` | Discovery, drift detection, apply/audit, dry-run. |
| `app/discipline.py` | Temporary speed-limit lifecycle and expiry restoration. |
| `app/retention.py` | Audit retention settings, purge, and daily scheduling. |
| `app/heartbeat.py` | Opt-in anonymous install heartbeat task. |
| `app/routes/*` | JSON API for auth, setup, users, devices, clients, audit, and system settings. |
| `app/main.py` | Lifespan, scheduler task, security headers, SPA and license serving. |

### Data model

`devices`

- `mac` unique, normalized lowercase colon form.
- `access_mode` (`allowed`, `blocked`, `scheduled`, `ignored`).
- schedule columns (`schedule_default`, `schedule_kind` for legacy rows,
  `schedule_timezone`, `schedule_windows` JSON with per-window actions).
- computed state (`effective_access`, `next_change_at`).
- enforcement bookkeeping (`last_applied_access`, `last_applied_at`, `last_error`).
- observed controller state (`observed_blocked`, hostname, IP, SSID, signal,
  last seen, UniFi client ID, connection type).
- provenance (`source`, `created_by`, timestamps).

`users`

Local Web UI accounts: unique case-insensitive username, optional display
name, scrypt `password_hash`, role (`admin` or `viewer`), disabled flag,
session version (incremented to revoke that user's sessions), and
created/updated/last-login timestamps. Passwords are never stored in plaintext
or returned by the API. `audit_log.user_id` references the acting user and is
set to NULL if the user is deleted, leaving the `actor` display snapshot.

`audit_log`

Append-only except for configured retention: timestamp, actor, user ID (when the
action came from a Web UI account), action, target MAC, result, JSON detail,
request ID. Secrets are never recorded. Purging and retention changes are
themselves audited.

`discipline_actions`

Active and historical temporary speed limits: device/MAC, download/upload
Kbps, start/expiry/end timestamps, previous and temporary UniFi user-group IDs,
client ID, actor, end reason, and last error. A device can have at most one
active action.

`settings`

Small key/value store for runtime facts: last sync summary, the runtime audit
retention override, and the last retention purge (timestamp, count, cutoff).

## 6. Reconciliation

A sync run performs:

1. **Discover** connected clients from the controller. Existing devices get
   refreshed observations; new wireless clients become `ignored` devices when
   discovery is enabled. Wired clients are recorded as wired but excluded from
   the inventory and never enforced. User-owned fields (label, notes, access
   mode) are never overwritten.
2. **Evaluate** every device whose mode is not `ignored`:
   - compute effective access and next transition,
   - compare with `last_applied_access`,
   - compare with the observed controller `blocked` flag when available.
3. **Apply** only when needed:
   - never call `unblock` merely because a new allowed device was added,
   - correct drift when the controller reports an unexpected block state,
   - block a device that is explicitly blocked even if it is offline.
4. **Audit** each call, including failures, and persist the sync summary.

The apply rule is intentionally conservative:

| Effective | Last applied | Observed blocked | Action |
| --- | --- | --- | --- |
| allowed | none | unknown/false | none |
| allowed | blocked | any | unblock |
| allowed | any | true | unblock |
| blocked | blocked | unknown/true | none |
| blocked | none/allowed | any | block |
| blocked | any | false | block |

Concurrent syncs are rejected with HTTP 409. Dry-run mode records intended
actions as `dry_run` audit entries without calling the controller.

### Audit retention

The audit log is append-only by application behaviour, with one explicit
administrative exception: retention. A retention period of `0` keeps entries
forever (the default). A positive value deletes rows older than that many days
(UTC). The runtime value is stored in the `settings` table and overrides the
`AUDIT_RETENTION_DAYS` environment variable.

- Automatic cleanup runs at most once per 24 hours from the scheduler.
- The UI can save a new retention value or run cleanup immediately.
- Purging records an `audit_purged` entry; changing retention records an
  `audit_retention_changed` entry.
- The last cleanup timestamp and deleted count are stored in `last_audit_purge`
  and shown in the UI.

### Temporary speed limits (Discipline Mode)

UniFi applies per-client bandwidth limits through **user groups** (bandwidth
profiles), not a single per-client speed field. Starting a limit:

1. Look up the client and remember its current `usergroup_id`.
2. Create a temporary user group with the requested download/upload Kbps.
3. Assign the temporary group to the client via `/upd/user/{id}`.
4. Persist the action, previous group, expiry, and audit entry.

Ending a limit (manual or expired) restores the previous group (or a default
unlimited group), deletes the temporary group, and records the result. The
scheduler checks for expired actions independently of normal sync. Capability
detection verifies that the classic API and user-group endpoints are available;
otherwise the UI disables the feature.

## 7. HTTP API

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/setup/status` | Whether first-run setup is required; returns a CSRF token. |
| POST | `/api/setup` | Create the initial administrator (only while no users exist). |
| POST | `/api/auth/login` | Session login; returns CSRF token and role. |
| POST | `/api/auth/logout` | Clear session. |
| GET | `/api/auth/me` | Current user, role, and CSRF token. |
| GET | `/api/users` | List users (admin only). |
| POST | `/api/users` | Create a user (admin only). |
| PATCH/DELETE | `/api/users/{id}` | Change role/status or delete a user (admin only). |
| POST | `/api/users/{id}/password` | Reset another user's password (admin only). |
| POST | `/api/account/password` | Change your own password. |
| GET | `/api/system/status` | Capabilities, counts, enforcement settings, last sync. |
| POST | `/api/system/sync` | Run discovery + reconciliation. |
| POST | `/api/system/test` | Force capability re-detection. |
| POST | `/api/devices/{id}/discipline` | Start a temporary speed limit. |
| GET | `/api/devices/{id}/discipline` | Read the active temporary speed limit. |
| DELETE | `/api/devices/{id}/discipline` | Restore normal speed early. |
| PUT | `/api/system/audit-retention` | Set runtime audit retention and purge once. |
| POST | `/api/system/audit-purge` | Run audit retention cleanup immediately. |
| GET | `/api/license` | License metadata and full text. |
| GET | `/LICENSE` | Plain-text MIT license. |
| GET | `/api/devices` | Search/filter inventory. |
| POST | `/api/devices` | Add a device by MAC. |
| GET/PATCH/DELETE | `/api/devices/{id}` | Read, edit, or remove a device. |
| POST | `/api/devices/{id}/schedule` | Set/replace a weekly schedule. |
| DELETE | `/api/devices/{id}/schedule` | Clear schedule; device becomes unmanaged. |
| POST | `/api/devices/{id}/apply` | Apply the current effective state now. |
| GET | `/api/clients` | Live connected clients merged with inventory state. |
| GET | `/api/wifi` | SSIDs and current MAC-filter policy. |
| GET | `/api/audit` | Filterable audit log. |

All mutating endpoints require the session cookie **and** an `X-CSRF-Token`
header. The API is same-origin only. Roles are enforced server-side: Viewer
accounts receive `403` from every mutating endpoint, including user management.

## 8. UI

Five views:

- **Overview** — counts, controller health, enforcement mode, recent activity.
- **Devices** — searchable WiFi-only inventory, allow/block actions, edit
  drawer with notes, observed state, and the schedule editor.
- **Live clients** — connected clients from the controller, with one-click
  allow/block or add-to-inventory.
- **Audit log** — filter by action, result, or MAC.
- **Settings** — controller capabilities, SSID list/filter state, enforcement
  settings, audit retention controls, security notes, and an About/license
  section with the full MIT text.

The About section also links to `/LICENSE`; the backend serves the same file at
`/LICENSE` (plain text) and `/api/license` (JSON).

A theme switch in the top bar offers System, Light, and Dark. The preference is
stored in `localStorage`; System follows `prefers-color-scheme` live. A small
external bootstrap script applies the saved theme before React renders to avoid
a flash of the wrong theme.

The layout is responsive: navigation wraps on phones, tables scroll inside their
panel, drawers and modals use the full viewport on small screens, inputs use
16px text to avoid iOS zoom, and safe-area insets are respected. A sidebar
horizontal strip is used on narrow tablets; phones wrap the navigation instead.

The schedule editor includes presets (allow weekdays, allow evenings, block
overnight, block weekdays, weekend access), day toggles, timezone selection,
multiple windows, per-window allow/block actions, a fallback state, and a
plain-English preview. Block windows are evaluated before allow windows.

The Discipline Mode panel provides download/upload limits in Mbps or Kbps,
quick presets (1 Mbps, 768 Kbps, 14.4 Kbps, 28.8 Kbps, 33.6 Kbps symmetrical,
and 56/33.6 Kbps), unit conversion with an exact Kbps preview, duration presets
or a custom duration, an active-limit expiry display, and an early **End speed
limit** action. UniFi stores whole Kbps, so fractional presets are rounded and
the preview shows the resulting values before the controller call.
The wording is deliberately neutral and parental-control oriented.

## 9. Security

- Multiple local accounts stored in SQLite: username, scrypt `password_hash`,
  role (`admin`/`viewer`), disabled flag, and session version. Plaintext
  passwords are never stored or logged, and hashes are never returned by the API.
- First-run setup creates the initial administrator. It is available only while
  the `users` table is empty, is CSRF-protected, and is guarded against
  concurrent submissions. An env-configured admin (`APP_PASSWORD` or
  `APP_PASSWORD_HASH`) is imported on first start for existing installs.
- Signed, HttpOnly session cookie; `Secure` flag via `COOKIE_SECURE`. The cookie
  carries the user id and session version, so disabling an account, changing a
  role, or changing a password invalidates that user's sessions immediately.
- CSRF double-submit token required on every mutation.
- Login rate limiting with temporary lockout.
- Secrets only in environment variables; never returned by the API or written
  to audit details.
- Controller TLS verification is on by default. `UNIFI_CA_BUNDLE` is preferred;
  `UNIFI_TLS_INSECURE` is an explicit, logged opt-out.
- Security headers include CSP, `X-Frame-Options: DENY`, `nosniff`, and HSTS
  when cookies are marked secure.
- Container runs as a non-root user. Compose also applies
  `no-new-privileges` and drops all Linux capabilities; unRAID uses `--user
  99:100` to match appdata ownership.
- API key inherits the creating UniFi admin's role. Use a dedicated, least-
  privileged local admin, ideally with a key expiry.
- Audit retention deletes historical records, but deletions are bounded by the
  configured age and are recorded in the audit log. Back up the database before
  shortening a retention period.

## 10. Failure handling

| Failure | Behaviour |
| --- | --- |
| Controller unreachable | Detection reports an error; sync returns 502; UI shows status. |
| Missing API key | Official reads/SSID features marked unavailable; classic paths still work. |
| Missing local admin | Block/unblock unavailable; inventory/schedules remain available and warn. |
| 2FA on local admin | Login fails with a clear message; use a service account without 2FA. |
| Self-signed certificate | Set `UNIFI_CA_BUNDLE` or explicitly disable verification. |
| Login rate limit / 429 | Surfaced as an error; no login-per-request loop. |
| Corrupt schedule | Fails closed to `blocked` and records an error on the device. |
| Sync overlap | Manual sync returns 409; background run is skipped. |
| Partial failure | Per-device errors are recorded; other devices still reconcile. |
| Speed-limit restore failure | The action stays active with an error and is retried on later scheduler cycles. |
| Retention purge failure | Logged; the scheduler continues and retries on the next cycle. |
| Scheduler disabled | Automatic sync and retention do not run; manual Sync/Purge still work. |

### Install tracking service

`telemetry/` contains a separate PHP 8 + MySQL/MariaDB application for the
public telemetry page at `unifiwifitool.smccloud.com`. It is designed for shared
hosting and does not require Docker. It stores install ID, first/last seen,
version, platform, and deployment only. The application never reads or logs
client IPs, and there are no IP columns in the database. Shared-host access logs
must be checked separately and disabled by the provider where possible. The
public page shows no statistics; the admin page is HTTP Basic protected and
shows anonymous install IDs.

## 11. Deployment

- Multi-stage Docker build: Node builds the SPA; Python runtime serves API and
  static files.
- Designed for direct `docker run` and unRAID Docker Manager (template in
  `unraid/unifi-wifi-tool.xml`). Compose is optional.
- `/data` is a declared volume. Persist it with a bind mount on unRAID
  (`/mnt/user/appdata/unifi-wifi-tool:/data`) or a named volume elsewhere. The
  Jenkins pipeline verifies persistence across container replacement.
  The database is always `/data/unifi-wifi.db` unless `DATABASE_URL` is
  overridden.
- The application version is read from the repo-root `VERSION` file (mirrored
  in `frontend/package.json` and `IMAGE_VERSION`) and exposed by the API/UI.
- Jenkins publishes successful `master` builds to Docker Hub as
  `smccloud/unifi-wifi-tool:<version>` (currently `1.1.2`) and `latest`, using
  the Jenkins credentials named `Docker`.
- On unRAID, running as `--user 99:100` matches the usual appdata ownership;
  the image itself also has a non-root `app` user.
- Startup applies additive SQLite migrations (for example, adding
  `schedule_default`) so an existing database survives image upgrades.
- Health check: `GET /api/healthz`.
- The MIT license is copied into the image and served at `/LICENSE` and
  `/api/license`; the UI shows it under Settings → About.
- Reverse proxy is optional; terminate TLS at Caddy/Traefik/nginx and set
  `COOKIE_SECURE=true`.
- One replica only: the scheduler and SQLite are not designed for horizontal
  scaling yet.

## 12. Roadmap

1. **Strict allowlist mode**: manage the SSID `clientFilteringPolicy` from the
   device inventory, with a batched "apply changes" step, blast-radius warning,
   and before/after diff. (Read path and experimental apply code exist.)
2. **Notifications**: email/webhook on block/unblock, login failure, controller
   outage, and schedule transitions.
3. **CSV import/export** of labels, notes, and schedules.
4. **OIDC / multi-user RBAC** with read-only and operator roles.
5. **Multiple controllers/sites** and per-site timezones.
6. **Prometheus metrics** and structured JSON logging.
7. **TLS certificate pinning** for self-signed consoles.
8. **Backup/restore** of the SQLite database and schedules.
9. **Audit export/forwarding** (CSV/syslog/webhook) before retention purges.

## 13. Verification checklist for a real controller

Before relying on enforcement, verify:

- [ ] `GET /api/system/test` shows the expected Network version and site.
- [ ] API key works for `/api/wifi` on the installed Network version.
- [ ] Local admin can block and unblock a test device; the client reconnects
      after unblock.
- [ ] A weekday schedule with an overnight window produces the expected
      transitions (observe the audit log).
- [ ] A mixed schedule with an allow window and an overlapping block window
      blocks during the overlap and allows outside it as configured.
- [ ] The timezone matches the site's local time; DST transitions behave.
- [ ] Blocking an offline known client persists after it reconnects.
- [ ] `DRY_RUN=true` produces `dry_run` audit rows and no controller changes.
- [ ] The container survives a restart with the SQLite volume intact.
- [ ] Removing and recreating the container with the same `/data` mount keeps
      the device inventory, schedules, and audit log.
- [ ] Setting a short audit retention removes only entries older than the
      cutoff; setting `0` stops automatic deletion.
- [ ] `/api/license` and `/LICENSE` return the MIT license, and Settings →
      About shows the same text.
- [ ] A temporary speed limit creates a user group, assigns the client, and
      restores the previous group after expiry.
- [ ] Manual early restoration works and removes the temporary group.
- [ ] The device shows the active speed limit and expiry.
