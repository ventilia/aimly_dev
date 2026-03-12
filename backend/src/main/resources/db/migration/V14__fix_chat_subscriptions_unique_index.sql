-- V13: исправление уникального индекса chat_subscriptions для поддержки ON CONFLICT
--
-- Проблема: userbot делает INSERT ... ON CONFLICT (user_id, chat_link) DO UPDATE,
-- но в V12 был создан partial unique index только для is_active = true.
-- PostgreSQL поддерживает ON CONFLICT только с полным (non-partial) уникальным индексом
-- или с partial индексом и точным совпадением WHERE-условия в запросе.
-- Userbot не передаёт WHERE is_active = true, поэтому нужен обычный уникальный индекс.

-- Удаляем partial index из V12
DROP INDEX IF EXISTS chat_subscriptions_user_id_chat_link_active_key;

-- Создаём обычный уникальный индекс по (user_id, chat_link) — без WHERE
-- Он позволяет ON CONFLICT (user_id, chat_link) DO UPDATE работать корректно
CREATE UNIQUE INDEX IF NOT EXISTS chat_subscriptions_user_id_chat_link_key
    ON chat_subscriptions (user_id, chat_link);