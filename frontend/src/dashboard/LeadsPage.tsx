import { useState, useEffect, useCallback, useRef } from 'react'
import { leadsApi, feedbackApi, type Lead, type LeadPage } from '../api/leads'
import { LEADS_COUNT_CHANGED } from './DashboardLayout'
import s from './Leadspage.module.css'

// ─── Константы ────────────────────────────────────────────────────────────────

const STATUS_COLOR: Record<string, { bg: string; text: string }> = {
    NEW:     { bg: 'rgba(239,68,68,.12)',    text: '#dc2626' },
    VIEWED:  { bg: 'rgba(245,158,11,.12)',   text: '#d97706' },
    REPLIED: { bg: 'rgba(16,185,129,.12)',   text: '#059669' },
    IGNORED: { bg: 'rgba(107,114,128,.12)', text: '#6b7280' },
}

const STATUS_LABEL: Record<string, string> = {
    NEW:     'Новый',
    VIEWED:  'Просмотрен',
    REPLIED: 'Отвечено',
    IGNORED: 'Архив',
}

const FILTERS = [
    { value: '',        label: 'Активные' },
    { value: 'NEW',     label: 'Новые'    },
    { value: 'VIEWED',  label: 'Просмотр' },
    { value: 'REPLIED', label: 'Отвечено' },
    { value: 'IGNORED', label: 'Архив'    },
]

// Ключ для localStorage — скрытие плашки об оценках
const RATING_BANNER_KEY = 'aimly_rating_hint_v1'

// ─── Вспомогательные функции ──────────────────────────────────────────────────

function dispatchLeadsCountChanged(newCount: number) {
    window.dispatchEvent(
        new CustomEvent(LEADS_COUNT_CHANGED, { detail: { newCount } })
    )
}

function formatLeadDate(iso: string): string {
    try {
        const normalized = iso.includes('T') && !iso.endsWith('Z') && !iso.includes('+') && !iso.includes('-', 10)
            ? iso + 'Z'
            : iso
        const d = new Date(normalized)
        if (isNaN(d.getTime())) return ''
        const now = new Date()

        const startOfToday     = new Date(now.getFullYear(), now.getMonth(), now.getDate())
        const startOfYesterday = new Date(startOfToday.getTime() - 86_400_000)
        const time = d.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })

        if (d >= startOfToday)     return `сегодня, ${time}`
        if (d >= startOfYesterday) return `вчера, ${time}`

        const sameYear = d.getFullYear() === now.getFullYear()
        if (sameYear) {
            return `${d.toLocaleDateString('ru-RU', { day: 'numeric', month: 'long' })}, ${time}`
        }
        return `${d.toLocaleDateString('ru-RU', { day: 'numeric', month: 'short', year: 'numeric' })}, ${time}`
    } catch {
        return ''
    }
}

// ─── SVG-иконки ───────────────────────────────────────────────────────────────

const IconUser = () => (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/>
    </svg>
)

const IconExternalLink = () => (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/>
        <polyline points="15 3 21 3 21 9"/><line x1="10" y1="14" x2="21" y2="3"/>
    </svg>
)

const IconArchive = () => (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <polyline points="21 8 21 21 3 21 3 8"/><rect x="1" y="3" width="22" height="5"/>
        <line x1="10" y1="12" x2="14" y2="12"/>
    </svg>
)

const IconCheck = () => (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
        <polyline points="20 6 9 17 4 12"/>
    </svg>
)

const IconEye = () => (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/>
        <circle cx="12" cy="12" r="3"/>
    </svg>
)

const IconEyeOff = () => (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/>
        <line x1="1" y1="1" x2="23" y2="23"/>
    </svg>
)

const IconUndo = () => (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <polyline points="9 14 4 9 9 4"/>
        <path d="M20 20v-7a4 4 0 0 0-4-4H4"/>
    </svg>
)

const IconAI = ({ valid }: { valid: boolean }) => (
    <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
        {valid
            ? <polyline points="20 6 9 17 4 12"/>
            : <><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></>
        }
    </svg>
)

const IconChevron = ({ open }: { open: boolean }) => (
    <svg
        width="13" height="13"
        viewBox="0 0 24 24" fill="none" stroke="currentColor"
        strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round"
        style={{ transition: 'transform .2s', transform: open ? 'rotate(180deg)' : 'rotate(0deg)' }}
    >
        <polyline points="6 9 12 15 18 9"/>
    </svg>
)

const IconFileImport = () => (
    <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
        <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
        <polyline points="14 2 14 8 20 8"/>
        <line x1="12" y1="18" x2="12" y2="12"/>
        <polyline points="9 15 12 18 15 15"/>
    </svg>
)

// Иконка «большой палец вверх»
const IconThumbUp = ({ size = 14 }: { size?: number }) => (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M14 9V5a3 3 0 0 0-3-3l-4 9v11h11.28a2 2 0 0 0 2-1.7l1.38-9a2 2 0 0 0-2-2.3H14z"/>
        <path d="M7 22H4a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2h3"/>
    </svg>
)

// Иконка «большой палец вниз»
const IconThumbDown = ({ size = 14 }: { size?: number }) => (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M10 15v4a3 3 0 0 0 3 3l4-9V2H5.72a2 2 0 0 0-2 1.7l-1.38 9a2 2 0 0 0 2 2.3H10z"/>
        <path d="M17 2h2.67A2.31 2.31 0 0 1 22 4v7a2.31 2.31 0 0 1-2.33 2H17"/>
    </svg>
)

