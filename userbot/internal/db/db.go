package db

import (
	"context"
	"fmt"
	"strings"
	"time"

	"userbot/internal/model"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"go.uber.org/zap"
)

type DB struct {
	pool *pgxpool.Pool
	log  *zap.Logger
}

func New(ctx context.Context, dsn string, log *zap.Logger) (*DB, error) {
	cfg, err := pgxpool.ParseConfig(dsn)
	if err != nil {
		return nil, fmt.Errorf("pgxpool.ParseConfig: %w", err)
	}

	cfg.MaxConns = 10
	cfg.MinConns = 2
	cfg.MaxConnLifetime = 30 * time.Minute
	cfg.MaxConnIdleTime = 5 * time.Minute
	cfg.HealthCheckPeriod = 1 * time.Minute

	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		return nil, fmt.Errorf("pgxpool.NewWithConfig: %w", err)
	}

	pingCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	if err := pool.Ping(pingCtx); err != nil {
		return nil, fmt.Errorf("PostgreSQL недоступна: %w", err)
	}

	log.Info("подключились к PostgreSQL",
		zap.Int32("maxConns", cfg.MaxConns),
		zap.Int32("minConns", cfg.MinConns),
	)
	return &DB{pool: pool, log: log}, nil
}

func (d *DB) Close() { d.pool.Close() }

func (d *DB) Stats() *pgxpool.Stat { return d.pool.Stat() }

func tableNotExist(err error) bool {
	if err == nil {
		return false
	}
	s := err.Error()
	return strings.Contains(s, "42P01") || strings.Contains(s, "does not exist")
}

// ─── Userbot сессии ───────────────────────────────────────────────────────────

func (d *DB) GetActiveSessions(ctx context.Context) ([]model.UserbotSession, error) {
	rows, err := d.pool.Query(ctx, `
		SELECT id, phone, string_session, is_active, chat_count, created_at
		FROM userbot_sessions
		WHERE is_active = true
		ORDER BY chat_count ASC
	`)
	if err != nil {
		if tableNotExist(err) {
			d.log.Warn("таблица userbot_sessions не найдена — примените миграцию V4 через Spring Boot")
			return nil, nil
		}
		return nil, fmt.Errorf("GetActiveSessions: %w", err)
	}
	defer rows.Close()

	var out []model.UserbotSession
	for rows.Next() {
		var s model.UserbotSession
		if err := rows.Scan(&s.ID, &s.Phone, &s.StringSession, &s.IsActive, &s.ChatCount, &s.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, s)
	}
	return out, rows.Err()
}

func (d *DB) SaveSession(ctx context.Context, phone, stringSession string) (int64, error) {
	var id int64
	err := d.pool.QueryRow(ctx, `
		INSERT INTO userbot_sessions (phone, string_session, is_active, chat_count)
		VALUES ($1, $2, true, 0)
		ON CONFLICT (phone) DO UPDATE
		    SET string_session = EXCLUDED.string_session, is_active = true
		RETURNING id
	`, phone, stringSession).Scan(&id)
	return id, err
}

func (d *DB) UpdateStringSession(ctx context.Context, sessionID int64, data string) error {
	_, err := d.pool.Exec(ctx,
		`UPDATE userbot_sessions SET string_session = $1 WHERE id = $2`,
		data, sessionID,
	)
	return err
}

func (d *DB) IncrementChatCount(ctx context.Context, sessionID int64) error {
	_, err := d.pool.Exec(ctx,
		`UPDATE userbot_sessions SET chat_count = chat_count + 1 WHERE id = $1`,
		sessionID,
	)
	return err
}

func (d *DB) DecrementChatCount(ctx context.Context, sessionID int64) error {
	_, err := d.pool.Exec(ctx,
		`UPDATE userbot_sessions SET chat_count = GREATEST(chat_count - 1, 0) WHERE id = $1`,
		sessionID,
	)
	return err
}

// ─── Подписки на чаты ────────────────────────────────────────────────────────

