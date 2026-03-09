-- V9__trial_support.sql
-- статус TRIAL хранится в users.subscription_status (VARCHAR) — схема уже поддерживает.
-- добавляем индекс для быстрой выборки trial-пользователей с истёкшим пробным периодом.

CREATE INDEX IF NOT EXISTS idx_users_sub_status ON users(subscription_status)
    WHERE subscription_status IS NOT NULL;

-- комментарий к колонке (документация)
COMMENT ON COLUMN users.subscription_status IS
    'Статус подписки: ACTIVE | TRIAL | INACTIVE | null. '
    'TRIAL — пробный период 7 дней, выдаётся автоматически при первой привязке Telegram.';