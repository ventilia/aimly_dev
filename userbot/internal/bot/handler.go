package bot

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"time"
	"userbot/internal/db"
	"userbot/internal/model"

	"go.uber.org/zap"
)

type MessageHandler struct {
	database          *db.DB
	log               *zap.Logger
	springBootURL     string
	internalAPISecret string
	httpClient        *http.Client
	groq              *GroqMatcher

	mu       sync.RWMutex
	keywords map[int64][]string

	msgBufMu  sync.Mutex
	msgBuffer map[int64][]string
}

const maxChatBuffers = 10_000

func NewMessageHandler(
	ctx context.Context,
	database *db.DB,
	log *zap.Logger,
	springBootURL string,
	internalAPISecret string,
	groqAPIKey string,
) (*MessageHandler, error) {

	kw, err := database.GetAllActiveKeywords(ctx)
	if err != nil {
		return nil, fmt.Errorf("не удалось загрузить ключевые слова: %w", err)
	}

	for userID, keywords := range kw {
		normalized := make([]string, 0, len(keywords))
		for _, k := range keywords {
			k = strings.TrimSpace(strings.ToLower(k))
			if k != "" {
				normalized = append(normalized, k)
			}
		}
		kw[userID] = normalized
	}

	totalKw := 0
	for _, v := range kw {
		totalKw += len(v)
	}

	log.Info("ключевые слова загружены в память",
		zap.Int("пользователей", len(kw)),
		zap.Int("ключевых_слов", totalKw),
	)

	return &MessageHandler{
		database:          database,
		log:               log,
		springBootURL:     springBootURL,
		internalAPISecret: internalAPISecret,
		keywords:          kw,
		msgBuffer:         make(map[int64][]string),
		groq:              NewGroqMatcher(groqAPIKey, log.Named("groq-matcher")),
		httpClient: &http.Client{
			Timeout: 15 * time.Second,
		},
	}, nil
}

func (h *MessageHandler) Database() *db.DB {
	return h.database
}

func (h *MessageHandler) KeywordsCount() int {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return len(h.keywords)
}

func (h *MessageHandler) UpdateKeywordsWithVariants(ctx context.Context, userID int64, originals []string, allVariants []string) error {

	normOriginals := make([]string, 0, len(originals))
	for _, kw := range originals {
		kw = strings.TrimSpace(strings.ToLower(kw))
		if kw != "" {
			normOriginals = append(normOriginals, kw)
		}
	}

	inMemory := allVariants
	if len(inMemory) == 0 {
		inMemory = originals
	}
	normInMemory := make([]string, 0, len(inMemory))
	seen := make(map[string]struct{})
	for _, kw := range inMemory {
		kw = strings.TrimSpace(strings.ToLower(kw))
		if kw != "" {
			if _, ok := seen[kw]; !ok {
				seen[kw] = struct{}{}
				normInMemory = append(normInMemory, kw)
			}
		}
	}

	h.mu.Lock()
	h.keywords[userID] = normInMemory
	h.mu.Unlock()

	h.log.Info("ключевые слова обновлены",
		zap.Int64("userID", userID),
		zap.Int("originals_db", len(normOriginals)),
		zap.Int("in_memory_total", len(normInMemory)),
	)

	return h.database.ReplaceKeywords(ctx, userID, normOriginals)
}

func (h *MessageHandler) UpdateKeywords(ctx context.Context, userID int64, keywords []string) error {
	return h.UpdateKeywordsWithVariants(ctx, userID, keywords, keywords)
}

func (h *MessageHandler) findMatchExact(userID int64, text string) string {
	h.mu.RLock()
	defer h.mu.RUnlock()

	kws, ok := h.keywords[userID]
	if !ok {
		return ""
	}

	lowerText := strings.ToLower(text)

	for _, kw := range kws {
		if strings.Contains(lowerText, kw) {
			return kw
		}
	}
	return ""
}