// Иконка часов — «ожидает оценки»
const IconClock = () => (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <circle cx="12" cy="12" r="10"/>
        <polyline points="12 6 12 12 16 14"/>
    </svg>
)

// Иконка уведомления Telegram
const IconTelegram = () => (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <line x1="22" y1="2" x2="11" y2="13"/>
        <polygon points="22 2 15 22 11 13 2 9 22 2"/>
    </svg>
)

// ─── Мелкие компоненты ────────────────────────────────────────────────────────

function ExportBadge() {
    return (
        <span style={{
            display: 'inline-flex', alignItems: 'center', gap: 4,
            fontSize: 10, fontWeight: 700,
            padding: '2px 7px', borderRadius: 100,
            background: 'rgba(139,92,246,.12)', color: '#7c3aed',
            border: '1px solid rgba(139,92,246,.25)',
            letterSpacing: '.2px', flexShrink: 0, whiteSpace: 'nowrap',
        }}>
            <IconFileImport />
            экспорт
        </span>
    )
}

// ─── Бейдж оценки в шапке карточки ───────────────────────────────────────────

function RatingBadge({ rating }: { rating: 'GOOD' | 'BAD' }) {
    const isGood = rating === 'GOOD'
    return (
        <span style={{
            display: 'inline-flex', alignItems: 'center', gap: 4,
            fontSize: 11, fontWeight: 700,
            padding: '3px 9px', borderRadius: 100,
            background: isGood ? 'rgba(16,185,129,.12)' : 'rgba(239,68,68,.1)',
            color:      isGood ? '#059669'              : '#dc2626',
            border:     `1px solid ${isGood ? 'rgba(16,185,129,.3)' : 'rgba(239,68,68,.25)'}`,
            flexShrink: 0, whiteSpace: 'nowrap',
            letterSpacing: '.1px',
        }}>
            {isGood ? <IconThumbUp size={10} /> : <IconThumbDown size={10} />}
            {isGood ? 'Оценён хорошо' : 'Нерелевантный'}
        </span>
    )
}

// ─── Баннер «оценки важны для AI» ────────────────────────────────────────────
// Показывается один раз, скрывается навсегда через localStorage.

function RatingHintBanner() {
    const [visible, setVisible] = useState(() =>
        localStorage.getItem(RATING_BANNER_KEY) !== '1'
    )

    if (!visible) return null

    const dismiss = () => {
        localStorage.setItem(RATING_BANNER_KEY, '1')
        setVisible(false)
    }

    return (
        <div style={{
            display: 'flex', alignItems: 'flex-start', gap: 12,
            background: 'rgba(99,102,241,.07)',
            border: '1.5px solid rgba(99,102,241,.22)',
            borderRadius: 14,
            padding: '14px 18px',
        }}>
            {/* Иконка */}
            <span style={{
                width: 32, height: 32, borderRadius: '50%', flexShrink: 0,
                background: 'rgba(99,102,241,.13)', color: '#4f46e5',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
            }}>
                <IconThumbUp size={15} />
            </span>

            {/* Текст */}
            <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{
                    fontSize: 13, fontWeight: 700,
                    color: 'var(--c-ink)', marginBottom: 4,
                }}>
                    Оценки лидов улучшают AI‑фильтрацию
                </div>
                <div style={{
                    fontSize: 12, color: 'var(--c-ink-2)', lineHeight: 1.55,
                }}>
                    Нажимайте <strong style={{ color: '#059669' }}>👍 Полезный</strong> или{' '}
                    <strong style={{ color: '#dc2626' }}>👎 Нерелевантный</strong> на каждый лид —
                    AI учится на ваших оценках и постепенно присылает только действительно нужные контакты.
                    Чем больше оценок — тем точнее фильтрация.
                </div>
            </div>

            {/* Кнопка закрыть */}
            <button
                onClick={dismiss}
                title="Больше не показывать"
                style={{
                    background: 'none', border: 'none', cursor: 'pointer',
                    color: 'var(--c-ink-3)', fontSize: 20, lineHeight: 1,
                    padding: '0 2px', flexShrink: 0, fontFamily: 'inherit',
                    opacity: 0.55, transition: 'opacity .15s', marginTop: -2,
                }}
                onMouseEnter={e => { (e.currentTarget as HTMLButtonElement).style.opacity = '1' }}
                onMouseLeave={e => { (e.currentTarget as HTMLButtonElement).style.opacity = '0.55' }}
            >
                ×
            </button>
        </div>
    )
}

// ─── Компонент кнопок оценки ──────────────────────────────────────────────────

interface FeedbackButtonsProps {
    leadId:    number
    current:   'GOOD' | 'BAD' | null
    disabled:  boolean
    onRate:    (leadId: number, rating: 'GOOD' | 'BAD') => Promise<void>
    // true — лид отправлялся в TG, поэтому оценка важна для разблокировки очереди
    isBlocking: boolean
}

