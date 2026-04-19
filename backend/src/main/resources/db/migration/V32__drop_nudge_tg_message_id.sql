-- ═══════════════════════════════════════════════════════════════════════
-- V32__drop_nudge_tg_message_id.sql
-- Удаление полей nudge_tg_message_id из обеих таблиц
-- (логика удаления TG-сообщений полностью убрана из кода)
-- ═══════════════════════════════════════════════════════════════════════

ALTER TABLE leads
DROP COLUMN IF EXISTS nudge_tg_message_id;

ALTER TABLE pending_lead_notifications
DROP COLUMN IF EXISTS nudge_tg_message_id;


-- ═══════════════════════════════════════════════════════════════════════
-- ОЧИСТКА БД ДЛЯ DEV-СРЕДЫ
-- Запускать вручную в psql или через DBeaver — НЕ миграция!
-- ═══════════════════════════════════════════════════════════════════════

-- Очистить все лиды и связанные данные:
TRUNCATE TABLE pending_lead_notifications CASCADE;
TRUNCATE TABLE leads CASCADE;

-- Если нужно сбросить счётчики автоинкремента:
-- ALTER SEQUENCE leads_id_seq RESTART WITH 1;
-- ALTER SEQUENCE pending_lead_notifications_id_seq RESTART WITH 1;