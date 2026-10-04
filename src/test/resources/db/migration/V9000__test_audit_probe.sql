-- Test classpath only: a minimal table for the BaseEntity auditing test. Never shipped.
-- Version 9000 keeps it after every real migration in src/main/resources/db/migration.
CREATE TABLE audit_probe (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(60) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL
);
