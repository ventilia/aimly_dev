ALTER TABLE users ADD COLUMN IF NOT EXISTS balance INTEGER NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS subscription_status VARCHAR(50) DEFAULT NULL;
ALTER TABLE users ADD COLUMN IF NOT EXISTS subscription_plan VARCHAR(50) DEFAULT NULL;
ALTER TABLE users ADD COLUMN IF NOT EXISTS leads_count INTEGER NOT NULL DEFAULT 0;


CREATE TABLE IF NOT EXISTS notifications (
                                             id          BIGSERIAL PRIMARY KEY,
                                             title       VARCHAR(255) NOT NULL,
    body        TEXT         NOT NULL,
    target      VARCHAR(20)  NOT NULL DEFAULT 'BOTH',  -- WEB, BOT, BOTH
    scheduled_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent        BOOLEAN      NOT NULL DEFAULT FALSE,
    sent_at     TIMESTAMP    DEFAULT NULL,
    created_by  BIGINT       NOT NULL REFERENCES users(id),
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
    );


CREATE TABLE IF NOT EXISTS user_notifications (
                                                  id              BIGSERIAL PRIMARY KEY,
                                                  user_id         BIGINT NOT NULL REFERENCES users(id),
    notification_id BIGINT NOT NULL REFERENCES notifications(id),
    read            BOOLEAN NOT NULL DEFAULT FALSE,
    read_at         TIMESTAMP DEFAULT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, notification_id)
    );

CREATE INDEX IF NOT EXISTS idx_user_notifications_user_id ON user_notifications(user_id);
CREATE INDEX IF NOT EXISTS idx_user_notifications_unread ON user_notifications(user_id, read) WHERE read = FALSE;
CREATE INDEX IF NOT EXISTS idx_notifications_scheduled ON notifications(scheduled_at, sent);