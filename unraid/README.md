# unRAID installation

This image is designed to run directly through unRAID's **Docker** tab
(Stable Docker Manager). Docker Compose is optional and not required.

## 1. Use the published image

The Jenkins pipeline publishes `smccloud/unifi-wifi-tool:latest` to Docker Hub
on successful `master` builds, along with the version tag (currently `1.0.0`).
The template uses `latest` by default; pin a version tag such as `:1.0.0` if you
prefer a fixed release.

If you publish under a different namespace, change `<Repository>` in the XML.
To build locally on the unRAID box instead:

```bash
docker build -t smccloud/unifi-wifi-tool:latest .
```

If you push your own image elsewhere, update `<Repository>` accordingly.

## Icon

`unraid/icon.png` is a 512×512 container icon (dark rounded tile with a teal
shield and WiFi mark). The template loads it from the public GitHub mirror's raw
URL, so if you fork the project, update `<Icon>` to point at your copy. The
editable vector source is `unraid/icon.svg`; after changing it, re-render the
PNG at 512×512 (for example with a headless browser or an SVG rasterizer).

## 2. Add the container

1. Copy `unifi-wifi-tool.xml` to
   `/boot/config/plugins/dockerMan/templates-user/` (or add it through the
   unRAID template UI).
2. If you are not using `smccloud/unifi-wifi-tool:latest`, change
   `<Repository>` in the XML to your image or pinned version tag.
3. In unRAID: **Docker → Add Container**, select the
   **UniFi-WiFi-Access-Manager** template.
4. Set:
   - **AppData**: `/mnt/user/appdata/unifi-wifi-tool`
   - **Web UI** port: usually `8080` → container port `8000`
   - **SECRET_KEY**
   - **APP_PASSWORD** only if you want to seed the first admin from the
     template; otherwise leave it empty and create the admin at `/setup` on
     first launch
   - **UNIFI_HOST**, **UNIFI_API_KEY**
   - **UNIFI_USERNAME** and **UNIFI_PASSWORD** if you want block/unblock
5. Apply.

The database is stored at:

```
/mnt/user/appdata/unifi-wifi-tool/unifi-wifi.db
```

UniFi block/unblock needs a local-only UniFi admin. The API key alone can read
the controller and WiFi configuration but cannot block clients.

## Permissions

The template uses `--user 99:100` (the standard unRAID `nobody:users` identity)
and the image runs as a non-root user. Prepare the appdata directory before the
first start:

```bash
mkdir -p /mnt/user/appdata/unifi-wifi-tool
chown -R 99:100 /mnt/user/appdata/unifi-wifi-tool
```

If you use different UID/GID values, change `--user` in **Extra Parameters** and
make the appdata directory match.

## Upgrades and persistence

The appdata path is a bind mount to `/data`, not an anonymous volume. Updating
the container image in unRAID recreates the container but keeps that directory.
The `unifi-wifi.db`, `unifi-wifi.db-wal`, and `unifi-wifi.db-shm` files survive
image updates. Do not delete the appdata directory when removing/updating the
container.

Back up a consistent database copy with:

```bash
docker exec UniFi-WiFi-Access-Manager python -c \
  "import sqlite3; s=sqlite3.connect('/data/unifi-wifi.db'); d=sqlite3.connect('/tmp/backup.db'); s.backup(d); d.close(); s.close()"

docker cp UniFi-WiFi-Access-Manager:/tmp/backup.db \
  /mnt/user/backups/unifi-wifi-$(date +%F).db
```

Alternatively stop the container and copy the database files.

## Standalone `docker run`

Without unRAID's template manager:

```bash
docker run -d \
  --name unifi-wifi-tool \
  --restart unless-stopped \
  --init \
  --user 99:100 \
  -p 8080:8000 \
  -v /mnt/user/appdata/unifi-wifi-tool:/data \
  -e SECRET_KEY="$(openssl rand -base64 48)" \
  -e UNIFI_HOST='https://192.168.1.1' \
  -e UNIFI_API_KEY='...' \
  -e UNIFI_USERNAME='unifi-service' \
  -e UNIFI_PASSWORD='...' \
  smccloud/unifi-wifi-tool:latest
```

On first launch with no users, open the Web UI to create the first
administrator (or set `APP_PASSWORD` to seed it automatically). Once users
exist, manage accounts in **Settings → Users**.

On a normal Linux host, omit `--user 99:100` and point the volume at any
directory writable by the image's `app` user.
