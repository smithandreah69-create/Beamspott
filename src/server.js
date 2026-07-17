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
app.set('trust proxy', 1);
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

// Mount router fingerprinting endpoint
const routerFingerprint = require('./routes/routerFingerprint');
app.use(routerFingerprint);

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

// Get payout details
app.get('/api/hosts/payout', requireAuth, async (req, res) => {
    const user = (await db.query(
        'SELECT payout_provider, payout_number, bank_name, bank_account, bank_holder FROM users WHERE id=$1',
        [req.user.userId]
    )).rows[0];
    if (!user) return res.status(404).json({ error: 'User not found' });
    res.json({
        payout_provider: user.payout_provider,
        payout_number: user.payout_number,
        bank_name: user.bank_name,
        bank_account: user.bank_account,
        bank_holder: user.bank_holder,
        payoutMethod: user.payout_provider,
        payoutNumber: user.payout_number,
        bankName: user.bank_name,
        bankAccount: user.bank_account,
        bankHolder: user.bank_holder
    });
});

function getMaxGuests(connectionType) {
    return 30; // ROUTER mode
}

// Create / update a listing (host registering their network)
app.post('/api/listings', requireAuth, async (req, res) => {
    const { connectionType, pricePerMin, ssid, bssid, beamSpotSsid } = req.body;
    if (!ssid || !bssid || !pricePerMin) return res.status(400).json({ error: 'ssid, bssid, pricePerMin required' });

    const upperBssid = bssid.toUpperCase();

    // BSSID uniqueness: reject if already claimed by another active listing
    const claimed = (await db.query(
        "SELECT id FROM listings WHERE bssid=$1 AND status='active' AND host_id!=$2",
        [upperBssid, req.user.userId]
    )).rows[0];
    if (claimed) {
        return res.status(400).json({ error: 'This BSSID is already claimed by another active listing.' });
    }

    // Upsert: one listing per host (simple v1)
    const existing = (await db.query('SELECT id FROM listings WHERE host_id=$1', [req.user.userId])).rows[0];
    let listing;
    if (existing) {
        listing = (await db.query(
            `UPDATE listings SET connection_type=$1, price_per_min=$2, ssid=$3, bssid=$4, beamspot_ssid=$5, status='active' WHERE id=$6 RETURNING *`,
            [connectionType, pricePerMin, ssid, upperBssid, beamSpotSsid || ssid + '_BeamSpot', existing.id]
        )).rows[0];
    } else {
        listing = (await db.query(
            `INSERT INTO listings (host_id, connection_type, price_per_min, ssid, bssid, beamspot_ssid) VALUES ($1,$2,$3,$4,$5,$6) RETURNING *`,
            [req.user.userId, connectionType, pricePerMin, ssid, upperBssid, beamSpotSsid || ssid + '_BeamSpot']
        )).rows[0];
    }
    res.json({ listing });
});

// Toggle sharing on/off
app.patch('/api/listings/:id/sharing', requireAuth, async (req, res) => {
    const { enabled } = req.body;
    
    if (enabled) {
        const listing = (await db.query('SELECT bssid FROM listings WHERE id=$1 AND host_id=$2', [req.params.id, req.user.userId])).rows[0];
        if (listing) {
            const claimed = (await db.query(
                "SELECT id FROM listings WHERE bssid=$1 AND status='active' AND host_id!=$2",
                [listing.bssid, req.user.userId]
            )).rows[0];
            if (claimed) {
                return res.status(400).json({ error: 'This BSSID is already claimed by another active listing.' });
            }
        }
    }

    await db.query(`UPDATE listings SET status=$1 WHERE id=$2 AND host_id=$3`, [enabled ? 'active' : 'paused', req.params.id, req.user.userId]);
    res.json({ ok: true });
});

// Update price
app.patch('/api/listings/:id/price', requireAuth, async (req, res) => {
    const { pricePerMin } = req.body;
    await db.query(`UPDATE listings SET price_per_min=$1 WHERE id=$2 AND host_id=$3`, [pricePerMin, req.params.id, req.user.userId]);
    res.json({ ok: true });
});

