<?php
declare(strict_types=1);

function current_admin(array $config): ?string
{
    $username = $_SERVER['PHP_AUTH_USER'] ?? null;
    $password = $_SERVER['PHP_AUTH_PW'] ?? null;

    if (!is_string($username) || !is_string($password)) {
        $header = $_SERVER['HTTP_AUTHORIZATION']
            ?? $_SERVER['REDIRECT_HTTP_AUTHORIZATION']
            ?? '';
        if (is_string($header) && stripos($header, 'Basic ') === 0) {
            $decoded = base64_decode(substr($header, 6), true);
            if (is_string($decoded) && str_contains($decoded, ':')) {
                [$username, $password] = explode(':', $decoded, 2);
            }
        }
    }

    if (!is_string($username) || !is_string($password)) {
        return null;
    }

    $expectedUser = (string) ($config['admin_username'] ?? '');
    $expectedPassword = (string) ($config['admin_password'] ?? '');
    if (
        $expectedUser !== ''
        && $expectedPassword !== ''
        && hash_equals($expectedUser, $username)
        && hash_equals($expectedPassword, $password)
    ) {
        return $username;
    }

    return null;
}

function require_admin(array $config): string
{
    $user = current_admin($config);
    if ($user === null) {
        header('WWW-Authenticate: Basic realm="UniFi WiFi Tool telemetry"');
        http_response_code(401);
        header('Content-Type: text/plain; charset=utf-8');
        echo "Admin authentication required.\n";
        exit;
    }
    return $user;
}

function csrf_token(array $config): string
{
    return hash_hmac('sha256', 'telemetry-prune', (string) $config['admin_password']);
}
