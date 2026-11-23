<?php
declare(strict_types=1);

function distribution(PDO $db, string $column, string $since): array
{
    $allowed = ['app_version', 'platform', 'deployment'];
    if (!in_array($column, $allowed, true)) {
        throw new InvalidArgumentException('Invalid distribution column');
    }

    $stmt = $db->prepare(
        "SELECT {$column} AS label, COUNT(*) AS install_count
         FROM installs
         WHERE last_seen >= ?
         GROUP BY {$column}
         ORDER BY install_count DESC, label ASC
         LIMIT 20"
    );
    $stmt->execute([$since]);
    return $stmt->fetchAll();
}

function stats(PDO $db): array
{
    $since30 = gmdate('Y-m-d H:i:s', time() - 30 * 86400);
    return [
        'versions' => distribution($db, 'app_version', $since30),
        'platforms' => distribution($db, 'platform', $since30),
    ];
}

function list_installs(
    PDO $db,
    string $version,
    string $platform,
    string $active,
    int $limit,
    int $offset
): array {
    $conditions = [];
    $params = [];

    if ($version !== '') {
        $conditions[] = 'app_version = ?';
        $params[] = $version;
    }
    if ($platform !== '') {
        $conditions[] = 'platform = ?';
        $params[] = $platform;
    }
    if ($active === '24h' || $active === '7d' || $active === '30d') {
        $seconds = ['24h' => 86400, '7d' => 7 * 86400, '30d' => 30 * 86400][$active];
        $conditions[] = 'last_seen >= ?';
        $params[] = gmdate('Y-m-d H:i:s', time() - $seconds);
    }

    $where = $conditions ? ' WHERE ' . implode(' AND ', $conditions) : '';
    $countStmt = $db->prepare('SELECT COUNT(*) FROM installs' . $where);
    $countStmt->execute($params);
    $total = (int) $countStmt->fetchColumn();

    $limit = max(1, min(200, $limit));
    $offset = max(0, $offset);
    $stmt = $db->prepare(
        'SELECT install_id, first_seen, last_seen, app_version, platform, deployment, schema_version
         FROM installs' . $where . '
         ORDER BY last_seen DESC, install_id ASC
         LIMIT ' . $limit . ' OFFSET ' . $offset
    );
    $stmt->execute($params);

    return [$stmt->fetchAll(), $total];
}

function install_is_active(string $lastSeen): bool
{
    return strtotime($lastSeen . ' UTC') >= time() - 86400;
}