function FeedbackButtons({ leadId, current, disabled, onRate, isBlocking }: FeedbackButtonsProps) {
    const [submitting, setSubmitting] = useState(false)

    const handle = async (rating: 'GOOD' | 'BAD') => {
        if (submitting || disabled) return
        // Клик на уже выбранную оценку — не делаем ничего (upsert делать нет смысла)
        if (current === rating) return
        setSubmitting(true)
        try {
            await onRate(leadId, rating)
        } finally {
            setSubmitting(false)
        }
    }

    const isGood = current === 'GOOD'
    const isBad  = current === 'BAD'
    const busy   = submitting || disabled

    const baseBtn: React.CSSProperties = {
        display:        'inline-flex',
        alignItems:     'center',
        gap:            5,
        padding:        '5px 11px',
        borderRadius:   100,
        fontSize:       12,
        fontWeight:     600,
        cursor:         busy ? 'default' : 'pointer',
        transition:     'all .15s',
        fontFamily:     'var(--font-body)',
        opacity:        busy ? 0.6 : 1,
        whiteSpace:     'nowrap',
        flexShrink:     0,
    }

    return (
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            {/* Подсказка для блокирующего лида */}
            {isBlocking && current === null && (
                <span style={{
                    fontSize: 11, color: 'var(--c-ink-3)', fontWeight: 500,
                    display: 'flex', alignItems: 'center', gap: 4, marginRight: 2,
                }}>
                    <IconClock />
                    оцените:
                </span>
            )}

            {/* GOOD — всегда зелёная, насыщеннее при выборе */}
            <button
                onClick={() => void handle('GOOD')}
                disabled={busy}
                title={isGood ? 'Полезный лид (изменить)' : 'Отметить как полезный'}
                style={{
                    ...baseBtn,
                    border:     isGood
                        ? '1.5px solid rgba(16,185,129,.7)'
                        : '1.5px solid rgba(16,185,129,.35)',
                    background: isGood
                        ? 'rgba(16,185,129,.15)'
                        : 'rgba(16,185,129,.06)',
                    color:      '#059669',
                }}
                onMouseEnter={e => {
                    if (busy || isGood) return
                    const el = e.currentTarget
                    el.style.borderColor = 'rgba(16,185,129,.55)'
                    el.style.background  = 'rgba(16,185,129,.11)'
                }}
                onMouseLeave={e => {
                    if (busy || isGood) return
                    const el = e.currentTarget
                    el.style.borderColor = 'rgba(16,185,129,.35)'
                    el.style.background  = 'rgba(16,185,129,.06)'
                }}
            >
                <IconThumbUp />
                {isGood ? 'Полезный' : 'Полезный'}
            </button>

            {/* BAD — всегда красная, насыщеннее при выборе */}
            <button
                onClick={() => void handle('BAD')}
                disabled={busy}
                title={isBad ? 'Нерелевантный лид (изменить)' : 'Отметить как нерелевантный'}
                style={{
                    ...baseBtn,
                    border:     isBad
                        ? '1.5px solid rgba(239,68,68,.65)'
                        : '1.5px solid rgba(239,68,68,.3)',
                    background: isBad
                        ? 'rgba(239,68,68,.12)'
                        : 'rgba(239,68,68,.05)',
                    color:      '#dc2626',
                }}
                onMouseEnter={e => {
                    if (busy || isBad) return
                    const el = e.currentTarget
                    el.style.borderColor = 'rgba(239,68,68,.5)'
                    el.style.background  = 'rgba(239,68,68,.09)'
                }}
                onMouseLeave={e => {
                    if (busy || isBad) return
                    const el = e.currentTarget
                    el.style.borderColor = 'rgba(239,68,68,.3)'
                    el.style.background  = 'rgba(239,68,68,.05)'
                }}
            >
                <IconThumbDown />
                {isBad ? 'Нерелевантный' : 'Нерелевантный'}
            </button>
        </div>
    )
}

// ─── Баннер «неоценённый лид блокирует очередь» ──────────────────────────────

interface BlockingBannerProps {
    lead:       Lead
    queueSize:  number
    onRate:     (leadId: number, rating: 'GOOD' | 'BAD') => Promise<void>
    onScrollTo: (leadId: number) => void
}

