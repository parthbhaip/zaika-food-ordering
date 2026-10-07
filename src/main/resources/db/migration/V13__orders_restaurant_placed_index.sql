-- Owner dashboard (today's sales, the 7-day chart, latest orders) and reports: a restaurant's orders by the time
-- they were placed. Unpaid online orders have no placed_at and fall outside every range.
CREATE INDEX idx_orders_restaurant_placed ON orders (restaurant_id, placed_at);
