<?php
declare(strict_types=1);

function db(array $config): PDO
{
    static $pdo = null;
    if ($pdo instanceof PDO) {
        return $pdo;
    }

    $dbConfig = $config['db'] ?? [];
    $pdo = new PDO(
        (string) $dbConfig['dsn'],
        (string) $dbConfig['user'],
        (string) $dbConfig['password'],
        [
            PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
            PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
            PDO::ATTR_EMULATE_PREPARES => false,
        ]
    );

    return $pdo;
}

function now_utc(): string
{
    return gmdate('Y-m-d H:i:s');
}

function get_setting(PDO $db, string $key): ?string
{
    $stmt = $db->prepare('SELECT v FROM settings WHERE k = ? LIMIT 1');
    $stmt->execute([$key]);
    $value = $stmt->fetchColumn();
    return $value === false ? null : (string) $value;
}

function set_setting(PDO $db, string $key, string $value): void
{
    $stmt = $db->prepare(
        'INSERT INTO settings (k, v) VALUES (?, ?) ON DUPLICATE KEY UPDATE v = VALUES(v)'
    );
    $stmt->execute([$key, $value]);
}

function prune_installs(PDO $db, int $retentionDays): int
{
    $cutoff = gmdate('Y-m-d H:i:s', time() - max(1, $retentionDays) * 86400);
    $stmt = $db->prepare('DELETE FROM installs WHERE last_seen < ?');
    $stmt->execute([$cutoff]);
    return $stmt->rowCount();
}

function maybe_prune(PDO $db, int $retentionDays): void
{
    $last = get_setting($db, 'last_prune');
    if ($last !== null && strtotime($last . ' UTC') > time() - 86400) {
        return;
    }

    prune_installs($db, $retentionDays);
    set_setting($db, 'last_prune', now_utc());
}
