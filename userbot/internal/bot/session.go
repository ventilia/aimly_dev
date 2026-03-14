package bot

import (
	"context"
	"fmt"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"userbot/internal/db"
	"userbot/internal/model"

	"github.com/gotd/td/session"
	"github.com/gotd/td/telegram"
	"github.com/gotd/td/telegram/auth"
	"github.com/gotd/td/tg"
	"go.uber.org/zap"
)

type HistoricalMessage struct {
	MessageID      int64
	Text           string
	AuthorName     string
	AuthorUsername string
	Date           time.Time
	MessageLink    string
}

type Session struct {
	meta     model.UserbotSession
	handler  *MessageHandler
	database *db.DB
	log      *zap.Logger
	apiID    int
	apiHash  string

	client *telegram.Client
	api    *tg.Client

	mu           sync.RWMutex
	watchedChats map[int64]string // chatTgID -> chatLink

	joinQueue chan string

	regDone chan int64
	regErr  chan error

	// pendingSlots — количество слотов, забронированных pool.GetSessionForUser,
	// но ещё не подтверждённых через AddChat. Используется для предотвращения
	// перераспределения слотов при одновременных subscribe-запросах.
	pendingSlots int32 // accessed via sync/atomic
}

func NewSession(
	meta model.UserbotSession,
	handler *MessageHandler,
	database *db.DB,
	log *zap.Logger,
	apiID int,
	apiHash string,
) *Session {
	return &Session{
		meta:         meta,
		handler:      handler,
		database:     database,
		log:          log.With(zap.String("phone", meta.Phone), zap.Int64("sessionID", meta.ID)),
		apiID:        apiID,
		apiHash:      apiHash,
		watchedChats: make(map[int64]string),
		joinQueue:    make(chan string, 100),
		regDone:      make(chan int64, 1),
		regErr:       make(chan error, 1),
	}
}

func (s *Session) Meta() model.UserbotSession { return s.meta }

func (s *Session) Stats() model.SessionStats {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return model.SessionStats{
		SessionID: s.meta.ID,
		Phone:     s.meta.Phone,
		ChatCount: len(s.watchedChats),
		Online:    s.api != nil,
	}
}

// ChatCount возвращает фактическое количество отслеживаемых чатов.
func (s *Session) ChatCount() int {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return len(s.watchedChats)
}

// EffectiveCount возвращает ChatCount() + pendingSlots.
// Используется pool для корректного балансирования при одновременных subscribe-запросах.
func (s *Session) EffectiveCount() int {
	s.mu.RLock()
	actual := len(s.watchedChats)
	s.mu.RUnlock()
	return actual + int(atomic.LoadInt32(&s.pendingSlots))
}

// releaseSlot вызывается когда slot был зарезервирован, но chat не добавлен
// (ошибка join, chatTgID=0 и т.д.).
func (s *Session) releaseSlot() {
	if atomic.LoadInt32(&s.pendingSlots) > 0 {
		atomic.AddInt32(&s.pendingSlots, -1)
	}
}

func (s *Session) IsWatchingChat(chatTgID int64) bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	_, ok := s.watchedChats[chatTgID]
	return ok
}

// IsWatchingChatLink проверяет, слушает ли сессия чат по его ссылке.
func (s *Session) IsWatchingChatLink(chatLink string) bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, link := range s.watchedChats {
		if link == chatLink {
			return true
		}
	}
	return false
}

