<?php
declare(strict_types=1);

// Copy this file to config.php outside the public document root.
return [
    'db' => [
        // Use the database DSN supplied by the shared host.
        'dsn' => 'mysql:host=localhost;dbname=telemetry;charset=utf8mb4',
        'user' => 'telemetry',
        'password' => 'change-me',
    ],
    'admin_username' => 'admin',
    'admin_password' => 'change-me',
    'retention_days' => 90,
    'public_url' => 'https://unifiwifitool.smccloud.com',
    'timezone' => 'UTC',
];