function BlockingBanner({ lead, queueSize, onRate, onScrollTo }: BlockingBannerProps) {
    const [rating, setRating]     = useState<'GOOD' | 'BAD' | null>(lead.userRating)
    const [submitting, setSubmit] = useState(false)

    const handle = async (r: 'GOOD' | 'BAD') => {
        if (submitting || r === rating) return
        setSubmit(true)
        try {
            await onRate(lead.id, r)
            setRating(r)
        } finally {
            setSubmit(false)
        }
    }

    const rated = rating !== null

    return (
        <div style={{
            background:   'var(--c-surface)',
            border:       `1.5px solid ${rated ? 'rgba(16,185,129,.3)' : 'rgba(245,158,11,.35)'}`,
            borderRadius: 14,
            padding:      '16px 20px',
            display:      'flex',
            flexDirection:'column',
            gap:          12,
        }}>
            {/* Заголовок */}
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <span style={{
                        width: 28, height: 28, borderRadius: '50%', flexShrink: 0,
                        background: rated ? 'rgba(16,185,129,.12)' : 'rgba(245,158,11,.12)',
                        color:      rated ? '#059669'              : '#d97706',
                        display: 'flex', alignItems: 'center', justifyContent: 'center',
                    }}>
                        {rated ? <IconCheck /> : <IconTelegram />}
                    </span>
                    <div>
                        <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--c-ink)' }}>
                            {rated
                                ? 'Оценка сохранена — следующий лид отправлен в Telegram'
                                : 'Оцените лид, чтобы получить следующий в Telegram'
                            }
                        </div>
                        {!rated && queueSize > 0 && (
                            <div style={{ fontSize: 12, color: 'var(--c-ink-3)', marginTop: 2 }}>
                                {queueSize === 1
                                    ? 'Ещё 1 лид ожидает доставки'
                                    : `Ещё ${queueSize} лидов ожидают доставки`
                                }
                            </div>
                        )}
                    </div>
                </div>

                {/* Ссылка «Перейти к лиду» */}
                <button
                    onClick={() => onScrollTo(lead.id)}
                    style={{
                        fontSize: 12, fontWeight: 600, color: 'var(--c-accent)',
                        background: 'none', border: 'none', cursor: 'pointer',
                        fontFamily: 'var(--font-body)', padding: 0,
                        flexShrink: 0,
                    }}
                >
                    Перейти к лиду →
                </button>
            </div>

            {/* Превью лида */}
            <div style={{
                background:   'var(--c-bg)',
                border:       '1px solid var(--c-border)',
                borderRadius: 10,
                padding:      '10px 14px',
                display:      'flex',
                flexDirection:'column',
                gap:          8,
            }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                    <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--c-ink)' }}>
                        {lead.authorName || lead.authorUsername || 'Аноним'}
                    </span>
                    {lead.authorUsername && (
                        <span style={{ fontSize: 12, color: 'var(--c-ink-3)' }}>@{lead.authorUsername}</span>
                    )}
                    <span style={{
                        fontSize: 11, fontWeight: 700, padding: '2px 8px', borderRadius: 100,
                        background: 'rgba(245,158,11,.12)', color: '#d97706',
                        border: '1px solid rgba(245,158,11,.3)',
                    }}>
                        {lead.matchedKeyword}
                    </span>
                </div>
                <p style={{
                    fontSize: 13, color: 'var(--c-ink-2)', lineHeight: 1.55,
                    margin: 0, display: '-webkit-box',
                    WebkitLineClamp: 3, WebkitBoxOrient: 'vertical',
                    overflow: 'hidden',
                }}>
                    {lead.messageText}
                </p>
            </div>

            {/* Кнопки оценки в баннере */}
            {!rated && (
                <FeedbackButtons
                    leadId={lead.id}
                    current={rating}
                    disabled={submitting}
                    onRate={async (id, r) => { await handle(r); await onRate(id, r) }}
                    isBlocking={true}
                />
            )}
        </div>
    )
}

// ─── Основной компонент ───────────────────────────────────────────────────────

