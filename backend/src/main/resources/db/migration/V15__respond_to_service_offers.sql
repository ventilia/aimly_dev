-- Миграция: добавление поля respond_to_service_offers в таблицу users
-- Выполнить на БД (PostgreSQL)

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS respond_to_service_offers BOOLEAN NOT NULL DEFAULT FALSE;

-- Также добавить переменную tgstat.api-token в application.properties / .env:
-- tgstat.api-token=f689280073b8ffcdc81df6337db794fc