func (h *MessageHandler) findMatch(ctx context.Context, userID int64, text string) string {
	if matched := h.findMatchExact(userID, text); matched != "" {
		return matched
	}

	h.mu.RLock()
	kws := make([]string, len(h.keywords[userID]))
	copy(kws, h.keywords[userID])
	h.mu.RUnlock()

	if len(kws) == 0 {
		return ""
	}

	matchCtx, cancel := context.WithTimeout(ctx, 6*time.Second)
	defer cancel()

	return h.groq.SemanticMatch(matchCtx, text, kws)
}

func sanitize(s string) string {
	return strings.ReplaceAll(strings.TrimSpace(s), "\u0000", "")
}

func (h *MessageHandler) addToMsgBuffer(chatTgID int64, text string) {
	h.msgBufMu.Lock()
	defer h.msgBufMu.Unlock()

	if _, ok := h.msgBuffer[chatTgID]; !ok {
		if len(h.msgBuffer) >= maxChatBuffers {
			for k := range h.msgBuffer {
				delete(h.msgBuffer, k)
				break
			}
		}
		h.msgBuffer[chatTgID] = make([]string, 0, 5)
	}

	h.msgBuffer[chatTgID] = append(h.msgBuffer[chatTgID], text)
	if len(h.msgBuffer[chatTgID]) > 5 {
		h.msgBuffer[chatTgID] = h.msgBuffer[chatTgID][1:]
	}
}

func (h *MessageHandler) HandleMessage(
	ctx context.Context,
	chatTgID int64,
	chatLink string,
	chatTitle string,
	messageID int64,
	authorName string,
	authorUsername string,
	text string,
) {

	dbCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	subs, err := h.database.GetSubscriptionsByChat(dbCtx, chatTgID)
	cancel()

	if err != nil {
		h.log.Warn("не удалось получить подписки для чата — пробуем по chatLink",
			zap.Int64("chatTgID", chatTgID),
			zap.Error(err),
		)

		dbCtx2, cancel2 := context.WithTimeout(ctx, 5*time.Second)
		subs, err = h.database.GetSubscriptionsByChatLink(dbCtx2, chatLink)
		cancel2()
		if err != nil {
			h.log.Warn("не удалось получить подписки ни по chatTgID ни по chatLink",
				zap.Int64("chatTgID", chatTgID),
				zap.String("chatLink", chatLink),
				zap.Error(err),
			)
			h.addToMsgBuffer(chatTgID, text)
			return
		}
	}

	if len(subs) == 0 {

		h.addToMsgBuffer(chatTgID, text)
		return
	}

	for _, sub := range subs {
		userID := sub.UserID

		h.mu.RLock()
		_, hasKeywords := h.keywords[userID]
		h.mu.RUnlock()

		if !hasKeywords {

			continue
		}

		matched := h.findMatch(ctx, userID, text)
		if matched == "" {
			continue
		}

		h.msgBufMu.Lock()
		rawBuf := make([]string, len(h.msgBuffer[chatTgID]))
		copy(rawBuf, h.msgBuffer[chatTgID])
		h.msgBufMu.Unlock()

		var contextMsgs []string
		if len(rawBuf) > 0 {
			filterCtx, filterCancel := context.WithTimeout(ctx, 5*time.Second)
			contextMsgs = h.groq.FilterRelevantContext(filterCtx, text, rawBuf)
			filterCancel()
		}

		messageLink := buildMessageLink(chatLink, chatTgID, messageID)

		msg := model.IncomingMessage{
			UserID:          userID,
			ChatLink:        chatLink,
			ChatTitle:       sanitize(chatTitle),
			ChatTgID:        chatTgID,
			TgMessageID:     messageID,
			AuthorName:      sanitize(authorName),
			AuthorUsername:  sanitize(authorUsername),
			MessageText:     sanitize(text),
			MessageLink:     messageLink,
			MatchedKeyword:  matched,
			ContextMessages: contextMsgs,
		}

		h.log.Info("🎯 совпадение в реальном времени",
			zap.Int64("userID", userID),
			zap.String("keyword", matched),
			zap.String("chat", chatTitle),
			zap.Int64("messageID", messageID),
			zap.Int("contextFiltered", len(contextMsgs)),
		)

		if err := h.sendToSpringBoot(ctx, msg); err != nil {
			h.log.Warn("не удалось отправить сообщение",
				zap.Error(err),
				zap.Int64("messageID", messageID),
			)
		}
	}

	h.addToMsgBuffer(chatTgID, text)
}

