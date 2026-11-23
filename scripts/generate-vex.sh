#!/usr/bin/env bash
#
# Generate OpenVEX exceptions for CVEs that are not applicable to this image.
#
# Usage:
#   ./scripts/generate-vex.sh [IMAGE] [OUTPUT_DIR]
#
# The image is inspected for the exact Debian package versions, so the VEX
# statements match what Docker Scout reports. The output directory defaults to
# .vex in the repository root.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="${1:-${IMAGE:-unifi-wifi-tool:ci}}"
OUT_DIR="${2:-${ROOT}/.vex}"
VEX_AUTHOR="${VEX_AUTHOR:-security@smccloud.com}"

case "$OUT_DIR" in
  /*|[A-Za-z]:/*) ;;
  *) OUT_DIR="$ROOT/$OUT_DIR" ;;
esac
mkdir -p "$OUT_DIR"

# The statements below describe Debian packages. Skip them for images that are
# not Debian-based (for example the Alpine/musl runtime), because Docker Scout
# cannot match Debian VEX statements to packages from another distribution.
if ! docker run --rm --entrypoint sh "$IMAGE" -c 'command -v dpkg-query >/dev/null 2>&1'; then
  printf '%s is not Debian-based; skipping Debian VEX statements\n' "$IMAGE" >&2
  exit 0
fi

generate_vex() {
  local cve="$1"
  local binary_package="$2"
  local source_package="$3"
  local justification="$4"
  local impact="$5"
  local output="$OUT_DIR/$cve.vex.json"

  docker run --rm -i --entrypoint python "$IMAGE" - \
    "$IMAGE" "$cve" "$binary_package" "$source_package" "$VEX_AUTHOR" "$justification" "$impact" \
    > "$output" <<'PY'
import json
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote

image_ref, cve, binary_package, source_package, author, justification, impact = sys.argv[1:8]


def read_os_release():
    values = {}
    for line in Path("/etc/os-release").read_text().splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            values[key] = value.strip().strip('"')
    return values


def package_version(name):
    return subprocess.check_output(
        ["dpkg-query", "-W", "-f=${Version}", name], text=True
    ).strip()


def image_purl(ref):
    ref = ref.removeprefix("docker.io/")
    if "@" in ref:
        repo, version = ref.rsplit("@", 1)
    else:
        slash = ref.rfind("/")
        colon = ref.rfind(":")
        if colon > slash:
            repo, version = ref[:colon], ref[colon + 1 :]
        else:
            repo, version = ref, "latest"
    return f"pkg:docker/{repo}@{version}"


os_release = read_os_release()
version = package_version(binary_package)
distro = os_release.get("VERSION_CODENAME") or os_release.get("DEBIAN_CODENAME", "")
os_name = os_release.get("ID", "debian")
os_version = os_release.get("VERSION_ID", "")
qualifiers = (
    f"os_distro={quote(distro, safe='')}"
    f"&os_name={quote(os_name, safe='')}"
    f"&os_version={quote(os_version, safe='')}"
)
subcomponent = (
    f"pkg:deb/{os_name}/{source_package}@{quote(version, safe='')}?{qualifiers}"
)

document = {
    "@context": "https://openvex.dev/ns/v0.2.0",
    "@id": f"https://smccloud.com/vex/{cve}",
    "author": author,
    "timestamp": datetime.now(timezone.utc)
    .replace(microsecond=0)
    .isoformat()
    .replace("+00:00", "Z"),
    "version": 1,
    "statements": [
        {
            "vulnerability": {"name": cve},
            "products": [
                {
                    "@id": image_purl(image_ref),
                    "subcomponents": [{"@id": subcomponent}],
                }
            ],
            "status": "not_affected",
            "justification": justification,
            "impact_statement": impact,
        }
    ],
}
print(json.dumps(document, indent=2))
PY

  printf 'wrote %s\n' "$output" >&2
}

generate_vex \
  "CVE-2005-2541" \
  "tar" \
  "tar" \
  "vulnerable_code_not_in_execute_path" \
  "The runtime image runs as the non-root app user and does not extract untrusted tar archives as root."

generate_vex \
  "CVE-2007-5686" \
  "passwd" \
  "shadow" \
  "vulnerable_code_not_present" \
  "The vulnerable rPath Linux initscripts are not present in this Debian-based image. The image does not run sshd or a login service and does not use /var/log/btmp."

generate_vex \
  "CVE-2010-0928" \
  "openssl" \
  "openssl" \
  "vulnerable_code_cannot_be_controlled_by_adversary" \
  "Exploitation requires physical control of the hardware to inject voltage faults. That threat model is not applicable to this container."

generate_vex \
  "CVE-2010-4756" \
  "libc6" \
  "glibc" \
  "vulnerable_code_not_in_execute_path" \
  "The application does not pass attacker-controlled patterns to glob(3). The glibc behavior is standard POSIX behavior and requires the application to impose its own limits."

generate_vex \
  "CVE-2011-3374" \
  "apt" \
  "apt" \
  "vulnerable_code_not_in_execute_path" \
  "The runtime image does not use apt or apt-key and has no keyring URI configured. Package management is only used during the image build."

generate_vex \
  "CVE-2011-4116" \
  "perl-base" \
  "perl" \
  "vulnerable_code_not_in_execute_path" \
  "The runtime image does not use Perl File::Temp; Perl is present only as a Debian base package and the application runs as Python."

generate_vex \
  "CVE-2013-4392" \
  "libsystemd0" \
  "systemd" \
  "vulnerable_code_not_in_execute_path" \
  "The container does not run systemd as PID 1, does not use SELinux, and does not use systemd tmpfiles or SELinux relabeling. Only libsystemd0 is present as a shared library."
