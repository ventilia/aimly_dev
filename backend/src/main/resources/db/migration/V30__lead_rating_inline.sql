-- ─── Оценка лида прямо в таблице leads ───────────────────────────────────────
-- Перемещаем rating и момент оценки из lead_feedbacks в leads.
-- lead_feedbacks сохраняется для истории, но приложение работает с полями leads.

ALTER TABLE leads
    ADD COLUMN IF NOT EXISTS user_rating VARCHAR(10)  NULL CHECK (user_rating IN ('GOOD', 'BAD')),
    ADD COLUMN IF NOT EXISTS rating_at   TIMESTAMPTZ  NULL;

-- Мигрируем существующие оценки из lead_feedbacks → leads (без потери данных)
UPDATE leads l
SET    user_rating = f.rating,
       rating_at   = f.created_at
    FROM   lead_feedbacks f
WHERE  f.lead_id = l.id;

-- Индекс для getFeedbackExamplesForPrompt (последние оценённые лиды пользователя)
CREATE INDEX IF NOT EXISTS leads_user_rating_idx
    ON leads (user_id, rating_at DESC)
    WHERE user_rating IS NOT NULL;

-- ─── ID nudge-сообщения в Telegram ───────────────────────────────────────────
-- Нужен чтобы удалить nudge после того как пользователь оценил лид.
ALTER TABLE pending_lead_notifications
    ADD COLUMN IF NOT EXISTS nudge_tg_message_id BIGINT NULL;