<?php
declare(strict_types=1);

$config = require dirname(__DIR__) . '/src/bootstrap.php';
require_once dirname(__DIR__) . '/src/database.php';
require_once dirname(__DIR__) . '/src/stats.php';
require_once dirname(__DIR__) . '/src/auth.php';
require_once dirname(__DIR__) . '/src/views.php';

header('X-Content-Type-Options: nosniff');
header('X-Frame-Options: DENY');
header('Referrer-Policy: no-referrer');
header(
    "Content-Security-Policy: default-src 'self'; style-src 'self'; "
    . "img-src 'self' data:; script-src 'self'; frame-ancestors 'none'"
);

function json_response(array $data, int $status = 200, array $headers = []): never
{
    http_response_code($status);
    header('Content-Type: application/json; charset=utf-8');
    foreach ($headers as $name => $value) {
        header($name . ': ' . $value);
    }
    echo json_encode($data, JSON_UNESCAPED_SLASHES);
    exit;
}

function handle_heartbeat(array $config): never
{
    $length = (int) ($_SERVER['CONTENT_LENGTH'] ?? 0);
    if ($length > 8192) {
        json_response(['error' => 'payload too large'], 413);
    }

    $raw = file_get_contents('php://input');
    try {
        $data = json_decode((string) $raw, true, 32, JSON_THROW_ON_ERROR);
    } catch (JsonException) {
        json_response(['error' => 'invalid JSON'], 400);
    }

    $installId = is_array($data) ? ($data['install_id'] ?? '') : '';
    $version = is_array($data) ? ($data['app_version'] ?? '') : '';
    $platform = is_array($data) ? ($data['platform'] ?? '') : '';
    $deployment = is_array($data) ? ($data['deployment'] ?? '') : '';
    $schemaVersion = is_array($data) ? ($data['schema_version'] ?? 1) : 1;

    $uuidPattern = '/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i';
    if (
        !is_string($installId)
        || !preg_match($uuidPattern, $installId)
        || !is_string($version)
        || !preg_match('/^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$/', $version)
        || !is_string($platform)
        || !preg_match('/^[a-z0-9_.-]+\/[a-z0-9_.-]+$/', $platform)
        || !is_string($deployment)
        || !preg_match('/^[a-z0-9_.-]+$/', $deployment)
        || !is_int($schemaVersion)
        || $schemaVersion < 1
        || $schemaVersion > 100
    ) {
        json_response(['error' => 'invalid heartbeat'], 400);
    }

    $db = db($config);
    $now = now_utc();
    $stmt = $db->prepare(
        'INSERT INTO installs
            (install_id, first_seen, last_seen, app_version, platform, deployment, schema_version)
         VALUES (?, ?, ?, ?, ?, ?, ?)
         ON DUPLICATE KEY UPDATE
            last_seen = VALUES(last_seen),
            app_version = VALUES(app_version),
            platform = VALUES(platform),
            deployment = VALUES(deployment),
            schema_version = VALUES(schema_version)'
    );
    $stmt->execute([
        strtolower($installId),
        $now,
        $now,
        $version,
        $platform,
        $deployment,
        $schemaVersion,
    ]);

    maybe_prune($db, (int) ($config['retention_days'] ?? 90));

    http_response_code(204);
    header('Cache-Control: no-store');
    exit;
}

set_exception_handler(function (Throwable $error): void {
    // Log only the failure type/message. Never log request headers or IP data.
    error_log('telemetry service error: ' . $error->getMessage());
    if (!headers_sent()) {
        http_response_code(500);
        header('Content-Type: text/plain; charset=utf-8');
    }
    echo "Internal server error.\n";
});

$method = strtoupper((string) ($_SERVER['REQUEST_METHOD'] ?? 'GET'));
$path = parse_url((string) ($_SERVER['REQUEST_URI'] ?? '/'), PHP_URL_PATH) ?: '/';

if ($path === '/healthz' && $method === 'GET') {
    json_response(['status' => 'ok']);
}

if ($path === '/api/v1/heartbeat' && $method === 'POST') {
    handle_heartbeat($config);
}

if ($path === '/' && $method === 'GET') {
    render_public(gmdate('Y-m-d H:i:s'));
    exit;
}

if ($path === '/admin' && $method === 'GET') {
    require_admin($config);
    $version = trim((string) ($_GET['version'] ?? ''));
    $platform = trim((string) ($_GET['platform'] ?? ''));
    $active = trim((string) ($_GET['active'] ?? ''));
    if (!in_array($active, ['', '24h', '7d', '30d'], true)) {
        $active = '';
    }
    $page = max(1, (int) ($_GET['page'] ?? 1));
    $perPage = 50;

    $db = db($config);
    [$installs, $total] = list_installs(
        $db,
        $version,
        $platform,
        $active,
        $perPage,
        ($page - 1) * $perPage
    );
    $pages = max(1, (int) ceil($total / $perPage));
    $pruned = null;
    if (isset($_GET['pruned']) && ctype_digit((string) $_GET['pruned'])) {
        $pruned = (int) $_GET['pruned'];
    }

    render_admin(
        $installs,
        $total,
        $page,
        $pages,
        stats($db),
        $version,
        $platform,
        $active,
        (int) ($config['retention_days'] ?? 90),
        csrf_token($config),
        $pruned
    );
    exit;
}

if ($path === '/admin/prune' && $method === 'POST') {
    require_admin($config);
    $submitted = (string) ($_POST['csrf_token'] ?? '');
    if (!hash_equals(csrf_token($config), $submitted)) {
        http_response_code(403);
        echo 'Invalid CSRF token.';
        exit;
    }

    $db = db($config);
    $deleted = prune_installs($db, (int) ($config['retention_days'] ?? 90));
    header('Location: /admin?pruned=' . $deleted, true, 303);
    exit;
}

http_response_code(404);
header('Content-Type: text/plain; charset=utf-8');
echo "Not found.\n";
