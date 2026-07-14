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
