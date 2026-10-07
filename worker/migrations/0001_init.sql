-- One row per vending machine ("automat").
CREATE TABLE automats (
  id              TEXT PRIMARY KEY,
  name            TEXT NOT NULL,
  token_hash      TEXT UNIQUE,             -- SHA-256 of the phone's device token
  pair_code       TEXT UNIQUE,             -- one-time code shown as QR while pairing
  pair_expires    INTEGER,
  config          TEXT NOT NULL,           -- config.json, source of truth
  config_version  INTEGER NOT NULL DEFAULT 1,
  ntfy_topic      TEXT,
  offline_after   INTEGER NOT NULL DEFAULT 900,  -- seconds without heartbeat before alert
  status          TEXT,                    -- latest heartbeat body (JSON)
  status_hash     TEXT,
  last_seen       INTEGER,                 -- ms; written at most every 5 min unless status changes
  offline_alerted INTEGER NOT NULL DEFAULT 0,
  created         INTEGER NOT NULL
);

-- Admins who can see and manage the automats they are assigned to.
-- The owner (provisioning) account is not in this table; it lives in Worker secrets.
CREATE TABLE users (
  id       TEXT PRIMARY KEY,
  username TEXT NOT NULL UNIQUE,
  pw_hash  TEXT NOT NULL,
  created  INTEGER NOT NULL
);

CREATE TABLE automat_admins (
  automat_id TEXT NOT NULL REFERENCES automats(id) ON DELETE CASCADE,
  user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  PRIMARY KEY (automat_id, user_id)
);

-- Commands waiting to be picked up by the next heartbeat (delivered at most once).
CREATE TABLE commands (
  id         TEXT PRIMARY KEY,
  automat_id TEXT NOT NULL REFERENCES automats(id) ON DELETE CASCADE,
  body       TEXT NOT NULL,
  created    INTEGER NOT NULL
);
CREATE INDEX commands_by_automat ON commands(automat_id);

CREATE TABLE events (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  automat_id TEXT NOT NULL REFERENCES automats(id) ON DELETE CASCADE,
  ts         INTEGER NOT NULL,
  type       TEXT NOT NULL,
  body       TEXT NOT NULL
);
CREATE INDEX events_by_automat ON events(automat_id, ts);
CREATE INDEX events_by_ts ON events(ts);
