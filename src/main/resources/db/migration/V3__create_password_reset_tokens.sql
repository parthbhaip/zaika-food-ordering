-- "Forgot password" links. Only the SHA-256 hash of the emailed token is stored, so a database leak cannot be
-- used to reset passwords. A token works once (used_at) and only until expires_at (30 minutes after issue).
CREATE TABLE password_reset_tokens (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    used_at DATETIME(6),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT fk_password_reset_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uk_password_reset_tokens_hash UNIQUE (token_hash)
);

CREATE INDEX idx_password_reset_tokens_user ON password_reset_tokens (user_id);