func (s *Session) Start(ctx context.Context) error {
	storage := &session.StorageMemory{}

	if s.meta.StringSession != "" {
		if err := storage.StoreSession(ctx, []byte(s.meta.StringSession)); err != nil {
			return fmt.Errorf("не удалось загрузить string session: %w", err)
		}
	}

	dispatcher := tg.NewUpdateDispatcher()
	dispatcher.OnNewMessage(s.onNewMessage)
	dispatcher.OnNewChannelMessage(s.onNewChannelMessage)

	s.client = telegram.NewClient(s.apiID, s.apiHash, telegram.Options{
		SessionStorage: storage,
		UpdateHandler:  dispatcher,
		Logger:         s.log.Named("gotd"),
	})

	s.log.Info("запускаем MTProto клиент")

	return s.client.Run(ctx, func(ctx context.Context) error {
		status, err := s.client.Auth().Status(ctx)
		if err != nil {
			return fmt.Errorf("ошибка проверки авторизации: %w", err)
		}
		if !status.Authorized {
			s.log.Warn("аккаунт не авторизован — нужна ручная авторизация")
			return fmt.Errorf("аккаунт %s не авторизован", s.meta.Phone)
		}

		s.api = tg.NewClient(s.client)
		s.log.Info("✅ аккаунт авторизован и слушает сообщения")

		restoreCtx, restoreCancel := context.WithTimeout(ctx, 10*time.Second)
		subs, subErr := s.database.GetActiveSubscriptionsBySession(restoreCtx, s.meta.ID)
		restoreCancel()
		if subErr == nil {
			for _, sub := range subs {
				if sub.ChatTgID != 0 {
					s.addChatInternal(sub.ChatTgID, sub.ChatLink)
				}
			}
			s.log.Info("восстановлены подписки чатов из БД", zap.Int("count", len(subs)))
		} else {
			s.log.Warn("не удалось восстановить подписки из БД", zap.Error(subErr))
		}

		go s.persistSession(ctx, storage)
		go s.processJoinQueue(ctx)

		<-ctx.Done()
		return ctx.Err()
	})
}

func (s *Session) StartRegistration(ctx context.Context, codeCh <-chan string, pwdCh <-chan string) {
	storage := &session.StorageMemory{}
	s.client = telegram.NewClient(s.apiID, s.apiHash, telegram.Options{
		SessionStorage: storage,
		Logger:         s.log.Named("gotd-reg"),
	})

	flow := auth.NewFlow(
		channelAuth{phone: s.meta.Phone, codeCh: codeCh, pwdCh: pwdCh},
		auth.SendCodeOptions{},
	)

	err := s.client.Run(ctx, func(ctx context.Context) error {
		if err := s.client.Auth().IfNecessary(ctx, flow); err != nil {
			return fmt.Errorf("авторизация: %w", err)
		}
		status, err := s.client.Auth().Status(ctx)
		if err != nil {
			return fmt.Errorf("статус: %w", err)
		}
		if !status.Authorized {
			return fmt.Errorf("не авторизован после прохождения flow")
		}
		data, err := storage.LoadSession(ctx)
		if err != nil || len(data) == 0 {
			return fmt.Errorf("не удалось извлечь string session из storage")
		}
		dbID, err := s.database.SaveSession(ctx, s.meta.Phone, string(data))
		if err != nil {
			return fmt.Errorf("сохранение в БД: %w", err)
		}
		s.meta.ID = dbID
		s.meta.StringSession = string(data)
		s.api = tg.NewClient(s.client)
		s.regDone <- dbID
		s.log.Info("✅ регистрация завершена", zap.Int64("dbID", dbID))
		go s.persistSession(ctx, storage)
		go s.processJoinQueue(ctx)
		<-ctx.Done()
		return ctx.Err()
	})
	if err != nil {
		s.regErr <- err
	}
}

func (s *Session) WaitRegistration(ctx context.Context) (int64, error) {
	select {
	case dbID := <-s.regDone:
		return dbID, nil
	case err := <-s.regErr:
		return 0, err
	case <-ctx.Done():
		return 0, fmt.Errorf("таймаут ожидания регистрации")
	}
}

// JoinChat вступает в чат и добавляет его в watchlist.
// Если API ещё не готов — кладёт ссылку в очередь (pending-слот НЕ освобождается здесь,
// он освободится в processJoinQueue).
func (s *Session) JoinChat(ctx context.Context, chatLink string) (int64, string, error) {
	if s.api == nil {
		s.joinQueue <- chatLink
		// Слот уже зарезервирован в GetSessionForUser; освободится в processJoinQueue.
		return 0, chatLink, nil
	}

	chatTgID, title, err := s.joinChatNow(ctx, chatLink)
	if err != nil {
		// Join провалился — освобождаем слот
		s.releaseSlot()
		return 0, title, err
	}

	if chatTgID != 0 {
		// AddChat внутри освобождает pending-слот и добавляет чат в watchedChats
		s.AddChat(chatTgID, chatLink)
	} else {
		// chatTgID=0 — соединение ещё устанавливается; слот освобождаем
		s.releaseSlot()
	}

	return chatTgID, title, nil
}

