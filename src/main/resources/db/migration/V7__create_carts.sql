-- Saved carts of signed-in customers (guests keep theirs in the session until they sign in).
-- One cart per customer, dishes from one restaurant at a time (enforced by CartService).
-- unit_price is the price the customer last saw, so a later price change can be pointed out on the cart page.
CREATE TABLE carts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT uk_carts_user UNIQUE (user_id),
    CONSTRAINT fk_carts_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE cart_items (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    cart_id BIGINT NOT NULL,
    menu_item_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    unit_price DECIMAL(10,2) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT uk_cart_items_cart_menu_item UNIQUE (cart_id, menu_item_id),
    CONSTRAINT fk_cart_items_cart FOREIGN KEY (cart_id) REFERENCES carts (id) ON DELETE CASCADE,
    CONSTRAINT fk_cart_items_menu_item FOREIGN KEY (menu_item_id) REFERENCES menu_items (id) ON DELETE CASCADE,
    CONSTRAINT ck_cart_items_quantity CHECK (quantity BETWEEN 1 AND 20),
    CONSTRAINT ck_cart_items_unit_price CHECK (unit_price > 0)
);

CREATE INDEX idx_cart_items_menu_item ON cart_items (menu_item_id);