// Release a claimed network (delete listing)
app.delete('/api/listings/:id', requireAuth, async (req, res) => {
    const result = await db.query(
        'DELETE FROM listings WHERE id=$1 AND host_id=$2 RETURNING id',
        [req.params.id, req.user.userId]
    );
    if (result.rowCount === 0) {
        return res.status(404).json({ error: 'Listing not found' });
    }
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

    // Eligible pending withdrawal accounting for 7-day holding period
    const eligibleWithdrawRow = (await db.query(
        `SELECT COALESCE(SUM(host_payout),0) AS amount FROM sessions WHERE listing_id=$1 AND status='EXPIRED'
         AND expires_at <= NOW() - INTERVAL '7 days'
         AND id NOT IN (SELECT unnest(session_ids) FROM payouts WHERE host_id=$2)`,
        [listing.id, req.user.userId]
    )).rows[0];

    res.json({
        earningsToday: parseFloat(rows[0].earnings),
        activeGuests: parseInt(active.cnt),
        minutesSold: parseInt(rows[0].mins),
        pendingWithdrawal: parseFloat(eligibleWithdrawRow.amount)
    });
});

// Withdraw earnings
app.post('/api/hosts/withdraw', requireAuth, async (req, res) => {
    const listing = (await db.query('SELECT id FROM listings WHERE host_id=$1', [req.user.userId])).rows[0];
    if (!listing) return res.status(400).json({ error: 'No listing found' });

    // Enforce 7-day holding period: only select sessions expired >= 7 days ago
    const { rows } = await db.query(
        `SELECT id, host_payout FROM sessions WHERE listing_id=$1 AND status='EXPIRED'
         AND expires_at <= NOW() - INTERVAL '7 days'
         AND id NOT IN (SELECT unnest(session_ids) FROM payouts WHERE host_id=$2)`,
        [listing.id, req.user.userId]
    );
    if (!rows.length) return res.status(400).json({ error: 'Nothing eligible to withdraw yet. Eligible sessions must have a 7-day holding period.' });

    const amount = rows.reduce((s, r) => s + parseFloat(r.host_payout), 0);

    // Enforce KES 1000 minimum
    if (amount < 1000) {
        return res.status(400).json({ error: `Minimum withdrawable amount is KES 1000. Your eligible balance is KES ${amount.toFixed(2)}.` });
    }

    const sessionIds = rows.map(r => r.id);

    await db.query(`INSERT INTO payouts (host_id, amount, session_ids, status) VALUES ($1,$2,$3,'pending')`, [req.user.userId, amount, sessionIds]);
    
    // IntaSend B2C payout can be triggered here
    res.json({ ok: true, amount });
});

