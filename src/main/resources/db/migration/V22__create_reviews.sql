-- A customer's review of a delivered order: 1-5 stars and an optional comment, at most one per order. The
-- restaurant's owner may reply once; an admin may hide a review (hidden ones leave the public list and the rating).
-- restaurant_id and customer_id copy the order's, so lists never join through orders.
CREATE TABLE reviews (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    restaurant_id BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,
    stars INT NOT NULL,
    comment VARCHAR(500),
    reply VARCHAR(500),
    replied_at DATETIME(6),
    hidden BOOLEAN NOT NULL,
    hidden_at DATETIME(6),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT uk_reviews_order UNIQUE (order_id),
    CONSTRAINT ck_reviews_stars CHECK (stars BETWEEN 1 AND 5),
    CONSTRAINT ck_reviews_reply CHECK ((reply IS NULL AND replied_at IS NULL) OR (reply IS NOT NULL AND replied_at IS NOT NULL))
);

-- the restaurant's public list (newest first, or highest rated) and the owner's list
CREATE INDEX idx_reviews_restaurant_created ON reviews (restaurant_id, hidden, created_at);
CREATE INDEX idx_reviews_restaurant_stars ON reviews (restaurant_id, hidden, stars, created_at);
CREATE INDEX idx_reviews_customer ON reviews (customer_id);
-- the admin list, newest first
CREATE INDEX idx_reviews_created ON reviews (created_at);
CREATE INDEX idx_reviews_hidden_created ON reviews (hidden, created_at);

-- the indexes above (and the unique one) serve the foreign keys
ALTER TABLE reviews
    ADD CONSTRAINT fk_reviews_order FOREIGN KEY (order_id) REFERENCES orders (id);
ALTER TABLE reviews
    ADD CONSTRAINT fk_reviews_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants (id);
ALTER TABLE reviews
    ADD CONSTRAINT fk_reviews_customer FOREIGN KEY (customer_id) REFERENCES users (id);
