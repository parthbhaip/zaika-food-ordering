-- Admin rider and user lists (/admin/riders, /admin/users): newest sign-ups first, optionally filtered by status or role.
CREATE INDEX idx_delivery_partner_profiles_created ON delivery_partner_profiles (created_at);
CREATE INDEX idx_delivery_partner_profiles_status_created ON delivery_partner_profiles (status, created_at);
CREATE INDEX idx_users_created ON users (created_at);
CREATE INDEX idx_users_role_created ON users (role, created_at);
