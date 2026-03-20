package bot

import (
	"context"
	"fmt"
	"sync"
	"time"
	"userbot/internal/db"
	"userbot/internal/model"

	"github.com/google/uuid"
	"go.uber.org/zap"
)

type Pool struct {
	sessions []*Session
	handler  *MessageHandler
	database *db.DB
	log      *zap.Logger
	apiID    int
	apiHash  string
	mu       sync.RWMutex

	appCtx context.Context

	pendingMu  sync.Mutex
	pendingReg map[string]*PendingRegistration
}

type PendingRegistration struct {
	ID      string
	Phone   string
	APIID   int
	APIHash string
	Session *Session
	CodeCh  chan string
	PwdCh   chan string
	Cancel  context.CancelFunc
}

func NewPool(database *db.DB, handler *MessageHandler, log *zap.Logger, apiID int, apiHash string) *Pool {
	return &Pool{
		database:   database,
		handler:    handler,
		log:        log,
		apiID:      apiID,
		apiHash:    apiHash,
		pendingReg: make(map[string]*PendingRegistration),
	}
}

func (p *Pool) Start(ctx context.Context) error {
	p.appCtx = ctx

	sessions, err := p.database.GetActiveSessions(ctx)
	if err != nil {
		return fmt.Errorf("не удалось загрузить сессии: %w", err)
	}

	if len(sessions) == 0 {
		p.log.Warn("в БД нет ни одного userbot-аккаунта",
			zap.String("action", "добавьте через админ-панель /api/v1/admin/userbot/sessions"),
		)
		<-ctx.Done()
		return nil
	}

	p.log.Info("загружены userbot-аккаунты", zap.Int("count", len(sessions)))

	p.mu.Lock()
	for _, meta := range sessions {
		sess := NewSession(meta, p.handler, p.database, p.log, p.apiID, p.apiHash)
		p.sessions = append(p.sessions, sess)
		go func(s *Session) {
			if err := s.Start(ctx); err != nil {
				p.log.Error("ошибка запуска сессии", zap.Int64("id", s.Meta().ID), zap.Error(err))
			}
		}(sess)
	}
	p.mu.Unlock()

	go p.recoverSubscriptions(ctx)
	return nil
}

func (p *Pool) recoverSubscriptions(ctx context.Context) {
	select {
	case <-time.After(15 * time.Second):
	case <-ctx.Done():
		return
	}

	p.log.Info("начинаем восстановление подписок после перезапуска")

	subs, err := p.database.GetActiveSubscriptions(ctx)
	if err != nil {
		p.log.Error("не удалось получить активные подписки для восстановления", zap.Error(err))
		return
	}

	if len(subs) == 0 {
		p.log.Info("активных подписок для восстановления нет")
		return
	}

	recovered, skipped, errors := 0, 0, 0

	for _, sub := range subs {
		select {
		case <-ctx.Done():
			return
		default:
		}

		if sub.ChatTgID != 0 && p.GetSessionByChat(sub.ChatTgID) != nil {
			skipped++
			continue
		}

		sess := p.getOrAssignSession(ctx, sub)
		if sess == nil {
			errors++
			continue
		}

		joinCtx, cancel := context.WithTimeout(ctx, 45*time.Second)
		chatTgID, title, err := sess.JoinChat(joinCtx, sub.ChatLink)
		cancel()

		if err != nil {
			p.log.Error("не удалось восстановить подписку",
				zap.Int64("userID", sub.UserID), zap.String("chatLink", sub.ChatLink), zap.Error(err))
			errors++
		} else {
			if chatTgID != 0 {
				updateCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
				_ = p.database.UpdateSubscriptionChatID(updateCtx, sess.Meta().ID, sub.ChatLink, chatTgID, title)
				cancel()
			}
			recovered++
		}
		time.Sleep(2 * time.Second)
	}

	p.log.Info("восстановление подписок завершено",
		zap.Int("recovered", recovered), zap.Int("skipped", skipped), zap.Int("errors", errors))
}

