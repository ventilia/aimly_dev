import { useState, useEffect, useCallback, useRef } from 'react'
import { leadsApi, feedbackApi, type Lead, type LeadPage, type FeedbackStatusResponse } from '../api/leads'
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

// ─── Форматирование даты ──────────────────────────────────────────────────────
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

const IconThumbUp = () => (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M14 9V5a3 3 0 0 0-3-3l-4 9v11h11.28a2 2 0 0 0 2-1.7l1.38-9a2 2 0 0 0-2-2.3H14z"/>
        <path d="M7 22H4a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2h3"/>
    </svg>
)

const IconThumbDown = () => (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M10 15v4a3 3 0 0 0 3 3l4-9V2H5.72a2 2 0 0 0-2 1.7l-1.38 9a2 2 0 0 0 2 2.3H10z"/>
        <path d="M17 2h2.67A2.31 2.31 0 0 1 22 4v7a2.31 2.31 0 0 1-2.33 2H17"/>
    </svg>
)

const IconClock = () => (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <circle cx="12" cy="12" r="10"/>
        <polyline points="12 6 12 12 16 14"/>
    </svg>
)

// ─── Компонент: тег «из экспорта» ─────────────────────────────────────────────
function ExportBadge() {
    return (
        <span style={{
            display: 'inline-flex', alignItems: 'center', gap: 4,
            fontSize: 10, fontWeight: 700, padding: '2px 7px', borderRadius: 100,
            background: 'rgba(139,92,246,.12)', color: '#7c3aed',
            border: '1px solid rgba(139,92,246,.25)', letterSpacing: '.2px',
            flexShrink: 0, whiteSpace: 'nowrap',
        }}>
            <IconFileImport />
            экспорт
        </span>
    )
}

// ─── Компонент: строка оценки ─────────────────────────────────────────────────
interface FeedbackRowProps {
    rating:    'GOOD' | 'BAD' | null
    submitting: boolean
    onRate:    (r: 'GOOD' | 'BAD') => void
}

function FeedbackRow({ rating, submitting, onRate }: FeedbackRowProps) {
    const hasRating = rating !== null

    return (
        <div className={s.feedbackRow}>
            <span className={s.feedbackLabel}>
                {hasRating ? 'Оценка:' : 'Оценить:'}
            </span>

            {/* Кнопка GOOD */}
            <button
                className={[
                    s.feedbackBtn,
                    rating === 'GOOD' ? s.feedbackBtnGood : '',
                    hasRating && rating !== 'GOOD' ? s.feedbackBtnDimmed : '',
                ].join(' ')}
                onClick={() => onRate('GOOD')}
                disabled={submitting}
                title={rating === 'GOOD' ? 'Оценено как релевантный' : 'Отметить как релевантный'}
            >
                <IconThumbUp />
                Релевантный
            </button>

            {/* Кнопка BAD */}
            <button
                className={[
                    s.feedbackBtn,
                    rating === 'BAD' ? s.feedbackBtnBad : '',
                    hasRating && rating !== 'BAD' ? s.feedbackBtnDimmed : '',
                ].join(' ')}
                onClick={() => onRate('BAD')}
                disabled={submitting}
                title={rating === 'BAD' ? 'Оценено как нерелевантный' : 'Отметить как нерелевантный'}
            >
                <IconThumbDown />
                Нерелевантный
            </button>

            {/* Подсказка при наличии оценки */}
            {hasRating && !submitting && (
                <span className={s.feedbackChangeHint} onClick={() => {}}>
                    Изменить
                </span>
            )}

            {submitting && (
                <span style={{ fontSize: 11.5, color: 'var(--c-ink-3)', fontStyle: 'italic' }}>
                    Сохраняем...
                </span>
            )}
        </div>
    )
}


