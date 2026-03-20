package api

import (
	"context"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
	"go.uber.org/zap"
)

type Server struct {
	router   *chi.Mux
	handlers *Handlers
	log      *zap.Logger
	port     string
	secret   string
}

func NewServer(handlers *Handlers, log *zap.Logger, port string, secret string) *Server {
	s := &Server{
		handlers: handlers,
		log:      log,
		port:     port,
		secret:   secret,
	}
	s.setupRoutes()
	return s
}

func (s *Server) setupRoutes() {
	r := chi.NewRouter()

	r.Use(middleware.RequestID)
	r.Use(middleware.RealIP)
	r.Use(middleware.Recoverer)
	r.Use(s.zapLogger)

	r.Get("/health", s.handlers.Health)

	r.Group(func(r chi.Router) {
		r.Use(s.requireInternalSecret)

		r.Post("/chats/subscribe", s.handlers.SubscribeChat)
		r.Post("/chats/unsubscribe", s.handlers.UnsubscribeChat)
		r.Post("/keywords/update", s.handlers.UpdateKeywords)
		r.Get("/stats", s.handlers.Stats)

		// Admin: сессии
		r.Post("/admin/sessions/register", s.handlers.RegisterSession)
		r.Post("/admin/sessions/confirm", s.handlers.ConfirmSession)
		r.Post("/admin/sessions/delete", s.handlers.DeleteSession) // ← новый роут
		r.Get("/admin/sessions", s.handlers.GetSessions)           // ← новый роут

		// Admin: чаты
		r.Post("/admin/chats/leave", s.handlers.LeaveChat)
	})

	s.router = r
}

func (s *Server) Run(ctx context.Context) error {
	srv := &http.Server{
		Addr:         ":" + s.port,
		Handler:      s.router,
		ReadTimeout:  15 * time.Second,
		WriteTimeout: 5 * time.Minute,
		IdleTimeout:  60 * time.Second,
	}

	errCh := make(chan error, 1)
	go func() {
		s.log.Info("HTTP сервер запущен", zap.String("port", s.port))
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			errCh <- err
		}
	}()

	select {
	case err := <-errCh:
		return err
	case <-ctx.Done():
		shutCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		return srv.Shutdown(shutCtx)
	}
}

func (s *Server) requireInternalSecret(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if s.secret != "" && r.Header.Get("X-Internal-Secret") != s.secret {
			jsonError(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func (s *Server) zapLogger(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		next.ServeHTTP(w, r)
		s.log.Debug("HTTP",
			zap.String("method", r.Method),
			zap.String("path", r.URL.Path),
			zap.Duration("dur", time.Since(start)),
		)
	})
}
