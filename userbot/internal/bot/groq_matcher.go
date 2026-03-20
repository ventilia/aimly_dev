package bot

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"

	"go.uber.org/zap"
)

type GroqMatcher struct {
	apiKey     string
	httpClient *http.Client
	log        *zap.Logger
}

func NewGroqMatcher(apiKey string, log *zap.Logger) *GroqMatcher {
	return &GroqMatcher{
		apiKey: apiKey,
		log:    log,
		httpClient: &http.Client{
			Timeout: 8 * time.Second,
		},
	}
}

type groqMatchRequest struct {
	Model       string             `json:"model"`
	Messages    []groqMatchMessage `json:"messages"`
	MaxTokens   int                `json:"max_tokens"`
	Temperature float64            `json:"temperature"`
}

type groqMatchMessage struct {
	Role    string `json:"role"`
	Content string `json:"content"`
}

type groqMatchResponse struct {
	Choices []struct {
		Message struct {
			Content string `json:"content"`
		} `json:"message"`
	} `json:"choices"`
}

const groqModel = "llama-3.1-8b-instant"

// cleanGroqJSON убирает markdown-обёртки которые модель иногда добавляет
// несмотря на инструкцию "только JSON".
func cleanGroqJSON(raw string) string {
	s := strings.TrimSpace(raw)
	// Убираем ```json ... ``` и просто ``` ... ```
	for _, prefix := range []string{"```json", "```"} {
		if strings.HasPrefix(s, prefix) {
			s = strings.TrimPrefix(s, prefix)
			s = strings.TrimSuffix(s, "```")
			s = strings.TrimSpace(s)
		}
	}
	// Иногда модель пишет пояснение ПЕРЕД JSON — ищем первую фигурную скобку
	if idx := strings.Index(s, "{"); idx > 0 {
		s = s[idx:]
	}
	// И обрезаем всё после последней закрывающей скобки
	if idx := strings.LastIndex(s, "}"); idx >= 0 && idx < len(s)-1 {
		s = s[:idx+1]
	}
	return strings.TrimSpace(s)
}

// SemanticMatch — проверяет, есть ли в тексте семантическое совпадение с одним из ключевых слов.
// ЗАДАЧА 2: улучшен промпт — явно объясняем разницу между прямым ключевым словом-маркером спроса
// и предложением услуг, чтобы избежать ложных срабатываний.
func (g *GroqMatcher) SemanticMatch(ctx context.Context, text string, keywords []string) string {
	if g.apiKey == "" || len(keywords) == 0 {
		return ""
	}

	kwList := strings.Join(keywords, "\n- ")

	prompt := fmt.Sprintf(`Ты — система семантического поиска ключевых слов.

Список ключевых слов (каждое отражает ЗАПРОС на поиск услуги или исполнителя — то есть человек что-то ищет):
- %s

Сообщение для анализа:
"%s"

Задача: определи, содержит ли сообщение смысл хотя бы одного ключевого слова — то есть автор сообщения ИЩЕТ услугу или специалиста.

Учитывай:
- Морфологию русского языка: ищу/ищем/ищут/нужен/нужна/нужны/требуется/хочу найти/посоветуйте
- Синонимы и близкие по смыслу фразы
- Опечатки и сокращения

ВАЖНО — НЕ считай совпадением, если:
- Автор ПРЕДЛАГАЕТ услугу ("ищу заказы", "ищу клиентов", "ищу проекты" — автор специалист)
- Автор рекламирует себя ("я дизайнер", "выполню работу", "занимаюсь разработкой")
- Это реклама/спам/объявление без персонального запроса
- Ключевое слово упомянуто в тексте, но не как запрос, а как тема обсуждения

РАЗЛИЧАЙ:
- "ищу разработчика" → СОВПАДЕНИЕ (клиент ищет исполнителя)
- "ищу заказы на разработку" → НЕТ совпадения (специалист ищет клиентов)
- "нужен дизайнер" → СОВПАДЕНИЕ
- "я дизайнер, ищу проекты" → НЕТ совпадения

Если совпадений нет — верни пустую строку. Не придумывай совпадений.

Ответь строго JSON без пояснений и markdown:
{"matched": "точное ключевое слово из списка выше, или пустая строка если нет совпадений"}`,
		kwList, text)

	body, err := json.Marshal(groqMatchRequest{
		Model: groqModel,
		Messages: []groqMatchMessage{
			{Role: "system", Content: "Отвечай только JSON. Никаких пояснений, никаких markdown-блоков."},
			{Role: "user", Content: prompt},
		},
		MaxTokens:   100,
		Temperature: 0.0,
	})
	if err != nil {
		g.log.Warn("groq matcher: json.Marshal", zap.Error(err))
		return ""
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		"https://api.groq.com/openai/v1/chat/completions", bytes.NewReader(body))
	if err != nil {
		g.log.Warn("groq matcher: NewRequest", zap.Error(err))
		return ""
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+g.apiKey)

	resp, err := g.httpClient.Do(req)
	if err != nil {
		g.log.Warn("groq matcher: HTTP error", zap.Error(err))
		return ""
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		g.log.Warn("groq matcher: unexpected status", zap.Int("status", resp.StatusCode))
		return ""
	}

	var result groqMatchResponse
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		g.log.Warn("groq matcher: decode error", zap.Error(err))
		return ""
	}

	if len(result.Choices) == 0 {
		return ""
	}

	raw := result.Choices[0].Message.Content
	content := cleanGroqJSON(raw)

	var parsed struct {
		Matched string `json:"matched"`
	}
	if err := json.Unmarshal([]byte(content), &parsed); err != nil {
		g.log.Warn("groq matcher: parse result error",
			zap.String("raw", raw),
			zap.String("cleaned", content),
			zap.Error(err),
		)
		return ""
	}

	matched := strings.TrimSpace(parsed.Matched)

	for _, kw := range keywords {
		if strings.EqualFold(kw, matched) {
			return kw
		}
	}

	if matched != "" {
		g.log.Debug("groq matcher: вернул слово не из списка (игнорируем)",
			zap.String("matched", matched),
		)
	}

	return ""
}

