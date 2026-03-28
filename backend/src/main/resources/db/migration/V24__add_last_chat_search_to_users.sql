-- Добавляем поля для хранения последнего поиска чатов пользователя

ALTER TABLE users
    ADD COLUMN last_chat_search_query TEXT,
    ADD COLUMN last_chat_search_type VARCHAR(50),
    ADD COLUMN last_chat_search_at TIMESTAMP,
    ADD COLUMN last_chat_search_queries TEXT,
    ADD COLUMN last_chat_search_results TEXT;