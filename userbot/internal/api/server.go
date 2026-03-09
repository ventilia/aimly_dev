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

		r.Post("/admin/sessions/register", s.handlers.RegisterSession)
		r.Post("/admin/sessions/confirm", s.handlers.ConfirmSession)

		r.Post("/admin/chats/leave", s.handlers.LeaveChat)
	})

	s.router = r
}

func (s *Server) Run(ctx context.Context) error {
	srv := &http.Server{
		Addr:    ":" + s.port,
		Handler: s.router,

		ReadTimeout: 15 * time.Second,

		WriteTimeout: 5 * time.Minute,
		IdleTimeout:  60 * time.Second,
	}

	errCh := make(chan error, 1)
	go func() {
		s.log.Info("HTTP сервер запущен",
			zap.String("port", s.port),
			zap.String("writeTimeout", "5m"),
		)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			errCh <- err
		}
	}()

	select {
	case <-ctx.Done():
		s.log.Info("завершаем HTTP сервер...")
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		return srv.Shutdown(shutdownCtx)
	case err := <-errCh:
		return err
	}
}

func (s *Server) requireInternalSecret(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		secret := r.Header.Get("X-Internal-Secret")
		if secret != s.secret {
			s.log.Warn("неверный X-Internal-Secret",
				zap.String("ip", r.RemoteAddr),
				zap.String("path", r.URL.Path),
			)
			http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func (s *Server) zapLogger(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ww := middleware.NewWrapResponseWriter(w, r.ProtoMajor)
		start := time.Now()

		next.ServeHTTP(ww, r)

		duration := time.Since(start)
		level := zap.DebugLevel
		if duration > 5*time.Second {
			level = zap.WarnLevel
		}

		s.log.Log(level, "HTTP запрос",
			zap.String("method", r.Method),
			zap.String("path", r.URL.Path),
			zap.Int("status", ww.Status()),
			zap.Duration("duration", duration),
			zap.String("requestId", middleware.GetReqID(r.Context())),
		)
	})
}