// ─── Главный компонент ────────────────────────────────────────────────────────
export default function LeadsPage() {
    const [data,         setData]         = useState<LeadPage | null>(null)
    const [loading,      setLoading]      = useState(true)
    const [filter,       setFilter]       = useState('')
    const [page,         setPage]         = useState(0)
    const [error,        setError]        = useState('')
    const [updating,     setUpdating]     = useState<Set<number>>(new Set())
    const [expanded,     setExpanded]     = useState<Set<number>>(new Set())
    const [markingAll,   setMarkingAll]   = useState(false)
    const [submitting,   setSubmitting]   = useState<Set<number>>(new Set())

    // Состояние очереди оценок
    const [feedbackStatus, setFeedbackStatus] = useState<FeedbackStatusResponse | null>(null)

    // Ссылка на pending-лид для авто-скролла
    const pendingRef = useRef<HTMLDivElement | null>(null)

    const loadFeedbackStatus = useCallback(async () => {
        try {
            const status = await feedbackApi.getStatus()
            setFeedbackStatus(status)
        } catch {
            // не критично
        }
    }, [])

    const load = useCallback(async () => {
        setLoading(true)
        setError('')
        try {
            const statusParam = filter || undefined
            const result = await leadsApi.list({ status: statusParam, page, size: 20 })
            setData(result)
            dispatchLeadsCountChanged(result.newCount)
        } catch (e: unknown) {
            setError(e instanceof Error ? e.message : 'Ошибка загрузки')
        } finally {
            setLoading(false)
        }
    }, [filter, page])

    useEffect(() => {
        void load()
        void loadFeedbackStatus()
    }, [load, loadFeedbackStatus])

    const changeFilter = (value: string) => {
        setFilter(value)
        setPage(0)
    }

    const toggleExpand = (id: number) => {
        setExpanded(prev => {
            const next = new Set(prev)
            if (next.has(id)) next.delete(id); else next.add(id)
            return next
        })
    }

    const applyStatusUpdate = (updated: Lead, previousStatus: string) => {
        setData(prev => {
            if (!prev) return prev
            const content = prev.content.map(l => l.id === updated.id ? updated : l)
            let newCount = prev.newCount
            if (previousStatus === 'NEW' && updated.status !== 'NEW') newCount = Math.max(0, newCount - 1)
            else if (previousStatus !== 'NEW' && updated.status === 'NEW') newCount = newCount + 1
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
        if (lead.status === 'NEW') void setStatus(lead, 'VIEWED')
    }

    /**
     * Оценить лид — с оптимистичным обновлением UI.
     * После успеха обновляем статус очереди.
     */
    const handleRate = async (lead: Lead, rating: 'GOOD' | 'BAD') => {
        // Не даём повторно нажать ту же кнопку
        if (lead.rating === rating) return

        setSubmitting(prev => new Set(prev).add(lead.id))

        // Оптимистичное обновление
        const previousRating = lead.rating
        setData(prev => {
            if (!prev) return prev
            return {
                ...prev,
                content: prev.content.map(l =>
                    l.id === lead.id ? { ...l, rating } : l
                ),
            }
        })

        try {
            await feedbackApi.submit(lead.id, rating)
            // После успешной оценки обновляем статус очереди:
            // следующий лид мог быть доставлен в TG, pendingLeadId изменился
            await loadFeedbackStatus()
        } catch {
            // Откат при ошибке
            setData(prev => {
                if (!prev) return prev
                return {
                    ...prev,
                    content: prev.content.map(l =>
                        l.id === lead.id ? { ...l, rating: previousRating } : l
                    ),
                }
            })
        } finally {
            setSubmitting(prev => { const s = new Set(prev); s.delete(lead.id); return s })
        }
    }

    const scrollToPending = () => {
        pendingRef.current?.scrollIntoView({ behavior: 'smooth', block: 'center' })
    }

    const visibleContent = (data?.content ?? []).filter(lead =>
        filter === '' ? lead.status !== 'IGNORED' : true
    )

    const pendingLeadId = feedbackStatus?.pendingLeadId ?? null
    const queueSize     = feedbackStatus?.queueSize     ?? 0

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

            {/* ─── Шапка страницы ─── */}
            <div className={s.header}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
                    <h1 className={s.title}>Лиды</h1>
                    {(data?.newCount ?? 0) > 0 && (
                        <span style={{
                            display: 'inline-flex', alignItems: 'center',
                            padding: '3px 10px', borderRadius: 100,
                            background: 'rgba(239,68,68,.1)', color: '#dc2626',
                            fontSize: 12, fontWeight: 700,
                        }}>
                            {data!.newCount} новых
                        </span>
                    )}
                    {(data?.newCount ?? 0) > 0 && (
                        <button
                            onClick={handleMarkAllRead}
                            disabled={markingAll}
                            style={{
                                display: 'inline-flex', alignItems: 'center', gap: 5,
                                padding: '4px 12px', borderRadius: 100,
                                border: '1.5px solid var(--c-border)', background: 'none',
                                color: 'var(--c-ink-2)', fontSize: 12, fontWeight: 600,
                                cursor: markingAll ? 'default' : 'pointer',
                                fontFamily: 'var(--font-body)', transition: 'all .15s',
                                opacity: markingAll ? 0.5 : 1,
                            }}
                            onMouseEnter={e => {
                                if (!markingAll) {
                                    (e.currentTarget as HTMLButtonElement).style.borderColor = 'var(--c-accent)'
                                    ;(e.currentTarget as HTMLButtonElement).style.color = 'var(--c-accent)'
                                }
                            }}
                            onMouseLeave={e => {
                                (e.currentTarget as HTMLButtonElement).style.borderColor = 'var(--c-border)'
                                ;(e.currentTarget as HTMLButtonElement).style.color = 'var(--c-ink-2)'
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

            {/* ─── Баннер очереди оценок ─── */}
            {pendingLeadId !== null && (
                <div className={s.queueBanner}>
                    <div className={s.queueBannerIcon}>
                        <IconClock />
                    </div>
                    <div className={s.queueBannerText}>
                        <div className={s.queueBannerTitle}>
                            Оцените лид, чтобы продолжить получать новые
                        </div>
                        <div className={s.queueBannerSub}>
                            {queueSize > 0
                                ? `Следующих лидов в очереди: ${queueSize}. Каждая оценка обучает систему.`
                                : 'Ваша оценка помогает нейросети подбирать более точные лиды.'
                            }
                        </div>
                    </div>
                    <button className={s.queueBannerBtn} onClick={scrollToPending}>
                        Перейти к лиду
                    </button>
                </div>
            )}

            {error && <div className={s.error}>{error}</div>}

            {/* ─── Список лидов ─── */}
            {visibleContent.length === 0 ? (
                <div className={s.empty}>
                    <div className={s.emptyIcon}>
                        {filter === 'IGNORED' ? <IconArchive /> : '—'}
                    </div>
                    <p>{filter === 'IGNORED' ? 'Архив пуст' : 'Лидов нет'}</p>
                    <span>
                        {filter === 'IGNORED'
                            ? 'Здесь будут лиды, перемещённые в архив'
                            : 'Добавьте чаты и ключевые слова для мониторинга'
                        }
                    </span>
                </div>
            ) : (
                <>
                    <div className={s.list}>
                        {visibleContent.map(lead => {
                            const color      = STATUS_COLOR[lead.status] || STATUS_COLOR.NEW
                            const isNew      = lead.status === 'NEW'
                            const isViewed   = lead.status === 'VIEWED'
                            const isReplied  = lead.status === 'REPLIED'
                            const isArchived = lead.status === 'IGNORED'
                            const isExpanded = expanded.has(lead.id)
                            const isUpdating = updating.has(lead.id)
                            const isExport   = lead.source === 'MANUAL_EXPORT'
                            const isPending  = lead.id === pendingLeadId
                            const isRating   = submitting.has(lead.id)

                            const contextMsgs: string[] = lead.contextMessages ?? []
                            const displayDate = isExport
                                ? (lead.messageDate || lead.foundAt)
                                : lead.foundAt

                            return (
                                <div
                                    key={lead.id}
                                    ref={isPending ? pendingRef : undefined}
                                    className={[
                                        s.leadCard,
                                        isPending ? s.leadCardPending : '',
                                    ].join(' ')}
                                    style={{
                                        ...(isNew && !isPending  ? { borderLeftColor: '#dc2626', borderLeftWidth: 3 } : {}),
                                        ...(isArchived           ? { opacity: 0.65 } : {}),
                                        ...(isExport && isNew && !isPending ? { borderLeftColor: '#7c3aed' } : {}),
                                    }}
                                >
                                    {/* ─── Шапка карточки ─── */}
                                    <div className={s.leadHead}>
                                        <div className={s.leadMeta}>
                                            {/* Аватар-инициал */}
                                            <div style={{
                                                width: 32, height: 32, borderRadius: '50%', flexShrink: 0,
                                                background: isPending
                                                    ? 'rgba(245,158,11,.12)'
                                                    : isExport ? 'rgba(139,92,246,.1)' : 'var(--c-accent-soft)',
                                                color: isPending
                                                    ? '#b45309'
                                                    : isExport ? '#7c3aed' : 'var(--c-accent)',
                                                display: 'flex', alignItems: 'center', justifyContent: 'center',
                                                fontWeight: 700, fontSize: 14, fontFamily: 'var(--font-head)',
                                            }}>
                                                {(lead.authorName || lead.chatTitle || '?').charAt(0).toUpperCase()}
                                            </div>

                                            <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                                                <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
                                                    <span className={s.leadChat}>{lead.chatTitle || lead.chatLink}</span>
                                                    {isExport   && <ExportBadge />}
                                                    {isPending  && (
                                                        <span className={s.pendingBadge}>
                                                            <IconClock />
                                                            ожидает оценки
                                                        </span>
                                                    )}
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
                                                    fontSize: 11, background: 'var(--c-accent-soft)',
                                                    color: 'var(--c-accent)', padding: '2px 8px',
                                                    borderRadius: 100, fontWeight: 600,
                                                }}>
                                                    {lead.matchedKeyword}
                                                </span>
                                            )}
                                        </div>

                                        {/* Кнопка статуса */}
                                        <button
                                            onClick={() => {
                                                if (isNew)    void setStatus(lead, 'VIEWED')
                                                if (isViewed) void setStatus(lead, 'NEW')
                                            }}
                                            disabled={isUpdating || isReplied || isArchived}
                                            style={{
                                                ...color,
                                                fontSize: 11, fontWeight: 700,
                                                padding: '3px 10px', borderRadius: 100,
                                                flexShrink: 0, letterSpacing: '.2px',
                                                border: 'none', background: color.bg, color: color.text,
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

                                    {/* ─── Текст сообщения ─── */}
                                    <div className={s.leadTextBlock}>
                                        <div className={s.leadTextInner}>
                                            <div className={s.leadText}>{lead.messageText}</div>
                                        </div>
                                    </div>

                                    {/* ─── Кнопка «Детали» ─── */}
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

                                    {/* ─── Раскрытые детали ─── */}
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

                                    {/* ─── Подвал: автор + действия ─── */}
                                    <div className={s.leadFooter}>
                                        <div className={s.leadAuthor}>
                                            <span style={{ color: 'var(--c-ink-3)', flexShrink: 0 }}><IconUser /></span>
                                            <span className={s.authorName}>{lead.authorName || 'Аноним'}</span>
                                            {lead.authorUsername && (
                                                <span className={s.authorUsername}>@{lead.authorUsername}</span>
                                            )}
                                        </div>

                                        <div className={s.leadActions}>
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

                                            {(isNew || isViewed) && (
                                                <button
                                                    onClick={() => void setStatus(lead, 'REPLIED')}
                                                    disabled={isUpdating}
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

                                            {isReplied && (
                                                <button
                                                    onClick={() => void setStatus(lead, 'VIEWED')}
                                                    disabled={isUpdating}
                                                    style={{
                                                        display: 'flex', alignItems: 'center', gap: 4,
                                                        padding: '5px 12px', borderRadius: 100,
                                                        border: '1.5px solid var(--c-border)',
                                                        background: 'none', color: 'var(--c-ink-3)',
                                                        fontSize: 12, fontWeight: 600, cursor: 'pointer',
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

                                            {!isArchived && (
                                                <button
                                                    onClick={() => void setStatus(lead, 'IGNORED')}
                                                    disabled={isUpdating}
                                                    style={{
                                                        display: 'flex', alignItems: 'center', gap: 4,
                                                        padding: '5px 12px', borderRadius: 100,
                                                        border: '1.5px solid var(--c-border)',
                                                        background: 'none', color: 'var(--c-ink-3)',
                                                        fontSize: 12, fontWeight: 600, cursor: 'pointer',
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

                                            {isArchived && (
                                                <button
                                                    onClick={() => void setStatus(lead, 'NEW')}
                                                    disabled={isUpdating}
                                                    style={{
                                                        display: 'flex', alignItems: 'center', gap: 4,
                                                        padding: '5px 12px', borderRadius: 100,
                                                        border: '1.5px solid var(--c-border)',
                                                        background: 'none', color: 'var(--c-ink-3)',
                                                        fontSize: 12, fontWeight: 600, cursor: 'pointer',
                                                        fontFamily: 'var(--font-body)', transition: 'all .15s',
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
                                                    onClick={() => handleOpen(lead)}
                                                >
                                                    Открыть
                                                    <IconExternalLink />
                                                </a>
                                            )}
                                        </div>
                                    </div>

                                    {/* ─── Строка оценок ─── */}
                                    {!isArchived && (
                                        <FeedbackRow
                                            rating={lead.rating ?? null}
                                            submitting={isRating}
                                            onRate={r => void handleRate(lead, r)}
                                        />
                                    )}
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