-- Orders and their dishes. Everything the customer saw at checkout is copied onto the order (address, dish names
-- and prices, the bill, the coupon code), so later menu, address or coupon edits never change a placed order.
-- Status changes go only through OrderWorkflow; each status has its own timestamp (PENDING_PAYMENT = created_at).
-- code is "ZK-26-000123", assigned from the id right after the insert, hence nullable.
CREATE TABLE orders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(20),
    customer_id BIGINT NOT NULL,
    restaurant_id BIGINT NOT NULL,
    rider_id BIGINT,
    status VARCHAR(20) NOT NULL,
    address_label VARCHAR(10) NOT NULL,
    address_house VARCHAR(120) NOT NULL,
    address_street VARCHAR(160) NOT NULL,
    address_landmark VARCHAR(120),
    address_city VARCHAR(60) NOT NULL,
    address_pincode VARCHAR(6) NOT NULL,
    restaurant_note VARCHAR(250),
    delivery_note VARCHAR(250),
    item_total DECIMAL(10,2) NOT NULL,
    discount DECIMAL(10,2) NOT NULL,
    delivery_fee DECIMAL(10,2) NOT NULL,
    platform_fee DECIMAL(10,2) NOT NULL,
    gst DECIMAL(10,2) NOT NULL,
    grand_total DECIMAL(10,2) NOT NULL,
    coupon_code VARCHAR(20),
    payment_method VARCHAR(10) NOT NULL,
    payment_status VARCHAR(20) NOT NULL,
    delivery_otp VARCHAR(4) NOT NULL,
    estimated_delivery_at DATETIME(6) NOT NULL,
    placed_at DATETIME(6),
    accepted_at DATETIME(6),
    preparing_at DATETIME(6),
    ready_at DATETIME(6),
    out_for_delivery_at DATETIME(6),
    delivered_at DATETIME(6),
    cancelled_at DATETIME(6),
    rejected_at DATETIME(6),
    cancel_reason VARCHAR(500),
    cancelled_by VARCHAR(20),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT uk_orders_code UNIQUE (code),
    CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES users (id),
    CONSTRAINT fk_orders_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants (id),
    CONSTRAINT fk_orders_rider FOREIGN KEY (rider_id) REFERENCES users (id),
    CONSTRAINT ck_orders_status CHECK (status IN ('PENDING_PAYMENT', 'PLACED', 'ACCEPTED', 'PREPARING',
        'READY_FOR_PICKUP', 'OUT_FOR_DELIVERY', 'DELIVERED', 'CANCELLED', 'REJECTED')),
    CONSTRAINT ck_orders_address_label CHECK (address_label IN ('HOME', 'WORK', 'OTHER')),
    CONSTRAINT ck_orders_money CHECK (item_total > 0 AND discount >= 0 AND discount <= item_total
        AND delivery_fee >= 0 AND platform_fee >= 0 AND gst >= 0 AND grand_total >= 0),
    CONSTRAINT ck_orders_payment_method CHECK (payment_method IN ('COD', 'ONLINE')),
    CONSTRAINT ck_orders_payment_status CHECK (payment_status IN ('PENDING', 'PAID', 'FAILED', 'REFUND_PENDING',
        'REFUNDED')),
    CONSTRAINT ck_orders_delivery_otp CHECK (CHAR_LENGTH(delivery_otp) = 4),
    CONSTRAINT ck_orders_cancelled_by CHECK (cancelled_by IS NULL OR cancelled_by IN ('CUSTOMER', 'RESTAURANT',
        'RIDER', 'ADMIN', 'SYSTEM'))
);

-- customer's order history (newest first) and "has this customer ordered before" (first-order coupons)
CREATE INDEX idx_orders_customer_created ON orders (customer_id, created_at);
CREATE INDEX idx_orders_customer_status ON orders (customer_id, status);
-- owner's live board and reports
CREATE INDEX idx_orders_restaurant_status_created ON orders (restaurant_id, status, created_at);
-- rider's active delivery and earnings
CREATE INDEX idx_orders_rider_status ON orders (rider_id, status);
-- unclaimed orders for riders, expiry of unpaid orders
CREATE INDEX idx_orders_status_created ON orders (status, created_at);
-- admin's all-orders list
CREATE INDEX idx_orders_created ON orders (created_at);

-- menu_item_id keeps the link to the dish (no cascade: a dish that was ever ordered is archived, never deleted).
CREATE TABLE order_items (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    menu_item_id BIGINT,
    name VARCHAR(80) NOT NULL,
    unit_price DECIMAL(10,2) NOT NULL,
    quantity INT NOT NULL,
    veg BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE,
    CONSTRAINT fk_order_items_menu_item FOREIGN KEY (menu_item_id) REFERENCES menu_items (id),
    CONSTRAINT ck_order_items_quantity CHECK (quantity BETWEEN 1 AND 20),
    CONSTRAINT ck_order_items_unit_price CHECK (unit_price > 0)
);

CREATE INDEX idx_order_items_order ON order_items (order_id);
CREATE INDEX idx_order_items_menu_item ON order_items (menu_item_id);
