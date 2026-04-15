import { useState, useEffect, useCallback, useRef } from 'react'
import { leadsApi, feedbackApi, type Lead, type LeadPage, type FeedbackStatus } from '../api/leads'
import { LEADS_COUNT_CHANGED } from './DashboardLayout'
import s from './Leadspage.module.css'

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

// Интервал фонового поллинга оценок (сделанных с бота) — 30 секунд
const RATINGS_POLL_INTERVAL = 30_000

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
        const d   = new Date(normalized)
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

// ─── SVG иконки ──────────────────────────────────────────────────────────────

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

// Иконка большого пальца вверх (лайк)
const IconThumbUp = ({ size = 16 }: { size?: number }) => (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M14 9V5a3 3 0 0 0-3-3l-4 9v11h11.28a2 2 0 0 0 2-1.7l1.38-9a2 2 0 0 0-2-2.3H14z"/>
        <path d="M7 22H4a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2h3"/>
    </svg>
)

// Иконка большого пальца вниз (дизлайк)
const IconThumbDown = ({ size = 16 }: { size?: number }) => (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M10 15v4a3 3 0 0 0 3 3l4-9V2H5.72a2 2 0 0 0-2 1.7l-1.38 9a2 2 0 0 0 2 2.3H10z"/>
        <path d="M17 2h2.67A2.31 2.31 0 0 1 22 4v7a2.31 2.31 0 0 1-2.33 2H17"/>
    </svg>
)

// Иконка замка — для заблокированных лидов
const IconLock = () => (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <rect x="3" y="11" width="18" height="11" rx="2" ry="2"/>
        <path d="M7 11V7a5 5 0 0 1 10 0v4"/>
    </svg>
)

// Иконка колокольчика — для баннера очереди
const IconBell = () => (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"/>
        <path d="M13.73 21a2 2 0 0 1-3.46 0"/>
    </svg>
)

// Иконка мозга/AI — для подсказки про важность оценок
const IconSparkle = () => (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z"/>
    </svg>
)

function ExportBadge() {
    return (
        <span style={{
            display:       'inline-flex',
            alignItems:    'center',
            gap:           4,
            fontSize:      10,
            fontWeight:    700,
            padding:       '2px 7px',
            borderRadius:  100,
            background:    'rgba(139,92,246,.12)',
            color:         '#7c3aed',
            border:        '1px solid rgba(139,92,246,.25)',
            letterSpacing: '.2px',
            flexShrink:    0,
            whiteSpace:    'nowrap',
        }}>
            <IconFileImport />
            экспорт
        </span>
    )
}

// ─── Компонент кнопок оценки (правая колонка карточки) ──────────────────────

interface FeedbackButtonsProps {
    leadId:    number
    rating:    'GOOD' | 'BAD' | null
    disabled?: boolean
    onVote:    (leadId: number, rating: 'GOOD' | 'BAD') => Promise<void>
}

