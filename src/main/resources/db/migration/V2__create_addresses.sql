-- A customer's saved delivery addresses (address book, at most 10 per user, enforced by AddressService).
-- Orders keep their own snapshot of the address, so rows can be edited or deleted freely.
CREATE TABLE addresses (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    label VARCHAR(10) NOT NULL,
    house VARCHAR(120) NOT NULL,
    street VARCHAR(160) NOT NULL,
    landmark VARCHAR(120),
    city VARCHAR(60) NOT NULL,
    pincode VARCHAR(6) NOT NULL,
    is_default BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT fk_addresses_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_addresses_label CHECK (label IN ('HOME', 'WORK', 'OTHER'))
);

CREATE INDEX idx_addresses_user ON addresses (user_id);
