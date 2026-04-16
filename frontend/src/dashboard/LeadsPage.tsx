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

// ─── Компонент кнопок оценки ──────────────────────────────────────────────────

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
            display:    'flex',
            alignItems: 'center',
            gap:        4,
            flexShrink: 0,
        }}>
            {/* Лайк */}
            <button
                onClick={() => void handleVote('GOOD')}
                disabled={submitting || disabled}
                title={isGood ? 'Хороший лид ✓ (нажмите чтобы изменить)' : 'Хороший лид'}
                style={{
                    display:        'flex',
                    alignItems:     'center',
                    justifyContent: 'center',
                    gap:            4,
                    padding:        '4px 9px',
                    borderRadius:   8,
                    border:         isGood
                        ? '1.5px solid rgba(16,185,129,.6)'
                        : '1.5px solid var(--c-border)',
                    background:     isGood
                        ? 'rgba(16,185,129,.13)'
                        : 'var(--c-bg)',
                    color:          isGood
                        ? '#059669'
                        : 'var(--c-ink-3)',
                    cursor:         (submitting || disabled) ? 'not-allowed' : 'pointer',
                    transition:     'all .15s',
                    opacity:        submitting ? 0.5 : 1,
                    flexShrink:     0,
                    fontSize:       11,
                    fontWeight:     600,
                    fontFamily:     'var(--font-body)',
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
                <IconThumbUp size={13} />
                {isGood && <span>Хороший</span>}
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
                    gap:            4,
                    padding:        '4px 9px',
                    borderRadius:   8,
                    border:         isBad
                        ? '1.5px solid rgba(239,68,68,.55)'
                        : '1.5px solid var(--c-border)',
                    background:     isBad
                        ? 'rgba(239,68,68,.11)'
                        : 'var(--c-bg)',
                    color:          isBad
                        ? '#dc2626'
                        : 'var(--c-ink-3)',
                    cursor:         (submitting || disabled) ? 'not-allowed' : 'pointer',
                    transition:     'all .15s',
                    opacity:        submitting ? 0.5 : 1,
                    flexShrink:     0,
                    fontSize:       11,
                    fontWeight:     600,
                    fontFamily:     'var(--font-body)',
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
                <IconThumbDown size={13} />
                {isBad && <span>Мусор</span>}
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
                {' '}Оцените лиды ниже, чтобы увидеть новые.
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

function LockedOverlay({ pendingLeadId }: { pendingLeadId: number }) {
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
                Оцените лид #{pendingLeadId} чтобы увидеть этот
            </span>
        </div>
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
    // Состояние очереди неоценённых лидов — отражает реальный размер TG-очереди на бэкенде
    const [, setFeedbackStatus] = useState<FeedbackStatus | null>(null)
    const [bannerDismissed, setBannerDismissed] = useState(false)
    // Локальные оценки: leadId → rating (оптимистичные обновления до подтверждения с сервера)
    const [localRatings, setLocalRatings]     = useState<Map<number, 'GOOD' | 'BAD' | null>>(new Map())

    // Ref для тихого фонового обновления
    const silentRefreshRef = useRef<ReturnType<typeof setTimeout> | null>(null)
    // Флаг: идёт ли в данный момент тихое обновление (чтобы не дублировать)
    const isSilentRefreshing = useRef(false)

    // ─── Тихое фоновое обновление (без спиннера) ────────────────────────────
    // Вызывается при возвращении на вкладку/фокусе окна — заменяет поллинг.
    // Синхронизирует оценки сделанные через Telegram-бота.
    const silentRefresh = useCallback(async () => {
        if (isSilentRefreshing.current) return
        isSilentRefreshing.current = true
        try {
            const statusParam = filter || undefined
            const [result, fStatus] = await Promise.all([
                leadsApi.list({ status: statusParam, page, size: 20 }),
                feedbackApi.getStatus(),
            ])
            setData(prev => {
                if (!prev) return result
                // Обновляем myRating и status из свежих данных с сервера.
                // Если у нас есть localRating (пользователь только что нажал кнопку) —
                // не перетираем, ждём подтверждения с бэкенда.
                const updatedContent = prev.content.map(oldLead => {
                    const freshLead = result.content.find(fl => fl.id === oldLead.id)
                    if (!freshLead) return oldLead
                    if (localRatings.has(oldLead.id)) return oldLead
                    return { ...oldLead, myRating: freshLead.myRating, status: freshLead.status }
                })
                return { ...prev, content: updatedContent, newCount: result.newCount }
            })
            setFeedbackStatus(fStatus)
            dispatchLeadsCountChanged(result.newCount)
        } catch {
            // Тихий — ошибки не показываем
        } finally {
            isSilentRefreshing.current = false
        }
    }, [filter, page, localRatings])

    // ─── Подписка на события видимости вкладки и фокуса окна ────────────────
    // При возвращении пользователя — мгновенно синхронизируем данные с сервером.
    // Это профессиональная замена поллинга: обновляем только когда нужно.
    useEffect(() => {
        const handleVisibilityChange = () => {
            if (!document.hidden) {
                // Небольшая задержка чтобы не конкурировать с основной загрузкой
                if (silentRefreshRef.current) clearTimeout(silentRefreshRef.current)
                silentRefreshRef.current = setTimeout(() => void silentRefresh(), 300)
            }
        }
        const handleFocus = () => {
            if (silentRefreshRef.current) clearTimeout(silentRefreshRef.current)
            silentRefreshRef.current = setTimeout(() => void silentRefresh(), 300)
        }

        document.addEventListener('visibilitychange', handleVisibilityChange)
        window.addEventListener('focus', handleFocus)
        return () => {
            document.removeEventListener('visibilitychange', handleVisibilityChange)
            window.removeEventListener('focus', handleFocus)
            if (silentRefreshRef.current) clearTimeout(silentRefreshRef.current)
        }
    }, [silentRefresh])

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
            // Удаляем из localRatings ключи для которых бэкенд вернул myRating —
            // данные синхронизированы, локальная копия больше не нужна.
            setLocalRatings(prev => {
                if (prev.size === 0) return prev
                const next = new Map(prev)
                for (const lead of result.content) {
                    next.delete(lead.id)
                }
                return next
            })
        } catch (e: unknown) {
            setError(e instanceof Error ? e.message : 'Ошибка загрузки')
        } finally {
            setLoading(false)
        }
    }, [filter, page])

    useEffect(() => { void load() }, [load])

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
     *    Также пересчитываем какой лид теперь «первый неоценённый» — разблокируем следующий.
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
        // Оптимистичное обновление myRating прямо в data
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
                const wasNew = prev.content.find(l => l.id === leadId)?.status === 'NEW'
                const content = prev.content.map(l => {
                    if (l.id !== leadId) return l
                    const newStatus = res.leadStatus as Lead['status']
                    return { ...l, myRating: rating, status: newStatus }
                })
                const newCount = wasNew ? Math.max(0, prev.newCount - 1) : prev.newCount
                if (wasNew) dispatchLeadsCountChanged(newCount)
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

            // После успешного сохранения на сервере синхронизируем localRatings:
            // удаляем из localRatings, т.к. lead.myRating в data уже обновлён корректно
            setLocalRatings(prev => {
                const next = new Map(prev)
                next.delete(leadId)
                return next
            })
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
     * После успешной отправки на бэкенд localRatings для этого лида очищается,
     * и управление переходит к lead.myRating который уже обновлён.
     */
    const getRating = (lead: Lead): 'GOOD' | 'BAD' | null => {
        if (localRatings.has(lead.id)) return localRatings.get(lead.id) ?? null
        return lead.myRating ?? null
    }

    const visibleContent = (data?.content ?? []).filter(lead =>
        filter === '' ? lead.status !== 'IGNORED' : true
    )

    // ─── Логика блокировки ────────────────────────────────────────────────────
    //
    // Логика идентична бэкенду (LeadFeedbackService):
    //
    // Бэкенд отправляет лиды последовательно: пока последний отправленный лид
    // не оценён — новые лиды складываются в очередь (pending_lead_notifications).
    // Задача фронта: показать пользователю какой лид нужно оценить СЕЙЧАС.
    //
    // Алгоритм:
    // 1. Находим первый неоценённый лид в списке (myRating === null, с учётом localRatings).
    //    Это тот лид, который нужно оценить чтобы разблокировать следующий.
    // 2. Все лиды ПОСЛЕ него без оценки — заблокированы (пользователь ещё не дошёл до них).
    // 3. Лиды ДО него или оценённые — не блокируются никогда.
    //
    // Важно: myRating приходит с бэкенда и является source of truth.
    // При перезаходе на страницу — бэкенд уже знает все оценки (и из TG-бота тоже),
    // поэтому проблемы «просит оценить заново» не будет.
    const firstUnratedIndex = visibleContent.findIndex(l => getRating(l) === null)

    // Есть ли хоть один неоценённый лид
    const hasUnratedLeads = firstUnratedIndex !== -1

    const isLeadLocked = (lead: Lead, idx: number): boolean => {
        // Если нет ни одного неоценённого — никто не заблокирован
        if (!hasUnratedLeads) return false
        // Первый неоценённый — не заблокирован, его надо оценить
        if (idx === firstUnratedIndex) return false
        // Лиды ДО первого неоценённого — не заблокированы (они уже оценены)
        if (idx < firstUnratedIndex) return false
        // Лиды ПОСЛЕ первого неоценённого без оценки — заблокированы
        return getRating(lead) === null
    }

    // ID первого неоценённого лида — передаём в оверлей заблокированных карточек
    const firstUnratedId = firstUnratedIndex >= 0 ? visibleContent[firstUnratedIndex]?.id : null

    // Баннер: показываем если есть неоценённые лиды на странице
    // (не зависим от feedbackStatus.hasQueue — тот отражает только TG-очередь)
    const unratedCount = visibleContent.filter(l => getRating(l) === null).length
    const showBanner = unratedCount > 0 && !bannerDismissed

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
                    queueSize={unratedCount}
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
                            const isNew      = lead.status === 'NEW'
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
                                    className={`${s.card} ${isNew ? s.cardNew : ''}`}
                                    style={{ position: 'relative', overflow: 'hidden' }}
                                >
                                    {/* Оверлей блокировки */}
                                    {locked && firstUnratedId !== null && (
                                        <LockedOverlay pendingLeadId={firstUnratedId} />
                                    )}

                                    <div className={s.cardInner}>
                                        {/* ─── Шапка карточки ─── */}
                                        <div className={s.cardHead}>
                                            <div className={s.cardMeta}>
                                                {/* Аватар */}
                                                <div className={s.avatar}>
                                                    {(lead.authorName || lead.authorUsername || lead.chatTitle || '?')
                                                        .replace('@', '').charAt(0).toUpperCase()}
                                                </div>

                                                <div className={s.metaInfo}>
                                                    <div className={s.metaRow}>
                                                        {lead.authorName?.trim() && (
                                                            <span className={s.authorName}>{lead.authorName}</span>
                                                        )}
                                                        {lead.authorUsername?.trim() && (
                                                            <span className={s.authorUser}>@{lead.authorUsername}</span>
                                                        )}
                                                        {!lead.authorName?.trim() && !lead.authorUsername?.trim() && (
                                                            <span className={s.authorAnon}>Аноним</span>
                                                        )}
                                                        <span
                                                            className={s.statusBadge}
                                                            style={{
                                                                background: STATUS_COLOR[lead.status]?.bg,
                                                                color:      STATUS_COLOR[lead.status]?.text,
                                                            }}
                                                        >
                                                            {STATUS_LABEL[lead.status] ?? lead.status}
                                                        </span>
                                                        {isExport && <ExportBadge />}
                                                        {lead.aiValid !== null && lead.aiValid !== undefined && (
                                                            <span
                                                                className={s.aiBadge}
                                                                style={{
                                                                    background: lead.aiValid
                                                                        ? 'rgba(16,185,129,.1)'
                                                                        : 'rgba(239,68,68,.08)',
                                                                    color: lead.aiValid ? '#10b981' : '#ef4444',
                                                                    border: `1px solid ${lead.aiValid ? 'rgba(16,185,129,.25)' : 'rgba(239,68,68,.2)'}`,
                                                                }}
                                                            >
                                                                <IconAI valid={lead.aiValid} />
                                                                AI {lead.aiValid ? '✓' : '✗'}
                                                            </span>
                                                        )}
                                                    </div>
                                                    <div className={s.metaSub}>
                                                        {(lead.chatTitle || lead.chatLink) && (
                                                            <span className={s.chatName}>
                                                                {lead.chatTitle || lead.chatLink}
                                                            </span>
                                                        )}
                                                        <span className={s.dateStr}>{formatLeadDate(displayDate)}</span>
                                                        {lead.matchedKeyword && (
                                                            <span className={s.keyword}>{lead.matchedKeyword}</span>
                                                        )}
                                                    </div>
                                                </div>
                                            </div>
                                        </div>

                                        {/* ─── Текст сообщения ─── */}
                                        <div
                                            className={`${s.messageText} ${isExpanded ? s.messageExpanded : ''}`}
                                            style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}
                                        >
                                            {lead.messageText}
                                        </div>

                                        {/* ─── Контекст (если есть) ─── */}
                                        {isExpanded && contextMsgs.length > 0 && (
                                            <div className={s.context}>
                                                <div className={s.contextLabel}>Контекст чата:</div>
                                                {contextMsgs.map((msg, i) => (
                                                    <div key={i} className={s.contextMsg}>{msg}</div>
                                                ))}
                                            </div>
                                        )}

                                        {/* ─── AI-обоснование ─── */}
                                        {isExpanded && lead.aiReason && (
                                            <div
                                                className={s.aiReason}
                                                style={{
                                                    background: lead.aiValid
                                                        ? 'rgba(16,185,129,.06)'
                                                        : 'rgba(239,68,68,.06)',
                                                    border: `1px solid ${lead.aiValid ? 'rgba(16,185,129,.18)' : 'rgba(239,68,68,.18)'}`,
                                                }}
                                            >
                                                <span
                                                    style={{ fontWeight: 700, flexShrink: 0, color: lead.aiValid ? '#10b981' : '#ef4444' }}
                                                >
                                                    AI {lead.aiValid ? '✓' : '✗'}
                                                </span>
                                                <span style={{ fontSize: 12, color: 'var(--c-ink-2)', lineHeight: 1.45 }}>
                                                    {lead.aiReason}
                                                </span>
                                            </div>
                                        )}

                                        {/* ─── Действия ─── */}
                                        <div className={s.leadFooter}>
                                            {!isArchived && !isReplied && (
                                                <button
                                                    onClick={() => void setStatus(lead, 'REPLIED')}
                                                    disabled={isUpdating}
                                                    title="Отмечено как «отвечено»"
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
                                                        btn.style.borderColor = '#059669'
                                                        btn.style.color       = '#059669'
                                                    }}
                                                    onMouseLeave={e => {
                                                        const btn = e.currentTarget as HTMLButtonElement
                                                        btn.style.borderColor = 'var(--c-border)'
                                                        btn.style.color       = 'var(--c-ink-3)'
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
                                                        border:      '1.5px solid rgba(16,185,129,.4)',
                                                        background:  'rgba(16,185,129,.08)',
                                                        color:       '#059669',
                                                        fontSize:    12,
                                                        fontWeight:  600,
                                                        cursor:      'pointer',
                                                        fontFamily:  'var(--font-body)',
                                                    }}
                                                >
                                                    <IconUndo />
                                                    Отменить
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

                                        {/* ─── Кнопки оценки — нижний левый угол карточки ─── */}
                                        <div style={{
                                            display:     'flex',
                                            alignItems:  'center',
                                            gap:         8,
                                            marginTop:   8,
                                            paddingTop:  8,
                                            borderTop:   '1px solid var(--c-border)',
                                        }}>
                                            <span style={{
                                                fontSize:      10,
                                                fontWeight:    600,
                                                color:         'var(--c-ink-3)',
                                                textTransform: 'uppercase',
                                                letterSpacing: '.3px',
                                                flexShrink:    0,
                                            }}>
                                                Оценка:
                                            </span>
                                            <FeedbackButtons
                                                leadId={lead.id}
                                                rating={rating}
                                                disabled={locked}
                                                onVote={handleVote}
                                            />
                                            {/* Раскрыть/свернуть карточку */}
                                            <button
                                                onClick={() => toggleExpand(lead.id)}
                                                style={{
                                                    marginLeft:  'auto',
                                                    display:     'flex',
                                                    alignItems:  'center',
                                                    gap:         4,
                                                    background:  'none',
                                                    border:      'none',
                                                    color:       'var(--c-ink-3)',
                                                    fontSize:    11,
                                                    cursor:      'pointer',
                                                    padding:     '2px 4px',
                                                    borderRadius: 4,
                                                    fontFamily:  'var(--font-body)',
                                                    flexShrink:  0,
                                                    transition:  'color .15s',
                                                }}
                                                onMouseEnter={e => { e.currentTarget.style.color = 'var(--c-ink-1)' }}
                                                onMouseLeave={e => { e.currentTarget.style.color = 'var(--c-ink-3)' }}
                                            >
                                                <IconChevron open={isExpanded} />
                                                {isExpanded ? 'Свернуть' : 'Подробнее'}
                                            </button>
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