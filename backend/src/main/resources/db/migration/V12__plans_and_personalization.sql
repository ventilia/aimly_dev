-- V12: переименование планов, персонализация бизнеса, исправление удаления keywords
-- MINIMUM (старый START без AI) → новый дешёвый тариф
-- START (старый BUSINESS с AI)  → переименован из BUSINESS

-- 1. Переименуем планы: старый START → MINIMUM, старый BUSINESS → START
UPDATE users SET subscription_plan = 'MINIMUM' WHERE subscription_plan = 'START';
UPDATE users SET subscription_plan = 'START'   WHERE subscription_plan = 'BUSINESS';

-- 2. Добавляем поле бизнес-контекста для персонализации (только для тарифа START)
ALTER TABLE users ADD COLUMN IF NOT EXISTS business_context TEXT;

-- 3. Исправляем баг с удалением keyword: добавляем физическое удаление через уникальный partial index.
--    Старый constraint "keywords_user_id_keyword_key" не учитывал is_active,
--    поэтому после soft-delete нельзя было добавить то же слово снова.
--
--    Решение: удаляем старый unique constraint и заменяем его partial unique index,
--    который применяется только к АКТИВНЫМ записям (is_active = true).

ALTER TABLE keywords DROP CONSTRAINT IF EXISTS keywords_user_id_keyword_key;

CREATE UNIQUE INDEX IF NOT EXISTS keywords_user_id_keyword_active_key
    ON keywords (user_id, keyword)
    WHERE is_active = true;

-- То же самое для chat_subscriptions на случай аналогичного бага
ALTER TABLE chat_subscriptions DROP CONSTRAINT IF EXISTS chat_subscriptions_user_id_chat_link_key;

CREATE UNIQUE INDEX IF NOT EXISTS chat_subscriptions_user_id_chat_link_active_key
    ON chat_subscriptions (user_id, chat_link)
    WHERE is_active = true;