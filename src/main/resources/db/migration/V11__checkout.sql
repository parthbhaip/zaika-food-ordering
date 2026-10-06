-- Checkout. Each checkout form carries a random key; a second submit of the same form (double click, back + resubmit)
-- finds the order the first one placed instead of placing another.
ALTER TABLE orders ADD COLUMN idempotency_key VARCHAR(36);
ALTER TABLE orders ADD CONSTRAINT uk_orders_customer_idempotency_key UNIQUE (customer_id, idempotency_key);

-- The order a coupon use belongs to, so a cancelled, rejected or expired order can give the use back (T25, T27).
ALTER TABLE coupon_redemptions ADD COLUMN order_id BIGINT;
ALTER TABLE coupon_redemptions ADD CONSTRAINT fk_coupon_redemptions_order FOREIGN KEY (order_id) REFERENCES orders (id)
    ON DELETE CASCADE;
CREATE INDEX idx_coupon_redemptions_order ON coupon_redemptions (order_id);
