-- ─── Оценки лидов пользователем ──────────────────────────────────────────────
-- Хранит персональные оценки: пользователь пометил лид как "хороший" или "не лид".
-- Используется для персонального дообучения AI-фильтрации.
CREATE TABLE IF NOT EXISTS lead_feedbacks (
                                              id              BIGSERIAL PRIMARY KEY,
                                              user_id         BIGINT        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    lead_id         BIGINT        NOT NULL REFERENCES leads(id) ON DELETE CASCADE,
    rating          VARCHAR(10)   NOT NULL CHECK (rating IN ('GOOD', 'BAD')),
    -- Снимок текста лида (первые 200 символов) — нужен для промпта ИИ,
    -- чтобы не делать join к leads при каждом запросе.
    message_snippet TEXT          NOT NULL DEFAULT '',
    matched_keyword VARCHAR(200)  NOT NULL DEFAULT '',
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT lead_feedbacks_user_lead_uq UNIQUE (user_id, lead_id)
    );

CREATE INDEX IF NOT EXISTS lead_feedbacks_user_idx
    ON lead_feedbacks (user_id, created_at DESC);

-- ─── Очередь ожидающих уведомлений ──────────────────────────────────────────
-- Когда у пользователя есть неоцененный лид, новые лиды ставятся в очередь.
-- После оценки — первый из очереди отправляется в TG автоматически.
CREATE TABLE IF NOT EXISTS pending_lead_notifications (
                                                          id          BIGSERIAL PRIMARY KEY,
                                                          user_id     BIGINT        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    lead_id     BIGINT        NOT NULL REFERENCES leads(id) ON DELETE CASCADE,
    -- Весь payload для отправки уведомления сохраняется здесь,
    -- чтобы не делать дополнительный запрос к leads при отправке.
    chat_title      TEXT          NOT NULL DEFAULT '',
    message_preview TEXT          NOT NULL DEFAULT '',
    message_link    VARCHAR(500)  NOT NULL DEFAULT '',
    keyword         VARCHAR(200)  NOT NULL DEFAULT '',
    author_username VARCHAR(200)  NOT NULL DEFAULT '',
    author_name     VARCHAR(200)  NOT NULL DEFAULT '',
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT pending_lead_notif_user_lead_uq UNIQUE (user_id, lead_id)
    );

CREATE INDEX IF NOT EXISTS pending_lead_notif_user_created_idx
    ON pending_lead_notifications (user_id, created_at ASC);

-- ─── Признак отправки TG-уведомления на лиде ─────────────────────────────────
-- tg_notified_at — момент когда уведомление реально ушло пользователю.
-- NULL = уведомление ещё не отправлено (лид в очереди или AI ещё думает).
ALTER TABLE leads
    ADD COLUMN IF NOT EXISTS tg_notified_at TIMESTAMPTZ NULL;

CREATE INDEX IF NOT EXISTS leads_tg_notified_user_idx
    ON leads (user_id, tg_notified_at)
    WHERE tg_notified_at IS NOT NULL;