-- Compatible with MariaDB 10.11 (XAMPP) and MySQL 8+.
CREATE TABLE IF NOT EXISTS app_metadata (
    meta_key VARCHAR(64) PRIMARY KEY,
    meta_value VARCHAR(128) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS operators (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    username VARCHAR(64) NOT NULL UNIQUE,
    email VARCHAR(160) NOT NULL UNIQUE,
    role ENUM('Controller','Admin') NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_active TIMESTAMP NULL DEFAULT NULL,
    clearance VARCHAR(160) NOT NULL,
    password_hash VARCHAR(512) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS incidents (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    source_key VARCHAR(191) NOT NULL UNIQUE,
    occurred_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    callsign_a VARCHAR(16) NOT NULL,
    callsign_b VARCHAR(16) NOT NULL,
    severity ENUM('Critical','Warning') NOT NULL,
    detail VARCHAR(512) NOT NULL,
    status ENUM('Open','Acknowledged','Resolved') NOT NULL DEFAULT 'Open',
    sector VARCHAR(64) NOT NULL,
    acknowledged_by VARCHAR(64) NULL,
    acknowledged_at TIMESTAMP(6) NULL DEFAULT NULL,
    INDEX ix_incident_time (occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