// FilterRelevantContext фильтрует буфер сообщений чата, оставляя только те,
// которые являются реальным контекстом для целевого сообщения (лида).
// Если API недоступен — возвращает оригинальный срез (не более 3 сообщений).
func (g *GroqMatcher) FilterRelevantContext(ctx context.Context, targetMessage string, rawBuffer []string) []string {
	if len(rawBuffer) == 0 {
		return nil
	}

	if g.apiKey == "" {
		if len(rawBuffer) > 3 {
			return rawBuffer[len(rawBuffer)-3:]
		}
		return rawBuffer
	}

	numbered := make([]string, 0, len(rawBuffer))
	for i, msg := range rawBuffer {
		numbered = append(numbered, fmt.Sprintf("%d. \"%s\"", i+1, msg))
	}
	contextList := strings.Join(numbered, "\n")

	prompt := fmt.Sprintf(`Целевое сообщение:
"%s"

Предшествующие сообщения в чате:
%s

Задача: определи, какие из предшествующих сообщений являются ПРЯМЫМ контекстом для целевого сообщения — то есть части одного диалога или обсуждения темы.

Включай сообщение в контекст только если оно:
- часть того же диалога/обсуждения
- задаёт тему, на которую отвечает целевое сообщение
- от того же автора (продолжение мысли)

НЕ включай если:
- совершенно другая тема
- не связанный разговор других людей
- общие реплики не по теме

Верни ТОЛЬКО номера подходящих сообщений. Если ни одно не подходит — верни пустой список.

Ответь строго JSON без пояснений: {"relevant": [1, 2]} или {"relevant": []}`,
		targetMessage, contextList)

	body, err := json.Marshal(groqMatchRequest{
		Model: groqModel,
		Messages: []groqMatchMessage{
			{Role: "system", Content: "Отвечай только JSON. Никаких пояснений."},
			{Role: "user", Content: prompt},
		},
		MaxTokens:   80,
		Temperature: 0.0,
	})
	if err != nil {
		g.log.Warn("groq context filter: json.Marshal", zap.Error(err))
		return rawBuffer[:min(3, len(rawBuffer))]
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		"https://api.groq.com/openai/v1/chat/completions", bytes.NewReader(body))
	if err != nil {
		g.log.Warn("groq context filter: NewRequest", zap.Error(err))
		return rawBuffer[:min(3, len(rawBuffer))]
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+g.apiKey)

	resp, err := g.httpClient.Do(req)
	if err != nil {
		g.log.Warn("groq context filter: HTTP error", zap.Error(err))
		return rawBuffer[:min(3, len(rawBuffer))]
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		g.log.Warn("groq context filter: unexpected status", zap.Int("status", resp.StatusCode))
		return rawBuffer[:min(3, len(rawBuffer))]
	}

	var result groqMatchResponse
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		g.log.Warn("groq context filter: decode error", zap.Error(err))
		return rawBuffer[:min(3, len(rawBuffer))]
	}

	if len(result.Choices) == 0 {
		return nil
	}

	raw := result.Choices[0].Message.Content
	content := cleanGroqJSON(raw)

	var parsed struct {
		Relevant []int `json:"relevant"`
	}
	if err := json.Unmarshal([]byte(content), &parsed); err != nil {
		g.log.Warn("groq context filter: parse error",
			zap.String("raw", raw),
			zap.String("cleaned", content),
			zap.Error(err),
		)
		return rawBuffer[:min(3, len(rawBuffer))]
	}

	filtered := make([]string, 0, len(parsed.Relevant))
	for _, idx := range parsed.Relevant {
		if idx >= 1 && idx <= len(rawBuffer) {
			filtered = append(filtered, rawBuffer[idx-1])
		}
	}

	return filtered
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}
