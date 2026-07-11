/**
 * BeamSpot API Server
 * Optimized for deployment on Railway (production tier)
 */

require('dotenv').config();
const express = require('express');
const { Pool } = require('pg');
const bcrypt = require('bcrypt');
const jwt = require('jsonwebtoken');
const { OAuth2Client } = require('google-auth-library');
const helmet = require('helmet');
const rateLimit = require('express-rate-limit');
const path = require('path');

const app = express();
const port = process.env.PORT || 3000;

// ─── Database ─────────────────────────────────────────────────────────────
const db = new Pool({
    connectionString: process.env.DATABASE_URL,
    ssl: process.env.NODE_ENV === 'production' ? { rejectUnauthorized: false } : false
});

// ─── Google OAuth client ──────────────────────────────────────────────────
const googleClient = new OAuth2Client(process.env.GOOGLE_WEB_CLIENT_ID);

// ─── Middleware ───────────────────────────────────────────────────────────
app.use(helmet());
app.use(express.json());
app.use(express.static(path.join(__dirname, '../public'))); // serve guest web page

// Rate limiting
const generalLimit = rateLimit({ windowMs: 60_000, max: 60, message: { error: 'Too many requests' } });
const authLimit    = rateLimit({ windowMs: 15 * 60_000, max: 10, message: { error: 'Too many auth attempts' } });
const guestLimit   = rateLimit({ windowMs: 60_000, max: 20 });
app.use(generalLimit);

// ─── Auth middleware ──────────────────────────────────────────────────────
function requireAuth(req, res, next) {
    const header = req.headers.authorization;
    if (!header?.startsWith('Bearer ')) return res.status(401).json({ error: 'Missing token', code: 'UNAUTHORIZED' });
    try {
        req.user = jwt.verify(header.slice(7), process.env.JWT_SECRET);
        next();
    } catch {
        res.status(401).json({ error: 'Invalid or expired token', code: 'INVALID_TOKEN' });
    }
}

// ─────────────────────────────────────────────────────────────────────────
// AUTH ROUTES
// ─────────────────────────────────────────────────────────────────────────

// Google Sign-In — Android app sends the ID token Google gave it
// Backend verifies with Google, creates/finds user, returns our own JWT
app.post('/api/auth/google', authLimit, async (req, res) => {
    const { idToken } = req.body;
    if (!idToken) return res.status(400).json({ error: 'idToken required', code: 'MISSING_TOKEN' });

    try {
        const ticket = await googleClient.verifyIdToken({
            idToken,
            audience: process.env.GOOGLE_WEB_CLIENT_ID
        });
        const payload = ticket.getPayload();
        const googleId   = payload.sub;
        const email      = payload.email;
        const name       = payload.name;
        const pictureUrl = payload.picture;

        // Find or create user
        let user = (await db.query('SELECT * FROM users WHERE auth_provider_id = $1', [googleId])).rows[0];
        if (!user) {
            user = (await db.query(
                `INSERT INTO users (auth_provider, auth_provider_id, email, display_name, picture_url)
                 VALUES ('google', $1, $2, $3, $4) RETURNING *`,
                [googleId, email, name, pictureUrl]
            )).rows[0];
        }

        const token = jwt.sign({ userId: user.id, email: user.email }, process.env.JWT_SECRET, { expiresIn: '30d' });
        res.json({ token, user: { id: user.id, name: user.display_name, email: user.email, pictureUrl: user.picture_url, hasSetupPayout: !!user.payout_number, hasSetupListing: false } });
    } catch (e) {
        console.error('Google auth error:', e.message);
        res.status(401).json({ error: 'Google token verification failed', code: 'GOOGLE_AUTH_FAILED' });
    }
});

// ─────────────────────────────────────────────────────────────────────────
// HOST ROUTES
// ─────────────────────────────────────────────────────────────────────────