// Host pending active sessions (polled by VPN service)
app.get('/api/hosts/sessions/pending', requireAuth, async (req, res) => {
    try {
        const listing = (await db.query('SELECT id FROM listings WHERE host_id=$1', [req.user.userId])).rows[0];
        if (!listing) return res.json([]);

        // Find all active connected sessions for this listing
        const { rows } = await db.query(
            `SELECT id, guest_device_id, duration_min, status, expires_at FROM sessions
             WHERE listing_id=$1 AND status='CONNECTED'`,
            [listing.id]
        );

        const pendingList = rows.map(s => {
            const parts = s.guest_device_id.split('|');
            const guestIp = parts.length > 1 ? parts[1] : null;
            const durationMin = Math.max(1, Math.ceil((new Date(s.expires_at) - Date.now()) / 60_000));
            return {
                sessionId: s.id,
                guestIp: guestIp,
                guestDeviceId: s.guest_device_id,
                durationMin: durationMin,
                status: s.status
            };
        });

        res.json(pendingList);
    } catch (e) {
        console.error('Error fetching pending sessions:', e.message);
        res.status(500).json({ error: 'Server error' });
    }
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
    if (!listingId || !durationMin || durationMin < 1 || durationMin > 1440) // TEMPORARY for testing — restore to 15 before production launch
        return res.status(400).json({ error: 'Invalid session parameters' });

    // Phone number validation & normalization (Item 26)
    const isBypass = req.body.testBypassPassword === "beamspot-test-2026";
    let normalizedPhone = '';
    if (!isBypass) {
        if (!phone) {
            return res.status(400).json({ error: 'Phone number is required for payments' });
        }
        const phoneRegex = /^(?:\+?254|0)(7|1)\d{8}$/;
        if (!phoneRegex.test(phone)) {
            return res.status(400).json({ error: 'Enter a valid Safaricom/Airtel number (e.g. 0712345678)' });
        }
        const cleanPhone = phone.replace(/\D/g, '');
        if (cleanPhone.startsWith('0')) {
            normalizedPhone = '254' + cleanPhone.slice(1);
        } else if (cleanPhone.startsWith('254')) {
            normalizedPhone = cleanPhone;
        } else {
            normalizedPhone = '254' + cleanPhone;
        }
    } else if (phone) {
        const phoneRegex = /^(?:\+?254|0)(7|1)\d{8}$/;
        if (!phoneRegex.test(phone)) {
            return res.status(400).json({ error: 'Invalid phone number format.' });
        }
        const cleanPhone = phone.replace(/\D/g, '');
        if (cleanPhone.startsWith('0')) {
            normalizedPhone = '254' + cleanPhone.slice(1);
        } else if (cleanPhone.startsWith('254')) {
            normalizedPhone = cleanPhone;
        } else {
            normalizedPhone = '254' + cleanPhone;
        }
    }

    // Block double purchase on same listing for same device
    const active = (await db.query(
        `SELECT id FROM sessions WHERE listing_id=$1 AND guest_device_id=$2 AND status='CONNECTED'`,
        [listingId, guestDeviceId]
    )).rows[0];
    if (active) return res.status(409).json({ error: 'You already have an active session on this network', code: 'DUPLICATE_SESSION' });

    const listing = (await db.query('SELECT price_per_min, connection_type FROM listings WHERE id=$1 AND status=$2', [listingId, 'active'])).rows[0];
    if (!listing) return res.status(404).json({ error: 'Network not found or unavailable' });

    // Per-listing guest caps based on sharing mode
    const activeCount = parseInt((await db.query(
        `SELECT COUNT(*)::int as count FROM sessions WHERE listing_id=$1 AND status='CONNECTED'`,
        [listingId]
    )).rows[0].count);
    const maxGuests = getMaxGuests(listing.connection_type);

    if (activeCount >= maxGuests) {
        return res.status(403).json({ error: 'This BeamSpot has reached its maximum guest capacity. Please try again later.' });
    }

    const amountTotal = listing.price_per_min * durationMin;
    const platformFee = amountTotal * 0.05;
    const hostPayout  = amountTotal - platformFee;

    const session = (await db.query(
        `INSERT INTO sessions (listing_id, guest_device_id, duration_min, amount_total, platform_fee, host_payout, status)
         VALUES ($1,$2,$3,$4,$5,$6,'PENDING_PAYMENT') RETURNING id`,
         [listingId, guestDeviceId, durationMin, amountTotal, platformFee, hostPayout]
    )).rows[0];

    // TEST MODE ONLY — remove before wiring up real payment
    if (req.body.testBypassPassword === "beamspot-test-2026") {
        const now = new Date();
        const expiresAt = new Date(now.getTime() + durationMin * 60_000);
        await db.query(
            `UPDATE sessions SET status='CONNECTED', started_at=$1, expires_at=$2 WHERE id=$3`,
            [now, expiresAt, session.id]
        );
        await db.query(
            `INSERT INTO router_actions (listing_id, guest_device_id, action, expires_at)
             VALUES ($1,$2,'authorize',$3)`,
            [listingId, guestDeviceId, expiresAt]
        ).catch(() => {});
        return res.json({ 
            sessionId: session.id, 
            amountTotal, 
            platformFee, 
            hostPayout, 
            checkoutUrl: null, 
            testMode: true 
        });
    }

    // Create IntaSend checkout and return checkoutUrl
    let checkoutUrl = null;
    try {
        const response = await fetch(`${process.env.INTASEND_API_BASE_URL || 'https://sandbox.intasend.com'}/api/v1/checkout/`, {
            method: 'POST',
            headers: {
                'Authorization': `Bearer ${process.env.INTASEND_SECRET_KEY}`,
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                public_key: process.env.INTASEND_PUBLIC_KEY,
                amount: parseFloat(amountTotal.toFixed(2)),
                currency: process.env.INTASEND_CURRENCY || 'KES',
                email: req.body.email || 'guest@beamspot.com',
                first_name: 'BeamSpot',
                last_name: 'Guest',
                phone_number: normalizedPhone || '',
                host: process.env.PUBLIC_BASE_URL || 'http://localhost:3000',
                redirect_url: `${process.env.PUBLIC_BASE_URL || 'http://localhost:3000'}/api/sessions/callback`,
                api_ref: `beamspot-sess-${session.id}`
            })
        });

        const data = await response.json();
        if (data.url) {
            checkoutUrl = data.url;
        } else {
            console.error('IntaSend API response error:', data);
        }
    } catch (e) {
        console.error('IntaSend payment initiation failed:', e.message);
    }

    res.json({ sessionId: session.id, amountTotal, platformFee, hostPayout, checkoutUrl });
});