func (d *DB) GetActiveSubscriptions(ctx context.Context) ([]model.ChatSubscription, error) {
	rows, err := d.pool.Query(ctx, `
		SELECT id, user_id, chat_link, chat_title, chat_tg_id, session_id, is_active, created_at
		FROM chat_subscriptions
		WHERE is_active = true
		ORDER BY created_at ASC
	`)
	if err != nil {
		if tableNotExist(err) {
			d.log.Warn("таблица chat_subscriptions не найдена")
			return nil, nil
		}
		return nil, fmt.Errorf("GetActiveSubscriptions: %w", err)
	}
	defer rows.Close()

	var out []model.ChatSubscription
	for rows.Next() {
		var s model.ChatSubscription
		if err := rows.Scan(&s.ID, &s.UserID, &s.ChatLink, &s.ChatTitle, &s.ChatTgID, &s.SessionID, &s.IsActive, &s.CreatedAt); err != nil {
			return nil, fmt.Errorf("GetActiveSubscriptions scan: %w", err)
		}
		out = append(out, s)
	}
	return out, rows.Err()
}

func (d *DB) GetActiveSubscriptionsBySession(ctx context.Context, sessionID int64) ([]model.ChatSubscription, error) {
	rows, err := d.pool.Query(ctx, `
		SELECT id, user_id, chat_link, chat_title, chat_tg_id, session_id, is_active, created_at
		FROM chat_subscriptions
		WHERE session_id = $1 AND is_active = true
		ORDER BY created_at ASC
	`, sessionID)
	if err != nil {
		if tableNotExist(err) {
			d.log.Warn("таблица chat_subscriptions не найдена")
			return nil, nil
		}
		return nil, fmt.Errorf("GetActiveSubscriptionsBySession: %w", err)
	}
	defer rows.Close()

	var out []model.ChatSubscription
	for rows.Next() {
		var s model.ChatSubscription
		if err := rows.Scan(&s.ID, &s.UserID, &s.ChatLink, &s.ChatTitle, &s.ChatTgID, &s.SessionID, &s.IsActive, &s.CreatedAt); err != nil {
			return nil, fmt.Errorf("GetActiveSubscriptionsBySession scan: %w", err)
		}
		out = append(out, s)
	}
	return out, rows.Err()
}

func (d *DB) GetSubscriptionsByChatLink(ctx context.Context, chatLink string) ([]model.ChatSubscription, error) {
	rows, err := d.pool.Query(ctx, `
		SELECT id, user_id, chat_link, chat_title, chat_tg_id, session_id, is_active, created_at
		FROM chat_subscriptions
		WHERE chat_link = $1 AND is_active = true
	`, chatLink)
	if err != nil {
		if tableNotExist(err) {
			return nil, nil
		}
		return nil, fmt.Errorf("GetSubscriptionsByChatLink: %w", err)
	}
	defer rows.Close()

	var out []model.ChatSubscription
	for rows.Next() {
		var s model.ChatSubscription
		if err := rows.Scan(&s.ID, &s.UserID, &s.ChatLink, &s.ChatTitle, &s.ChatTgID, &s.SessionID, &s.IsActive, &s.CreatedAt); err != nil {
			return nil, fmt.Errorf("GetSubscriptionsByChatLink scan: %w", err)
		}
		out = append(out, s)
	}
	return out, rows.Err()
}

func (d *DB) AddSubscription(ctx context.Context, sub *model.ChatSubscription) (int64, error) {
	var id int64
	err := d.pool.QueryRow(ctx, `
		INSERT INTO chat_subscriptions (user_id, chat_link, chat_title, chat_tg_id, session_id, is_active)
		VALUES ($1, $2, $3, $4, $5, true)
		ON CONFLICT (user_id, chat_link) DO UPDATE
		    SET is_active  = true,
		        session_id = EXCLUDED.session_id,
		        chat_tg_id = COALESCE(EXCLUDED.chat_tg_id, chat_subscriptions.chat_tg_id),
		        chat_title = COALESCE(EXCLUDED.chat_title, chat_subscriptions.chat_title)
		RETURNING id
	`, sub.UserID, sub.ChatLink, sub.ChatTitle, sub.ChatTgID, sub.SessionID).Scan(&id)
	return id, err
}

func (d *DB) UpdateSubscriptionChatID(ctx context.Context, sessionID int64, chatLink string, chatTgID int64, chatTitle string) error {
	_, err := d.pool.Exec(ctx, `
		UPDATE chat_subscriptions
		SET chat_tg_id = $1, chat_title = $2
		WHERE session_id = $3 AND chat_link = $4 AND is_active = true
	`, chatTgID, chatTitle, sessionID, chatLink)
	return err
}

