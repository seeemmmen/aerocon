-- Run once as a database administrator in phpMyAdmin or the MariaDB/MySQL CLI.
-- Replace CHANGE_ME with your own password before running this script.
-- Use the same password in the untracked config/database.properties file.
CREATE DATABASE IF NOT EXISTS aerocon
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'aerocon'@'localhost' IDENTIFIED BY 'CHANGE_ME';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX
    ON aerocon.* TO 'aerocon'@'localhost';

-- The application creates its tables and seeds initial demo accounts once.
-- Existing application data is retained on subsequent launches.
