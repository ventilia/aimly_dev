package db

import (
	"context"
	"fmt"

	"userbot/internal/model"
)

func (d *DB) GetSessionByID(ctx context.Context, id int64) (*model.UserbotSession, error) {
	var s model.UserbotSession
	err := d.pool.QueryRow(ctx, `
		SELECT id, phone, string_session, is_active, chat_count, created_at
		FROM userbot_sessions
		WHERE id = $1
	`, id).Scan(&s.ID, &s.Phone, &s.StringSession, &s.IsActive, &s.ChatCount, &s.CreatedAt)
	if err != nil {
		return nil, fmt.Errorf("GetSessionByID: %w", err)
	}
	return &s, nil
}

func (d *DB) DeactivateSession(ctx context.Context, sessionID int64) error {
	_, err := d.pool.Exec(ctx,
		`UPDATE userbot_sessions SET is_active = false WHERE id = $1`,
		sessionID,
	)
	return err
}
