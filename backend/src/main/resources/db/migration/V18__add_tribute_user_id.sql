
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS tribute_user_id VARCHAR(50) NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_users_tribute_user_id
    ON users (tribute_user_id)
    WHERE tribute_user_id IS NOT NULL;