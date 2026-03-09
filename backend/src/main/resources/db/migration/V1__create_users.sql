CREATE TABLE users (
                       id                  BIGSERIAL PRIMARY KEY,
                       email               VARCHAR(255) NOT NULL UNIQUE,
                       password            VARCHAR(255) NOT NULL,
                       first_name          VARCHAR(100),

                       telegram_id         BIGINT UNIQUE,
                       telegram_username   VARCHAR(100),
                       telegram_linked_at  TIMESTAMP,
                       email_verified      BOOLEAN NOT NULL DEFAULT FALSE,
                       is_active           BOOLEAN NOT NULL DEFAULT TRUE,
                       role                VARCHAR(20) NOT NULL DEFAULT 'USER',

                       created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
                       updated_at          TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_email        ON users(email);
CREATE INDEX idx_users_telegram_id  ON users(telegram_id);