func (p *Pool) getOrAssignSession(ctx context.Context, sub model.ChatSubscription) *Session {
	p.mu.RLock()
	defer p.mu.RUnlock()

	if len(p.sessions) == 0 {
		return nil
	}

	if sub.SessionID != nil && *sub.SessionID != 0 {
		for _, s := range p.sessions {
			if s.Meta().ID == *sub.SessionID {
				return s
			}
		}
	}

	var best *Session
	for _, s := range p.sessions {
		if best == nil || s.ChatCount() < best.ChatCount() {
			best = s
		}
	}
	return best
}

func (p *Pool) StartRegistration(_ context.Context, phone string, apiID int, apiHash string) (string, error) {
	tempID := uuid.New().String()
	meta := model.UserbotSession{Phone: phone}
	sess := NewSession(meta, p.handler, p.database, p.log, apiID, apiHash)
	codeCh := make(chan string, 1)
	pwdCh := make(chan string, 1)
	regCtx, regCancel := context.WithTimeout(context.Background(), 10*time.Minute)

	pending := &PendingRegistration{
		ID: tempID, Phone: phone, APIID: apiID, APIHash: apiHash,
		Session: sess, CodeCh: codeCh, PwdCh: pwdCh, Cancel: regCancel,
	}

	p.pendingMu.Lock()
	p.pendingReg[tempID] = pending
	p.pendingMu.Unlock()

	go sess.StartRegistration(regCtx, codeCh, pwdCh)
	p.log.Info("начата регистрация сессии", zap.String("tempID", tempID), zap.String("phone", phone))
	return tempID, nil
}

func (p *Pool) ConfirmRegistration(ctx context.Context, tempID, code, password string) (int64, string, error) {
	p.pendingMu.Lock()
	pending, ok := p.pendingReg[tempID]
	if !ok {
		p.pendingMu.Unlock()
		return 0, "", fmt.Errorf("сессия не найдена")
	}
	p.pendingMu.Unlock()

	select {
	case pending.CodeCh <- code:
	case <-ctx.Done():
		return 0, "", fmt.Errorf("таймаут отправки кода")
	}

	if password != "" {
		select {
		case pending.PwdCh <- password:
		case <-ctx.Done():
			return 0, "", fmt.Errorf("таймаут отправки пароля")
		}
	}

	dbID, err := pending.Session.WaitRegistration(ctx)
	p.pendingMu.Lock()
	delete(p.pendingReg, tempID)
	p.pendingMu.Unlock()
	pending.Cancel()

	if err != nil {
		return 0, "", err
	}

	newMeta := model.UserbotSession{
		ID: dbID, Phone: pending.Phone,
		StringSession: pending.Session.meta.StringSession, IsActive: true,
	}
	newSess := NewSession(newMeta, p.handler, p.database, p.log, pending.APIID, pending.APIHash)

	p.mu.Lock()
	p.sessions = append(p.sessions, newSess)
	p.mu.Unlock()

	go func() {
		if err := newSess.Start(p.appCtx); err != nil {
			p.log.Error("ошибка запуска новой сессии", zap.Int64("dbID", dbID), zap.Error(err))
		}
	}()

	go p.recoverAfterNewSession(p.appCtx, dbID, newSess)

	p.log.Info("✅ новая сессия добавлена в пул", zap.Int64("dbID", dbID), zap.String("phone", pending.Phone))
	return dbID, pending.Phone, nil
}

