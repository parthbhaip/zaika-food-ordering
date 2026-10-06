-- Online payment attempts for an order (the mock gateway today). One row per attempt, failed ones included, so the
-- payment page can say why the last try failed and support can see every try. Never holds a card number, CVV or a
-- full UPI ID: only a masked description ("Card ending 1111", "ri••@upi").
-- No cascade: orders are never deleted, and a payment record must outlive anything that tries.
CREATE TABLE payments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    gateway VARCHAR(20) NOT NULL,
    channel VARCHAR(10) NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    status VARCHAR(10) NOT NULL,
    instrument VARCHAR(60) NOT NULL,
    gateway_reference VARCHAR(40),
    failure_reason VARCHAR(200),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT fk_payments_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT uk_payments_gateway_reference UNIQUE (gateway, gateway_reference),
    CONSTRAINT ck_payments_channel CHECK (channel IN ('UPI', 'CARD')),
    CONSTRAINT ck_payments_status CHECK (status IN ('SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_payments_amount CHECK (amount > 0),
    CONSTRAINT ck_payments_outcome CHECK ((status = 'SUCCEEDED' AND gateway_reference IS NOT NULL)
        OR (status = 'FAILED' AND failure_reason IS NOT NULL))
);

-- the payment page's "last attempt" and an order's payment history
CREATE INDEX idx_payments_order ON payments (order_id, id);
