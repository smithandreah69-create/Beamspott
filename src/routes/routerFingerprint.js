const express = require('express');
const router = express.Router();
const { Pool } = require('pg');

const db = new Pool({
    connectionString: process.env.DATABASE_URL,
    ssl: process.env.NODE_ENV === 'production' ? { rejectUnauthorized: false } : false
});

const DEFAULT_PROFILES = [
  {
    brand: "MikroTik",
    mac_prefix: "18FD74",
    gateway_ip: "192.168.88.1",
    requires_unique_sticker_password: false,
    target_login_url: "http://192.168.88.1",
    request_method: "POST",
    payload_format: "FORM_DATA",
    username_field: "username",
    password_field: "password",
    real_time_credentials: [
      { "username": "admin", "password": "" }
    ]
  },
  {
    brand: "TP-Link",
    mac_prefix: "34E894",
    gateway_ip: "192.168.0.1",
    requires_unique_sticker_password: false,
    target_login_url: "http://192.168.0.1",
    request_method: "POST",
    payload_format: "FORM_DATA",
    username_field: "username",
    password_field: "password",
    real_time_credentials: [
      { "username": "admin", "password": "admin" },
      { "username": "admin", "password": "" }
    ]
  },
  {
    brand: "Tenda",
    mac_prefix: "502AAF",
    gateway_ip: "192.168.0.1",
    requires_unique_sticker_password: false,
    target_login_url: "http://192.168.0.1",
    request_method: "POST",
    payload_format: "FORM_DATA",
    username_field: "username",
    password_field: "password",
    real_time_credentials: [
      { "username": "", "password": "admin" },
      { "username": "admin", "password": "admin" }
    ]
  },
  {
    brand: "Huawei",
    mac_prefix: "283152",
    gateway_ip: "192.168.8.1",
    requires_unique_sticker_password: false,
    target_login_url: "http://192.168.8.1",
    request_method: "POST",
    payload_format: "FORM_DATA",
    username_field: "username",
    password_field: "password",
    real_time_credentials: [
      { "username": "admin", "password": "admin" },
      { "username": "telecomadmin", "password": "admintelecom" },
      { "username": "root", "password": "admin" }
    ]
  },
  {
    brand: "D-Link",
    mac_prefix: "1CB094",
    gateway_ip: "192.168.0.1",
    requires_unique_sticker_password: false,
    target_login_url: "http://192.168.0.1",
    request_method: "POST",
    payload_format: "FORM_DATA",
    username_field: "username",
    password_field: "password",
    real_time_credentials: [
      { "username": "admin", "password": "" },
      { "username": "admin", "password": "admin" },
      { "username": "admin", "password": "password" }
    ]
  },
  {
    brand: "ASUS",
    mac_prefix: "F07960",
    gateway_ip: "192.168.50.1",
    requires_unique_sticker_password: false,
    target_login_url: "http://192.168.50.1",
    request_method: "POST",
    payload_format: "FORM_DATA",
    username_field: "username",
    password_field: "password",
    real_time_credentials: [
      { "username": "admin", "password": "admin" },
      { "username": "admin", "password": "password" }
    ]
  },
  {
    brand: "TP-Link Archer Series (Modern)",
    mac_prefix: "74DA38",
    gateway_ip: "192.168.1.1",
    requires_unique_sticker_password: true,
    target_login_url: null,
    request_method: null,
    payload_format: null,
    username_field: null,
    password_field: null,
    real_time_credentials: []
  },
  {
    brand: "Tenda Nova Series (Modern)",
    mac_prefix: "502AB0",
    gateway_ip: "192.168.5.1",
    requires_unique_sticker_password: true,
    target_login_url: null,
    request_method: null,
    payload_format: null,
    username_field: null,
    password_field: null,
    real_time_credentials: []
  }
];

