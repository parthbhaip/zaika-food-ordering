-- A customer's favourite restaurants (the heart on a restaurant page), listed on /account/favourites newest first.
CREATE TABLE favourites (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    restaurant_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT uk_favourites_user_restaurant UNIQUE (user_id, restaurant_id)
);

-- the customer's list, newest first (also serves fk_favourites_user)
CREATE INDEX idx_favourites_user_created ON favourites (user_id, created_at);
CREATE INDEX idx_favourites_restaurant ON favourites (restaurant_id);

ALTER TABLE favourites
    ADD CONSTRAINT fk_favourites_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;
ALTER TABLE favourites
    ADD CONSTRAINT fk_favourites_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants (id) ON DELETE CASCADE;
