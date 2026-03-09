-- V7__create_userbot_tables.sql

-- ─── Userbot аккаунты ─────────────────────────────────────────────────────────
-- Каждая строка = один Telegram аккаунт в пуле.
-- string_session — сериализованные MTProto ключи (~500 байт base64).
-- Авторизуется один раз через CLI, после работает автоматически.

CREATE TABLE IF NOT EXISTS userbot_sessions (
                                                id             BIGSERIAL    PRIMARY KEY,
                                                phone          VARCHAR(20)  NOT NULL UNIQUE,
    string_session TEXT         NOT NULL,
    is_active      BOOLEAN      NOT NULL DEFAULT TRUE,
    chat_count     INTEGER      NOT NULL DEFAULT 0,
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW()
    );

CREATE INDEX IF NOT EXISTS idx_userbot_sessions_active ON userbot_sessions(is_active);

-- ─── Подписки пользователей на чаты ──────────────────────────────────────────
-- Один пользователь → много чатов.
-- Один чат → много пользователей (каждый со своими keyword'ами).
-- session_id — какой userbot-аккаунт физически слушает этот чат.

CREATE TABLE IF NOT EXISTS chat_subscriptions (
                                                  id          BIGSERIAL    PRIMARY KEY,
                                                  user_id     BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    chat_link   VARCHAR(255) NOT NULL,                -- t.me/smm_russia
    chat_title  VARCHAR(255) NOT NULL DEFAULT '',     -- заполняется после вступления
    chat_tg_id  BIGINT       NOT NULL DEFAULT 0,      -- внутренний ID чата в Telegram
    session_id  BIGINT       REFERENCES userbot_sessions(id) ON DELETE SET NULL,
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),

    UNIQUE(user_id, chat_link)
    );

CREATE INDEX IF NOT EXISTS idx_chat_subscriptions_user    ON chat_subscriptions(user_id);
CREATE INDEX IF NOT EXISTS idx_chat_subscriptions_chat_id ON chat_subscriptions(chat_tg_id) WHERE is_active = TRUE;
CREATE INDEX IF NOT EXISTS idx_chat_subscriptions_active  ON chat_subscriptions(is_active);

-- ─── Ключевые слова ───────────────────────────────────────────────────────────
-- По ним Go-сервис фильтрует входящие сообщения.
-- UNIQUE(user_id, keyword) — нет дублей при ReplaceKeywords.

CREATE TABLE IF NOT EXISTS keywords (
                                        id         BIGSERIAL    PRIMARY KEY,
                                        user_id    BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    keyword    VARCHAR(255) NOT NULL,
    is_active  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW(),

    UNIQUE(user_id, keyword)
    );

CREATE INDEX IF NOT EXISTS idx_keywords_user   ON keywords(user_id);
CREATE INDEX IF NOT EXISTS idx_keywords_active ON keywords(user_id) WHERE is_active = TRUE;

-- ─── Лиды ────────────────────────────────────────────────────────────────────
-- Создаются Spring Boot при обработке POST /internal/messages/incoming.
-- UNIQUE(tg_message_id, tg_chat_id, user_id) — защита от дублей.

CREATE TABLE IF NOT EXISTS leads (
                                     id              BIGSERIAL    PRIMARY KEY,
                                     user_id         BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    subscription_id BIGINT       REFERENCES chat_subscriptions(id) ON DELETE SET NULL,

    tg_message_id   BIGINT       NOT NULL,
    tg_chat_id      BIGINT       NOT NULL,
    author_name     VARCHAR(255) NOT NULL DEFAULT '',
    author_username VARCHAR(255) NOT NULL DEFAULT '',
    message_text    TEXT         NOT NULL,
    message_link    VARCHAR(500) NOT NULL DEFAULT '',  -- https://t.me/chat/123
    matched_keyword VARCHAR(255) NOT NULL DEFAULT '',

    ai_valid        BOOLEAN,
    ai_reason       TEXT,

    -- NEW → VIEWED → REPLIED / IGNORED
    status          VARCHAR(20)  NOT NULL DEFAULT 'NEW',

    found_at        TIMESTAMP    NOT NULL DEFAULT NOW(),

    UNIQUE(tg_message_id, tg_chat_id, user_id)
    );

CREATE INDEX IF NOT EXISTS idx_leads_user_id  ON leads(user_id);
CREATE INDEX IF NOT EXISTS idx_leads_status   ON leads(user_id, status);
CREATE INDEX IF NOT EXISTS idx_leads_found_at ON leads(found_at DESC);
CREATE INDEX IF NOT EXISTS idx_leads_chat     ON leads(tg_chat_id);