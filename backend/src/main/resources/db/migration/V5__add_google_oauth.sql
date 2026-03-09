-- миграция: добавляем google_id для OAuth2 и делаем password nullable
-- положи этот файл в: src/main/resources/db/migration/
-- имя файла: V{следующий_номер}__add_google_oauth.sql
-- например: V4__add_google_oauth.sql (замени 4 на твой следующий номер)

-- добавляем колонку google_id (nullable, уникальная)
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS google_id VARCHAR(255) UNIQUE;

-- делаем password nullable для Google-пользователей (у них пароля нет)
ALTER TABLE users
    ALTER COLUMN password DROP NOT NULL;