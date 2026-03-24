/**
 * userLogger.ts — централизованный модуль логирования действий пользователя.
 *
 * Что делает:
 *  - Собирает событие (action, label, page, meta) + контекст пользователя
 *  - Отправляет на бэкенд через navigator.sendBeacon (надёжно при закрытии вкладки)
 *    с fallback на fetch
 *  - Дедуплицирует частые одинаковые события (защита от двойных вызовов)
 *  - В dev-режиме выводит все события в консоль
 *
 * Что НЕ делает:
 *  - Не хранит данные локально
 *  - Не передаёт пароли, токены, платёжные данные
 *  - Не блокирует UI (всё асинхронно / fire-and-forget)
 */

const BASE = import.meta.env.VITE_API_URL || ''
const ENDPOINT = `${BASE}/api/v1/log/action`
const IS_DEV = import.meta.env.DEV

// ─── Типы ─────────────────────────────────────────────────────────────────────

export type ActionType =
// Навигация
    | 'PAGE_VIEW'
    | 'PAGE_LEAVE'
    // Клики
    | 'CLICK'
    | 'BUTTON_CLICK'
    | 'LINK_CLICK'
    // Модалки
    | 'MODAL_OPEN'
    | 'MODAL_CLOSE'
    // Формы
    | 'FORM_SUBMIT'
    | 'FORM_ERROR'
    // Аутентификация
    | 'LOGIN_ATTEMPT'
    | 'LOGIN_SUCCESS'
    | 'LOGIN_FAIL'
    | 'REGISTER_ATTEMPT'
    | 'REGISTER_SUCCESS'
    | 'LOGOUT'
    | 'GOOGLE_AUTH_CLICK'
    | 'EMAIL_VERIFY_SUBMIT'
    | 'FORGOT_PASSWORD_SUBMIT'
    | 'RESET_PASSWORD_SUBMIT'
    // Лиды
    | 'LEADS_VIEW'
    | 'LEAD_COPY'
    | 'LEAD_EXPORT'
    // Чаты
    | 'CHAT_ADD_START'
    | 'CHAT_ADD_SUCCESS'
    | 'CHAT_ADD_FAIL'
    | 'CHAT_DELETE'
    | 'CHAT_SEARCH_START'
    | 'CHAT_SEARCH_SUCCESS'
    // Ключевые слова
    | 'KEYWORD_ADD_START'
    | 'KEYWORD_ADD_SUCCESS'
    | 'KEYWORD_ADD_FAIL'
    | 'KEYWORD_DELETE'
    | 'KEYWORD_EXPAND_CLICK'
    // Подписка / оплата
    | 'PLAN_VIEW'
    | 'PLAN_SELECT'
    | 'BUY_CLICK'
    | 'CHECKOUT_OPEN'
    | 'CHECKOUT_SUBMIT'
    | 'SUBSCRIPTION_UPGRADE_CLICK'
    // Профиль
    | 'PROFILE_EDIT_START'
    | 'PROFILE_EDIT_SAVE'
    | 'TELEGRAM_LINK_CLICK'
    | 'TELEGRAM_UNLINK_CLICK'
    | 'PASSWORD_CHANGE_SUBMIT'
    // Уведомления
    | 'NOTIFICATION_OPEN'
    | 'NOTIFICATION_MARK_READ'
    // Лэндинг
    | 'HERO_CTA_CLICK'
    | 'PRICING_CTA_CLICK'
    | 'SECTION_VIEW'
    // Ошибки
    | 'CLIENT_ERROR'

export interface LogEventOptions {
    /** Метка элемента: название кнопки, заголовок страницы и т.п. */
    label?: string
    /** Путь страницы — берётся автоматически, но можно переопределить */
    page?:  string
    /** Свободные метаданные (максимум 10 ключей, без чувствительных данных) */
    meta?:  Record<string, string>
}

// ─── Контекст пользователя ────────────────────────────────────────────────────
// Устанавливается из AuthContext при логине/логауте

interface UserContext {
    userId?:    number
    userEmail?: string
}

let _userCtx: UserContext = {}

export function setLoggerUserContext(ctx: UserContext) {
    _userCtx = ctx
}

export function clearLoggerUserContext() {
    _userCtx = {}
}

// ─── Дедупликация ─────────────────────────────────────────────────────────────
// Если одно и то же событие с теми же параметрами отправлено дважды
// в течение 500мс — второй вызов игнорируется.

const _recentKeys = new Map<string, number>()
const DEDUP_MS = 500

function isDuplicate(key: string): boolean {
    const last = _recentKeys.get(key)
    const now  = Date.now()
    if (last !== undefined && now - last < DEDUP_MS) return true
    _recentKeys.set(key, now)
    // чистим старые записи
    if (_recentKeys.size > 200) {
        const cutoff = now - DEDUP_MS * 10
        for (const [k, t] of _recentKeys) {
            if (t < cutoff) _recentKeys.delete(k)
        }
    }
    return false
}

// ─── Главная функция ──────────────────────────────────────────────────────────

export function logEvent(action: ActionType, options: LogEventOptions = {}): void {
    const { label, page, meta } = options

    // Санитизируем meta — не передаём чувствительные ключи
    const safeMeta = sanitizeMeta(meta)

    const currentPage = page ?? getCurrentPage()
    const dedupKey    = `${action}|${label ?? ''}|${currentPage}`

    if (isDuplicate(dedupKey)) return

    const payload = {
        action,
        label:     label?.slice(0, 128),
        page:      currentPage?.slice(0, 256),
        meta:      safeMeta,
        userId:    _userCtx.userId,
        userEmail: _userCtx.userEmail,
        ts:        new Date().toISOString(),
    }

    if (IS_DEV) {
        console.debug('[logEvent]', payload)
    }

    send(payload)
}

// ─── Отправка ─────────────────────────────────────────────────────────────────

function send(payload: object): void {
    const json = JSON.stringify(payload)

    // sendBeacon — работает даже при закрытии вкладки, не блокирует UI
    if (typeof navigator !== 'undefined' && navigator.sendBeacon) {
        const blob = new Blob([json], { type: 'application/json' })
        const sent = navigator.sendBeacon(ENDPOINT, blob)
        if (sent) return
    }

    // fallback: обычный fetch (fire-and-forget, ошибки не бросаем в UI)
    fetch(ENDPOINT, {
        method:      'POST',
        headers:     { 'Content-Type': 'application/json' },
        body:        json,
        credentials: 'include',
        keepalive:   true,
    }).catch(() => {
        // намеренно игнорируем — логи не должны ломать приложение
    })
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function getCurrentPage(): string {
    if (typeof window === 'undefined') return ''
    return window.location.pathname + window.location.search
}

/** Убираем ключи, похожие на чувствительные данные */
const SENSITIVE_KEY_RE = /password|token|secret|card|cvv|pin|auth|bearer|key/i

function sanitizeMeta(
    meta?: Record<string, string>,
): Record<string, string> | undefined {
    if (!meta) return undefined

    const result: Record<string, string> = {}
    let count = 0

    for (const [k, v] of Object.entries(meta)) {
        if (count >= 10) break
        if (SENSITIVE_KEY_RE.test(k)) continue
        result[k.slice(0, 64)] = String(v).slice(0, 256)
        count++
    }

    return Object.keys(result).length > 0 ? result : undefined
}