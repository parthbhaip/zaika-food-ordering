-- The admin coupon list (/admin/coupons) shows the newest coupons first.
CREATE INDEX idx_coupons_created ON coupons (created_at);