func (s *Session) LeaveChat(ctx context.Context, chatTgID int64) error {
	if s.api == nil {
		return fmt.Errorf("клиент ещё не запущен")
	}
	s.mu.RLock()
	chatLink := s.watchedChats[chatTgID]
	s.mu.RUnlock()

	var accessHash int64
	if chatLink != "" && !isInviteLink(normalizeLink(chatLink)) {
		username := extractUsername(normalizeLink(chatLink))
		if username != "" {
			resolved, err := s.api.ContactsResolveUsername(ctx, &tg.ContactsResolveUsernameRequest{Username: username})
			if err == nil && len(resolved.Chats) > 0 {
				if ch, ok := resolved.Chats[0].(*tg.Channel); ok {
					accessHash = ch.AccessHash
				}
			}
		}
	}

	_, err := s.api.ChannelsLeaveChannel(ctx, &tg.InputChannel{ChannelID: chatTgID, AccessHash: accessHash})
	if err != nil {
		s.log.Warn("LeaveChannel ошибка, пробуем через MessagesDeleteChatUser",
			zap.Int64("chatTgID", chatTgID), zap.Error(err))
		_, err2 := s.api.MessagesDeleteChatUser(ctx, &tg.MessagesDeleteChatUserRequest{
			ChatID: chatTgID,
			UserID: &tg.InputUserSelf{},
		})
		if err2 != nil {
			return fmt.Errorf("LeaveChannel: %w, DeleteChatUser: %w", err, err2)
		}
	}

	s.RemoveChat(chatTgID)
	s.log.Info("вышли из чата", zap.Int64("chatTgID", chatTgID), zap.String("chatLink", chatLink))
	return nil
}

func (s *Session) resolveChannelPeer(ctx context.Context, chatLink string) (*tg.InputPeerChannel, error) {
	clean := normalizeLink(chatLink)
	if isInviteLink(clean) {
		return nil, fmt.Errorf("invite-ссылки не поддерживаются для резолва peer")
	}
	username := extractUsername(clean)
	if username == "" {
		return nil, fmt.Errorf("не удалось извлечь username из ссылки: %s", chatLink)
	}
	resolved, err := s.api.ContactsResolveUsername(ctx, &tg.ContactsResolveUsernameRequest{Username: username})
	if err != nil {
		return nil, fmt.Errorf("ContactsResolveUsername(%q): %w", username, err)
	}
	for _, chat := range resolved.Chats {
		if ch, ok := chat.(*tg.Channel); ok {
			return &tg.InputPeerChannel{ChannelID: ch.ID, AccessHash: ch.AccessHash}, nil
		}
	}
	return nil, fmt.Errorf("канал не найден: %s", chatLink)
}

