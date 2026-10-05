-- Coupons: platform-wide or for one restaurant. Codes are stored upper-case. discount_value is a percentage for
-- PERCENT (capped by max_discount when set) and rupees for FLAT. valid_until is exclusive; NULL means no end date.
-- Limits are NULL when unlimited. (VALUE and TYPE are keywords in H2, hence the discount_ prefix.)
CREATE TABLE coupons (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(20) NOT NULL,
    description VARCHAR(160) NOT NULL,
    discount_type VARCHAR(10) NOT NULL,
    discount_value DECIMAL(10,2) NOT NULL,
    max_discount DECIMAL(10,2),
    min_order DECIMAL(10,2) NOT NULL,
    valid_from DATETIME(6) NOT NULL,
    valid_until DATETIME(6),
    total_limit INT,
    per_user_limit INT,
    first_order_only BOOLEAN NOT NULL,
    restaurant_id BIGINT,
    active BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT uk_coupons_code UNIQUE (code),
    CONSTRAINT fk_coupons_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants (id) ON DELETE CASCADE,
    CONSTRAINT ck_coupons_type CHECK (discount_type IN ('PERCENT', 'FLAT')),
    CONSTRAINT ck_coupons_value CHECK (discount_value > 0 AND (discount_type <> 'PERCENT' OR discount_value <= 100)),
    CONSTRAINT ck_coupons_max_discount CHECK (max_discount IS NULL OR max_discount > 0),
    CONSTRAINT ck_coupons_min_order CHECK (min_order >= 0),
    CONSTRAINT ck_coupons_total_limit CHECK (total_limit IS NULL OR total_limit > 0),
    CONSTRAINT ck_coupons_per_user_limit CHECK (per_user_limit IS NULL OR per_user_limit > 0),
    CONSTRAINT ck_coupons_window CHECK (valid_until IS NULL OR valid_until > valid_from)
);

CREATE INDEX idx_coupons_restaurant ON coupons (restaurant_id);
-- the offers list: active coupons that have started
CREATE INDEX idx_coupons_active_valid_from ON coupons (active, valid_from);

-- One row per order placed with a coupon (written at checkout); the per-customer and total limits count these.
CREATE TABLE coupon_redemptions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    coupon_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    discount DECIMAL(10,2) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT fk_coupon_redemptions_coupon FOREIGN KEY (coupon_id) REFERENCES coupons (id),
    CONSTRAINT fk_coupon_redemptions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_coupon_redemptions_discount CHECK (discount >= 0)
);

CREATE INDEX idx_coupon_redemptions_coupon_user ON coupon_redemptions (coupon_id, user_id);
CREATE INDEX idx_coupon_redemptions_user ON coupon_redemptions (user_id);

-- The coupon a customer applied in their cart; checkout re-checks it.
ALTER TABLE carts ADD COLUMN coupon_id BIGINT;
ALTER TABLE carts ADD CONSTRAINT fk_carts_coupon FOREIGN KEY (coupon_id) REFERENCES coupons (id) ON DELETE SET NULL;
CREATE INDEX idx_carts_coupon ON carts (coupon_id);
