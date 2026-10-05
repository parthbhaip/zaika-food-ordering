-- The customer listing (/restaurants) always filters on status = 'APPROVED', then filters or sorts by one of these.
-- Search (LIKE '%text%' on restaurant and dish names) cannot use a B-tree index; at city scale it scans one
-- restaurant's menu rows through idx_menu_items_restaurant.
CREATE INDEX idx_restaurants_status_rating ON restaurants (status, rating_average);
CREATE INDEX idx_restaurants_status_prep ON restaurants (status, avg_prep_minutes);
CREATE INDEX idx_restaurants_status_cost ON restaurants (status, cost_for_two);
CREATE INDEX idx_restaurants_status_veg ON restaurants (status, pure_veg);
CREATE INDEX idx_restaurants_status_hours ON restaurants (status, accepting_orders, opens_at, closes_at);
