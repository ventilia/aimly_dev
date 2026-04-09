-- Migration: add source and message_date to leads
-- Применять после деплоя новых Entities.kt / Leadservice.kt

-- Источник лида: LIVE (бот в реальном времени) или MANUAL_EXPORT (ручной импорт файла)
ALTER TABLE leads
    ADD COLUMN IF NOT EXISTS source VARCHAR(32) NOT NULL DEFAULT 'LIVE';

-- Реальная дата сообщения.
-- Для LIVE совпадает с found_at.
-- Для MANUAL_EXPORT — оригинальная дата из файла экспорта Telegram.
ALTER TABLE leads
    ADD COLUMN IF NOT EXISTS message_date TIMESTAMP;

-- Проставляем message_date = found_at для всех уже существующих лидов
UPDATE leads SET message_date = found_at WHERE message_date IS NULL;

-- Индекс для быстрой выборки лидов из экспорта
CREATE INDEX IF NOT EXISTS idx_leads_source ON leads (source);