// ProcessChatHistory загружает и обрабатывает историю чата за последние 24 часа.
func (s *Session) ProcessChatHistory(ctx context.Context, userID int64, chatTgID int64, chatLink string, chatTitle string) {
	if s.api == nil {
		s.log.Warn("api не готов для загрузки истории", zap.Int64("chatTgID", chatTgID))
		return
	}
	if chatTgID == 0 {
		s.log.Warn("chatTgID=0 — история запустится после join", zap.String("chatLink", chatLink))
		return
	}

	cutoff := time.Now().Add(-24 * time.Hour)
	s.log.Info("загружаем историю чата",
		zap.Int64("chatTgID", chatTgID),
		zap.String("chatLink", chatLink),
		zap.Time("since", cutoff),
	)

	time.Sleep(2 * time.Second)

	resolveCtx, resolveCancel := context.WithTimeout(ctx, 10*time.Second)
	peer, err := s.resolveChannelPeer(resolveCtx, chatLink)
	resolveCancel()
	if err != nil {
		s.log.Warn("не удалось резолвить AccessHash, пробуем без него",
			zap.String("chatLink", chatLink), zap.Error(err))
		peer = &tg.InputPeerChannel{ChannelID: chatTgID}
	}

	var messages []HistoricalMessage
	var offsetID int

	for {
		var resp tg.MessagesMessagesClass
		var fetchErr error
		for attempt := 0; attempt < 3; attempt++ {
			resp, fetchErr = s.api.MessagesGetHistory(ctx, &tg.MessagesGetHistoryRequest{
				Peer:     peer,
				Limit:    100,
				OffsetID: offsetID,
			})
			if fetchErr == nil {
				break
			}
			s.log.Warn("ошибка загрузки истории, retry",
				zap.Int64("chatTgID", chatTgID),
				zap.Int("attempt", attempt+1),
				zap.Error(fetchErr),
			)
			select {
			case <-ctx.Done():
				return
			case <-time.After(time.Duration(attempt+1) * 2 * time.Second):
			}
		}
		if fetchErr != nil {
			s.log.Error("не удалось загрузить историю после 3 попыток",
				zap.Int64("chatTgID", chatTgID), zap.Error(fetchErr))
			break
		}

		var msgs []tg.MessageClass
		var users map[int64]*tg.User

		switch r := resp.(type) {
		case *tg.MessagesChannelMessages:
			msgs = r.Messages
			users = make(map[int64]*tg.User)
			for _, u := range r.Users {
				if usr, ok := u.(*tg.User); ok {
					users[usr.ID] = usr
				}
			}
		case *tg.MessagesMessages:
			msgs = r.Messages
		default:
			break
		}

		if len(msgs) == 0 {
			break
		}

		tooOld := false
		for _, m := range msgs {
			msg, ok := m.(*tg.Message)
			if !ok || msg.Out {
				continue
			}
			msgTime := time.Unix(int64(msg.Date), 0)
			if msgTime.Before(cutoff) {
				tooOld = true
				continue
			}
			text := strings.TrimSpace(msg.Message)
			if text == "" {
				continue
			}

			authorName := "Неизвестный"
			authorUsername := ""
			if msg.FromID != nil {
				if p, ok := msg.FromID.(*tg.PeerUser); ok {
					if users != nil {
						if u, ok := users[p.UserID]; ok {
							parts := []string{}
							if u.FirstName != "" {
								parts = append(parts, u.FirstName)
							}
							if u.LastName != "" {
								parts = append(parts, u.LastName)
							}
							if len(parts) > 0 {
								authorName = strings.Join(parts, " ")
							}
							authorUsername = u.Username
						}
					}
				}
			}

			messages = append(messages, HistoricalMessage{
				MessageID:      int64(msg.ID),
				Text:           text,
				AuthorName:     authorName,
				AuthorUsername: authorUsername,
				Date:           msgTime,
				MessageLink:    buildMessageLink(chatLink, chatTgID, int64(msg.ID)),
			})
			offsetID = msg.ID
		}

		if tooOld || len(msgs) < 100 {
			break
		}

		select {
		case <-ctx.Done():
			return
		case <-time.After(500 * time.Millisecond):
		}
	}

	if len(messages) == 0 {
		s.log.Info("история пуста или нет новых сообщений", zap.Int64("chatTgID", chatTgID))
		return
	}

	for i, j := 0, len(messages)-1; i < j; i, j = i+1, j-1 {
		messages[i], messages[j] = messages[j], messages[i]
	}

	s.log.Info("обрабатываем историю", zap.Int("count", len(messages)), zap.Int64("chatTgID", chatTgID))
	s.handler.ProcessHistoricalMessages(ctx, userID, chatTgID, chatLink, chatTitle, messages)
}

func (s *Session) joinChatNow(ctx context.Context, chatLink string) (int64, string, error) {
	clean := normalizeLink(chatLink)
	s.log.Info("вступаем в чат", zap.String("link", chatLink), zap.String("clean", clean))

	if isInviteLink(clean) {
		hash := extractInviteHash(clean)
		updates, err := s.api.MessagesImportChatInvite(ctx, hash)
		if err != nil {
			return 0, clean, fmt.Errorf("MessagesImportChatInvite: %w", err)
		}
		chatID, title := extractFromUpdates(updates)
		s.log.Info("вступили в чат по invite link",
			zap.String("link", chatLink), zap.Int64("chatID", chatID), zap.String("title", title))
		return chatID, title, nil
	}

	username := extractUsername(clean)
	resolved, err := s.api.ContactsResolveUsername(ctx, &tg.ContactsResolveUsernameRequest{Username: username})
	if err != nil {
		return 0, clean, fmt.Errorf("ContactsResolveUsername(%q): %w", username, err)
	}
	if len(resolved.Chats) == 0 {
		return 0, clean, fmt.Errorf("чат @%s не найден", username)
	}

	switch c := resolved.Chats[0].(type) {
	case *tg.Channel:
		_, err := s.api.ChannelsJoinChannel(ctx, &tg.InputChannel{ChannelID: c.ID, AccessHash: c.AccessHash})
		if err != nil && !isAlreadyMember(err) {
			return 0, c.Title, fmt.Errorf("ChannelsJoinChannel: %w", err)
		}
		s.log.Info("вступили в канал/группу", zap.String("title", c.Title), zap.Int64("id", c.ID))
		return c.ID, c.Title, nil
	case *tg.Chat:
		s.log.Info("обычный чат (без join)", zap.String("title", c.Title), zap.Int64("id", c.ID))
		return c.ID, c.Title, nil
	default:
		return 0, clean, fmt.Errorf("неизвестный тип чата: %T", resolved.Chats[0])
	}
}

