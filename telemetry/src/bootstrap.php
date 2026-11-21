<?php
declare(strict_types=1);

date_default_timezone_set('UTC');
ini_set('display_errors', '0');
ini_set('log_errors', '1');
error_reporting(E_ALL);

$configFile = dirname(__DIR__) . '/config.php';
if (!is_file($configFile)) {
    http_response_code(500);
    echo 'Configuration is missing. Copy config.example.php to config.php.';
    exit;
}

$config = require $configFile;
if (!is_array($config)) {
    http_response_code(500);
    echo 'Configuration is invalid.';
    exit;
}

date_default_timezone_set((string) ($config['timezone'] ?? 'UTC'));

return $config;