function FeedbackButtons({ leadId, rating, disabled, onVote }: FeedbackButtonsProps) {
    const [submitting, setSubmitting] = useState(false)

    const handleVote = async (r: 'GOOD' | 'BAD') => {
        if (submitting || disabled) return
        setSubmitting(true)
        try {
            await onVote(leadId, r)
        } finally {
            setSubmitting(false)
        }
    }

    const isGood = rating === 'GOOD'
    const isBad  = rating === 'BAD'

    return (
        <div style={{
            display:        'flex',
            flexDirection:  'column',
            alignItems:     'center',
            gap:            6,
            padding:        '12px 10px',
            borderLeft:     '1px solid var(--c-border)',
            flexShrink:     0,
            alignSelf:      'stretch',
            justifyContent: 'center',
            minWidth:       56,
        }}>
            {/* Метка */}
            {rating === null && !disabled && (
                <span style={{
                    fontSize:    9,
                    color:       'var(--c-ink-3)',
                    fontWeight:  600,
                    letterSpacing: '.3px',
                    textTransform: 'uppercase',
                    textAlign:   'center',
                    lineHeight:  1.2,
                    marginBottom: 2,
                }}>
                    Оцените
                </span>
            )}
            {rating !== null && (
                <span style={{
                    fontSize:    9,
                    color:       isGood ? '#059669' : '#dc2626',
                    fontWeight:  700,
                    letterSpacing: '.3px',
                    textTransform: 'uppercase',
                    textAlign:   'center',
                    lineHeight:  1.2,
                    marginBottom: 2,
                }}>
                    {isGood ? 'Хороший' : 'Мусор'}
                </span>
            )}

            {/* Лайк */}
            <button
                onClick={() => void handleVote('GOOD')}
                disabled={submitting || disabled}
                title={isGood ? 'Хороший лид ✓ (нажмите чтобы изменить)' : 'Хороший лид'}
                style={{
                    display:        'flex',
                    alignItems:     'center',
                    justifyContent: 'center',
                    width:          36,
                    height:         36,
                    borderRadius:   10,
                    border:         isGood
                        ? '2px solid rgba(16,185,129,.6)'
                        : '1.5px solid var(--c-border)',
                    background:     isGood
                        ? 'rgba(16,185,129,.15)'
                        : 'var(--c-bg)',
                    color:          isGood
                        ? '#059669'
                        : 'var(--c-ink-3)',
                    cursor:         (submitting || disabled) ? 'not-allowed' : 'pointer',
                    transition:     'all .15s',
                    opacity:        submitting ? 0.5 : 1,
                    flexShrink:     0,
                    boxShadow:      isGood ? '0 0 0 3px rgba(16,185,129,.12)' : 'none',
                }}
                onMouseEnter={e => {
                    if (!submitting && !disabled && !isGood) {
                        const btn = e.currentTarget as HTMLButtonElement
                        btn.style.borderColor = 'rgba(16,185,129,.5)'
                        btn.style.color       = '#059669'
                        btn.style.background  = 'rgba(16,185,129,.08)'
                    }
                }}
                onMouseLeave={e => {
                    if (!isGood) {
                        const btn = e.currentTarget as HTMLButtonElement
                        btn.style.borderColor = 'var(--c-border)'
                        btn.style.color       = 'var(--c-ink-3)'
                        btn.style.background  = 'var(--c-bg)'
                    }
                }}
            >
                <IconThumbUp size={16} />
            </button>

            {/* Дизлайк */}
            <button
                onClick={() => void handleVote('BAD')}
                disabled={submitting || disabled}
                title={isBad ? 'Нерелевантный лид ✓ (нажмите чтобы изменить)' : 'Нерелевантный лид'}
                style={{
                    display:        'flex',
                    alignItems:     'center',
                    justifyContent: 'center',
                    width:          36,
                    height:         36,
                    borderRadius:   10,
                    border:         isBad
                        ? '2px solid rgba(239,68,68,.55)'
                        : '1.5px solid var(--c-border)',
                    background:     isBad
                        ? 'rgba(239,68,68,.13)'
                        : 'var(--c-bg)',
                    color:          isBad
                        ? '#dc2626'
                        : 'var(--c-ink-3)',
                    cursor:         (submitting || disabled) ? 'not-allowed' : 'pointer',
                    transition:     'all .15s',
                    opacity:        submitting ? 0.5 : 1,
                    flexShrink:     0,
                    boxShadow:      isBad ? '0 0 0 3px rgba(239,68,68,.1)' : 'none',
                }}
                onMouseEnter={e => {
                    if (!submitting && !disabled && !isBad) {
                        const btn = e.currentTarget as HTMLButtonElement
                        btn.style.borderColor = 'rgba(239,68,68,.45)'
                        btn.style.color       = '#dc2626'
                        btn.style.background  = 'rgba(239,68,68,.07)'
                    }
                }}
                onMouseLeave={e => {
                    if (!isBad) {
                        const btn = e.currentTarget as HTMLButtonElement
                        btn.style.borderColor = 'var(--c-border)'
                        btn.style.color       = 'var(--c-ink-3)'
                        btn.style.background  = 'var(--c-bg)'
                    }
                }}
            >
                <IconThumbDown size={16} />
            </button>
        </div>
    )
}

// ─── Баннер: очередь неоценённых лидов ───────────────────────────────────────

function QueueBanner({ queueSize, onDismiss }: { queueSize: number; onDismiss: () => void }) {
    return (
        <div style={{
            display:       'flex',
            alignItems:    'center',
            gap:           10,
            padding:       '10px 16px',
            borderRadius:  10,
            background:    'rgba(245,158,11,.08)',
            border:        '1px solid rgba(245,158,11,.25)',
            marginBottom:  16,
        }}>
            <span style={{ color: '#d97706', flexShrink: 0 }}>
                <IconBell />
            </span>
            <span style={{ fontSize: 13, color: 'var(--c-ink-2)', flex: 1 }}>
                <strong style={{ color: 'var(--c-ink-1)', fontWeight: 700 }}>
                    {queueSize} {queueSize === 1 ? 'лид ожидает' : queueSize < 5 ? 'лида ожидают' : 'лидов ожидают'} оценки.
                </strong>
                {' '}Оцените лиды ниже, чтобы получать новые уведомления в Telegram.
            </span>
            <button
                onClick={onDismiss}
                style={{
                    background:    'none',
                    border:        'none',
                    color:         'var(--c-ink-3)',
                    cursor:        'pointer',
                    padding:       '2px 6px',
                    borderRadius:  4,
                    fontSize:      16,
                    lineHeight:    1,
                    flexShrink:    0,
                    transition:    'color .15s',
                }}
                onMouseEnter={e => { e.currentTarget.style.color = 'var(--c-ink-1)' }}
                onMouseLeave={e => { e.currentTarget.style.color = 'var(--c-ink-3)' }}
                title="Скрыть"
            >
                ×
            </button>
        </div>
    )
}

// ─── Подсказка про важность оценок ───────────────────────────────────────────