// Save payout details
app.post('/api/hosts/payout', requireAuth, async (req, res) => {
    const { payoutMethod, payoutNumber, bankName, bankAccount, bankHolder } = req.body;
    await db.query(
        `UPDATE users SET payout_provider=$1, payout_number=$2, bank_name=$3, bank_account=$4, bank_holder=$5 WHERE id=$6`,
        [payoutMethod, payoutNumber, bankName || null, bankAccount || null, bankHolder || null, req.user.userId]
    );
    res.json({ ok: true });
});

// Create / update a listing (host registering their network)
app.post('/api/listings', requireAuth, async (req, res) => {
    const { connectionType, pricePerMin, ssid, bssid, beamSpotSsid } = req.body;
    if (!ssid || !bssid || !pricePerMin) return res.status(400).json({ error: 'ssid, bssid, pricePerMin required' });

    // Upsert: one listing per host (simple v1)
    const existing = (await db.query('SELECT id FROM listings WHERE host_id=$1', [req.user.userId])).rows[0];
    let listing;
    if (existing) {
        listing = (await db.query(
            `UPDATE listings SET connection_type=$1, price_per_min=$2, ssid=$3, bssid=$4, beamspot_ssid=$5, status='active' WHERE id=$6 RETURNING *`,
            [connectionType, pricePerMin, ssid, bssid.toUpperCase(), beamSpotSsid || ssid + '_BeamSpot', existing.id]
        )).rows[0];
    } else {
        listing = (await db.query(
            `INSERT INTO listings (host_id, connection_type, price_per_min, ssid, bssid, beamspot_ssid) VALUES ($1,$2,$3,$4,$5,$6) RETURNING *`,
            [req.user.userId, connectionType, pricePerMin, ssid, bssid.toUpperCase(), beamSpotSsid || ssid + '_BeamSpot']
        )).rows[0];
    }
    res.json({ listing });
});

// Toggle sharing on/off
app.patch('/api/listings/:id/sharing', requireAuth, async (req, res) => {
    const { enabled } = req.body;
    await db.query(`UPDATE listings SET status=$1 WHERE id=$2 AND host_id=$3`, [enabled ? 'active' : 'paused', req.params.id, req.user.userId]);
    res.json({ ok: true });
});

// Update price
app.patch('/api/listings/:id/price', requireAuth, async (req, res) => {
    const { pricePerMin } = req.body;
    await db.query(`UPDATE listings SET price_per_min=$1 WHERE id=$2 AND host_id=$3`, [pricePerMin, req.params.id, req.user.userId]);
    res.json({ ok: true });
});

// Host stats — today's earnings, active guests, minutes sold
app.get('/api/hosts/stats', requireAuth, async (req, res) => {
    const listing = (await db.query('SELECT id FROM listings WHERE host_id=$1', [req.user.userId])).rows[0];
    if (!listing) return res.json({ earningsToday: 0, activeGuests: 0, minutesSold: 0, pendingWithdrawal: 0 });

    const today = new Date(); today.setHours(0, 0, 0, 0);
    const { rows } = await db.query(
        `SELECT COALESCE(SUM(host_payout),0) AS earnings, COUNT(*) AS count, COALESCE(SUM(duration_min),0) AS mins
         FROM sessions WHERE listing_id=$1 AND status IN ('CONNECTED','EXPIRED') AND started_at >= $2`,
        [listing.id, today]
    );
    const payouts = (await db.query(`SELECT COALESCE(SUM(amount),0) AS paid FROM payouts WHERE host_id=$1 AND status='sent'`, [req.user.userId])).rows[0];
    const active = (await db.query(`SELECT COUNT(*) AS cnt FROM sessions WHERE listing_id=$1 AND status='CONNECTED'`, [listing.id])).rows[0];

    res.json({
        earningsToday: parseFloat(rows[0].earnings),
        activeGuests: parseInt(active.cnt),
        minutesSold: parseInt(rows[0].mins),
        pendingWithdrawal: parseFloat(rows[0].earnings) - parseFloat(payouts.paid)
    });
});