func (d *DB) UpdateSubscriptionSession(ctx context.Context, subscriptionID int64, sessionID int64) error {
	_, err := d.pool.Exec(ctx,
		`UPDATE chat_subscriptions SET session_id = $1 WHERE id = $2`,
		sessionID, subscriptionID,
	)
	return err
}

func (d *DB) RemoveSubscription(ctx context.Context, userID int64, chatLink string) error {
	_, err := d.pool.Exec(ctx,
		`UPDATE chat_subscriptions SET is_active = false WHERE user_id = $1 AND chat_link = $2`,
		userID, chatLink,
	)
	return err
}

func (d *DB) GetSubscriptionsByChat(ctx context.Context, chatTgID int64) ([]model.ChatSubscription, error) {
	rows, err := d.pool.Query(ctx, `
		SELECT id, user_id, chat_link, chat_title, chat_tg_id, session_id, is_active, created_at
		FROM chat_subscriptions
		WHERE chat_tg_id = $1 AND is_active = true
	`, chatTgID)
	if err != nil {
		if tableNotExist(err) {
			return nil, nil
		}
		return nil, err
	}
	defer rows.Close()

	var out []model.ChatSubscription
	for rows.Next() {
		var s model.ChatSubscription
		if err := rows.Scan(&s.ID, &s.UserID, &s.ChatLink, &s.ChatTitle, &s.ChatTgID, &s.SessionID, &s.IsActive, &s.CreatedAt); err != nil {
			return nil, fmt.Errorf("GetSubscriptionsByChat scan: %w", err)
		}
		out = append(out, s)
	}
	return out, rows.Err()
}

// ─── Ключевые слова ───────────────────────────────────────────────────────────

func (d *DB) GetAllActiveKeywords(ctx context.Context) (map[int64][]string, error) {
	rows, err := d.pool.Query(ctx,
		`SELECT user_id, keyword FROM keywords WHERE is_active = true ORDER BY user_id, keyword`,
	)
	if err != nil {
		if tableNotExist(err) {
			d.log.Warn("таблица keywords не найдена — сначала запустите Spring Boot (применит миграцию V4)")
			return make(map[int64][]string), nil
		}
		return nil, err
	}
	defer rows.Close()

	result := make(map[int64][]string)
	for rows.Next() {
		var userID int64
		var kw string
		if err := rows.Scan(&userID, &kw); err != nil {
			return nil, err
		}
		result[userID] = append(result[userID], kw)
	}

	d.log.Info("загружены ключевые слова из БД", zap.Int("пользователей", len(result)))
	return result, rows.Err()
}

func (d *DB) ReplaceKeywords(ctx context.Context, userID int64, keywords []string) error {
	d.log.Debug("ReplaceKeywords: начало транзакции",
		zap.Int64("userID", userID),
		zap.Int("count", len(keywords)),
	)

	tx, err := d.pool.BeginTx(ctx, pgx.TxOptions{
		IsoLevel:   pgx.ReadCommitted,
		AccessMode: pgx.ReadWrite,
	})
	if err != nil {
		return fmt.Errorf("BeginTx: %w", err)
	}
	defer func() {
		if err := tx.Rollback(ctx); err != nil && err != pgx.ErrTxClosed {
			d.log.Warn("ReplaceKeywords: rollback", zap.Error(err))
		}
	}()

	if _, err := tx.Exec(ctx,
		`UPDATE keywords SET is_active = false WHERE user_id = $1`,
		userID,
	); err != nil {
		return fmt.Errorf("деактивация старых ключевых слов: %w", err)
	}

	for _, kw := range keywords {
		kw = strings.TrimSpace(strings.ToLower(kw))
		if kw == "" {
			continue
		}
		if _, err := tx.Exec(ctx, `
			INSERT INTO keywords (user_id, keyword, is_active)
			VALUES ($1, $2, true)
			ON CONFLICT (user_id, keyword) DO UPDATE SET is_active = true
		`, userID, kw); err != nil {
			return fmt.Errorf("вставка ключевого слова %q: %w", kw, err)
		}
	}

	if err := tx.Commit(ctx); err != nil {
		return fmt.Errorf("Commit: %w", err)
	}

	d.log.Debug("ReplaceKeywords: транзакция завершена",
		zap.Int64("userID", userID),
		zap.Int("count", len(keywords)),
	)
	return nil
}