function FeedbackHint() {
    const [dismissed, setDismissed] = useState(() => {
        try { return localStorage.getItem('aimly_feedback_hint_dismissed') === '1' } catch { return false }
    })

    if (dismissed) return null

    return (
        <div style={{
            display:       'flex',
            alignItems:    'flex-start',
            gap:           10,
            padding:       '10px 14px',
            borderRadius:  10,
            background:    'rgba(99,102,241,.06)',
            border:        '1px solid rgba(99,102,241,.2)',
            marginBottom:  16,
        }}>
            <span style={{ color: '#6366f1', flexShrink: 0, marginTop: 1 }}>
                <IconSparkle />
            </span>
            <span style={{ fontSize: 13, color: 'var(--c-ink-2)', flex: 1, lineHeight: 1.5 }}>
                <strong style={{ color: '#6366f1', fontWeight: 700 }}>Оценки лидов обучают AI‑фильтрацию.</strong>
                {' '}Чем больше вы оцениваете — тем точнее система отсеивает нерелевантные сообщения и присылает только то, что вам нужно.
            </span>
            <button
                onClick={() => {
                    setDismissed(true)
                    try { localStorage.setItem('aimly_feedback_hint_dismissed', '1') } catch { /* ignore */ }
                }}
                style={{
                    background: 'none', border: 'none', color: 'var(--c-ink-3)',
                    cursor: 'pointer', padding: '2px 6px', borderRadius: 4,
                    fontSize: 16, lineHeight: 1, flexShrink: 0, transition: 'color .15s',
                }}
                onMouseEnter={e => { e.currentTarget.style.color = 'var(--c-ink-1)' }}
                onMouseLeave={e => { e.currentTarget.style.color = 'var(--c-ink-3)' }}
                title="Скрыть"
            >
                ×
            </button>
        </div>
    )
}

// ─── Оверлей блокировки карточки ─────────────────────────────────────────────

function LockedOverlay({ pendingLeadNumber }: { pendingLeadNumber: number }) {
    return (
        <div style={{
            position:       'absolute',
            inset:          0,
            borderRadius:   'inherit',
            background:     'rgba(var(--c-bg-rgb, 255,255,255), 0.82)',
            backdropFilter: 'blur(3px)',
            display:        'flex',
            flexDirection:  'column',
            alignItems:     'center',
            justifyContent: 'center',
            gap:            8,
            zIndex:         10,
        }}>
            <span style={{ color: 'var(--c-ink-3)' }}>
                <IconLock />
            </span>
            <span style={{ fontSize: 13, color: 'var(--c-ink-2)', fontWeight: 600, textAlign: 'center', maxWidth: 240 }}>
                Оцените лид #{pendingLeadNumber} чтобы увидеть этот
            </span>
        </div>
    )
}

// ─── Бейдж оценки (в заголовке карточки) ─────────────────────────────────────

function RatingBadge({ rating }: { rating: 'GOOD' | 'BAD' }) {
    const isGood = rating === 'GOOD'
    return (
        <span style={{
            display:       'inline-flex',
            alignItems:    'center',
            gap:           4,
            fontSize:      11,
            fontWeight:    700,
            padding:       '2px 8px',
            borderRadius:  100,
            background:    isGood ? 'rgba(16,185,129,.12)' : 'rgba(239,68,68,.1)',
            color:         isGood ? '#059669' : '#dc2626',
            border:        `1px solid ${isGood ? 'rgba(16,185,129,.3)' : 'rgba(239,68,68,.3)'}`,
            flexShrink:    0,
            whiteSpace:    'nowrap',
        }}>
            {isGood ? <IconThumbUp size={11} /> : <IconThumbDown size={11} />}
            {isGood ? 'Хороший' : 'Нерелевантный'}
        </span>
    )
}

// ─── Главный компонент ────────────────────────────────────────────────────────

