/*
 * CI pipeline for UniFi WiFi Access Manager.
 *
 * Requirements on the Jenkins agent:
 *   - Docker Engine / CLI (the agent user must be able to run docker)
 *   - Network access to Docker Hub, PyPI, and npm
 *
 * The pipeline builds and tests the image, verifies persistence, and publishes
 * successful master builds to Docker Hub using the Jenkins credentials named
 * "Docker" (configurable via DOCKERHUB_CREDENTIALS_ID). Pull requests and other
 * branches are built and tested but not pushed.
 *
 * DOCKER_NETWORK defaults to "default" so build and test containers use the
 * Docker bridge network. Switch it to "host" on restricted, nested, or rootless
 * CI agents where bridge/veth creation is unavailable.
 */

pipeline {
    agent any

    parameters {
        choice(
            name: 'DOCKER_NETWORK',
            choices: ['default', 'host'],
            description: 'Network mode for build and test containers. Default uses the Docker bridge network; use host on restricted/nested/rootless agents.'
        )
        string(
            name: 'DOCKERHUB_REPOSITORY',
            defaultValue: 'smccloud/unifi-wifi-tool',
            description: 'Docker Hub repository to publish to.'
        )
        string(
            name: 'DOCKERHUB_CREDENTIALS_ID',
            defaultValue: 'Docker',
            description: 'Jenkins username/password credentials ID for Docker Hub.'
        )
        booleanParam(
            name: 'PUSH_TO_DOCKERHUB',
            defaultValue: true,
            description: 'Publish the tested image on master builds.'
        )
        choice(
            name: 'SCOUT_FAIL_ON_SEVERITY',
            choices: ['critical', 'none', 'critical,high'],
            description: 'Fail the build when Docker Scout finds vulnerabilities at these severities. Use none to report only.'
        )
    }

    options {
        timestamps()
        disableConcurrentBuilds()
        timeout(time: 30, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '10'))
    }

    environment {
        IMAGE             = "unifi-wifi-tool:ci"
        AUTH_CONTAINER    = "unifi-wifi-ci-auth-${BUILD_NUMBER}"
        SECURE_CONTAINER  = "unifi-wifi-ci-secure-${BUILD_NUMBER}"
        PERSIST_CONTAINER = "unifi-wifi-ci-persist-${BUILD_NUMBER}"
        DATA_VOLUME       = "unifi-wifi-ci-data-${BUILD_NUMBER}"
        COMPOSE_PROJECT   = "unifi-wifi-ci-${BUILD_NUMBER}"
        DOCKER_NETWORK    = "${params.DOCKER_NETWORK ?: 'default'}"
        DOCKERHUB_REPOSITORY = "${params.DOCKERHUB_REPOSITORY ?: 'smccloud/unifi-wifi-tool'}"
        DOCKERHUB_CREDENTIALS_ID = "${params.DOCKERHUB_CREDENTIALS_ID ?: 'Docker'}"
        DOCKER_BUILDKIT   = "1"
        CI                = "true"
        DOCKER_SCOUT_NO_CACHE = "true"
        SCOUT_FAIL_ON_SEVERITY = "${params.SCOUT_FAIL_ON_SEVERITY ?: 'critical'}"
        VEX_AUTHOR = "security@smccloud.com"
    }

    stages {
        stage('Validate toolchain') {
            steps {
                sh '''
                    set -eu
                    command -v docker >/dev/null 2>&1 || {
                        echo "ERROR: docker CLI is required on this Jenkins agent." >&2
                        exit 1
                    }
                    docker version
                    docker info --format '{{.ServerVersion}}'
                '''
            }
        }

        stage('Backend tests') {
            steps {
                sh '''
                    set -eu
                    rm -f backend/test-results.xml
                    docker run --rm \
                        --network "$DOCKER_NETWORK" \
                        -v "$WORKSPACE:/workspace" \
                        -w /workspace/backend \
                        -e AUTH_DISABLED=true \
                        -e DATABASE_URL=sqlite:////tmp/ci-test.db \
                        -e SECRET_KEY=jenkins-test-secret-key-0123456789 \
                        python:3.13-slim \
                        sh -ec "pip install --quiet --no-cache-dir -r requirements-dev.txt && pytest --junitxml=test-results.xml"
                '''
            }
        }

        stage('Frontend build') {
            steps {
                sh '''
                    set -eu
                    docker run --rm \
                        --network "$DOCKER_NETWORK" \
                        -v "$WORKSPACE/frontend:/src" \
                        -v "unifi-wifi-ci-node-modules-$BUILD_NUMBER:/src/node_modules" \
                        -w /src \
                        node:22-alpine \
                        sh -ec "npm ci --no-audit --no-fund && npm run build"
                '''
            }
        }

        stage('Telemetry tests') {
            steps {
                sh '''
                    set -eu
                    rm -f telemetry/test-results.xml
                    docker run --rm \
                        --network "$DOCKER_NETWORK" \
                        -v "$WORKSPACE/telemetry:/src" \
                        -w /src \
                        python:3.13-slim \
                        sh -ec "pip install --quiet --no-cache-dir -r requirements-dev.txt && pytest --junitxml=test-results.xml"
                '''
            }
        }

        stage('Telemetry image build') {
            steps {
                sh '''
                    set -eu
                    docker build --network "$DOCKER_NETWORK" -t "unifi-wifi-telemetry:$BUILD_NUMBER" telemetry
                '''
            }
        }

        stage('Docker build') {
            steps {
                sh '''
                    set -eu
                    docker build --pull --network "$DOCKER_NETWORK" -t "$IMAGE" .
                    docker image inspect "$IMAGE" > image-inspect.json
                '''
            }
            post {
                success {
                    archiveArtifacts artifacts: 'image-inspect.json', allowEmptyArchive: true
                }
            }
        }

        stage('Analyze image with Docker Scout') {
            steps {
                withCredentials([usernamePassword(
                    credentialsId: env.DOCKERHUB_CREDENTIALS_ID,
                    usernameVariable: 'DOCKERHUB_USER',
                    passwordVariable: 'DOCKERHUB_PASSWORD'
                )]) {
                    sh '''
                        set -eu

                        # Docker CLI discovers plugins from its plugin directory,
                        # not from PATH. Install Scout there when it is missing.
                        if ! docker scout version >/dev/null 2>&1; then
                            scout_plugin_dir="${DOCKER_CONFIG:-$HOME/.docker}/cli-plugins"
                            mkdir -p "$scout_plugin_dir"
                            curl -sSfL https://raw.githubusercontent.com/docker/scout-cli/main/install.sh | sh -s -- -b "$scout_plugin_dir"
                        fi
                        docker scout version

                        printf '%s' "$DOCKERHUB_PASSWORD" | docker login -u "$DOCKERHUB_USER" --password-stdin
                        trap 'docker logout >/dev/null 2>&1 || true' EXIT

                        # Generate VEX exceptions for disputed base-image CVEs.
                        ./scripts/generate-vex.sh "$IMAGE" "$WORKSPACE/.vex"

                        # Generate a vulnerability report and SBOM for the built image.
                        docker scout cves "$IMAGE" \
                            --vex-location "$WORKSPACE/.vex" \
                            --vex-author "$VEX_AUTHOR" \
                            --format sarif \
                            --output docker-scout.sarif
                        docker scout sbom "$IMAGE" \
                            --format spdx \
                            --output docker-scout.sbom.json || true
                        docker scout recommendations "$IMAGE" || true

                        # Fail the build for the configured severity threshold.
                        # The default is critical so unfixed base-image highs
                        # do not block CI. Set SCOUT_FAIL_ON_SEVERITY to
                        # critical,high (or none) to change the policy.
                        if [ "$SCOUT_FAIL_ON_SEVERITY" != "none" ]; then
                            docker scout cves "$IMAGE" \
                                --vex-location "$WORKSPACE/.vex" \
                                --vex-author "$VEX_AUTHOR" \
                                --only-severity "$SCOUT_FAIL_ON_SEVERITY" \
                                --exit-code
                        else
                            echo "Docker Scout enforcement disabled; report archived."
                        fi
                    '''
                }
            }
            post {
                always {
                    archiveArtifacts artifacts: 'docker-scout.sarif, docker-scout.sbom.json', allowEmptyArchive: true
                }
            }
        }

        stage('Compose config') {
            steps {
                script {
                    def composeAvailable = sh(
                        script: 'docker compose version >/dev/null 2>&1',
                        returnStatus: true
                    ) == 0
                    if (composeAvailable) {
                        sh 'ENV_FILE=.env.example docker compose -p "$COMPOSE_PROJECT" config -q'
                    } else {
                        echo 'Docker Compose plugin not available; skipping compose validation.'
                    }
                }
            }
        }

        stage('Container smoke test') {
            steps {
                sh '''
                    set -eu

                    cleanup() {
                        docker rm -f "$AUTH_CONTAINER" "$SECURE_CONTAINER" >/dev/null 2>&1 || true
                    }
                    trap cleanup EXIT

                    echo "=== Starting unauthenticated smoke container ==="
                    # Network "none" keeps the test hermetic: all HTTP checks run
                    # inside the container with docker exec, so no host ports are
                    # published and no bridge is required.
                    docker run -d --name "$AUTH_CONTAINER" \
                        --network none \
                        -e AUTH_DISABLED=true \
                        -e SECRET_KEY=jenkins-smoke-secret-key-0123456789 \
                        -e SCHEDULER_ENABLED=false \
                        -e UNIFI_HOST= \
                        -e DATABASE_URL=sqlite:////tmp/smoke.db \
                        "$IMAGE" >/dev/null

                    ready=0
                    for i in $(seq 1 60); do
                        if docker exec "$AUTH_CONTAINER" python -c \
                            "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/api/healthz', timeout=2)" \
                            >/dev/null 2>&1; then
                            ready=1
                            break
                        fi
                        sleep 1
                    done
                    if [ "$ready" -ne 1 ]; then
                        echo "ERROR: container did not become healthy" >&2
                        docker logs "$AUTH_CONTAINER"
                        exit 1
                    fi

                    docker exec "$AUTH_CONTAINER" python -c \
                        "import urllib.request; print(urllib.request.urlopen('http://127.0.0.1:8000/api/healthz', timeout=3).read().decode())" \
                        | tee smoke-health.json
                    echo

                    # The multi-stage image must contain the built SPA, not the
                    # backend's "frontend not built" placeholder.
                    docker exec -i "$AUTH_CONTAINER" python - <<'PY'
import sys
import urllib.request

body = urllib.request.urlopen("http://127.0.0.1:8000/", timeout=3).read().decode()
if 'id="root"' not in body:
    print("ERROR: frontend index.html was not served", file=sys.stderr)
    sys.exit(1)
print("frontend index OK")
PY

                    docker exec -i "$AUTH_CONTAINER" python - <<'PY'
import sys
import urllib.request

response = urllib.request.urlopen("http://127.0.0.1:8000/api/system/status", timeout=5)
if response.getcode() != 200:
    print(f"ERROR: /api/system/status returned {response.getcode()}", file=sys.stderr)
    sys.exit(1)

license_response = urllib.request.urlopen("http://127.0.0.1:8000/api/license", timeout=5)
license_body = license_response.read().decode()
if license_response.getcode() != 200 or "MIT License" not in license_body:
    print("ERROR: /api/license did not return the MIT license", file=sys.stderr)
    sys.exit(1)
print("status and license endpoints OK")
PY

                    if docker logs "$AUTH_CONTAINER" 2>&1 | grep -q "Traceback (most recent call last)"; then
                        echo "ERROR: traceback found in container logs" >&2
                        docker logs "$AUTH_CONTAINER"
                        exit 1
                    fi

                    docker stop "$AUTH_CONTAINER" >/dev/null

                    echo "=== Starting authenticated smoke container ==="
                    docker run -d --name "$SECURE_CONTAINER" \
                        --network none \
                        -e AUTH_DISABLED=false \
                        -e APP_USERNAME=admin \
                        -e APP_PASSWORD=ci-password \
                        -e SECRET_KEY=jenkins-smoke-secret-key-0123456789 \
                        -e SCHEDULER_ENABLED=false \
                        -e UNIFI_HOST= \
                        -e DATABASE_URL=sqlite:////tmp/secure.db \
                        "$IMAGE" >/dev/null

                    ready=0
                    for i in $(seq 1 60); do
                        if docker exec "$SECURE_CONTAINER" python -c \
                            "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/api/healthz', timeout=2)" \
                            >/dev/null 2>&1; then
                            ready=1
                            break
                        fi
                        sleep 1
                    done
                    if [ "$ready" -ne 1 ]; then
                        echo "ERROR: authenticated container did not become healthy" >&2
                        docker logs "$SECURE_CONTAINER"
                        exit 1
                    fi

                    # Exercise auth, CSRF, and the device API end-to-end.
                    docker exec -i "$SECURE_CONTAINER" python - <<'PY'
import http.cookiejar
import json
import sys
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8000"


def expect_http_error(request, code, label, opener=None):
    open_request = opener.open if opener else urllib.request.urlopen
    try:
        open_request(request, timeout=5)
    except urllib.error.HTTPError as exc:
        if exc.code != code:
            print(f"ERROR: {label} returned {exc.code}, expected {code}", file=sys.stderr)
            sys.exit(1)
        return
    print(f"ERROR: {label} unexpectedly succeeded", file=sys.stderr)
    sys.exit(1)


# 1. Unauthenticated API access is rejected.
expect_http_error(urllib.request.Request(BASE + "/api/devices"), 401, "unauthenticated /api/devices")

# 2. Wrong password is rejected.
wrong = urllib.request.Request(
    BASE + "/api/auth/login",
    data=json.dumps({"username": "admin", "password": "wrong"}).encode(),
    headers={"Content-Type": "application/json"},
    method="POST",
)
expect_http_error(wrong, 401, "wrong-password login")

# 3. Correct login returns a session cookie and CSRF token.
jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
login = urllib.request.Request(
    BASE + "/api/auth/login",
    data=json.dumps({"username": "admin", "password": "ci-password"}).encode(),
    headers={"Content-Type": "application/json"},
    method="POST",
)
with opener.open(login, timeout=5) as response:
    if response.getcode() != 200:
        print(f"ERROR: login returned {response.getcode()}", file=sys.stderr)
        sys.exit(1)
    payload = json.loads(response.read())
csrf = payload.get("csrf_token")
if not csrf:
    print("ERROR: login response missing csrf_token", file=sys.stderr)
    sys.exit(1)

# 4. Authenticated read works.
with opener.open(urllib.request.Request(BASE + "/api/devices"), timeout=5) as response:
    if response.getcode() != 200:
        print(f"ERROR: authenticated /api/devices returned {response.getcode()}", file=sys.stderr)
        sys.exit(1)

# 5. Mutation without CSRF is rejected.
no_csrf = urllib.request.Request(
    BASE + "/api/devices",
    data=json.dumps({"mac": "aa:bb:cc:dd:ee:01", "label": "ci"}).encode(),
    headers={"Content-Type": "application/json"},
    method="POST",
)
expect_http_error(no_csrf, 403, "mutation without CSRF token", opener)

# 6. Mutation with CSRF succeeds.
with_csrf = urllib.request.Request(
    BASE + "/api/devices",
    data=json.dumps({"mac": "aa:bb:cc:dd:ee:01", "label": "ci"}).encode(),
    headers={"Content-Type": "application/json", "X-CSRF-Token": csrf},
    method="POST",
)
with opener.open(with_csrf, timeout=5) as response:
    if response.getcode() != 201:
        print(f"ERROR: device create returned {response.getcode()}", file=sys.stderr)
        sys.exit(1)
    created = json.loads(response.read())
if created.get("mac") != "aa:bb:cc:dd:ee:01":
    print(f"ERROR: unexpected created device: {created}", file=sys.stderr)
    sys.exit(1)

retention = urllib.request.Request(
    BASE + "/api/system/audit-retention",
    data=json.dumps({"days": 30}).encode(),
    headers={"Content-Type": "application/json", "X-CSRF-Token": csrf},
    method="PUT",
)
with opener.open(retention, timeout=5) as response:
    if response.getcode() != 200:
        print(f"ERROR: audit retention update returned {response.getcode()}", file=sys.stderr)
        sys.exit(1)
    retention_payload = json.loads(response.read())
if retention_payload.get("retention_days") != 30:
    print(f"ERROR: unexpected retention response: {retention_payload}", file=sys.stderr)
    sys.exit(1)

print("auth/CSRF/device/retention API smoke test OK")
PY

                    echo "=== Smoke tests passed ==="
                '''
            }
        }

        stage('Data persistence') {
            steps {
                sh '''
                    set -eu

                    docker rm -f "$PERSIST_CONTAINER" >/dev/null 2>&1 || true
                    docker volume rm "$DATA_VOLUME" >/dev/null 2>&1 || true

                    start_persist_container() {
                        docker run -d --name "$PERSIST_CONTAINER" \
                            --network none \
                            -v "$DATA_VOLUME:/data" \
                            -e AUTH_DISABLED=true \
                            -e SECRET_KEY=jenkins-persist-secret-key-0123456789 \
                            -e SCHEDULER_ENABLED=false \
                            -e UNIFI_HOST= \
                            "$IMAGE" >/dev/null

                        ready=0
                        for i in $(seq 1 60); do
                            if docker exec "$PERSIST_CONTAINER" python -c \
                                "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/api/healthz', timeout=2)" \
                                >/dev/null 2>&1; then
                                ready=1
                                break
                            fi
                            sleep 1
                        done
                        if [ "$ready" -ne 1 ]; then
                            echo "ERROR: persistence container did not become healthy" >&2
                            docker logs "$PERSIST_CONTAINER"
                            exit 1
                        fi
                    }

                    echo "=== Creating data in a fresh /data volume ==="
                    start_persist_container
                    db_url="$(docker exec "$PERSIST_CONTAINER" printenv DATABASE_URL)"
                    [ "$db_url" = "sqlite:////data/unifi-wifi.db" ] || {
                        echo "ERROR: unexpected DATABASE_URL: $db_url" >&2
                        exit 1
                    }
                    # Run this through sh so Git Bash/MSYS on Windows does not
                    # rewrite the container path before docker sees it.
                    docker exec "$PERSIST_CONTAINER" sh -c 'test -f /data/unifi-wifi.db'

                    docker exec -i "$PERSIST_CONTAINER" python - <<'PY'
import json
import urllib.request

request = urllib.request.Request(
    "http://127.0.0.1:8000/api/devices",
    data=json.dumps({"mac": "aa:bb:cc:dd:ee:99", "label": "persistence-test"}).encode(),
    headers={"Content-Type": "application/json"},
    method="POST",
)
with urllib.request.urlopen(request, timeout=5) as response:
    assert response.getcode() == 201
    created = json.loads(response.read())
assert created["label"] == "persistence-test"
print("created persistence-test device")
PY

                    docker stop "$PERSIST_CONTAINER" >/dev/null
                    docker rm "$PERSIST_CONTAINER" >/dev/null

                    echo "=== Replacing the container with the same /data volume ==="
                    start_persist_container
                    docker exec -i "$PERSIST_CONTAINER" python - <<'PY'
import json
import sys
import urllib.request

with urllib.request.urlopen("http://127.0.0.1:8000/api/devices", timeout=5) as response:
    payload = json.loads(response.read())

if not any(item.get("label") == "persistence-test" for item in payload["items"]):
    print("ERROR: device did not survive container replacement", file=sys.stderr)
    sys.exit(1)
print("PERSISTENCE OK: /data/unifi-wifi.db survived container replacement")
PY

                    docker stop "$PERSIST_CONTAINER" >/dev/null
                    docker rm "$PERSIST_CONTAINER" >/dev/null
                    docker volume rm "$DATA_VOLUME" >/dev/null
                '''
            }
        }

        stage('Publish to Docker Hub') {
            when {
                allOf {
                    expression {
                        def branchName = env.GIT_BRANCH ?: env.BRANCH_NAME ?: ''
                        return branchName == 'master' || branchName.endsWith('/master')
                    }
                    expression { return params.PUSH_TO_DOCKERHUB }
                }
            }
            steps {
                withCredentials([usernamePassword(
                    credentialsId: env.DOCKERHUB_CREDENTIALS_ID,
                    usernameVariable: 'DOCKERHUB_USER',
                    passwordVariable: 'DOCKERHUB_PASSWORD'
                )]) {
                    sh '''
                        set -eu
                        test -n "$DOCKERHUB_REPOSITORY" || {
                            echo "ERROR: DOCKERHUB_REPOSITORY is empty" >&2
                            exit 1
                        }

                        version="$(tr -d '[:space:]' < VERSION)"
                        test -n "$version" || {
                            echo "ERROR: VERSION is empty" >&2
                            exit 1
                        }

                        version_tag="$DOCKERHUB_REPOSITORY:$version"
                        latest_tag="$DOCKERHUB_REPOSITORY:latest"

                        printf '%s' "$DOCKERHUB_PASSWORD" | docker login -u "$DOCKERHUB_USER" --password-stdin
                        trap 'docker logout >/dev/null 2>&1 || true' EXIT

                        push_tag() {
                            tag="$1"
                            if output="$(docker push "$tag" 2>&1)"; then
                                printf '%s\n' "$output"
                            elif printf '%s\n' "$output" | grep -q "immutability"; then
                                echo "WARNING: $tag is immutable on Docker Hub; skipping update." >&2
                            else
                                printf '%s\n' "$output" >&2
                                return 1
                            fi
                        }

                        if docker manifest inspect "$version_tag" >/dev/null 2>&1; then
                            echo "Version $version is already published as $version_tag; skipping version tag push."
                            echo "Bump VERSION to publish a new release."
                        else
                            docker tag "$IMAGE" "$version_tag"
                            push_tag "$version_tag"
                        fi

                        docker tag "$IMAGE" "$latest_tag"
                        push_tag "$latest_tag"

                        echo "Publish stage complete for $version_tag and $latest_tag"
                    '''
                }
            }
        }
    }

    post {
        always {
            junit allowEmptyResults: true, testResults: 'backend/test-results.xml'
            junit allowEmptyResults: true, testResults: 'telemetry/test-results.xml'
            archiveArtifacts artifacts: 'image-inspect.json, smoke-health.json', allowEmptyArchive: true
            sh '''
                docker rm -f "$AUTH_CONTAINER" "$SECURE_CONTAINER" "$PERSIST_CONTAINER" >/dev/null 2>&1 || true
                docker volume rm "$DATA_VOLUME" >/dev/null 2>&1 || true
                docker volume rm "unifi-wifi-ci-node-modules-$BUILD_NUMBER" >/dev/null 2>&1 || true
                docker rmi "$IMAGE" "unifi-wifi-telemetry:$BUILD_NUMBER" >/dev/null 2>&1 || true
                rm -f smoke-cookies.txt smoke-login.json
                rm -rf "$WORKSPACE/.vex"
            '''
        }
        failure {
            sh '''
                echo "--- unauthenticated container logs ---"
                docker logs "$AUTH_CONTAINER" 2>&1 || true
                echo "--- authenticated container logs ---"
                docker logs "$SECURE_CONTAINER" 2>&1 || true
                echo "--- persistence container logs ---"
                docker logs "$PERSIST_CONTAINER" 2>&1 || true
            '''
        }
    }
}
