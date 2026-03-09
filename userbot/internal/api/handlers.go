package api

import (
	"context"
	"encoding/json"
	"net/http"
	"time"
	"userbot/internal/bot"
	"userbot/internal/model"

	"go.uber.org/zap"
)

type Handlers struct {
	pool    *bot.Pool
	handler *bot.MessageHandler
	log     *zap.Logger
	secret  string
}

func NewHandlers(pool *bot.Pool, handler *bot.MessageHandler, log *zap.Logger, secret string) *Handlers {
	return &Handlers{
		pool:    pool,
		handler: handler,
		log:     log,
		secret:  secret,
	}
}

func (h *Handlers) checkSecret(r *http.Request) bool {
	if h.secret == "" {
		return true
	}
	return r.Header.Get("X-Internal-Secret") == h.secret
}

// Health — GET /health
func (h *Handlers) Health(w http.ResponseWriter, r *http.Request) {
	jsonOK(w, map[string]string{"status": "ok"})
}

// Stats — GET /stats
func (h *Handlers) Stats(w http.ResponseWriter, r *http.Request) {
	perSession := h.pool.Stats()

	totalChats := 0
	for _, s := range perSession {
		totalChats += s.ChatCount
	}

	resp := model.StatsResponse{
		Sessions:     len(perSession),
		TotalChats:   totalChats,
		TotalUsers:   h.handler.KeywordsCount(),
		KeywordCount: h.handler.KeywordsCount(),
		PerSession:   perSession,
	}

	jsonOK(w, resp)
}

// RegisterSession — POST /admin/sessions/register
func (h *Handlers) RegisterSession(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Phone   string `json:"phone"`
		ApiID   int    `json:"apiId"`
		ApiHash string `json:"apiHash"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		jsonError(w, "невалидный JSON", http.StatusBadRequest)
		return
	}
	if req.Phone == "" || req.ApiID == 0 || req.ApiHash == "" {
		jsonError(w, "phone, apiId и apiHash обязательны", http.StatusBadRequest)
		return
	}

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	tempID, err := h.pool.StartRegistration(ctx, req.Phone, req.ApiID, req.ApiHash)
	if err != nil {
		h.log.Error("StartRegistration ошибка", zap.String("phone", req.Phone), zap.Error(err))
		jsonError(w, "ошибка запуска регистрации: "+err.Error(), http.StatusInternalServerError)
		return
	}

	h.log.Info("регистрация сессии начата", zap.String("phone", req.Phone), zap.String("tempID", tempID))
	jsonOK(w, map[string]string{"tempId": tempID})
}

// ConfirmSession — POST /admin/sessions/confirm
func (h *Handlers) ConfirmSession(w http.ResponseWriter, r *http.Request) {
	var req struct {
		TempID   string `json:"tempId"`
		Code     string `json:"code"`
		Password string `json:"password"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		jsonError(w, "невалидный JSON", http.StatusBadRequest)
		return
	}
	if req.TempID == "" || req.Code == "" {
		jsonError(w, "tempId и code обязательны", http.StatusBadRequest)
		return
	}

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()

	dbID, phone, err := h.pool.ConfirmRegistration(ctx, req.TempID, req.Code, req.Password)
	if err != nil {
		h.log.Error("ConfirmRegistration ошибка", zap.String("tempID", req.TempID), zap.Error(err))
		jsonError(w, "ошибка подтверждения: "+err.Error(), http.StatusInternalServerError)
		return
	}

	h.log.Info("сессия подтверждена и добавлена в пул", zap.Int64("sessionID", dbID), zap.String("phone", phone))
	jsonOK(w, map[string]any{"sessionId": dbID, "phone": phone})
}