// Initialize and seed database if necessary
async function initDatabase() {
    try {
        await db.query(`
            CREATE TABLE IF NOT EXISTS router_brand_profiles (
                id                               UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                brand                            TEXT NOT NULL,
                mac_prefix                       VARCHAR(6) UNIQUE,
                gateway_ip                       TEXT,
                requires_unique_sticker_password BOOLEAN NOT NULL DEFAULT FALSE,
                target_login_url                 TEXT,
                request_method                   TEXT DEFAULT 'POST',
                payload_format                   TEXT DEFAULT 'FORM_DATA',
                username_field                   TEXT DEFAULT 'username',
                password_field                   TEXT DEFAULT 'password',
                real_time_credentials            JSONB DEFAULT '[]'::jsonb,
                created_at                       TIMESTAMPTZ DEFAULT NOW()
            );
        `);
        const countRes = await db.query('SELECT COUNT(*)::int as count FROM router_brand_profiles');
        if (countRes.rows[0].count === 0) {
            for (const profile of DEFAULT_PROFILES) {
                await db.query(`
                    INSERT INTO router_brand_profiles 
                    (brand, mac_prefix, gateway_ip, requires_unique_sticker_password, target_login_url, request_method, payload_format, username_field, password_field, real_time_credentials)
                    VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
                    ON CONFLICT (mac_prefix) DO NOTHING
                `, [
                    profile.brand,
                    profile.mac_prefix,
                    profile.gateway_ip,
                    profile.requires_unique_sticker_password,
                    profile.target_login_url,
                    profile.request_method || 'POST',
                    profile.payload_format || 'FORM_DATA',
                    profile.username_field || 'username',
                    profile.password_field || 'password',
                    JSON.stringify(profile.real_time_credentials || [])
                ]);
            }
            console.log('Router profiles table initialized and seeded.');
        }
    } catch (err) {
        console.warn('Database initialization warning (likely extension or permission issue):', err.message);
    }
}

// Call database initialization
initDatabase();

