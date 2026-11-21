-- Anonymous install tracking schema.
-- Import through phpMyAdmin or the MySQL/MariaDB CLI.
-- There are intentionally no IP, hostname, MAC, SSID, or user-agent columns.

CREATE TABLE IF NOT EXISTS installs (
    install_id CHAR(36) NOT NULL,
    first_seen DATETIME NOT NULL,
    last_seen DATETIME NOT NULL,
    app_version VARCHAR(32) NOT NULL,
    platform VARCHAR(64) NOT NULL,
    deployment VARCHAR(32) NOT NULL,
    schema_version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (install_id),
    INDEX ix_installs_last_seen (last_seen),
    INDEX ix_installs_app_version (app_version),
    INDEX ix_installs_platform (platform)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS settings (
    k VARCHAR(64) NOT NULL,
    v TEXT NOT NULL,
    PRIMARY KEY (k)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
