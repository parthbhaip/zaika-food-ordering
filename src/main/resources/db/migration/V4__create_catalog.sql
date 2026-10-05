-- Catalog: cuisines, restaurants (one per owner account), their menu categories and menu items.
-- Restaurants are addressed by slug in URLs. Listing pages filter on status and city, so both are indexed.

CREATE TABLE cuisines (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(40) NOT NULL,
    slug VARCHAR(40) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT uk_cuisines_name UNIQUE (name),
    CONSTRAINT uk_cuisines_slug UNIQUE (slug)
);

CREATE TABLE restaurants (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    slug VARCHAR(80) NOT NULL,
    name VARCHAR(80) NOT NULL,
    tagline VARCHAR(120),
    description VARCHAR(1000),
    phone VARCHAR(10) NOT NULL,
    address VARCHAR(200) NOT NULL,
    area VARCHAR(60) NOT NULL,
    city VARCHAR(60) NOT NULL,
    pincode VARCHAR(6) NOT NULL,
    logo_path VARCHAR(255),
    cover_path VARCHAR(255),
    cost_for_two DECIMAL(10,2) NOT NULL,
    minimum_order DECIMAL(10,2) NOT NULL,
    delivery_fee DECIMAL(10,2) NOT NULL,
    avg_prep_minutes INT NOT NULL,
    opens_at TIME NOT NULL,
    closes_at TIME NOT NULL,
    accepting_orders BOOLEAN NOT NULL,
    pure_veg BOOLEAN NOT NULL,
    status VARCHAR(20) NOT NULL,
    status_reason VARCHAR(500),
    rating_average DECIMAL(3,2) NOT NULL,
    rating_count INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT fk_restaurants_owner FOREIGN KEY (owner_id) REFERENCES users (id),
    CONSTRAINT uk_restaurants_owner UNIQUE (owner_id),
    CONSTRAINT uk_restaurants_slug UNIQUE (slug),
    CONSTRAINT ck_restaurants_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'SUSPENDED')),
    CONSTRAINT ck_restaurants_money CHECK (cost_for_two >= 0 AND minimum_order >= 0 AND delivery_fee >= 0),
    CONSTRAINT ck_restaurants_prep CHECK (avg_prep_minutes BETWEEN 1 AND 180),
    CONSTRAINT ck_restaurants_rating CHECK (rating_average BETWEEN 0 AND 5 AND rating_count >= 0)
);

CREATE INDEX idx_restaurants_status ON restaurants (status);
CREATE INDEX idx_restaurants_city ON restaurants (city);

CREATE TABLE restaurant_cuisines (
    restaurant_id BIGINT NOT NULL,
    cuisine_id BIGINT NOT NULL,
    PRIMARY KEY (restaurant_id, cuisine_id),
    CONSTRAINT fk_restaurant_cuisines_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants (id) ON DELETE CASCADE,
    CONSTRAINT fk_restaurant_cuisines_cuisine FOREIGN KEY (cuisine_id) REFERENCES cuisines (id)
);

CREATE INDEX idx_restaurant_cuisines_cuisine ON restaurant_cuisines (cuisine_id);

CREATE TABLE menu_categories (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    restaurant_id BIGINT NOT NULL,
    name VARCHAR(60) NOT NULL,
    sort_order INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT fk_menu_categories_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants (id)
);

-- Also serves the foreign key (leftmost column)
CREATE INDEX idx_menu_categories_restaurant ON menu_categories (restaurant_id, sort_order);

-- Items that were ever ordered are archived instead of deleted (orders keep their own snapshot anyway).
CREATE TABLE menu_items (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    restaurant_id BIGINT NOT NULL,
    category_id BIGINT NOT NULL,
    name VARCHAR(80) NOT NULL,
    description VARCHAR(300),
    price DECIMAL(10,2) NOT NULL,
    veg BOOLEAN NOT NULL,
    bestseller BOOLEAN NOT NULL,
    spice_level INT NOT NULL,
    available BOOLEAN NOT NULL,
    archived BOOLEAN NOT NULL,
    image_path VARCHAR(255),
    sort_order INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT fk_menu_items_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants (id),
    CONSTRAINT fk_menu_items_category FOREIGN KEY (category_id) REFERENCES menu_categories (id),
    CONSTRAINT ck_menu_items_price CHECK (price > 0),
    CONSTRAINT ck_menu_items_spice CHECK (spice_level BETWEEN 0 AND 3)
);

CREATE INDEX idx_menu_items_restaurant ON menu_items (restaurant_id, sort_order);
CREATE INDEX idx_menu_items_category ON menu_items (category_id);
