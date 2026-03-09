package config

import (
	"fmt"
	"os"
	"strconv"

	"github.com/joho/godotenv"
)

type Config struct {
	TgApiID   int
	TgApiHash string

	DbDSN string

	SpringBootURL     string
	InternalAPISecret string

	HTTPPort string
	AppEnv   string

	GroqAPIKey string
}

func Load() (*Config, error) {
	_ = godotenv.Load()

	apiID := 0
	if v := os.Getenv("TG_API_ID"); v != "" {
		var err error
		apiID, err = strconv.Atoi(v)
		if err != nil {
			return nil, fmt.Errorf("TG_API_ID должен быть числом: %w", err)
		}
	}

	return &Config{
		TgApiID:           apiID,
		TgApiHash:         os.Getenv("TG_API_HASH"),
		DbDSN:             mustEnv("DB_DSN"),
		SpringBootURL:     getEnv("SPRING_BOOT_URL", "http://localhost:8080"),
		InternalAPISecret: getEnv("INTERNAL_API_SECRET", "aimly_internal_secret_change_in_prod"),
		HTTPPort:          getEnv("HTTP_PORT", "9090"),
		AppEnv:            getEnv("APP_ENV", "development"),
		GroqAPIKey:        os.Getenv("GROQ_API_KEY"),
	}, nil
}

func (c *Config) IsDev() bool { return c.AppEnv == "development" }

func mustEnv(key string) string {
	v := os.Getenv(key)
	if v == "" {
		panic(fmt.Sprintf("обязательная переменная %s не задана", key))
	}
	return v
}

func getEnv(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