export default function LeadsPage() {
    const [data,        setData]        = useState<LeadPage | null>(null)
    const [loading,     setLoading]     = useState(true)
    const [filter,      setFilter]      = useState('')
    const [page,        setPage]        = useState(0)
    const [error,       setError]       = useState('')
    const [updating,    setUpdating]    = useState<Set<number>>(new Set())
    const [expanded,    setExpanded]    = useState<Set<number>>(new Set())
    const [markingAll,  setMarkingAll]  = useState(false)

    // Локальная карта оценок leadId → rating.
    // Всегда инициализируется из userRating бэка при загрузке.
    // Обновляется оптимистично при клике, но не блокирует повторную синхронизацию с БД.
    const [ratings, setRatings] = useState<Record<number, 'GOOD' | 'BAD' | null>>({})
    // Лиды, у которых оценка сейчас «в полёте» (отправляется на бэк)
    const [ratingBusy, setRatingBusy] = useState<Set<number>>(new Set())

    // Статус очереди TG-уведомлений
    const [queueSize, setQueueSize] = useState(0)

    // Рефы для прокрутки к карточке лида
    const cardRefs = useRef<Record<number, HTMLDivElement | null>>({})

    // ── Загрузка ──────────────────────────────────────────────────────────────

    const load = useCallback(async () => {
        setLoading(true)
        setError('')
        try {
            const [result, status] = await Promise.all([
                leadsApi.list({ status: filter || undefined, page, size: 20 }),
                feedbackApi.getStatus(),
            ])
            setData(result)
            setQueueSize(status.queueSize)
            dispatchLeadsCountChanged(result.newCount)

            // Синхронизируем локальную карту оценок из данных бэка.
            // Бэк возвращает userRating прямо в DTO лида через batch-запрос к lead_feedbacks.
            // Не перезаписываем только те лиды, по которым прямо сейчас летит запрос (ratingBusy),
            // чтобы не сбросить оптимистичное обновление в момент in-flight запроса.
            setRatings(prev => {
                const next = { ...prev }
                result.content.forEach(lead => {
                    // Пропускаем только если запрос на оценку прямо сейчас в полёте
                    // (проверяем через ratingBusy в момент вызова setRatings)
                    next[lead.id] = lead.userRating ?? null
                })
                return next
            })
        } catch (e: unknown) {
            setError(e instanceof Error ? e.message : 'Ошибка загрузки')
        } finally {
            setLoading(false)
        }
    }, [filter, page])

    useEffect(() => { void load() }, [load])

    // ── Смена фильтра ─────────────────────────────────────────────────────────

    const changeFilter = (value: string) => {
        setFilter(value)
        setPage(0)
    }

    // ── Развернуть/свернуть карточку ─────────────────────────────────────────

    const toggleExpand = (id: number) => {
        setExpanded(prev => {
            const next = new Set(prev)
            if (next.has(id)) {
                next.delete(id)
            } else {
                next.add(id)
            }
            return next
        })
    }

    // ── Изменение статуса ─────────────────────────────────────────────────────

    const applyStatusUpdate = (updated: Lead, previousStatus: string) => {
        setData(prev => {
            if (!prev) return prev
            const content = prev.content.map(l => l.id === updated.id ? updated : l)
            let newCount = prev.newCount
            if (previousStatus === 'NEW' && updated.status !== 'NEW') newCount = Math.max(0, newCount - 1)
            if (previousStatus !== 'NEW' && updated.status === 'NEW') newCount = newCount + 1
            dispatchLeadsCountChanged(newCount)
            return { ...prev, content, newCount }
        })
    }

    const setStatus = async (lead: Lead, status: string) => {
        if (lead.status === status) return
        const previousStatus = lead.status
        setUpdating(prev => new Set(prev).add(lead.id))
        try {
            const updated = await leadsApi.updateStatus(lead.id, status)
            applyStatusUpdate(updated, previousStatus)
        } catch {
            // silent
        } finally {
            setUpdating(prev => { const s = new Set(prev); s.delete(lead.id); return s })
        }
    }

    // ── Оценка лида ───────────────────────────────────────────────────────────

    /**
     * Отправляет оценку лида на бэк.
     * Поддерживает upsert — повторный вызов меняет оценку.
     * Оптимистичное обновление: rating ставим немедленно, до ответа сервера.
     * При ошибке — откатываем на предыдущее значение.
     */
    const submitRating = async (leadId: number, rating: 'GOOD' | 'BAD') => {
        const prevRating = ratings[leadId] ?? null

        // Оптимистичное обновление UI
        setRatings(prev => ({ ...prev, [leadId]: rating }))
        setRatingBusy(prev => new Set(prev).add(leadId))
        try {
            const res = await feedbackApi.submit(leadId, rating)
            // Обновляем счётчик очереди из ответа бэка
            if (res.queueEmpty) {
                setQueueSize(0)
            } else {
                // Точный размер очереди узнаём отдельным запросом
                const status = await feedbackApi.getStatus()
                setQueueSize(status.queueSize)
            }
            // Если лид был NEW — при первой оценке бэк автоматически ставит VIEWED.
            // Обновляем локально.
            setData(prev => {
                if (!prev) return prev
                return {
                    ...prev,
                    content: prev.content.map(l => {
                        if (l.id !== leadId) return l
                        const wasNew = l.status === 'NEW'
                        return { ...l, userRating: rating, status: wasNew ? 'VIEWED' : l.status }
                    }),
                }
            })
        } catch {
            // Откатываем оптимистичное обновление
            setRatings(prev => ({ ...prev, [leadId]: prevRating }))
        } finally {
            setRatingBusy(prev => { const s = new Set(prev); s.delete(leadId); return s })
        }
    }

    // ── Отметить все прочитанными ─────────────────────────────────────────────

    const handleMarkAllRead = async () => {
        if (markingAll) return
        setMarkingAll(true)
        try {
            await leadsApi.markAllRead()
            setData(prev => {
                if (!prev) return prev
                const content = prev.content.map(l =>
                    l.status === 'NEW' ? { ...l, status: 'VIEWED' as const } : l
                )
                dispatchLeadsCountChanged(0)
                return { ...prev, content, newCount: 0 }
            })
        } catch {
            // silent
        } finally {
            setMarkingAll(false)
        }
    }

    // ── Открытие лида по ссылке ───────────────────────────────────────────────

    const handleOpen = (lead: Lead) => {
        if (lead.status === 'NEW') void setStatus(lead, 'VIEWED')
    }

    // ── Прокрутка к карточке ──────────────────────────────────────────────────

    const scrollToLead = (leadId: number) => {
        const el = cardRefs.current[leadId]
        if (el) {
            el.scrollIntoView({ behavior: 'smooth', block: 'center' })
            // Краткая подсветка
            el.style.transition = 'box-shadow .2s'
            el.style.boxShadow  = '0 0 0 2.5px var(--c-accent)'
            setTimeout(() => { el.style.boxShadow = '' }, 1200)
        }
    }

    // ── Данные ────────────────────────────────────────────────────────────────

    const visibleContent = (data?.content ?? []).filter(lead =>
        filter === '' ? lead.status !== 'IGNORED' : true
    )

    /**
     * «Блокирующий» лид — последний, который был доставлен в TG (tgNotifiedAt != null),
     * но ещё не оценён. Именно из-за него следующие лиды сидят в очереди.
     * Показываем в баннере.
     */
    const blockingLead = visibleContent.find(lead =>
        lead.tgNotifiedAt !== null && (ratings[lead.id] ?? lead.userRating) === null
    ) ?? null

    // ── Рендер: загрузка ──────────────────────────────────────────────────────

    if (loading) {
        return (
            <div className={s.page}>
                <div className={s.header}>
                    <h1 className={s.title}>Лиды</h1>
                    <div className={s.tabs}>
                        {FILTERS.map(f => (
                            <button key={f.value} className={s.tab}>{f.label}</button>
                        ))}
                    </div>
                </div>
                <div className={s.empty} style={{ color: 'var(--c-ink-3)', fontSize: 14 }}>
                    Загрузка...
                </div>
            </div>
        )
    }

    // ── Рендер: ошибка ────────────────────────────────────────────────────────

    if (error) {
        return (
            <div className={s.page}>
                <div className={s.header}>
                    <h1 className={s.title}>Лиды</h1>
                </div>
                <div className={s.empty} style={{ color: '#dc2626', fontSize: 14 }}>{error}</div>
            </div>
        )
    }

    // ── Рендер: основной ──────────────────────────────────────────────────────

    return (
        <div className={s.page}>
            <div className={s.header}>
                <h1 className={s.title}>Лиды</h1>

                <div className={s.tabs}>
                    {FILTERS.map(f => (
                        <button
                            key={f.value}
                            className={`${s.tab} ${filter === f.value ? s.tabActive : ''}`}
                            onClick={() => changeFilter(f.value)}
                        >
                            {f.label}
                        </button>
                    ))}
                </div>

                {(data?.newCount ?? 0) > 0 && (
                    <button
                        className={s.markAllBtn}
                        onClick={() => void handleMarkAllRead()}
                        disabled={markingAll}
                    >
                        {markingAll ? 'Отмечаем...' : 'Отметить все прочитанными'}
                    </button>
                )}
            </div>

            {/* ── Плашка «оценки улучшают AI» — показывается один раз ── */}
            <RatingHintBanner />

            {/* ── Баннер блокирующего лида ── */}
            {blockingLead && queueSize > 0 && (
                <BlockingBanner
                    lead={blockingLead}
                    queueSize={queueSize}
                    onRate={submitRating}
                    onScrollTo={scrollToLead}
                />
            )}

            {visibleContent.length === 0 ? (
                <div className={s.empty}>
                    <svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ opacity: 0.25, marginBottom: 8 }}>
                        <path d="M22 12h-4l-3 9L9 3l-3 9H2"/>
                    </svg>
                    <span>Лидов нет</span>
                </div>
            ) : (
                <>
                    <div className={s.leadsList}>
                        {visibleContent.map(lead => {
                            const color       = STATUS_COLOR[lead.status] ?? STATUS_COLOR.VIEWED
                            const isNew       = lead.status === 'NEW'
                            const isViewed    = lead.status === 'VIEWED'
                            const isReplied   = lead.status === 'REPLIED'
                            const isArchived  = lead.status === 'IGNORED'
                            const isUpdating  = updating.has(lead.id)
                            const isExpanded  = expanded.has(lead.id)
                            const contextMsgs = lead.contextMessages ?? []

                            const currentRating = ratings[lead.id] !== undefined
                                ? ratings[lead.id]
                                : (lead.userRating ?? null)
                            const ratingLoading = ratingBusy.has(lead.id)

                            // Лид является «блокирующим»: отправлен в TG, но не оценён
                            const isBlocking = lead.tgNotifiedAt !== null && currentRating === null

                            return (
                                <div
                                    key={lead.id}
                                    ref={el => { cardRefs.current[lead.id] = el }}
                                    className={s.leadCard}
                                    style={{
                                        // Блокирующий лид — лёгкий жёлтый акцент рамки
                                        outline: isBlocking
                                            ? '2px solid rgba(245,158,11,.35)'
                                            : undefined,
                                    }}
                                >
                                    {/* Заголовок карточки */}
                                    <div className={s.leadHeader}>
                                        <div className={s.leadMeta}>
                                            <span style={{ fontSize: 12, color: 'var(--c-ink-3)', flexShrink: 0 }}>
                                                {formatLeadDate(lead.messageDate || lead.foundAt)}
                                            </span>

                                            {lead.source === 'MANUAL_EXPORT' && <ExportBadge />}

                                            {lead.matchedKeyword && (
                                                <span style={{
                                                    fontSize: 11, fontWeight: 700,
                                                    padding: '2px 8px', borderRadius: 100,
                                                    background: 'rgba(99,102,241,.1)', color: '#4f46e5',
                                                    border: '1px solid rgba(99,102,241,.2)',
                                                    letterSpacing: '.15px', flexShrink: 0, whiteSpace: 'nowrap',
                                                }}>
                                                    {lead.matchedKeyword}
                                                </span>
                                            )}

                                            {/* Бейдж оценки — показываем в шапке, если лид уже оценён */}
                                            {currentRating !== null && (
                                                <RatingBadge rating={currentRating} />
                                            )}
                                        </div>

                                        {/* Статус-бейдж */}
                                        <button
                                            onClick={() => {
                                                if (isNew)    void setStatus(lead, 'VIEWED')
                                                if (isViewed) void setStatus(lead, 'NEW')
                                            }}
                                            disabled={isUpdating || isReplied || isArchived}
                                            title={
                                                isNew    ? 'Пометить прочитанным'   :
                                                    isViewed ? 'Пометить непрочитанным' :
                                                        undefined
                                            }
                                            style={{
                                                fontSize: 11, fontWeight: 700,
                                                padding: '3px 10px', borderRadius: 100,
                                                flexShrink: 0, letterSpacing: '.2px',
                                                border: 'none',
                                                background: color.bg, color: color.text,
                                                cursor: (isNew || isViewed) && !isUpdating ? 'pointer' : 'default',
                                                transition: 'opacity .15s',
                                                display: 'flex', alignItems: 'center', gap: 4,
                                                opacity: isUpdating ? 0.5 : 1,
                                            }}
                                        >
                                            {STATUS_LABEL[lead.status] ?? lead.status}
                                            {(isNew || isViewed) && !isUpdating && (
                                                isNew ? <IconEye /> : <IconEyeOff />
                                            )}
                                        </button>
                                    </div>

                                    {/* Текст сообщения */}
                                    <div className={s.leadTextBlock}>
                                        <div className={s.leadTextInner}>
                                            <div className={s.leadText}>{lead.messageText}</div>
                                        </div>
                                    </div>

                                    {/* Детали (AI + контекст) */}
                                    {(contextMsgs.length > 0 || (lead.aiValid !== null && lead.aiValid !== undefined)) && (
                                        <button
                                            onClick={() => toggleExpand(lead.id)}
                                            style={{
                                                display: 'flex', alignItems: 'center', gap: 5,
                                                padding: '4px 0', background: 'none', border: 'none',
                                                color: 'var(--c-ink-3)', fontSize: 12, fontWeight: 500,
                                                cursor: 'pointer', fontFamily: 'var(--font-body)',
                                                width: 'fit-content', transition: 'color .15s',
                                            }}
                                            onMouseEnter={e => { e.currentTarget.style.color = 'var(--c-accent)' }}
                                            onMouseLeave={e => { e.currentTarget.style.color = 'var(--c-ink-3)' }}
                                        >
                                            <IconChevron open={isExpanded} />
                                            {isExpanded
                                                ? 'Скрыть детали'
                                                : `Детали${contextMsgs.length > 0 ? ` · ${contextMsgs.length} сообщ.` : ''}${lead.aiValid !== null ? ' · AI' : ''}`
                                            }
                                        </button>
                                    )}

                                    {isExpanded && (
                                        <div style={{
                                            display: 'flex', flexDirection: 'column', gap: 10,
                                            padding: '10px 14px', background: 'var(--c-bg)',
                                            borderRadius: 10, border: '1px solid var(--c-border)',
                                        }}>
                                            {lead.aiValid !== null && lead.aiValid !== undefined && (
                                                <div style={{
                                                    display: 'flex', alignItems: 'flex-start', gap: 8,
                                                    padding: '8px 12px', borderRadius: 8,
                                                    background: lead.aiValid ? 'rgba(16,185,129,.08)' : 'rgba(239,68,68,.08)',
                                                    border: `1px solid ${lead.aiValid ? 'rgba(16,185,129,.2)' : 'rgba(239,68,68,.2)'}`,
                                                }}>
                                                    <span style={{
                                                        display: 'flex', alignItems: 'center', gap: 4,
                                                        fontSize: 11, fontWeight: 700,
                                                        color: lead.aiValid ? '#10b981' : '#ef4444',
                                                        flexShrink: 0, marginTop: 1,
                                                    }}>
                                                        <IconAI valid={lead.aiValid} />
                                                        AI {lead.aiValid ? 'одобрил' : 'отклонил'}
                                                    </span>
                                                    {lead.aiReason && (
                                                        <span style={{ fontSize: 12, color: 'var(--c-ink-2)', lineHeight: 1.5 }}>
                                                            {lead.aiReason}
                                                        </span>
                                                    )}
                                                </div>
                                            )}

                                            {contextMsgs.length > 0 && (
                                                <div>
                                                    <div style={{
                                                        fontSize: 11, fontWeight: 700, textTransform: 'uppercase',
                                                        letterSpacing: '.5px', color: 'var(--c-ink-3)', marginBottom: 8,
                                                    }}>
                                                        Контекст чата
                                                    </div>
                                                    <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                                                        {contextMsgs.map((msg, i) => (
                                                            <div key={i} style={{
                                                                fontSize: 13, color: 'var(--c-ink-2)', lineHeight: 1.5,
                                                                padding: '6px 10px',
                                                                borderLeft: '2px solid var(--c-border)',
                                                                background: 'var(--c-surface)',
                                                                borderRadius: '0 6px 6px 0',
                                                                wordBreak: 'break-word',
                                                                whiteSpace: 'pre-wrap',
                                                            }}>
                                                                {msg}
                                                            </div>
                                                        ))}
                                                    </div>
                                                </div>
                                            )}
                                        </div>
                                    )}

                                    {/* Футер: автор + действия */}
                                    <div className={s.leadFooter}>
                                        <div className={s.leadAuthor}>
                                            <span style={{ color: 'var(--c-ink-3)', flexShrink: 0 }}><IconUser /></span>
                                            <span className={s.authorName}>{lead.authorName || 'Аноним'}</span>
                                            {lead.authorUsername && (
                                                <span className={s.authorUsername}>@{lead.authorUsername}</span>
                                            )}
                                        </div>

                                        <div className={s.leadActions}>
                                            {/* AI-бейдж */}
                                            {lead.aiValid !== null && lead.aiValid !== undefined && (
                                                <div
                                                    className={s.aiBadge}
                                                    style={{
                                                        background: lead.aiValid ? 'rgba(16,185,129,.12)' : 'rgba(239,68,68,.12)',
                                                        color: lead.aiValid ? '#10b981' : '#ef4444',
                                                    }}
                                                >
                                                    <IconAI valid={lead.aiValid} />
                                                    AI
                                                </div>
                                            )}

                                            {/* Кнопки оценки — показываем для всех лидов кроме архива */}
                                            {!isArchived && (
                                                <FeedbackButtons
                                                    leadId={lead.id}
                                                    current={currentRating}
                                                    disabled={ratingLoading}
                                                    onRate={submitRating}
                                                    isBlocking={isBlocking}
                                                />
                                            )}

                                            {/* Кнопка «Ответил» */}
                                            {(isNew || isViewed) && (
                                                <button
                                                    onClick={() => void setStatus(lead, 'REPLIED')}
                                                    disabled={isUpdating}
                                                    title="Отметить как отвечено"
                                                    style={{
                                                        display: 'flex', alignItems: 'center', gap: 4,
                                                        padding: '5px 12px', borderRadius: 100,
                                                        border: '1.5px solid rgba(16,185,129,.45)',
                                                        background: 'rgba(16,185,129,.08)',
                                                        color: '#059669', fontSize: 12,
                                                        fontWeight: 700, cursor: 'pointer',
                                                        fontFamily: 'var(--font-body)', transition: 'all .15s',
                                                    }}
                                                    onMouseEnter={e => {
                                                        (e.currentTarget as HTMLButtonElement).style.background = 'rgba(16,185,129,.18)'
                                                        ;(e.currentTarget as HTMLButtonElement).style.borderColor = 'rgba(16,185,129,.7)'
                                                    }}
                                                    onMouseLeave={e => {
                                                        (e.currentTarget as HTMLButtonElement).style.background = 'rgba(16,185,129,.08)'
                                                        ;(e.currentTarget as HTMLButtonElement).style.borderColor = 'rgba(16,185,129,.45)'
                                                    }}
                                                >
                                                    <IconCheck />
                                                    Ответил
                                                </button>
                                            )}

                                            {/* Вернуть из «Отвечено» */}
                                            {isReplied && (
                                                <button
                                                    onClick={() => void setStatus(lead, 'VIEWED')}
                                                    disabled={isUpdating}
                                                    title="Вернуть в просмотренные"
                                                    style={{
                                                        display: 'flex', alignItems: 'center', gap: 4,
                                                        padding: '5px 12px', borderRadius: 100,
                                                        border: '1.5px solid var(--c-border)',
                                                        background: 'none',
                                                        color: 'var(--c-ink-3)', fontSize: 12,
                                                        fontWeight: 600, cursor: 'pointer',
                                                        fontFamily: 'var(--font-body)', transition: 'all .15s',
                                                    }}
                                                    onMouseEnter={e => {
                                                        (e.currentTarget as HTMLButtonElement).style.borderColor = '#d97706'
                                                        ;(e.currentTarget as HTMLButtonElement).style.color = '#d97706'
                                                    }}
                                                    onMouseLeave={e => {
                                                        (e.currentTarget as HTMLButtonElement).style.borderColor = 'var(--c-border)'
                                                        ;(e.currentTarget as HTMLButtonElement).style.color = 'var(--c-ink-3)'
                                                    }}
                                                >
                                                    <IconUndo />
                                                    Вернуть
                                                </button>
                                            )}

                                            {/* В архив */}
                                            {!isArchived && (
                                                <button
                                                    onClick={() => void setStatus(lead, 'IGNORED')}
                                                    disabled={isUpdating}
                                                    title="В архив"
                                                    style={{
                                                        display: 'flex', alignItems: 'center', gap: 4,
                                                        padding: '5px 12px', borderRadius: 100,
                                                        border: '1.5px solid var(--c-border)',
                                                        background: 'none',
                                                        color: 'var(--c-ink-3)', fontSize: 12,
                                                        fontWeight: 600, cursor: 'pointer',
                                                        fontFamily: 'var(--font-body)', transition: 'all .15s',
                                                    }}
                                                    onMouseEnter={e => {
                                                        (e.currentTarget as HTMLButtonElement).style.borderColor = '#6b7280'
                                                        ;(e.currentTarget as HTMLButtonElement).style.color = '#6b7280'
                                                    }}
                                                    onMouseLeave={e => {
                                                        (e.currentTarget as HTMLButtonElement).style.borderColor = 'var(--c-border)'
                                                        ;(e.currentTarget as HTMLButtonElement).style.color = 'var(--c-ink-3)'
                                                    }}
                                                >
                                                    <IconArchive />
                                                    В архив
                                                </button>
                                            )}

                                            {/* Восстановить из архива */}
                                            {isArchived && (
                                                <button
                                                    onClick={() => void setStatus(lead, 'NEW')}
                                                    disabled={isUpdating}
                                                    title="Вернуть из архива"
                                                    style={{
                                                        display: 'flex', alignItems: 'center', gap: 4,
                                                        padding: '5px 12px', borderRadius: 100,
                                                        border: '1.5px solid var(--c-border)',
                                                        background: 'none',
                                                        color: 'var(--c-ink-3)', fontSize: 12,
                                                        fontWeight: 600, cursor: 'pointer',
                                                        fontFamily: 'var(--font-body)', transition: 'all .15s',
                                                    }}
                                                >
                                                    Восстановить
                                                </button>
                                            )}

                                            {/* Открыть сообщение */}
                                            {lead.messageLink && (
                                                <a
                                                    href={lead.messageLink}
                                                    target="_blank"
                                                    rel="noopener noreferrer"
                                                    className={s.openLink}
                                                    style={{ display: 'flex', alignItems: 'center', gap: 4 }}
                                                    onClick={() => handleOpen(lead)}
                                                >
                                                    Открыть
                                                    <IconExternalLink />
                                                </a>
                                            )}
                                        </div>
                                    </div>
                                </div>
                            )
                        })}
                    </div>

                    {data && data.totalPages > 1 && (
                        <div className={s.pagination}>
                            {page > 0 && (
                                <button className={s.pageBtn} onClick={() => setPage(p => p - 1)}>
                                    Назад
                                </button>
                            )}
                            <span className={s.pageInfo}>
                                {page + 1} / {data.totalPages}
                            </span>
                            {page < data.totalPages - 1 && (
                                <button className={s.pageBtn} onClick={() => setPage(p => p + 1)}>
                                    Вперёд
                                </button>
                            )}
                        </div>
                    )}
                </>
            )}
        </div>
    )
}