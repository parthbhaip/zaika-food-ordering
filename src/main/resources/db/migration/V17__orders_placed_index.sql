-- Admin dashboard: platform-wide orders by the time they were placed (today's orders and GMV, the 14-day chart, top
-- restaurants of the last 30 days, the recent orders table, newest first).
CREATE INDEX idx_orders_placed ON orders (placed_at);
