-- T36 delivery flow.
-- The rider types the customer's 4-digit code to finish a delivery: wrong codes are counted per order, and the
-- fifth wrong one in a row locks the code for 10 minutes (the count starts again after that).
ALTER TABLE orders ADD COLUMN otp_failed_attempts INT DEFAULT 0 NOT NULL;
ALTER TABLE orders ADD COLUMN otp_locked_until DATETIME(6);
ALTER TABLE orders ADD CONSTRAINT ck_orders_otp_failed_attempts CHECK (otp_failed_attempts BETWEEN 0 AND 4);

-- What a rider earned for one delivered order: the base payout plus their share of its delivery fee, and the cash
-- they collected at the door for a cash-on-delivery order (0 for an order paid online). One row per order.
CREATE TABLE rider_earnings (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    rider_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    base_payout DECIMAL(10,2) NOT NULL,
    fee_share DECIMAL(10,2) NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    cash_collected DECIMAL(10,2) NOT NULL,
    earned_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT uk_rider_earnings_order UNIQUE (order_id),
    CONSTRAINT ck_rider_earnings_money CHECK (base_payout >= 0 AND fee_share >= 0 AND amount >= 0
        AND cash_collected >= 0)
);

-- the rider's earnings page sums and lists by rider and time; the unique index above serves the order FK
CREATE INDEX idx_rider_earnings_rider_earned ON rider_earnings (rider_id, earned_at);

ALTER TABLE rider_earnings
    ADD CONSTRAINT fk_rider_earnings_rider FOREIGN KEY (rider_id) REFERENCES users (id);
ALTER TABLE rider_earnings
    ADD CONSTRAINT fk_rider_earnings_order FOREIGN KEY (order_id) REFERENCES orders (id);
