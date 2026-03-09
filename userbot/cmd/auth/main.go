package main

import (
	"context"
	"fmt"
	"log"
	"os"

	"userbot/internal/config"
	"userbot/internal/db"

	"github.com/gotd/td/session"
	"github.com/gotd/td/telegram"
	"github.com/gotd/td/telegram/auth"
	"github.com/gotd/td/tg"
	"go.uber.org/zap"
)

func main() {
	ctx := context.Background()

	cfg, err := config.Load()
	if err != nil {
		log.Fatalf("config: %v", err)
	}

	if cfg.TgApiID == 0 || cfg.TgApiHash == "" {
		log.Fatal("для ручной регистрации нужны TG_API_ID и TG_API_HASH в .env")
	}

	logger, _ := zap.NewDevelopment()
	defer logger.Sync() //nolint:errcheck

	database, err := db.New(ctx, cfg.DbDSN, logger)
	if err != nil {
		log.Fatalf("db: %v", err)
	}
	defer database.Close()

	phone := readLine("Номер телефона (например +79001234567): ")

	storage := &session.StorageMemory{}
	client := telegram.NewClient(cfg.TgApiID, cfg.TgApiHash, telegram.Options{
		SessionStorage: storage,
		Logger:         logger.Named("gotd"),
	})

	flow := auth.NewFlow(
		terminalAuth{phone: phone},
		auth.SendCodeOptions{},
	)

	err = client.Run(ctx, func(ctx context.Context) error {
		if err := client.Auth().IfNecessary(ctx, flow); err != nil {
			return fmt.Errorf("авторизация: %w", err)
		}

		status, err := client.Auth().Status(ctx)
		if err != nil {
			return fmt.Errorf("статус: %w", err)
		}
		if !status.Authorized {
			return fmt.Errorf("не авторизован после прохождения flow")
		}

		raw := tg.NewClient(client)
		full, err := raw.UsersGetFullUser(ctx, &tg.InputUserSelf{})
		if err == nil && len(full.Users) > 0 {
			if u, ok := full.Users[0].(*tg.User); ok {
				fmt.Printf("\n✅ Авторизован как: %s %s (@%s)\n\n", u.FirstName, u.LastName, u.Username)
			}
		}

		data, err := storage.LoadSession(ctx)
		if err != nil || len(data) == 0 {
			return fmt.Errorf("не удалось извлечь session из storage")
		}

		sessionID, err := database.SaveSession(ctx, phone, string(data))
		if err != nil {
			return fmt.Errorf("сохранение в БД: %w", err)
		}

		fmt.Printf("✅ Сессия сохранена в БД (id=%d, phone=%s)\n", sessionID, phone)
		fmt.Println("Теперь запустите: go run ./cmd/userbot")
		return nil
	})
	if err != nil {
		log.Fatalf("ошибка: %v", err)
	}
}

// ─── auth flow ────────────────────────────────────────────────────────────────

type terminalAuth struct {
	phone string
}

func (t terminalAuth) Phone(_ context.Context) (string, error) {
	return t.phone, nil
}

func (t terminalAuth) Code(_ context.Context, _ *tg.AuthSentCode) (string, error) {
	return readLine("Код из Telegram/SMS: "), nil
}

func (t terminalAuth) Password(_ context.Context) (string, error) {
	fmt.Println("\n⚠️  На аккаунте включена 2FA.")
	fmt.Println("   Введите облачный пароль (Telegram → Settings → Privacy → Two-Step Verification).")
	pwd := readLine("2FA пароль: ")
	if pwd == "" {
		return "", fmt.Errorf("пароль не может быть пустым")
	}
	return pwd, nil
}

func (t terminalAuth) AcceptTermsOfService(_ context.Context, _ tg.HelpTermsOfService) error {
	return nil
}

func (t terminalAuth) SignUp(_ context.Context) (auth.UserInfo, error) {
	return auth.UserInfo{}, fmt.Errorf("регистрация новых аккаунтов не поддерживается")
}

func readLine(prompt string) string {
	fmt.Print(prompt)
	var line string
	buf := make([]byte, 1)
	for {
		n, err := os.Stdin.Read(buf)
		if n > 0 {
			c := buf[0]
			if c == '\n' {
				break
			}
			if c != '\r' {
				line += string(c)
			}
		}
		if err != nil {
			break
		}
	}
	return line
}