func (s *Session) processJoinQueue(ctx context.Context) {
	time.Sleep(3 * time.Second)

	for {
		select {
		case <-ctx.Done():
			return
		case link := <-s.joinQueue:
			joinCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
			chatID, title, err := s.joinChatNow(joinCtx, link)
			cancel()

			if err != nil {
				s.log.Error("ошибка вступления в чат из очереди",
					zap.String("link", link), zap.Error(err))
				// Освобождаем слот, зарезервированный в GetSessionForUser
				s.releaseSlot()
				continue
			}

			if chatID != 0 {
				// AddChat освобождает pending-слот внутри
				s.AddChat(chatID, link)

				updateCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
				if err := s.database.UpdateSubscriptionChatID(updateCtx, s.meta.ID, link, chatID, title); err != nil {
					s.log.Warn("не удалось обновить chatTgID в БД",
						zap.String("link", link), zap.Int64("chatID", chatID), zap.Error(err))
				}
				cancel()

				histCtx, histCancel := context.WithTimeout(ctx, 10*time.Second)
				subs, subErr := s.database.GetSubscriptionsByChatLink(histCtx, link)
				histCancel()
				if subErr == nil {
					for _, sub := range subs {
						subCopy := sub
						go s.ProcessChatHistory(context.Background(), subCopy.UserID, chatID, link, title)
					}
				} else {
					s.log.Warn("не удалось получить подписки для истории",
						zap.String("link", link), zap.Error(subErr))
				}
			} else {
				// chatID=0 — освобождаем слот
				s.releaseSlot()
			}

			select {
			case <-ctx.Done():
				return
			case <-time.After(1 * time.Second):
			}
		}
	}
}

// AddChat добавляет чат в watchlist и освобождает ранее зарезервированный pending-слот.
func (s *Session) AddChat(chatTgID int64, chatLink string) {
	s.mu.Lock()
	s.watchedChats[chatTgID] = chatLink
	s.mu.Unlock()

	// Освобождаем pending-слот, зарезервированный в GetSessionForUser
	s.releaseSlot()

	s.log.Info("чат добавлен в watchlist",
		zap.Int64("chatTgID", chatTgID),
		zap.String("link", chatLink),
		zap.Int("totalChats", s.ChatCount()),
	)
}

// addChatInternal добавляет чат без освобождения pending-слота.
// Используется при старте сессии (restore), когда слот не был зарезервирован.
func (s *Session) addChatInternal(chatTgID int64, chatLink string) {
	s.mu.Lock()
	s.watchedChats[chatTgID] = chatLink
	s.mu.Unlock()
}

func (s *Session) RemoveChat(chatTgID int64) {
	s.mu.Lock()
	delete(s.watchedChats, chatTgID)
	s.mu.Unlock()
}

func (s *Session) ID() int64     { return s.meta.ID }
func (s *Session) Phone() string { return s.meta.Phone }

func (s *Session) onNewMessage(ctx context.Context, e tg.Entities, u *tg.UpdateNewMessage) error {
	msg, ok := u.Message.(*tg.Message)
	if !ok || msg.Out {
		return nil
	}
	return s.processMessage(ctx, e, msg)
}

func (s *Session) onNewChannelMessage(ctx context.Context, e tg.Entities, u *tg.UpdateNewChannelMessage) error {
	msg, ok := u.Message.(*tg.Message)
	if !ok || msg.Out {
		return nil
	}
	return s.processMessage(ctx, e, msg)
}

func (s *Session) processMessage(ctx context.Context, e tg.Entities, msg *tg.Message) error {
	text := strings.TrimSpace(msg.Message)
	if text == "" {
		return nil
	}
	chatTgID := extractChatID(msg.PeerID)
	if chatTgID == 0 {
		return nil
	}
	s.mu.RLock()
	chatLink, monitored := s.watchedChats[chatTgID]
	s.mu.RUnlock()
	if !monitored {
		return nil
	}

	authorName, authorUsername := extractAuthor(msg, e)
	chatTitle := extractChatTitle(chatTgID, e)

	s.log.Debug("входящее сообщение",
		zap.Int64("chatTgID", chatTgID),
		zap.String("author", authorName),
		zap.Int("len", len(text)),
	)

	s.handler.HandleMessage(ctx, chatTgID, chatLink, chatTitle, int64(msg.ID), authorName, authorUsername, text)
	return nil
}