// LeaveChat — POST /admin/chats/leave
func (h *Handlers) LeaveChat(w http.ResponseWriter, r *http.Request) {
	var req struct {
		ChatTgID int64 `json:"chatTgId"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		jsonError(w, "невалидный JSON", http.StatusBadRequest)
		return
	}
	if req.ChatTgID == 0 {
		jsonError(w, "chatTgId обязателен", http.StatusBadRequest)
		return
	}

	session := h.pool.GetSessionByChat(req.ChatTgID)
	if session == nil {
		jsonError(w, "сессия для данного чата не найдена", http.StatusNotFound)
		return
	}

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	if err := session.LeaveChat(ctx, req.ChatTgID); err != nil {
		h.log.Error("LeaveChat ошибка", zap.Int64("chatTgID", req.ChatTgID), zap.Error(err))
		jsonError(w, "ошибка выхода из чата: "+err.Error(), http.StatusInternalServerError)
		return
	}

	h.log.Info("вышли из чата", zap.Int64("chatTgID", req.ChatTgID))
	jsonOK(w, map[string]string{"status": "ok"})
}

func (h *Handlers) SubscribeChat(w http.ResponseWriter, r *http.Request) {
	if !h.checkSecret(r) {
		jsonError(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	var req struct {
		UserID      int64    `json:"userId"`
		ChatLink    string   `json:"chatLink"`
		Keywords    []string `json:"keywords"`
		AllVariants []string `json:"allVariants"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		jsonError(w, "невалидный JSON", http.StatusBadRequest)
		return
	}

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	session := h.pool.GetSessionForUser(req.UserID)
	if session == nil {
		jsonError(w, "нет доступной сессии", http.StatusServiceUnavailable)
		return
	}

	chatTgID, title, err := session.JoinChat(ctx, req.ChatLink)
	if err != nil {
		h.log.Error("не удалось вступить в чат",
			zap.Int64("userID", req.UserID),
			zap.String("chatLink", req.ChatLink),
			zap.Error(err),
		)
		jsonError(w, "ошибка вступления: "+err.Error(), http.StatusInternalServerError)
		return
	}

	sessID := session.Meta().ID
	if _, err := h.handler.Database().AddSubscription(ctx, &model.ChatSubscription{
		UserID:    req.UserID,
		ChatLink:  req.ChatLink,
		ChatTitle: title,
		ChatTgID:  chatTgID,
		SessionID: &sessID,
	}); err != nil {
		h.log.Error("не удалось сохранить подписку", zap.Error(err))
		jsonError(w, "ошибка БД: "+err.Error(), http.StatusInternalServerError)
		return
	}

	if err := h.handler.UpdateKeywordsWithVariants(ctx, req.UserID, req.Keywords, req.AllVariants); err != nil {
		h.log.Warn("не удалось обновить ключевые слова", zap.Error(err))
	}

	if chatTgID != 0 {
		go session.ProcessChatHistory(
			context.Background(),
			req.UserID,
			chatTgID,
			req.ChatLink,
			title,
		)
	} else {
		h.log.Info("chatTgID=0 — история запустится после join из очереди",
			zap.Int64("userID", req.UserID),
			zap.String("chatLink", req.ChatLink),
		)
	}

	h.log.Info("пользователь подписан на чат",
		zap.Int64("userID", req.UserID),
		zap.String("chatLink", req.ChatLink),
		zap.Int64("chatTgID", chatTgID),
	)

	jsonOK(w, map[string]any{
		"status":   "ok",
		"chatTgID": chatTgID,
		"title":    title,
	})
}

func (h *Handlers) UnsubscribeChat(w http.ResponseWriter, r *http.Request) {
	if !h.checkSecret(r) {
		jsonError(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	var req struct {
		UserID   int64  `json:"userId"`
		ChatLink string `json:"chatLink"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		jsonError(w, "невалидный JSON", http.StatusBadRequest)
		return
	}

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	if err := h.handler.Database().RemoveSubscription(ctx, req.UserID, req.ChatLink); err != nil {
		jsonError(w, "ошибка БД: "+err.Error(), http.StatusInternalServerError)
		return
	}

	h.log.Info("пользователь отписан от чата",
		zap.Int64("userID", req.UserID),
		zap.String("chatLink", req.ChatLink),
	)

	jsonOK(w, map[string]any{"status": "ok"})
}

func (h *Handlers) UpdateKeywords(w http.ResponseWriter, r *http.Request) {
	if !h.checkSecret(r) {
		jsonError(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	var req struct {
		UserID      int64    `json:"userId"`
		Keywords    []string `json:"keywords"`
		AllVariants []string `json:"allVariants"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		jsonError(w, "невалидный JSON", http.StatusBadRequest)
		return
	}

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	if err := h.handler.UpdateKeywordsWithVariants(ctx, req.UserID, req.Keywords, req.AllVariants); err != nil {
		h.log.Error("UpdateKeywordsWithVariants завершился ошибкой",
			zap.Int64("userID", req.UserID),
			zap.Error(err),
		)
		jsonError(w, "не удалось обновить ключевые слова: "+err.Error(), http.StatusInternalServerError)
		return
	}

	jsonOK(w, map[string]any{
		"status":       "updated",
		"userId":       req.UserID,
		"keywordCount": len(req.Keywords),
		"variantCount": len(req.AllVariants),
	})
}

func jsonOK(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(v)
}

func jsonError(w http.ResponseWriter, msg string, code int) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(map[string]string{"error": msg})
}
