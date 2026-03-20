package db

import (
	"context"
	"fmt"

	"go.uber.org/zap"
)

// EnsureSchema создаёт все таблицы и индексы если их нет.
// Вызывается в db.New() при каждом запуске.
func (d *DB) EnsureSchema(ctx context.Context) error {
	d.log.Info("проверяем/создаём схему БД")

	statements := []struct {
		name string
		sql  string
	}{
		// ─── Userbot сессии ───────────────────────────────────────────────────
		{
			name: "userbot_sessions table",
			sql: `
CREATE TABLE IF NOT EXISTS userbot_sessions (
    id             BIGSERIAL PRIMARY KEY,
    phone          TEXT NOT NULL,
    string_session TEXT NOT NULL DEFAULT '',
    is_active      BOOLEAN NOT NULL DEFAULT true,
    chat_count     INT NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT userbot_sessions_phone_key UNIQUE (phone)
)`,
		},
		// ─── Подписки на чаты ─────────────────────────────────────────────────
		{
			name: "chat_subscriptions table",
			sql: `
CREATE TABLE IF NOT EXISTS chat_subscriptions (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL,
    session_id  BIGINT REFERENCES userbot_sessions(id) ON DELETE SET NULL,
    chat_link   TEXT NOT NULL,
    chat_title  TEXT NOT NULL DEFAULT '',
    chat_tg_id  BIGINT NOT NULL DEFAULT 0,
    is_active   BOOLEAN NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
)`,
		},
		{
			name: "chat_subscriptions user_id+chat_link unique index",
			sql: `
CREATE UNIQUE INDEX IF NOT EXISTS chat_subscriptions_user_chat_idx
    ON chat_subscriptions (user_id, chat_link)`,
		},
		{
			name: "chat_subscriptions chat_tg_id index",
			sql: `
CREATE INDEX IF NOT EXISTS chat_subscriptions_chat_tg_id_idx
    ON chat_subscriptions (chat_tg_id)
    WHERE chat_tg_id != 0`,
		},
		// ─── Ключевые слова ───────────────────────────────────────────────────
		// ИСПРАВЛЕНИЕ КРИТИЧЕСКОГО БАГА:
		// Код делает ON CONFLICT (user_id, keyword) DO UPDATE
		// Без уникального индекса → SQLSTATE 42P10 → 500 → слова не доходят.
		{
			name: "keywords table",
			sql: `
CREATE TABLE IF NOT EXISTS keywords (
    id        BIGSERIAL PRIMARY KEY,
    user_id   BIGINT NOT NULL,
    keyword   TEXT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
)`,
		},
		{
			name: "keywords (user_id, keyword) unique index — CRITICAL for ON CONFLICT",
			sql: `
CREATE UNIQUE INDEX IF NOT EXISTS keywords_user_keyword_idx
    ON keywords (user_id, keyword)`,
		},
		{
			name: "keywords user_id+is_active index",
			sql: `
CREATE INDEX IF NOT EXISTS keywords_user_active_idx
    ON keywords (user_id)
    WHERE is_active = true`,
		},
	}

	for _, stmt := range statements {
		if _, err := d.pool.Exec(ctx, stmt.sql); err != nil {
			return fmt.Errorf("EnsureSchema [%s]: %w", stmt.name, err)
		}
		d.log.Debug("схема OK", zap.String("объект", stmt.name))
	}

	d.log.Info("схема БД актуальна")
	return nil
}
