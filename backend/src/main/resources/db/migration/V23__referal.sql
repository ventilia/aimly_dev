-- bonusDaysBuffer в subscription_expiry
ALTER TABLE subscription_expiry ADD COLUMN bonus_days_buffer INT NOT NULL DEFAULT 0;

-- Новые таблицы для рефералов
CREATE TABLE referral_codes (
                                id         BIGSERIAL PRIMARY KEY,
                                user_id    BIGINT NOT NULL UNIQUE REFERENCES users(id),
                                code       VARCHAR(16) NOT NULL UNIQUE,
                                created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_referral_code_code ON referral_codes(code);

CREATE TABLE referral_activations (
                                      id               BIGSERIAL PRIMARY KEY,
                                      referrer_id      BIGINT NOT NULL REFERENCES users(id),
                                      referee_id       BIGINT NOT NULL UNIQUE REFERENCES users(id),
                                      bonus_granted    BOOLEAN NOT NULL DEFAULT false,
                                      created_at       TIMESTAMP NOT NULL DEFAULT now(),
                                      bonus_granted_at TIMESTAMP
);
CREATE INDEX idx_ref_act_referrer ON referral_activations(referrer_id);