function matchByBanner(title, server, vendor) {
    const text = `${title} ${server} ${vendor}`.toLowerCase();
    
    // 1. Check modern series first
    if (/archer|deco|wifi\s*6|ax1800|ax3200|ax5400|tp-link.*smart/i.test(text)) {
        return {
            brand: "TP-Link Archer Series (Modern)",
            requires_unique_sticker_password: true,
            execution_profile: null,
            real_time_credentials: []
        };
    }
    if (/nighthawk|orbi/i.test(text)) {
        return {
            brand: "Netgear Nighthawk (Modern)",
            requires_unique_sticker_password: true,
            execution_profile: null,
            real_time_credentials: []
        };
    }
    if (/velop|atlas/i.test(text)) {
        return {
            brand: "Linksys Velop Series (Modern)",
            requires_unique_sticker_password: true,
            execution_profile: null,
            real_time_credentials: []
        };
    }
    if (/sticker.*password|unique.*key|printed.*sticker|randomized/i.test(text)) {
        return {
            brand: "Modern Series (Device Unique Key)",
            requires_unique_sticker_password: true,
            execution_profile: null,
            real_time_credentials: []
        };
    }

    // 2. Check legacy brands
    if (/tplink|tp-link/i.test(text)) {
        return {
            brand: "TP-Link",
            requires_unique_sticker_password: false,
            execution_profile: {
                target_login_url: "http://192.168.0.1",
                request_method: "POST",
                payload_format: "FORM_DATA",
                username_field: "username",
                password_field: "password"
            },
            real_time_credentials: [
                { "username": "admin", "password": "admin" },
                { "username": "admin", "password": "" }
            ]
        };
    }
    if (/tenda/i.test(text)) {
        return {
            brand: "Tenda",
            requires_unique_sticker_password: false,
            execution_profile: {
                target_login_url: "http://192.168.0.1",
                request_method: "POST",
                payload_format: "FORM_DATA",
                username_field: "username",
                password_field: "password"
            },
            real_time_credentials: [
                { "username": "", "password": "admin" },
                { "username": "admin", "password": "admin" }
            ]
        };
    }
    if (/huawei|echolife|hlink/i.test(text)) {
        return {
            brand: "Huawei",
            requires_unique_sticker_password: false,
            execution_profile: {
                target_login_url: "http://192.168.8.1",
                request_method: "POST",
                payload_format: "FORM_DATA",
                username_field: "username",
                password_field: "password"
            },
            real_time_credentials: [
                { "username": "admin", "password": "admin" },
                { "username": "telecomadmin", "password": "admintelecom" },
                { "username": "root", "password": "admin" }
            ]
        };
    }
    if (/netgear/i.test(text)) {
        return {
            brand: "Netgear",
            requires_unique_sticker_password: false,
            execution_profile: {
                target_login_url: "http://192.168.1.1",
                request_method: "POST",
                payload_format: "FORM_DATA",
                username_field: "username",
                password_field: "password"
            },
            real_time_credentials: [
                { "username": "admin", "password": "password" }
            ]
        };
    }
    if (/asus|rt-ax|rt-ac/i.test(text)) {
        return {
            brand: "ASUS",
            requires_unique_sticker_password: false,
            execution_profile: {
                target_login_url: "http://192.168.50.1",
                request_method: "POST",
                payload_format: "FORM_DATA",
                username_field: "username",
                password_field: "password"
            },
            real_time_credentials: [
                { "username": "admin", "password": "admin" },
                { "username": "admin", "password": "password" }
            ]
        };
    }
    if (/mikrotik|routeros/i.test(text)) {
        return {
            brand: "MikroTik",
            requires_unique_sticker_password: false,
            execution_profile: {
                target_login_url: "http://192.168.88.1",
                request_method: "POST",
                payload_format: "FORM_DATA",
                username_field: "username",
                password_field: "password"
            },
            real_time_credentials: [
                { "username": "admin", "password": "" }
            ]
        };
    }
    if (/d-link|dlink/i.test(text)) {
        return {
            brand: "D-Link",
            requires_unique_sticker_password: false,
            execution_profile: {
                target_login_url: "http://192.168.0.1",
                request_method: "POST",
                payload_format: "FORM_DATA",
                username_field: "username",
                password_field: "password"
            },
            real_time_credentials: [
                { "username": "admin", "password": "" },
                { "username": "admin", "password": "admin" },
                { "username": "admin", "password": "password" }
            ]
        };
    }
    if (/cisco|linksys/i.test(text)) {
        return {
            brand: "Cisco / Linksys",
            requires_unique_sticker_password: false,
            execution_profile: {
                target_login_url: "http://192.168.1.1",
                request_method: "POST",
                payload_format: "FORM_DATA",
                username_field: "username",
                password_field: "password"
            },
            real_time_credentials: [
                { "username": "admin", "password": "admin" },
                { "username": "admin", "password": "password" }
            ]
        };
    }
    if (/zte/i.test(text)) {
        return {
            brand: "ZTE",
            requires_unique_sticker_password: false,
            execution_profile: {
                target_login_url: "http://192.168.1.1",
                request_method: "POST",
                payload_format: "FORM_DATA",
                username_field: "username",
                password_field: "password"
            },
            real_time_credentials: [
                { "username": "admin", "password": "admin" }
            ]
        };
    }
    return null;
}

