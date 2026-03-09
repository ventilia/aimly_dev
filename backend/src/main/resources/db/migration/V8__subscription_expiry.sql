-- V8__subscription_expiry.sql

CREATE TABLE IF NOT EXISTS subscription_expiry (
                                                   id                BIGSERIAL   PRIMARY KEY,
                                                   user_id           BIGINT      NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    expires_at        TIMESTAMP   NOT NULL,
    notified_renewal  BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMP   NOT NULL DEFAULT NOW()
    );

CREATE INDEX IF NOT EXISTS idx_sub_expiry_expires ON subscription_expiry(expires_at);
CREATE INDEX IF NOT EXISTS idx_sub_expiry_user    ON subscription_expiry(user_id);