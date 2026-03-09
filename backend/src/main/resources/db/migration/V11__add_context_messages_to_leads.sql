-- V11__add_context_messages_to_leads.sql
-- Добавляет колонку context_messages в таблицу leads.
-- Хранит JSON-сериализованный список сообщений контекста (до 3 штук),
-- которые окружали лид-сообщение в момент его обнаружения.
-- NULL = лид создан до этой миграции (контекст не собирался).

ALTER TABLE leads ADD COLUMN IF NOT EXISTS context_messages TEXT;