// Withdraw earnings
app.post('/api/hosts/withdraw', requireAuth, async (req, res) => {
    const listing = (await db.query('SELECT id FROM listings WHERE host_id=$1', [req.user.userId])).rows[0];
    if (!listing) return res.status(400).json({ error: 'No listing found' });

    const { rows } = await db.query(
        `SELECT id, host_payout FROM sessions WHERE listing_id=$1 AND status='EXPIRED'
         AND id NOT IN (SELECT unnest(session_ids) FROM payouts WHERE host_id=$2)`,
        [listing.id, req.user.userId]
    );
    if (!rows.length) return res.json({ amount: 0, message: 'Nothing to withdraw yet' });

    const amount = rows.reduce((s, r) => s + parseFloat(r.host_payout), 0);
    const sessionIds = rows.map(r => r.id);

    await db.query(`INSERT INTO payouts (host_id, amount, session_ids, status) VALUES ($1,$2,$3,'pending')`, [req.user.userId, amount, sessionIds]);
    // TODO: trigger Flutterwave B2C payout here
    res.json({ ok: true, amount });
});

// ─────────────────────────────────────────────────────────────────────────
// NETWORK VERIFICATION (used by both Android app guest scan and web page)
// ─────────────────────────────────────────────────────────────────────────

// Android app sends scanned SSIDs + BSSIDs, we return only verified BeamSpot ones
// This is how the "I need internet" screen shows ONLY real registered networks
app.post('/api/networks/verify', guestLimit, async (req, res) => {
    const { scanned } = req.body; // [{ ssid, bssid }, ...]
    if (!Array.isArray(scanned) || !scanned.length) return res.json({ verified: [] });

    const bssids = scanned.map(n => n.bssid?.toUpperCase()).filter(Boolean);

    const { rows } = await db.query(
        `SELECT l.id, l.beamspot_ssid as display_name, l.ssid, l.bssid, l.price_per_min, l.connection_type,
                u.display_name as host_name,
                (SELECT COUNT(*) FROM sessions s WHERE s.listing_id=l.id AND s.status='CONNECTED') AS active_guests
         FROM listings l JOIN users u ON l.host_id=u.id
         WHERE l.bssid = ANY($1) AND l.status='active'`,
        [bssids]
    );

    // Only verified (BSSID-matched) results are returned — spoof networks with same SSID but different BSSID are NOT included
    res.json({ verified: rows });
});

// ─────────────────────────────────────────────────────────────────────────
// SESSION ROUTES (guest paying for internet)
// ─────────────────────────────────────────────────────────────────────────

// Create a session (guest picks duration, initiates payment)
app.post('/api/sessions', guestLimit, async (req, res) => {
    const { listingId, guestDeviceId, durationMin, paymentMethod, phone } = req.body;
    if (!listingId || !durationMin || durationMin < 15 || durationMin > 1440)
        return res.status(400).json({ error: 'Invalid session parameters' });

    // Block double purchase on same listing for same device
    const active = (await db.query(
        `SELECT id FROM sessions WHERE listing_id=$1 AND guest_device_id=$2 AND status='CONNECTED'`,
        [listingId, guestDeviceId]
    )).rows[0];
    if (active) return res.status(409).json({ error: 'You already have an active session on this network', code: 'DUPLICATE_SESSION' });

    const listing = (await db.query('SELECT price_per_min FROM listings WHERE id=$1 AND status=$2', [listingId, 'active'])).rows[0];
    if (!listing) return res.status(404).json({ error: 'Network not found or unavailable' });

    const amountTotal = listing.price_per_min * durationMin;
    const platformFee = amountTotal * 0.05;
    const hostPayout  = amountTotal - platformFee;

    const session = (await db.query(
        `INSERT INTO sessions (listing_id, guest_device_id, duration_min, amount_total, platform_fee, host_payout, status)
         VALUES ($1,$2,$3,$4,$5,$6,'PENDING_PAYMENT') RETURNING id`,
        [listingId, guestDeviceId, durationMin, amountTotal, platformFee, hostPayout]
    )).rows[0];

    // Create Flutterwave checkout and return checkoutUrl
    let checkoutUrl = null;
    try {
        const response = await fetch('https://api.flutterwave.com/v3/payments', {
            method: 'POST',
            headers: {
                'Authorization': `Bearer ${process.env.FLUTTERWAVE_SECRET_KEY}`,
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                tx_ref: `beamspot-sess-${session.id}`,
                amount: amountTotal.toString(),
                currency: process.env.FLUTTERWAVE_CURRENCY || 'KES',
                redirect_url: `${process.env.PUBLIC_BASE_URL || 'http://localhost:3000'}/api/sessions/callback`,
                meta: {
                    session_id: session.id
                },
                customer: {
                    email: req.body.email || 'guest@beamspot.com',
                    phonenumber: phone || '',
                    name: 'BeamSpot Guest'
                },
                customizations: {
                    title: 'BeamSpot Wi-Fi',
                    description: `Payment for ${durationMin} minutes of high-speed Wi-Fi access`
                }
            })
        });

        const data = await response.json();
        if (data.status === 'success' && data.data && data.data.link) {
            checkoutUrl = data.data.link;
        } else {
            console.error('Flutterwave API response error:', data);
        }
    } catch (e) {
        console.error('Flutterwave payment initiation failed:', e.message);
    }

    res.json({ sessionId: session.id, amountTotal, platformFee, hostPayout, checkoutUrl });
});

