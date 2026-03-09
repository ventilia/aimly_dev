package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"syscall"

	"userbot/internal/api"
	"userbot/internal/bot"
	"userbot/internal/config"
	"userbot/internal/db"

	"go.uber.org/zap"
)

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	cfg, err := config.Load()
	if err != nil {
		log.Fatalf("config: %v", err)
	}

	var logger *zap.Logger
	if cfg.IsDev() {
		logger, _ = zap.NewDevelopment()
	} else {
		logger, _ = zap.NewProduction()
	}
	defer logger.Sync()

	logger.Info("запускаем userbot-сервис",
		zap.String("env", cfg.AppEnv),
		zap.String("port", cfg.HTTPPort),
		zap.Bool("groq_enabled", cfg.GroqAPIKey != ""),
	)

	database, err := db.New(ctx, cfg.DbDSN, logger)
	if err != nil {
		logger.Fatal("не удалось подключиться к БД", zap.Error(err))
	}
	defer database.Close()

	handler, err := bot.NewMessageHandler(
		ctx,
		database,
		logger.Named("handler"),
		cfg.SpringBootURL,
		cfg.InternalAPISecret,
		cfg.GroqAPIKey,
	)
	if err != nil {
		logger.Fatal("не удалось создать MessageHandler", zap.Error(err))
	}

	pool := bot.NewPool(
		database,
		handler,
		logger.Named("pool"),
		cfg.TgApiID,
		cfg.TgApiHash,
	)

	go func() {
		if err := pool.Start(ctx); err != nil {
			logger.Error("pool.Start завершился с ошибкой", zap.Error(err))
		}
	}()

	handlers := api.NewHandlers(
		pool,
		handler,
		logger.Named("api"),
		cfg.InternalAPISecret,
	)
	server := api.NewServer(
		handlers,
		logger.Named("server"),
		cfg.HTTPPort,
		cfg.InternalAPISecret,
	)

	logger.Info("HTTP сервер готов", zap.String("addr", ":"+cfg.HTTPPort))

	if err := server.Run(ctx); err != nil {
		logger.Error("сервер завершился с ошибкой", zap.Error(err))
	}

	logger.Info("userbot-сервис остановлен")
}
