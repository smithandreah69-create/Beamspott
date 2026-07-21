-- BeamSpot Database Schema
-- Run this once when you first deploy: psql $DATABASE_URL -f schema.sql

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Hosts (Google OAuth users)
CREATE TABLE IF NOT EXISTS users (
    id               UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    auth_provider    TEXT NOT NULL DEFAULT 'google',
    auth_provider_id TEXT UNIQUE NOT NULL,      -- Google's "sub" field, permanent
    email            TEXT UNIQUE NOT NULL,
    display_name     TEXT NOT NULL,
    picture_url      TEXT,
    payout_provider  TEXT,                      -- 'mpesa' | 'airtel' | 'bank' | 'card'
    payout_number    TEXT,                      -- phone number or account number
    bank_name        TEXT,
    bank_account     TEXT,
    bank_holder      TEXT,
    created_at       TIMESTAMPTZ DEFAULT NOW()
);

-- Network listings (each host's active sharing offer)
CREATE TABLE IF NOT EXISTS listings (
    id               UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    host_id          UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    connection_type  TEXT NOT NULL,             -- 'MOBILE_HOTSPOT' | 'HOME_ROUTER' | 'SMART_BRIDGE'
    price_per_min    NUMERIC(10,2) NOT NULL,
    ssid             TEXT NOT NULL,             -- host's real router SSID
    bssid            TEXT UNIQUE NOT NULL,      -- hardware MAC — anti-spoof anchor (stored uppercase)
    beamspot_ssid    TEXT,                      -- the public name guests see
    status           TEXT DEFAULT 'active',     -- 'active' | 'paused' | 'offline'
    router_ip        TEXT,                      -- MikroTik IP (Task 8)
    router_api_port  INT,                       -- MikroTik API Port (Task 8)
    router_username  TEXT,                      -- MikroTik API Username (Task 8)
    router_password  TEXT,                      -- MikroTik API Password (Task 8)
    created_at       TIMESTAMPTZ DEFAULT NOW()
);

-- Guest sessions
CREATE TABLE IF NOT EXISTS sessions (
    id               UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    listing_id       UUID NOT NULL REFERENCES listings(id),
    guest_device_id  TEXT NOT NULL,             -- MAC address or device fingerprint
    duration_min     INT NOT NULL CHECK (duration_min >= 1 AND duration_min <= 1440),
    amount_total     NUMERIC(10,2) NOT NULL,
    platform_fee     NUMERIC(10,2) NOT NULL,    -- 5%
    host_payout      NUMERIC(10,2) NOT NULL,    -- 95%
    status           TEXT DEFAULT 'PENDING_PAYMENT',
    -- PENDING_PAYMENT → CONNECTED → EXPIRED | REFUNDED | FAILED
    payment_ref      TEXT,                      -- Flutterwave transaction reference
    guest_mac        TEXT,                      -- guest's router-provided MAC (Task 8)
    started_at       TIMESTAMPTZ,
    expires_at       TIMESTAMPTZ,
    created_at       TIMESTAMPTZ DEFAULT NOW()
);
-- Enforce one active session per device per listing
CREATE UNIQUE INDEX IF NOT EXISTS idx_sessions_device_active
    ON sessions (listing_id, guest_device_id)
    WHERE status = 'CONNECTED';

-- Router Mode routers
CREATE TABLE IF NOT EXISTS routers (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    host_id     UUID REFERENCES users(id),
    listing_id  UUID REFERENCES listings(id),
    api_key     TEXT UNIQUE NOT NULL,
    last_seen   TIMESTAMPTZ,
    status      TEXT DEFAULT 'active'
);

-- Actions queued for the router agent to pick up and execute
CREATE TABLE IF NOT EXISTS router_actions (
    id               UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    listing_id       UUID REFERENCES listings(id),
    guest_device_id  TEXT NOT NULL,
    action           TEXT NOT NULL,             -- 'authorize' | 'deauthorize'
    expires_at       TIMESTAMPTZ,
    sent             BOOLEAN DEFAULT FALSE,
    created_at       TIMESTAMPTZ DEFAULT NOW()
);

-- Host payouts
CREATE TABLE IF NOT EXISTS payouts (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    host_id     UUID REFERENCES users(id),
    amount      NUMERIC(10,2) NOT NULL,
    session_ids UUID[] NOT NULL,
    status      TEXT DEFAULT 'pending',         -- 'pending' | 'sent' | 'failed'
    sent_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);

-- Router Master Profiles for auto-detection
CREATE TABLE IF NOT EXISTS router_brand_profiles (
    id                               UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    brand                            TEXT NOT NULL,
    mac_prefix                       VARCHAR(6) UNIQUE, -- 6 hex chars, e.g., '34E894'
    gateway_ip                       TEXT,              -- e.g., '192.168.0.1'
    requires_unique_sticker_password BOOLEAN NOT NULL DEFAULT FALSE,
    target_login_url                 TEXT,
    request_method                   TEXT DEFAULT 'POST',
    payload_format                   TEXT DEFAULT 'FORM_DATA',
    username_field                   TEXT DEFAULT 'username',
    password_field                   TEXT DEFAULT 'password',
    real_time_credentials            JSONB DEFAULT '[]'::jsonb, -- Array of objects: {username, password}
    created_at                       TIMESTAMPTZ DEFAULT NOW()
);

-- Insert seed profiles if not already present
INSERT INTO router_brand_profiles (brand, mac_prefix, gateway_ip, requires_unique_sticker_password, target_login_url, real_time_credentials)
VALUES 
('MikroTik', '18FD74', '192.168.88.1', FALSE, 'http://192.168.88.1', '[{"username": "admin", "password": ""}]'::jsonb),
('TP-Link', '34E894', '192.168.0.1', FALSE, 'http://192.168.0.1', '[{"username": "admin", "password": "admin"}, {"username": "admin", "password": ""}]'::jsonb),
('Tenda', '502AAF', '192.168.0.1', FALSE, 'http://192.168.0.1', '[{"username": "", "password": "admin"}, {"username": "admin", "password": "admin"}]'::jsonb),
('Huawei', '283152', '192.168.8.1', FALSE, 'http://192.168.8.1', '[{"username": "admin", "password": "admin"}, {"username": "telecomadmin", "password": "admintelecom"}, {"username": "root", "password": "admin"}]'::jsonb),
('D-Link', '1CB094', '192.168.0.1', FALSE, 'http://192.168.0.1', '[{"username": "admin", "password": ""}, {"username": "admin", "password": "admin"}, {"username": "admin", "password": "password"}]'::jsonb),
('ASUS', 'F07960', '192.168.50.1', FALSE, 'http://192.168.50.1', '[{"username": "admin", "password": "admin"}, {"username": "admin", "password": "password"}]'::jsonb),
('TP-Link Archer Series (Modern)', '74DA38', '192.168.1.1', TRUE, NULL, '[]'::jsonb),
('Tenda Nova Series (Modern)', '502AB0', '192.168.5.1', TRUE, NULL, '[]'::jsonb)
ON CONFLICT (mac_prefix) DO NOTHING;