// GET route for Flutterwave redirect callback
app.get('/api/sessions/callback', async (req, res) => {
    const { status, tx_ref } = req.query;
    res.send(`
        <html>
            <head>
                <title>BeamSpot Payment Callback</title>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>
                    body { background: #0E1614; color: #F0EEE6; font-family: sans-serif; text-align: center; padding: 40px 20px; }
                    .card { background: #1A2925; padding: 30px; border-radius: 20px; max-width: 400px; margin: 0 auto; border: 1px solid #3FE0C5; box-shadow: 0 4px 15px rgba(0,0,0,0.5); }
                    h2 { color: #3FE0C5; margin-top: 0; }
                    p { color: #9EF0EEE6; font-size: 14px; line-height: 1.5; margin-bottom: 20px; }
                    .btn { display: inline-block; background: #FF7A45; color: #F0EEE6; padding: 12px 24px; text-decoration: none; border-radius: 10px; font-weight: bold; }
                </style>
            </head>
            <body>
                <div class="card">
                    <h2>Payment Processed</h2>
                    <p>Status: <strong>${status === 'successful' ? 'SUCCESSFUL' : 'PENDING / FAILED'}</strong></p>
                    <p>You can now return to the BeamSpot app or browser tab to start using high-speed internet.</p>
                    <a href="javascript:window.close()" class="btn">Close Window</a>
                </div>
            </body>
        </html>
    `);
});

// Poll session status (guest browser polls this every 3 seconds after paying)
app.get('/api/sessions/:id', guestLimit, async (req, res) => {
    const s = (await db.query(
        `SELECT id, status, expires_at, listing_id FROM sessions WHERE id=$1`,
        [req.params.id]
    )).rows[0];
    if (!s) return res.status(404).json({ error: 'Session not found' });
    res.json({ status: s.status, expiresAt: s.expires_at });
});