// POST /api/v1/router/fingerprint
router.post('/api/v1/router/fingerprint', async (req, res) => {
    const { gateway_ip, mac_prefix, http_banner } = req.body;
    
    // Step 1: Raw String Clean
    const cleanMac = mac_prefix ? mac_prefix.replace(/[^a-fA-F0-9]/g, '').toUpperCase().slice(0, 6) : '';
    
    // Step 2: Real-Time Header Audit (HTTP Banner Match)
    let matchedProfile = null;
    if (http_banner && (http_banner.page_title || http_banner.server_header || http_banner.meta_vendor)) {
        matchedProfile = matchByBanner(
            http_banner.page_title || '',
            http_banner.server_header || '',
            http_banner.meta_vendor || ''
        );
    }
    
    // Step 3: MAC/OUI Registry Query
    if (!matchedProfile && cleanMac) {
        try {
            const dbResult = await db.query(
                'SELECT * FROM router_brand_profiles WHERE mac_prefix = $1 LIMIT 1',
                [cleanMac]
            );
            if (dbResult.rows.length > 0) {
                const row = dbResult.rows[0];
                matchedProfile = {
                    brand: row.brand,
                    requires_unique_sticker_password: row.requires_unique_sticker_password,
                    execution_profile: row.requires_unique_sticker_password ? null : {
                        target_login_url: row.target_login_url || `http://${gateway_ip || '192.168.0.1'}`,
                        request_method: row.request_method || 'POST',
                        payload_format: row.payload_format || 'FORM_DATA',
                        username_field: row.username_field || 'username',
                        password_field: row.password_field || 'password'
                    },
                    real_time_credentials: row.real_time_credentials || []
                };
            }
        } catch (err) {
            console.error('Database MAC lookup failed, falling back to static:', err.message);
        }
        
        if (!matchedProfile) {
            const staticMatch = DEFAULT_PROFILES.find(p => p.mac_prefix === cleanMac);
            if (staticMatch) {
                matchedProfile = {
                    brand: staticMatch.brand,
                    requires_unique_sticker_password: staticMatch.requires_unique_sticker_password,
                    execution_profile: staticMatch.requires_unique_sticker_password ? null : {
                        target_login_url: staticMatch.target_login_url || `http://${gateway_ip || '192.168.0.1'}`,
                        request_method: staticMatch.request_method,
                        payload_format: staticMatch.payload_format,
                        username_field: staticMatch.username_field,
                        password_field: staticMatch.password_field
                    },
                    real_time_credentials: staticMatch.real_time_credentials
                };
            }
        }
    }
    
    // Step 4: Gateway Mapping Query
    if (!matchedProfile && gateway_ip) {
        try {
            const dbResult = await db.query(
                'SELECT * FROM router_brand_profiles WHERE gateway_ip = $1 LIMIT 1',
                [gateway_ip]
            );
            if (dbResult.rows.length > 0) {
                const row = dbResult.rows[0];
                matchedProfile = {
                    brand: row.brand,
                    requires_unique_sticker_password: row.requires_unique_sticker_password,
                    execution_profile: row.requires_unique_sticker_password ? null : {
                        target_login_url: row.target_login_url || `http://${gateway_ip}`,
                        request_method: row.request_method || 'POST',
                        payload_format: row.payload_format || 'FORM_DATA',
                        username_field: row.username_field || 'username',
                        password_field: row.password_field || 'password'
                    },
                    real_time_credentials: row.real_time_credentials || []
                };
            }
        } catch (err) {
            console.error('Database IP lookup failed, falling back to static:', err.message);
        }
        
        if (!matchedProfile) {
            const staticMatch = DEFAULT_PROFILES.find(p => p.gateway_ip === gateway_ip);
            if (staticMatch) {
                matchedProfile = {
                    brand: staticMatch.brand,
                    requires_unique_sticker_password: staticMatch.requires_unique_sticker_password,
                    execution_profile: staticMatch.requires_unique_sticker_password ? null : {
                        target_login_url: staticMatch.target_login_url || `http://${gateway_ip}`,
                        request_method: staticMatch.request_method,
                        payload_format: staticMatch.payload_format,
                        username_field: staticMatch.username_field,
                        password_field: staticMatch.password_field
                    },
                    real_time_credentials: staticMatch.real_time_credentials
                };
            }
        }
    }
    
    // Step 5: Fallback unidentified profile to prevent lockouts/obsolete crashes
    if (!matchedProfile) {
        matchedProfile = {
            brand: "Other / Not Sure",
            requires_unique_sticker_password: false,
            execution_profile: {
                target_login_url: `http://${gateway_ip || '192.168.0.1'}`,
                request_method: "POST",
                payload_format: "FORM_DATA",
                username_field: "username",
                password_field: "password"
            },
            real_time_credentials: [
                { "username": "admin", "password": "admin" },
                { "username": "admin", "password": "" }
            ]
        };
    }
    
    return res.json({
        status: "identified",
        brand: matchedProfile.brand,
        requires_unique_sticker_password: matchedProfile.requires_unique_sticker_password,
        execution_profile: matchedProfile.execution_profile,
        real_time_credentials: matchedProfile.real_time_credentials
    });
});

module.exports = router;