func buildMessageLink(chatLink string, chatTgID int64, messageID int64) string {
	chatLink = strings.TrimPrefix(chatLink, "https://t.me/")
	chatLink = strings.TrimPrefix(chatLink, "http://t.me/")
	chatLink = strings.TrimPrefix(chatLink, "t.me/")
	chatLink = strings.TrimPrefix(chatLink, "@")

	if strings.HasPrefix(chatLink, "+") || strings.HasPrefix(chatLink, "joinchat/") {
		chatLink = ""
	}

	if chatLink != "" {
		return fmt.Sprintf("https://t.me/%s/%d", chatLink, messageID)
	}

	if chatTgID != 0 {
		id := chatTgID
		if id < 0 {
			// Убираем префикс -100 для приватных супергрупп
			id = -id
			if id > 1000000000000 {
				id = id - 1000000000000
			}
		}
		return fmt.Sprintf("https://t.me/c/%d/%d", id, messageID)
	}

	return ""
}

func (h *MessageHandler) ProcessHistoricalMessages(
	ctx context.Context,
	userID int64,
	chatTgID int64,
	chatLink string,
	chatTitle string,
	messages []HistoricalMessage,
) {
	h.log.Info("обработка исторических сообщений",
		zap.Int64("userID", userID),
		zap.Int64("chatTgID", chatTgID),
		zap.Int("count", len(messages)),
	)

	for i, hm := range messages {
		matched := h.findMatch(ctx, userID, hm.Text)
		if matched == "" {
			h.addToMsgBuffer(chatTgID, hm.Text)
			continue
		}

		rawContext := make([]string, 0, 5)
		start := i - 5
		if start < 0 {
			start = 0
		}
		for _, prev := range messages[start:i] {
			rawContext = append(rawContext, sanitize(prev.Text))
		}

		var contextMsgs []string
		if len(rawContext) > 0 {
			filterCtx, filterCancel := context.WithTimeout(ctx, 5*time.Second)
			contextMsgs = h.groq.FilterRelevantContext(filterCtx, hm.Text, rawContext)
			filterCancel()
		}

		h.log.Info("🎯 совпадение в истории",
			zap.Int64("userID", userID),
			zap.String("keyword", matched),
			zap.String("chat", chatTitle),
			zap.Int64("messageID", hm.MessageID),
			zap.Int("contextFiltered", len(contextMsgs)),
		)

		messageLink := hm.MessageLink
		if messageLink == "" {
			messageLink = buildMessageLink(chatLink, chatTgID, hm.MessageID)
		}

		msg := model.IncomingMessage{
			UserID:          userID,
			ChatLink:        chatLink,
			ChatTitle:       sanitize(chatTitle),
			ChatTgID:        chatTgID,
			TgMessageID:     hm.MessageID,
			AuthorName:      sanitize(hm.AuthorName),
			AuthorUsername:  sanitize(hm.AuthorUsername),
			MessageText:     sanitize(hm.Text),
			MessageLink:     messageLink,
			MatchedKeyword:  matched,
			ContextMessages: contextMsgs,
		}

		if err := h.sendToSpringBoot(ctx, msg); err != nil {
			h.log.Warn("не удалось отправить историческое сообщение",
				zap.Error(err),
				zap.Int64("messageID", hm.MessageID),
			)
		}

		h.addToMsgBuffer(chatTgID, hm.Text)
	}
}

func (h *MessageHandler) sendToSpringBoot(ctx context.Context, msg model.IncomingMessage) error {
	body, err := json.Marshal(msg)
	if err != nil {
		return fmt.Errorf("marshal: %w", err)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, h.springBootURL+"/internal/messages/incoming", bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("new request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Internal-Secret", h.internalAPISecret)

	resp, err := h.httpClient.Do(req)
	if err != nil {
		return fmt.Errorf("http do: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 300 {
		return fmt.Errorf("spring boot returned %d", resp.StatusCode)
	}
	return nil
}
