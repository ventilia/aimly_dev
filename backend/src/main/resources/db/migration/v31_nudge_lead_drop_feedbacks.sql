-- ─── Резервное хранение ID nudge-сообщения прямо на лиде ───────────────────
-- Используется для удаления nudge если pending-запись уже была удалена
-- (race condition: оценка пришла одновременно из бота и с фронта).
-- После удаления nudge из Telegram поле очищается (NULL).
ALTER TABLE leads
    ADD COLUMN IF NOT EXISTS nudge_tg_message_id BIGINT NULL;

-- ─── Удаление устаревшей таблицы lead_feedbacks ──────────────────────────────
-- Данные уже перенесены в leads.user_rating и leads.rating_at (V30).
-- Таблица больше не используется приложением.
DROP TABLE IF EXISTS lead_feedbacks;