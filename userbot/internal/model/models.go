package model

import "time"

type UserbotSession struct {
	ID            int64     `db:"id"`
	Phone         string    `db:"phone"`
	StringSession string    `db:"string_session"`
	IsActive      bool      `db:"is_active"`
	ChatCount     int       `db:"chat_count"`
	CreatedAt     time.Time `db:"created_at"`
}

type ChatSubscription struct {
	ID        int64  `db:"id"`
	UserID    int64  `db:"user_id"`
	ChatLink  string `db:"chat_link"`
	ChatTitle string `db:"chat_title"`
	ChatTgID  int64  `db:"chat_tg_id"`

	SessionID *int64    `db:"session_id"`
	IsActive  bool      `db:"is_active"`
	CreatedAt time.Time `db:"created_at"`
}

type Keyword struct {
	ID        int64     `db:"id"`
	UserID    int64     `db:"user_id"`
	Keyword   string    `db:"keyword"`
	IsActive  bool      `db:"is_active"`
	CreatedAt time.Time `db:"created_at"`
}

type IncomingMessage struct {
	UserID          int64    `json:"userId"`
	ChatLink        string   `json:"chatLink"`
	ChatTitle       string   `json:"chatTitle"`
	ChatTgID        int64    `json:"chatTgId"`
	TgMessageID     int64    `json:"tgMessageId"`
	AuthorName      string   `json:"authorName"`
	AuthorUsername  string   `json:"authorUsername"`
	MessageText     string   `json:"messageText"`
	MessageLink     string   `json:"messageLink"`
	MatchedKeyword  string   `json:"matchedKeyword"`
	ContextMessages []string `json:"contextMessages"`
	IsHistorical    bool     `json:"isHistorical"`
}

type SubscribeRequest struct {
	UserID   int64    `json:"userId"`
	ChatLink string   `json:"chatLink"`
	Keywords []string `json:"keywords"`
}

type UnsubscribeRequest struct {
	UserID   int64  `json:"userId"`
	ChatLink string `json:"chatLink"`
}

type ConfirmSessionRequest struct {
	SessionID string `json:"sessionId"`
	Code      string `json:"code"`
	Password  string `json:"password"`
}

type StatsResponse struct {
	Sessions     int            `json:"sessions"`
	TotalChats   int            `json:"totalChats"`
	TotalUsers   int            `json:"totalUsers"`
	KeywordCount int            `json:"keywordCount"`
	PerSession   []SessionStats `json:"perSession"`
}

type SessionStats struct {
	SessionID int64  `json:"sessionId"`
	Phone     string `json:"phone"`
	ChatCount int    `json:"chatCount"`
	Online    bool   `json:"online"`
}
