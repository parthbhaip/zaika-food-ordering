-- A delivery partner's (rider's) profile: one per DELIVERY_PARTNER account, created by the rider sign-up.
-- Riders start PENDING and offline; an admin approves or suspends them (a suspension says why).
-- vehicle_number is the normalized number plate ("MH09AB1234"), empty for a bicycle.
CREATE TABLE delivery_partner_profiles (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    vehicle_type VARCHAR(10) NOT NULL,
    vehicle_number VARCHAR(12),
    preferred_area VARCHAR(60) NOT NULL,
    status VARCHAR(10) NOT NULL,
    status_reason VARCHAR(500),
    online BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT uk_delivery_partner_profiles_user UNIQUE (user_id),
    CONSTRAINT ck_delivery_partner_profiles_vehicle CHECK (vehicle_type IN ('BIKE', 'SCOOTER', 'BICYCLE')),
    CONSTRAINT ck_delivery_partner_profiles_number CHECK ((vehicle_type = 'BICYCLE' AND vehicle_number IS NULL)
        OR (vehicle_type <> 'BICYCLE' AND vehicle_number IS NOT NULL)),
    CONSTRAINT ck_delivery_partner_profiles_status CHECK (status IN ('PENDING', 'APPROVED', 'SUSPENDED')),
    CONSTRAINT ck_delivery_partner_profiles_online CHECK (online = FALSE OR status = 'APPROVED')
);

-- the unique index above serves the foreign key
ALTER TABLE delivery_partner_profiles
    ADD CONSTRAINT fk_delivery_partner_profiles_user FOREIGN KEY (user_id) REFERENCES users (id);

-- admin rider lists filter by status; the admin dashboard counts riders online
CREATE INDEX idx_delivery_partner_profiles_status ON delivery_partner_profiles (status, online);