// ─────────────────────────────────────────────────────────────────────────
// PAYMENT WEBHOOK (Flutterwave calls this when payment is confirmed)
// ─────────────────────────────────────────────────────────────────────────
app.post('/api/payments/webhook', express.json(), async (req, res) => {
    // Verify Flutterwave signature
    const signature = req.headers['verif-hash'];
    if (!signature || signature !== process.env.FLUTTERWAVE_WEBHOOK_SECRET) {
        return res.status(401).json({ error: 'Invalid signature' });
    }

    const { event, data } = req.body;
    if (event !== 'charge.completed' || data?.status !== 'successful') return res.json({ ok: true });

    const sessionId = data?.meta?.session_id;
    if (!sessionId) return res.json({ ok: true });

    const session = (await db.query(`SELECT * FROM sessions WHERE id=$1`, [sessionId])).rows[0];
    if (!session || session.status !== 'PENDING_PAYMENT') return res.json({ ok: true }); // idempotency

    const now      = new Date();
    const expiresAt = new Date(now.getTime() + session.duration_min * 60_000);

    await db.query(
        `UPDATE sessions SET status='CONNECTED', payment_ref=$1, started_at=$2, expires_at=$3 WHERE id=$4`,
        [data.flw_ref, now, expiresAt, sessionId]
    );

    // For Router Mode: write an authorize record the router agent will pick up
    await db.query(
        `INSERT INTO router_actions (listing_id, guest_device_id, action, expires_at)
         VALUES ($1,$2,'authorize',$3)`,
        [session.listing_id, session.guest_device_id, expiresAt]
    ).catch(() => {}); // ignore if router_actions table not set up yet

    res.json({ ok: true });
});

// ─────────────────────────────────────────────────────────────────────────
// ROUTER AGENT ENDPOINT (OpenWRT router polls this)
// ─────────────────────────────────────────────────────────────────────────
app.get('/api/router/pending', async (req, res) => {
    const { router_id, api_key } = req.query;
    const router = (await db.query(`SELECT id FROM routers WHERE id=$1 AND api_key=$2`, [router_id, api_key])).rows[0];
    if (!router) return res.status(401).json({ error: 'Invalid router credentials' });

    await db.query(`UPDATE routers SET last_seen=NOW() WHERE id=$1`, [router_id]);

    const { rows } = await db.query(
        `SELECT action, guest_device_id, expires_at FROM router_actions
         WHERE listing_id=(SELECT listing_id FROM routers WHERE id=$1) AND sent=false`,
        [router_id]
    );
    if (rows.length) {
        await db.query(`UPDATE router_actions SET sent=true WHERE listing_id=(SELECT listing_id FROM routers WHERE id=$1) AND sent=false`, [router_id]);
    }

    res.json({
        authorize:   rows.filter(r => r.action === 'authorize').map(r => ({ mac: r.guest_device_id, duration_minutes: Math.ceil((new Date(r.expires_at) - Date.now()) / 60_000) })),
        deauthorize: rows.filter(r => r.action === 'deauthorize').map(r => ({ mac: r.guest_device_id }))
    });
});

// ─────────────────────────────────────────────────────────────────────────
// Background job: expire sessions every 30 seconds
// ─────────────────────────────────────────────────────────────────────────
setInterval(async () => {
    try {
        const { rows } = await db.query(
            `UPDATE sessions SET status='EXPIRED' WHERE status='CONNECTED' AND expires_at <= NOW() RETURNING id, listing_id, guest_device_id`
        );
        for (const s of rows) {
            await db.query(
                `INSERT INTO router_actions (listing_id, guest_device_id, action, expires_at) VALUES ($1,$2,'deauthorize',NOW())`,
                [s.listing_id, s.guest_device_id]
            ).catch(() => {});
        }
        // Also fail sessions stuck in PENDING_PAYMENT for > 10 minutes
        await db.query(`UPDATE sessions SET status='FAILED' WHERE status='PENDING_PAYMENT' AND created_at < NOW() - INTERVAL '10 minutes'`);
    } catch (e) { console.error('Expiry job error:', e.message); }
}, 30_000);

// ─────────────────────────────────────────────────────────────────────────
// Health check
// ─────────────────────────────────────────────────────────────────────────
app.get('/health', (_, res) => res.json({ status: 'ok', ts: new Date().toISOString() }));

// Serve guest payment page for all non-API routes (SPA fallback)
app.get('*', (req, res) => {
    if (!req.path.startsWith('/api')) {
        res.sendFile(path.join(__dirname, '../public/index.html'));
    }
});

app.listen(port, () => console.log(`BeamSpot API running on port ${port}`));
