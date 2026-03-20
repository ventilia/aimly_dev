CREATE TABLE tribute_webhook_events (
                                        id               BIGSERIAL    PRIMARY KEY,
                                        event_key        VARCHAR(512) NOT NULL,
                                        event_name       VARCHAR(64)  NOT NULL,
                                        telegram_user_id BIGINT,
                                        processed_at     TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_tribute_event_key ON tribute_webhook_events (event_key);

COMMENT ON TABLE tribute_webhook_events IS
    'Журнал обработанных вебхуков Tribute — используется для идемпотентности (защита от повторной обработки).';