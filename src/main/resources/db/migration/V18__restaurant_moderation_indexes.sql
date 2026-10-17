-- Admin restaurant moderation (/admin/restaurants): newest sign-ups first, optionally filtered by status.
CREATE INDEX idx_restaurants_created ON restaurants (created_at);
CREATE INDEX idx_restaurants_status_created ON restaurants (status, created_at);