// GET route for IntaSend redirect callback
app.get('/api/sessions/callback', async (req, res) => {
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
                    <p>Status: <strong>SUCCESSFUL / COMPLETE</strong></p>
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
// PAYMENT WEBHOOK (IntaSend calls this when payment is confirmed)
// ─────────────────────────────────────────────────────────────────────────
app.post('/api/payments/webhook', express.json(), async (req, res) => {
    // Verify IntaSend webhook signature token
    const signature = req.headers['x-intasend-signature'];
    if (process.env.INTASEND_WEBHOOK_SECRET && signature !== process.env.INTASEND_WEBHOOK_SECRET) {
        return res.status(401).json({ error: 'Invalid signature' });
    }

    const { state, api_ref, invoice_id } = req.body;
    if (state !== 'COMPLETE') return res.json({ ok: true });

    if (!api_ref || !api_ref.startsWith('beamspot-sess-')) return res.json({ ok: true });
    const sessionId = api_ref.replace('beamspot-sess-', '');

    const session = (await db.query(`SELECT * FROM sessions WHERE id=$1`, [sessionId])).rows[0];
    if (!session || session.status !== 'PENDING_PAYMENT') return res.json({ ok: true }); // idempotency

    const now      = new Date();
    const expiresAt = new Date(now.getTime() + session.duration_min * 60_000);

    await db.query(
        `UPDATE sessions SET status='CONNECTED', payment_ref=$1, started_at=$2, expires_at=$3 WHERE id=$4`,
        [invoice_id, now, expiresAt, sessionId]
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
// Root URL
// ─────────────────────────────────────────────────────────────────────────
app.get('/', (_, res) => res.json({
    app: 'BeamSpot API',
    version: '1.0',
    status: 'running',
    endpoints: ['/health', '/api/auth/google', '/api/networks/verify', '/api/sessions']
}));

// ─────────────────────────────────────────────────────────────────────────
// Health check
// ─────────────────────────────────────────────────────────────────────────
app.get('/health', (_, res) => res.json({ status: 'ok', ts: new Date().toISOString() }));

// Serve terms document (Item 25)
app.get('/terms', (req, res) => {
    res.sendFile(path.join(__dirname, '../public/terms.html'));
});

// Serve privacy policy document
app.get('/privacy', (req, res) => {
    res.sendFile(path.join(__dirname, '../public/privacy.html'));
});

// Serve guest payment page for all non-API routes (SPA fallback)
app.get('*', (req, res) => {
    if (!req.path.startsWith('/api')) {
        res.sendFile(path.join(__dirname, '../public/index.html'));
    }
});

app.listen(port, () => console.log(`BeamSpot API running on port ${port}`));