// RemoveSession — жёсткое удаление сессии.
// Выходит из всех чатов, убирает из пула в памяти, деактивирует в БД.
func (p *Pool) RemoveSession(ctx context.Context, sessionID int64) error {
	p.mu.Lock()
	idx := -1
	for i, s := range p.sessions {
		if s.Meta().ID == sessionID {
			idx = i
			break
		}
	}

	if idx == -1 {
		p.mu.Unlock()
		p.log.Warn("RemoveSession: сессии нет в пуле, деактивируем только в БД", zap.Int64("sessionID", sessionID))
		dbCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
		defer cancel()
		return p.database.DeactivateSession(dbCtx, sessionID)
	}

	sess := p.sessions[idx]
	p.sessions = append(p.sessions[:idx], p.sessions[idx+1:]...)
	p.mu.Unlock()

	p.log.Info("RemoveSession: убираем сессию",
		zap.Int64("sessionID", sessionID), zap.String("phone", sess.Meta().Phone))

	// Собираем ID чатов которые слушала эта сессия
	sess.mu.RLock()
	chatIDs := make([]int64, 0, len(sess.watchedChats))
	for chatID := range sess.watchedChats {
		chatIDs = append(chatIDs, chatID)
	}
	sess.mu.RUnlock()

	// Выходим из чатов
	for _, chatID := range chatIDs {
		leaveCtx, cancel := context.WithTimeout(ctx, 15*time.Second)
		if err := sess.LeaveChat(leaveCtx, chatID); err != nil {
			p.log.Warn("RemoveSession: не удалось выйти из чата",
				zap.Int64("chatID", chatID), zap.Error(err))
		}
		cancel()
		time.Sleep(300 * time.Millisecond)
	}

	// Деактивируем в БД
	dbCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	if err := p.database.DeactivateSession(dbCtx, sessionID); err != nil {
		p.log.Error("RemoveSession: ошибка деактивации в БД", zap.Int64("sessionID", sessionID), zap.Error(err))
		return err
	}

	p.log.Info("✅ RemoveSession: сессия удалена",
		zap.Int64("sessionID", sessionID), zap.Int("chatCount", len(chatIDs)))
	return nil
}

// recoverAfterNewSession — восстанавливает подписки и ключевые слова после добавления новой сессии.
func (p *Pool) recoverAfterNewSession(ctx context.Context, sessionID int64, sess *Session) {
	p.log.Info("recoverAfterNewSession: ждём авторизации", zap.Int64("sessionID", sessionID))

	select {
	case <-time.After(20 * time.Second):
	case <-ctx.Done():
		return
	}

	reloadCtx, reloadCancel := context.WithTimeout(ctx, 15*time.Second)
	defer reloadCancel()

	if err := p.handler.ReloadKeywordsFromDB(reloadCtx); err != nil {
		p.log.Warn("recoverAfterNewSession: не удалось перезагрузить ключевые слова", zap.Error(err))
	} else {
		p.log.Info("recoverAfterNewSession: ключевые слова перезагружены из БД")
	}

	subs, err := p.database.GetActiveSubscriptions(ctx)
	if err != nil {
		p.log.Error("recoverAfterNewSession: не удалось получить подписки", zap.Error(err))
		return
	}

	joined, skipped := 0, 0
	for _, sub := range subs {
		select {
		case <-ctx.Done():
			return
		default:
		}

		if sub.ChatTgID != 0 && p.GetSessionByChat(sub.ChatTgID) != nil {
			skipped++
			continue
		}

		joinCtx, cancel := context.WithTimeout(ctx, 45*time.Second)
		chatTgID, title, joinErr := sess.JoinChat(joinCtx, sub.ChatLink)
		cancel()

		if joinErr != nil {
			p.log.Warn("recoverAfterNewSession: не удалось вступить в чат",
				zap.Int64("userID", sub.UserID), zap.String("chatLink", sub.ChatLink), zap.Error(joinErr))
			continue
		}

		if chatTgID != 0 {
			updateCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
			_ = p.database.UpdateSubscriptionChatID(updateCtx, sessionID, sub.ChatLink, chatTgID, title)
			cancel()
		}
		joined++
		time.Sleep(2 * time.Second)
	}

	p.log.Info("recoverAfterNewSession: завершено",
		zap.Int("joined", joined), zap.Int("skipped", skipped), zap.Int64("sessionID", sessionID))
}

func (p *Pool) Stats() []model.SessionStats {
	p.mu.RLock()
	defer p.mu.RUnlock()
	stats := make([]model.SessionStats, 0, len(p.sessions))
	for _, s := range p.sessions {
		stats = append(stats, s.Stats())
	}
	return stats
}

func (p *Pool) GetSessionForUser(_ int64) *Session {
	p.mu.RLock()
	defer p.mu.RUnlock()
	if len(p.sessions) == 0 {
		return nil
	}
	var best *Session
	for _, s := range p.sessions {
		if best == nil || s.ChatCount() < best.ChatCount() {
			best = s
		}
	}
	return best
}

func (p *Pool) GetSessionByChat(chatTgID int64) *Session {
	p.mu.RLock()
	defer p.mu.RUnlock()
	for _, s := range p.sessions {
		if s.IsWatchingChat(chatTgID) {
			return s
		}
	}
	return nil
}