export default function LeadsPage() {
    const [data,           setData]           = useState<LeadPage | null>(null)
    const [loading,        setLoading]        = useState(true)
    const [filter,         setFilter]         = useState('')
    const [page,           setPage]           = useState(0)
    const [error,          setError]          = useState('')
    const [updating,       setUpdating]       = useState<Set<number>>(new Set())
    const [expanded,       setExpanded]       = useState<Set<number>>(new Set())
    const [markingAll,     setMarkingAll]     = useState(false)
    // Состояние очереди неоценённых лидов
    const [feedbackStatus, setFeedbackStatus] = useState<FeedbackStatus | null>(null)
    const [bannerDismissed, setBannerDismissed] = useState(false)
    // Локальные оценки: leadId → rating (оптимистичные обновления до подтверждения сервера)
    // После загрузки страницы localRatings пуст — данные берём из lead.myRating (с бэкенда)
    const [localRatings, setLocalRatings]    = useState<Map<number, 'GOOD' | 'BAD' | null>>(new Map())

    // Ref для polling — чтобы можно было остановить таймер при размонтировании
    const pollTimerRef = useRef<ReturnType<typeof setInterval> | null>(null)

    const load = useCallback(async () => {
        setLoading(true)
        setError('')
        try {
            const statusParam = filter || undefined
            const [result, fStatus] = await Promise.all([
                leadsApi.list({ status: statusParam, page, size: 20 }),
                feedbackApi.getStatus(),
            ])
            setData(result)
            setFeedbackStatus(fStatus)
            dispatchLeadsCountChanged(result.newCount)
            // При полной перезагрузке данных сбрасываем локальные оценки —
            // теперь актуальные данные myRating пришли с бэкенда
            setLocalRatings(new Map())
        } catch (e: unknown) {
            setError(e instanceof Error ? e.message : 'Ошибка загрузки')
        } finally {
            setLoading(false)
        }
    }, [filter, page])

    useEffect(() => { load() }, [load])

    // ─── Фоновый поллинг оценок ──────────────────────────────────────────────
    // Синхронизируем оценки сделанные через Telegram-бота без перезагрузки страницы.
    // Каждые 30 секунд тихо обновляем myRating для всех лидов на странице.
    useEffect(() => {
        const poll = async () => {
            // Если страница скрыта — пропускаем запрос
            if (document.hidden) return
            try {
                const statusParam = filter || undefined
                const [result, fStatus] = await Promise.all([
                    leadsApi.list({ status: statusParam, page, size: 20 }),
                    feedbackApi.getStatus(),
                ])
                // Обновляем только оценки (myRating) и статус очереди, не трогаем остальные данные,
                // чтобы не сбрасывать раскрытые карточки и локальные изменения статуса
                setData(prev => {
                    if (!prev) return result
                    // Мержим свежие myRating из нового результата в старые данные
                    const updatedContent = prev.content.map(oldLead => {
                        const freshLead = result.content.find(fl => fl.id === oldLead.id)
                        if (!freshLead) return oldLead
                        // Обновляем только myRating если нет локальной оценки (т.е. пользователь
                        // не менял её в этой сессии — тогда приоритет у свежих данных с сервера)
                        if (localRatings.has(oldLead.id)) return oldLead
                        return { ...oldLead, myRating: freshLead.myRating }
                    })
                    return { ...prev, content: updatedContent }
                })
                setFeedbackStatus(fStatus)
            } catch {
                // Поллинг тихий — ошибки не показываем
            }
        }

        pollTimerRef.current = setInterval(poll, RATINGS_POLL_INTERVAL)
        return () => {
            if (pollTimerRef.current) clearInterval(pollTimerRef.current)
        }
    }, [filter, page, localRatings])

    const changeFilter = (value: string) => {
        setFilter(value)
        setPage(0)
    }

    const toggleExpand = (id: number) => {
        setExpanded(prev => {
            const next = new Set(prev)
            if (next.has(id)) next.delete(id)
            else next.add(id)
            return next
        })
    }

    const applyStatusUpdate = (updated: Lead, previousStatus: string) => {
        setData(prev => {
            if (!prev) return prev
            const content  = prev.content.map(l => l.id === updated.id ? updated : l)
            let newCount   = prev.newCount
            if (previousStatus === 'NEW' && updated.status !== 'NEW')
                newCount = Math.max(0, newCount - 1)
            else if (previousStatus !== 'NEW' && updated.status === 'NEW')
                newCount = newCount + 1
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

    const handleOpen = (lead: Lead) => {
        if (lead.status === 'NEW') {
            void setStatus(lead, 'VIEWED')
        }
    }

    /**
     * Отправить или изменить оценку.
     *
     * Алгоритм:
     * 1. Оптимистично обновляем UI (localRatings + myRating в data) — пользователь видит
     *    изменение мгновенно без задержки сети.
     * 2. Отправляем запрос на бэкенд.
     * 3. При успехе: обновляем статус лида (NEW→VIEWED автоматически) и размер очереди.
     * 4. При ошибке: откатываем оптимистичное обновление.
     */
    const handleVote = async (leadId: number, rating: 'GOOD' | 'BAD') => {
        // Запоминаем предыдущую оценку для возможного отката
        const prevRating = localRatings.has(leadId)
            ? localRatings.get(leadId) ?? null
            : data?.content.find(l => l.id === leadId)?.myRating ?? null

        const wasAlreadyRated = prevRating !== null

        // Оптимистичное обновление localRatings
        setLocalRatings(prev => {
            const next = new Map(prev)
            next.set(leadId, rating)
            return next
        })
        // Оптимистичное обновление myRating прямо в data (для корректной работы getRating
        // в тех местах где localRatings ещё не успел обновиться — React батчинг)
        setData(prev => {
            if (!prev) return prev
            const content = prev.content.map(l =>
                l.id === leadId ? { ...l, myRating: rating } : l
            )
            return { ...prev, content }
        })

        try {
            const res = await feedbackApi.submit(leadId, rating)

            // Обновляем статус лида согласно ответу бэкенда (NEW → VIEWED автоматически)
            setData(prev => {
                if (!prev) return prev
                const content = prev.content.map(l => {
                    if (l.id !== leadId) return l
                    const newStatus = res.leadStatus as Lead['status']
                    // Если статус изменился с NEW на VIEWED — обновляем счётчик
                    if (l.status === 'NEW' && newStatus !== 'NEW') {
                        dispatchLeadsCountChanged(Math.max(0, (prev.newCount ?? 1) - 1))
                        return { ...l, myRating: rating, status: newStatus }
                    }
                    return { ...l, myRating: rating }
                })
                const newCount = prev.content.find(l => l.id === leadId)?.status === 'NEW'
                    ? Math.max(0, prev.newCount - 1)
                    : prev.newCount
                return { ...prev, content, newCount }
            })

            // Обновляем размер очереди
            if (res.queueEmpty) {
                setFeedbackStatus({ queueSize: 0, hasQueue: false })
            } else if (!wasAlreadyRated) {
                // Первичная оценка — уменьшаем очередь на 1
                setFeedbackStatus(prev => {
                    if (!prev) return prev
                    const newSize = Math.max(0, prev.queueSize - 1)
                    return { queueSize: newSize, hasQueue: newSize > 0 }
                })
            }
        } catch {
            // Откат: возвращаем предыдущее состояние
            setLocalRatings(prev => {
                const next = new Map(prev)
                if (prevRating === null) {
                    next.delete(leadId)
                } else {
                    next.set(leadId, prevRating)
                }
                return next
            })
            setData(prev => {
                if (!prev) return prev
                const content = prev.content.map(l =>
                    l.id === leadId ? { ...l, myRating: prevRating } : l
                )
                return { ...prev, content }
            })
        }
    }

    /**
     * Получить актуальный рейтинг лида.
     * Приоритет: localRatings (оптимистичное) > lead.myRating (с бэкенда).
     * localRatings сбрасывается при полной загрузке данных, поэтому при перезагрузке
     * страницы корректно показываем данные с сервера.
     */
    const getRating = (lead: Lead): 'GOOD' | 'BAD' | null => {
        if (localRatings.has(lead.id)) return localRatings.get(lead.id) ?? null
        return lead.myRating ?? null
    }

    const visibleContent = (data?.content ?? []).filter(lead =>
        filter === '' ? lead.status !== 'IGNORED' : true
    )

    // ─── Логика блокировки ────────────────────────────────────────────────────
    // Находим первый неоценённый лид в списке. Все лиды ПОСЛЕ него (без оценки) — заблокированы.
    // Это воспроизводит логику бэкенда: пользователь должен оценивать по порядку.
    const firstUnratedIndex = feedbackStatus?.hasQueue
        ? visibleContent.findIndex(l => getRating(l) === null)
        : -1

    const isLeadLocked = (lead: Lead, idx: number): boolean => {
        if (!feedbackStatus?.hasQueue) return false
        if (firstUnratedIndex === -1) return false
        return idx > firstUnratedIndex && getRating(lead) === null
    }

    // Номер первого неоценённого лида в пользовательской нумерации (1-based)
    const pendingLeadNumber = firstUnratedIndex >= 0 ? firstUnratedIndex + 1 : null

    const showBanner = feedbackStatus?.hasQueue && !bannerDismissed

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
                <div className={s.skeletons}>
                    {[...Array(5)].map((_, i) => <div key={i} className={s.skeleton} />)}
                </div>
            </div>
        )
    }

    return (
        <div className={s.page}>
            <div className={s.header}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
                    <h1 className={s.title}>Лиды</h1>
                    {(data?.newCount ?? 0) > 0 && (
                        <span style={{
                            display:    'inline-flex',
                            alignItems: 'center',
                            padding:    '3px 10px',
                            borderRadius: 100,
                            background: 'rgba(239,68,68,.1)',
                            color:      '#dc2626',
                            fontSize:   12,
                            fontWeight: 700,
                        }}>
                            {data!.newCount} новых
                        </span>
                    )}
                    {(data?.newCount ?? 0) > 0 && (
                        <button
                            onClick={handleMarkAllRead}
                            disabled={markingAll}
                            style={{
                                display:     'inline-flex',
                                alignItems:  'center',
                                gap:         5,
                                padding:     '4px 12px',
                                borderRadius: 100,
                                border:      '1.5px solid var(--c-border)',
                                background:  'none',
                                color:       'var(--c-ink-2)',
                                fontSize:    12,
                                fontWeight:  600,
                                cursor:      markingAll ? 'default' : 'pointer',
                                fontFamily:  'var(--font-body)',
                                transition:  'all .15s',
                                opacity:     markingAll ? 0.5 : 1,
                            }}
                            onMouseEnter={e => {
                                if (!markingAll) {
                                    const btn = e.currentTarget as HTMLButtonElement
                                    btn.style.borderColor = 'var(--c-accent)'
                                    btn.style.color       = 'var(--c-accent)'
                                }
                            }}
                            onMouseLeave={e => {
                                const btn = e.currentTarget as HTMLButtonElement
                                btn.style.borderColor = 'var(--c-border)'
                                btn.style.color       = 'var(--c-ink-2)'
                            }}
                        >
                            <IconCheck />
                            {markingAll ? '...' : 'Прочитать всё'}
                        </button>
                    )}
                </div>
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
            </div>

            {/* Подсказка про важность оценок — показывается один раз, скрывается навсегда */}
            <FeedbackHint />

            {/* Баннер очереди */}
            {showBanner && (
                <QueueBanner
                    queueSize={feedbackStatus!.queueSize}
                    onDismiss={() => setBannerDismissed(true)}
                />
            )}

            {error && <div className={s.error}>{error}</div>}

            {visibleContent.length === 0 ? (
                <div className={s.empty}>
                    <div className={s.emptyIcon}>
                        {filter === 'IGNORED' ? <IconArchive /> : '—'}
                    </div>
                    <p>{filter === 'IGNORED' ? 'Архив пуст' : 'Лидов нет'}</p>
                    <span>
                        {filter === 'IGNORED'
                            ? 'Здесь будут лиды, перемещённые в архив'
                            : 'Добавьте чаты и ключевые слова для мониторинга'}
                    </span>
                </div>
            ) : (
                <>
                    <div className={s.list}>
                        {visibleContent.map((lead, idx) => {
                            const color      = STATUS_COLOR[lead.status] || STATUS_COLOR.NEW
                            const isNew      = lead.status === 'NEW'
                            const isViewed   = lead.status === 'VIEWED'
                            const isReplied  = lead.status === 'REPLIED'
                            const isArchived = lead.status === 'IGNORED'
                            const isExpanded = expanded.has(lead.id)
                            const isUpdating = updating.has(lead.id)
                            const isExport   = lead.source === 'MANUAL_EXPORT'
                            const locked     = isLeadLocked(lead, idx)
                            const rating     = getRating(lead)

                            const contextMsgs: string[] = lead.contextMessages ?? []

                            const displayDate = isExport
                                ? (lead.messageDate || lead.foundAt)
                                : lead.foundAt

                            return (
                                <div
                                    key={lead.id}
                                    className={s.leadCard}
                                    style={{
                                        position: 'relative',
                                        // Цветная левая полоса для новых лидов
                                        ...(isNew      ? { borderLeftColor: '#dc2626', borderLeftWidth: 3 } : {}),
                                        ...(isArchived ? { opacity: 0.65 } : {}),
                                        ...(isExport && isNew ? { borderLeftColor: '#7c3aed' } : {}),
                                        // Заблокированные карточки — притушенные
                                        ...(locked ? { opacity: 0.55 } : {}),
                                        // Цветная левая полоса по оценке (перекрывает статусную для оценённых)
                                        ...(rating === 'GOOD' && !isNew ? { borderLeftColor: '#059669', borderLeftWidth: 3 } : {}),
                                        ...(rating === 'BAD' && !isNew  ? { borderLeftColor: '#dc2626', borderLeftWidth: 3, opacity: isArchived ? 0.65 : 0.85 } : {}),
                                    }}
                                >
                                    {/* Оверлей блокировки */}
                                    {locked && pendingLeadNumber !== null && (
                                        <LockedOverlay pendingLeadNumber={pendingLeadNumber} />
                                    )}

                                    {/* ─── Основное содержимое карточки + правая колонка с оценкой ─── */}
                                    <div style={{ display: 'flex', alignItems: 'stretch' }}>

                                        {/* Левая часть — весь прежний контент */}
                                        <div style={{ flex: 1, minWidth: 0 }}>

                                            <div className={s.leadHead}>
                                                <div className={s.leadMeta}>
                                                    <div style={{
                                                        width:          32,
                                                        height:         32,
                                                        borderRadius:   '50%',
                                                        background:     isExport ? 'rgba(139,92,246,.1)' : 'var(--c-accent-soft)',
                                                        color:          isExport ? '#7c3aed' : 'var(--c-accent)',
                                                        display:        'flex',
                                                        alignItems:     'center',
                                                        justifyContent: 'center',
                                                        fontWeight:     700,
                                                        fontSize:       14,
                                                        flexShrink:     0,
                                                        fontFamily:     'var(--font-head)',
                                                    }}>
                                                        {(lead.authorName || lead.chatTitle || '?').charAt(0).toUpperCase()}
                                                    </div>
                                                    <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                                                        <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
                                                            <span className={s.leadChat}>{lead.chatTitle || lead.chatLink}</span>
                                                            {isExport && <ExportBadge />}
                                                            {/* Бейдж оценки — показываем рядом с чатом если оценён */}
                                                            {rating !== null && <RatingBadge rating={rating} />}
                                                        </div>
                                                        <span className={s.leadDate}>
                                                            {formatLeadDate(displayDate)}
                                                            {isExport && (
                                                                <span style={{ color: 'var(--c-ink-3)', fontSize: 10, marginLeft: 4 }}>
                                                                    · дата сообщения
                                                                </span>
                                                            )}
                                                        </span>
                                                    </div>
                                                    {lead.matchedKeyword && (
                                                        <span style={{
                                                            fontSize:     11,
                                                            background:   'var(--c-accent-soft)',
                                                            color:        'var(--c-accent)',
                                                            padding:      '2px 8px',
                                                            borderRadius: 100,
                                                            fontWeight:   600,
                                                        }}>
                                                            {lead.matchedKeyword}
                                                        </span>
                                                    )}
                                                </div>

                                                <button
                                                    onClick={() => {
                                                        if (isNew)    void setStatus(lead, 'VIEWED')
                                                        if (isViewed) void setStatus(lead, 'NEW')
                                                    }}
                                                    disabled={isUpdating || isReplied || isArchived}
                                                    title={
                                                        isNew    ? 'Нажмите — пометить прочитанным' :
                                                            isViewed ? 'Нажмите — пометить непрочитанным' :
                                                                STATUS_LABEL[lead.status]
                                                    }
                                                    style={{
                                                        display:     'inline-flex',
                                                        alignItems:  'center',
                                                        gap:         5,
                                                        padding:     '3px 10px',
                                                        borderRadius: 100,
                                                        border:      `1px solid ${color.text}44`,
                                                        background:  color.bg,
                                                        color:       color.text,
                                                        fontSize:    11,
                                                        fontWeight:  700,
                                                        cursor:      (isReplied || isArchived) ? 'default' : 'pointer',
                                                        flexShrink:  0,
                                                        fontFamily:  'var(--font-body)',
                                                        transition:  'opacity .15s',
                                                    }}
                                                    onMouseEnter={e => {
                                                        if (!isReplied && !isArchived)
                                                            e.currentTarget.style.opacity = '0.7'
                                                    }}
                                                    onMouseLeave={e => { e.currentTarget.style.opacity = '1' }}
                                                >
                                                    {isNew    ? <IconEye />    : null}
                                                    {isViewed ? <IconEyeOff /> : null}
                                                    {STATUS_LABEL[lead.status]}
                                                </button>
                                            </div>

                                            <div className={s.leadBody}>
                                                <button
                                                    className={s.leadText}
                                                    onClick={() => toggleExpand(lead.id)}
                                                    style={{ textAlign: 'left', background: 'none', border: 'none', cursor: 'pointer', padding: 0, width: '100%', fontFamily: 'var(--font-body)' }}
                                                >
                                                    <span style={{
                                                        display:    '-webkit-box',
                                                        WebkitLineClamp: isExpanded ? 'unset' : 4,
                                                        WebkitBoxOrient: 'vertical' as const,
                                                        overflow:   isExpanded ? 'visible' : 'hidden',
                                                        whiteSpace: 'pre-wrap',
                                                        wordBreak:  'break-word',
                                                    } as React.CSSProperties}>
                                                        {lead.messageText}
                                                    </span>
                                                </button>
                                                {lead.messageText.length > 300 && (
                                                    <button
                                                        onClick={() => toggleExpand(lead.id)}
                                                        style={{
                                                            display:    'inline-flex',
                                                            alignItems: 'center',
                                                            gap:        4,
                                                            marginTop:  4,
                                                            background: 'none',
                                                            border:     'none',
                                                            color:      'var(--c-accent)',
                                                            fontSize:   12,
                                                            fontWeight: 600,
                                                            cursor:     'pointer',
                                                            padding:    0,
                                                            fontFamily: 'var(--font-body)',
                                                        }}
                                                    >
                                                        <IconChevron open={isExpanded} />
                                                        {isExpanded ? 'Свернуть' : 'Развернуть'}
                                                    </button>
                                                )}
                                            </div>

                                            {isExpanded && (
                                                <div style={{ padding: '0 16px 14px', display: 'flex', flexDirection: 'column', gap: 14 }}>
                                                    {lead.aiValid !== null && lead.aiValid !== undefined && (
                                                        <div style={{
                                                            display:    'flex',
                                                            alignItems: 'flex-start',
                                                            gap:        8,
                                                            padding:    '8px 12px',
                                                            borderRadius: 9,
                                                            background: lead.aiValid ? 'rgba(16,185,129,.08)' : 'rgba(239,68,68,.08)',
                                                            border:     `1px solid ${lead.aiValid ? 'rgba(16,185,129,.2)' : 'rgba(239,68,68,.2)'}`,
                                                        }}>
                                                            <span style={{
                                                                display:    'inline-flex',
                                                                alignItems: 'center',
                                                                gap:        4,
                                                                fontSize:   12,
                                                                fontWeight: 700,
                                                                flexShrink: 0,
                                                                color:      lead.aiValid ? '#10b981' : '#ef4444',
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
                                                                fontSize:       11,
                                                                fontWeight:     700,
                                                                textTransform:  'uppercase',
                                                                letterSpacing:  '.5px',
                                                                color:          'var(--c-ink-3)',
                                                                marginBottom:   8,
                                                            }}>
                                                                Контекст чата
                                                            </div>
                                                            <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                                                                {contextMsgs.map((msg, i) => (
                                                                    <div key={i} style={{
                                                                        fontSize:    13,
                                                                        color:       'var(--c-ink-2)',
                                                                        lineHeight:  1.5,
                                                                        padding:     '6px 10px',
                                                                        borderLeft:  '2px solid var(--c-border)',
                                                                        background:  'var(--c-surface)',
                                                                        borderRadius: '0 6px 6px 0',
                                                                        wordBreak:   'break-word',
                                                                        whiteSpace:  'pre-wrap',
                                                                    }}>
                                                                        {msg}
                                                                    </div>
                                                                ))}
                                                            </div>
                                                        </div>
                                                    )}
                                                </div>
                                            )}

                                            <div className={s.leadFooter}>
                                                <div className={s.leadAuthor}>
                                                    <span style={{ color: 'var(--c-ink-3)', flexShrink: 0 }}><IconUser /></span>
                                                    <span className={s.authorName}>{lead.authorName || 'Аноним'}</span>
                                                    {lead.authorUsername && (
                                                        <span className={s.authorUsername}>@{lead.authorUsername}</span>
                                                    )}
                                                </div>

                                                <div className={s.leadActions}>
                                                    {/* AI бейдж */}
                                                    {lead.aiValid !== null && lead.aiValid !== undefined && (
                                                        <div
                                                            className={s.aiBadge}
                                                            style={{
                                                                background: lead.aiValid ? 'rgba(16,185,129,.12)' : 'rgba(239,68,68,.12)',
                                                                color:      lead.aiValid ? '#10b981' : '#ef4444',
                                                            }}
                                                        >
                                                            <IconAI valid={lead.aiValid} />
                                                            AI
                                                        </div>
                                                    )}

                                                    {(isNew || isViewed) && (
                                                        <button
                                                            onClick={() => void setStatus(lead, 'REPLIED')}
                                                            disabled={isUpdating}
                                                            title="Отметить как отвечено"
                                                            style={{
                                                                display:     'flex',
                                                                alignItems:  'center',
                                                                gap:         4,
                                                                padding:     '5px 12px',
                                                                borderRadius: 100,
                                                                border:      '1.5px solid rgba(16,185,129,.45)',
                                                                background:  'rgba(16,185,129,.08)',
                                                                color:       '#059669',
                                                                fontSize:    12,
                                                                fontWeight:  700,
                                                                cursor:      'pointer',
                                                                fontFamily:  'var(--font-body)',
                                                                transition:  'all .15s',
                                                            }}
                                                            onMouseEnter={e => {
                                                                const btn = e.currentTarget as HTMLButtonElement
                                                                btn.style.background  = 'rgba(16,185,129,.18)'
                                                                btn.style.borderColor = 'rgba(16,185,129,.7)'
                                                            }}
                                                            onMouseLeave={e => {
                                                                const btn = e.currentTarget as HTMLButtonElement
                                                                btn.style.background  = 'rgba(16,185,129,.08)'
                                                                btn.style.borderColor = 'rgba(16,185,129,.45)'
                                                            }}
                                                        >
                                                            <IconCheck />
                                                            Ответил
                                                        </button>
                                                    )}

                                                    {isReplied && (
                                                        <button
                                                            onClick={() => void setStatus(lead, 'VIEWED')}
                                                            disabled={isUpdating}
                                                            title="Вернуть в просмотренные"
                                                            style={{
                                                                display:     'flex',
                                                                alignItems:  'center',
                                                                gap:         4,
                                                                padding:     '5px 12px',
                                                                borderRadius: 100,
                                                                border:      '1.5px solid var(--c-border)',
                                                                background:  'none',
                                                                color:       'var(--c-ink-3)',
                                                                fontSize:    12,
                                                                fontWeight:  600,
                                                                cursor:      'pointer',
                                                                fontFamily:  'var(--font-body)',
                                                                transition:  'all .15s',
                                                            }}
                                                            onMouseEnter={e => {
                                                                const btn = e.currentTarget as HTMLButtonElement
                                                                btn.style.borderColor = '#d97706'
                                                                btn.style.color       = '#d97706'
                                                            }}
                                                            onMouseLeave={e => {
                                                                const btn = e.currentTarget as HTMLButtonElement
                                                                btn.style.borderColor = 'var(--c-border)'
                                                                btn.style.color       = 'var(--c-ink-3)'
                                                            }}
                                                        >
                                                            <IconUndo />
                                                            Вернуть
                                                        </button>
                                                    )}

                                                    {!isArchived && (
                                                        <button
                                                            onClick={() => void setStatus(lead, 'IGNORED')}
                                                            disabled={isUpdating}
                                                            title="В архив"
                                                            style={{
                                                                display:     'flex',
                                                                alignItems:  'center',
                                                                gap:         4,
                                                                padding:     '5px 12px',
                                                                borderRadius: 100,
                                                                border:      '1.5px solid var(--c-border)',
                                                                background:  'none',
                                                                color:       'var(--c-ink-3)',
                                                                fontSize:    12,
                                                                fontWeight:  600,
                                                                cursor:      'pointer',
                                                                fontFamily:  'var(--font-body)',
                                                                transition:  'all .15s',
                                                            }}
                                                            onMouseEnter={e => {
                                                                const btn = e.currentTarget as HTMLButtonElement
                                                                btn.style.borderColor = '#6b7280'
                                                                btn.style.color       = '#6b7280'
                                                            }}
                                                            onMouseLeave={e => {
                                                                const btn = e.currentTarget as HTMLButtonElement
                                                                btn.style.borderColor = 'var(--c-border)'
                                                                btn.style.color       = 'var(--c-ink-3)'
                                                            }}
                                                        >
                                                            <IconArchive />
                                                            В архив
                                                        </button>
                                                    )}

                                                    {isArchived && (
                                                        <button
                                                            onClick={() => void setStatus(lead, 'NEW')}
                                                            disabled={isUpdating}
                                                            title="Вернуть из архива"
                                                            style={{
                                                                display:     'flex',
                                                                alignItems:  'center',
                                                                gap:         4,
                                                                padding:     '5px 12px',
                                                                borderRadius: 100,
                                                                border:      '1.5px solid var(--c-border)',
                                                                background:  'none',
                                                                color:       'var(--c-ink-3)',
                                                                fontSize:    12,
                                                                fontWeight:  600,
                                                                cursor:      'pointer',
                                                                fontFamily:  'var(--font-body)',
                                                            }}
                                                        >
                                                            Восстановить
                                                        </button>
                                                    )}

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

                                        {/* ─── Правая колонка: кнопки оценки ─── */}
                                        <FeedbackButtons
                                            leadId={lead.id}
                                            rating={rating}
                                            disabled={locked}
                                            onVote={handleVote}
                                        />
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