func extractChatID(peer tg.PeerClass) int64 {
	switch p := peer.(type) {
	case *tg.PeerChat:
		return p.ChatID
	case *tg.PeerChannel:
		return p.ChannelID
	}
	return 0
}

func extractAuthor(msg *tg.Message, e tg.Entities) (name, username string) {
	if msg.FromID == nil {
		return "Аноним", ""
	}
	if p, ok := msg.FromID.(*tg.PeerUser); ok {
		if u, ok := e.Users[p.UserID]; ok {
			parts := []string{}
			if u.FirstName != "" {
				parts = append(parts, u.FirstName)
			}
			if u.LastName != "" {
				parts = append(parts, u.LastName)
			}
			return strings.Join(parts, " "), u.Username
		}
	}
	return "Неизвестный", ""
}

func extractChatTitle(chatTgID int64, e tg.Entities) string {
	if ch, ok := e.Channels[chatTgID]; ok {
		return ch.Title
	}
	if c, ok := e.Chats[chatTgID]; ok {
		return c.Title
	}
	return "Неизвестный чат"
}

func extractFromUpdates(updates tg.UpdatesClass) (int64, string) {
	switch u := updates.(type) {
	case *tg.Updates:
		for _, chat := range u.Chats {
			switch c := chat.(type) {
			case *tg.Channel:
				return c.ID, c.Title
			case *tg.Chat:
				return c.ID, c.Title
			}
		}
	}
	return 0, ""
}

func normalizeLink(link string) string {
	link = strings.TrimSpace(link)
	link = strings.TrimPrefix(link, "https://")
	link = strings.TrimPrefix(link, "http://")
	return link
}

func isInviteLink(link string) bool {
	return strings.Contains(link, "/joinchat/") || strings.Contains(link, "/+")
}

func extractInviteHash(link string) string {
	if idx := strings.LastIndex(link, "/+"); idx >= 0 {
		return link[idx+2:]
	}
	if idx := strings.LastIndex(link, "/joinchat/"); idx >= 0 {
		return link[idx+len("/joinchat/"):]
	}
	return link
}

func extractUsername(link string) string {
	link = strings.TrimPrefix(link, "t.me/")
	link = strings.TrimPrefix(link, "@")
	link = strings.Split(link, "/")[0]
	link = strings.Split(link, "?")[0]
	return link
}

func isAlreadyMember(err error) bool {
	if err == nil {
		return false
	}
	msg := err.Error()
	return strings.Contains(msg, "ALREADY_PARTICIPANT") ||
		strings.Contains(msg, "USER_ALREADY_PARTICIPANT")
}

func (s *Session) persistSession(ctx context.Context, storage *session.StorageMemory) {
	data, err := storage.LoadSession(ctx)
	if err != nil || len(data) == 0 {
		return
	}
	if err := s.database.UpdateStringSession(ctx, s.meta.ID, string(data)); err != nil {
		s.log.Warn("не удалось сохранить сессию", zap.Error(err))
	}
}

type channelAuth struct {
	phone  string
	codeCh <-chan string
	pwdCh  <-chan string
}

func (a channelAuth) Phone(_ context.Context) (string, error) { return a.phone, nil }

func (a channelAuth) Code(_ context.Context, _ *tg.AuthSentCode) (string, error) {
	code := <-a.codeCh
	return code, nil
}

func (a channelAuth) Password(ctx context.Context) (string, error) {
	select {
	case pwd := <-a.pwdCh:
		if pwd == "" {
			return "", fmt.Errorf("пароль не может быть пустым")
		}
		return pwd, nil
	case <-ctx.Done():
		return "", fmt.Errorf("таймаут ожидания 2FA пароля — отправьте /confirm с полем password")
	}
}

func (a channelAuth) AcceptTermsOfService(_ context.Context, _ tg.HelpTermsOfService) error {
	return nil
}

func (a channelAuth) SignUp(_ context.Context) (auth.UserInfo, error) {
	return auth.UserInfo{}, fmt.Errorf("регистрация новых аккаунтов не поддерживается")
}
