import { useState, useEffect, useCallback } from 'react'
import { leadsApi, type Lead, type LeadPage } from '../api/leads'
import s from './Leadspage.module.css'

const STATUS_LABEL: Record<string, string> = {
    NEW:     'Новый',
    VIEWED:  'Просмотрен',
    REPLIED: 'Отвечено',
    IGNORED: 'Игнорирован',
}

const STATUS_NEXT: Record<string, string> = {
    NEW:     'VIEWED',
    VIEWED:  'REPLIED',
    REPLIED: 'VIEWED',
    IGNORED: 'VIEWED',
}

const STATUS_COLOR: Record<string, { bg: string; text: string }> = {
    NEW:     { bg: 'rgba(239,68,68,.12)',    text: '#dc2626' },
    VIEWED:  { bg: 'rgba(245,158,11,.12)',   text: '#d97706' },
    REPLIED: { bg: 'rgba(16,185,129,.12)',   text: '#059669' },
    IGNORED: { bg: 'rgba(107,114,128,.12)', text: '#6b7280' },
}


const FILTERS = [
    { value: '',         label: 'Все',          excludeIgnored: true },
    { value: 'NEW',      label: 'Новые',         excludeIgnored: false },
    { value: 'VIEWED',   label: 'Просмотрены',   excludeIgnored: false },
    { value: 'REPLIED',  label: 'Отвечено',      excludeIgnored: false },
    { value: 'IGNORED',  label: 'Архив',         excludeIgnored: false },
]


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

export default function LeadsPage() {
    const [data,    setData]    = useState<LeadPage | null>(null)
    const [loading, setLoading] = useState(true)
    const [filter,  setFilter]  = useState('')
    const [page,    setPage]    = useState(0)
    const [error,   setError]   = useState('')
    const [updating, setUpdating] = useState<Set<number>>(new Set())

    const [expanded, setExpanded] = useState<Set<number>>(new Set())

    const currentFilterDef = FILTERS.find(f => f.value === filter) ?? FILTERS[0]

    const load = useCallback(async () => {
        setLoading(true)
        setError('')
        try {
            const statusParam = filter || undefined
            const result = await leadsApi.list({ status: statusParam, page, size: 20 })
            setData(result)
        } catch (e: unknown) {
            setError(e instanceof Error ? e.message : 'Ошибка загрузки')
        } finally {
            setLoading(false)
        }
    }, [filter, page])

    useEffect(() => { load() }, [load])

    const changeFilter = (value: string) => {
        setFilter(value)
        setPage(0)
    }

    const toggleExpand = (id: number) => {
        setExpanded(prev => {
            const next = new Set(prev)
            next.has(id) ? next.delete(id) : next.add(id)
            return next
        })
    }

    const handleStatusChange = async (lead: Lead) => {
        const next = STATUS_NEXT[lead.status] || 'VIEWED'
        setUpdating(prev => new Set(prev).add(lead.id))
        try {
            const updated = await leadsApi.updateStatus(lead.id, next)
            setData(prev => prev ? {
                ...prev,
                content: prev.content.map(l => l.id === updated.id ? updated : l),
            } : prev)
        } catch {

        } finally {
            setUpdating(prev => { const s = new Set(prev); s.delete(lead.id); return s })
        }
    }

    const handleIgnore = async (lead: Lead) => {
        setUpdating(prev => new Set(prev).add(lead.id))
        try {
            const updated = await leadsApi.updateStatus(lead.id, 'IGNORED')
            setData(prev => prev ? {
                ...prev,
                content: prev.content.map(l => l.id === updated.id ? updated : l),
            } : prev)
        } catch {

        } finally {
            setUpdating(prev => { const s = new Set(prev); s.delete(lead.id); return s })
        }
    }

    const visibleContent = data?.content.filter(lead =>
        currentFilterDef.excludeIgnored ? lead.status !== 'IGNORED' : true
    ) ?? []

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
                <div>
                    <h1 className={s.title}>Лиды</h1>
                    {data && data.newCount > 0 && (
                        <span style={{
                            fontSize: 12, fontWeight: 600,
                            background: 'rgba(239,68,68,.1)', color: '#dc2626',
                            padding: '2px 9px', borderRadius: 100,
                            marginTop: 4, display: 'inline-block',
                        }}>
                            {data.newCount} новых
                        </span>
                    )}
                </div>
                <div className={s.tabs}>
                    {FILTERS.map(f => (
                        <button
                            key={f.value}
                            className={`${s.tab} ${filter === f.value ? s.tabActive : ''}`}
                            onClick={() => changeFilter(f.value)}
                            style={f.value === 'IGNORED' ? { display: 'flex', alignItems: 'center', gap: 5 } : undefined}
                        >
                            {f.value === 'IGNORED' && <IconArchive />}
                            {f.label}
                        </button>
                    ))}
                </div>
            </div>

            {error && <div className={s.error}>{error}</div>}

            {visibleContent.length === 0 ? (
                <div className={s.empty}>
                    <div className={s.emptyIcon}>
                        {filter === 'IGNORED' ? <IconArchive /> : '—'}
                    </div>
                    <p>{filter === 'IGNORED' ? 'Архив пуст' : 'Лидов нет'}</p>
                    <span>
                        {filter === 'IGNORED'
                            ? 'Здесь будут лиды, отмеченные как проигнорированные'
                            : 'Добавьте чаты и ключевые слова для мониторинга'}
                    </span>
                </div>
            ) : (
                <>
                    <div className={s.list}>
                        {visibleContent.map(lead => {
                            const color = STATUS_COLOR[lead.status] || STATUS_COLOR.NEW
                            const isNew = lead.status === 'NEW'
                            const isArchived = lead.status === 'IGNORED'
                            const isExpanded = expanded.has(lead.id)

                            const hasContext = Array.isArray((lead as any).contextMessages)
                                ? (lead as any).contextMessages.length > 0
                                : false
                            const contextMsgs: string[] = hasContext ? (lead as any).contextMessages : []

                            return (
                                <div
                                    key={lead.id}
                                    className={s.leadCard}
                                    style={{
                                        ...(isNew ? { borderLeftColor: '#dc2626', borderLeftWidth: 3 } : {}),
                                        ...(isArchived ? { opacity: 0.7 } : {}),
                                    }}
                                >
                                    {}
                                    <div className={s.leadHead}>
                                        <div className={s.leadMeta}>
                                            <div style={{
                                                width: 32, height: 32, borderRadius: '50%',
                                                background: 'var(--c-accent-soft)', color: 'var(--c-accent)',
                                                display: 'flex', alignItems: 'center', justifyContent: 'center',
                                                fontWeight: 700, fontSize: 14, flexShrink: 0,
                                                fontFamily: 'var(--font-head)',
                                            }}>
                                                {(lead.authorName || lead.chatTitle || '?').charAt(0).toUpperCase()}
                                            </div>
                                            <div>
                                                <span className={s.leadChat}>{lead.chatTitle || lead.chatLink}</span>
                                                <span className={s.leadDate} style={{ marginLeft: 8 }}>
                                                    {new Date(lead.foundAt).toLocaleString('ru-RU', {
                                                        day: 'numeric', month: 'short',
                                                        hour: '2-digit', minute: '2-digit',
                                                    })}
                                                </span>
                                            </div>
                                            {lead.matchedKeyword && (
                                                <span style={{
                                                    fontSize: 11,
                                                    background: 'var(--c-accent-soft)',
                                                    color: 'var(--c-accent)',
                                                    padding: '2px 8px', borderRadius: 100, fontWeight: 600,
                                                }}>
                                                    {lead.matchedKeyword}
                                                </span>
                                            )}
                                        </div>
                                        <button
                                            className={s.statusBtn}
                                            style={{ background: color.bg, color: color.text }}
                                            onClick={() => handleStatusChange(lead)}
                                            disabled={updating.has(lead.id)}
                                            title={`Изменить на: ${STATUS_LABEL[STATUS_NEXT[lead.status] || 'VIEWED']}`}
                                        >
                                            {updating.has(lead.id) ? '...' : STATUS_LABEL[lead.status] || lead.status}
                                        </button>
                                    </div>

                                    {}
                                    <div className={s.leadTextBlock}>
                                        <div className={s.leadTextInner}>
                                            <div className={s.leadText}>{lead.messageText}</div>
                                        </div>
                                    </div>

                                    {}
                                    {(contextMsgs.length > 0 || lead.aiValid !== null && lead.aiValid !== undefined) && (
                                        <button
                                            onClick={() => toggleExpand(lead.id)}
                                            style={{
                                                display: 'flex',
                                                alignItems: 'center',
                                                gap: 5,
                                                padding: '4px 0',
                                                background: 'none',
                                                border: 'none',
                                                color: 'var(--c-ink-3)',
                                                fontSize: 12,
                                                fontWeight: 500,
                                                cursor: 'pointer',
                                                fontFamily: 'var(--font-body)',
                                                width: 'fit-content',
                                                transition: 'color .15s',
                                            }}
                                            onMouseEnter={e => (e.currentTarget.style.color = 'var(--c-accent)')}
                                            onMouseLeave={e => (e.currentTarget.style.color = 'var(--c-ink-3)')}
                                        >
                                            <IconChevron open={isExpanded} />
                                            {isExpanded
                                                ? 'Скрыть детали'
                                                : `Детали${contextMsgs.length > 0 ? ` · ${contextMsgs.length} сообщ. контекста` : ''}${lead.aiValid !== null ? ' · AI' : ''}`
                                            }
                                        </button>
                                    )}

                                    {/* ─── раскрытый блок: контекст + AI причина ─── */}
                                    {isExpanded && (
                                        <div style={{
                                            display: 'flex',
                                            flexDirection: 'column',
                                            gap: 10,
                                            padding: '10px 14px',
                                            background: 'var(--c-bg)',
                                            borderRadius: 10,
                                            border: '1px solid var(--c-border)',
                                        }}>
                                            {}
                                            {lead.aiValid !== null && lead.aiValid !== undefined && (
                                                <div style={{
                                                    display: 'flex',
                                                    alignItems: 'flex-start',
                                                    gap: 8,
                                                    padding: '8px 12px',
                                                    borderRadius: 8,
                                                    background: lead.aiValid
                                                        ? 'rgba(16,185,129,.08)'
                                                        : 'rgba(239,68,68,.08)',
                                                    border: `1px solid ${lead.aiValid ? 'rgba(16,185,129,.2)' : 'rgba(239,68,68,.2)'}`,
                                                }}>
                                                    <span style={{
                                                        display: 'flex',
                                                        alignItems: 'center',
                                                        gap: 4,
                                                        fontSize: 11,
                                                        fontWeight: 700,
                                                        color: lead.aiValid ? '#10b981' : '#ef4444',
                                                        flexShrink: 0,
                                                        marginTop: 1,
                                                    }}>
                                                        <IconAI valid={lead.aiValid} />
                                                        AI {lead.aiValid ? 'одобрил' : 'отклонил'}
                                                    </span>
                                                    {lead.aiReason && (
                                                        <span style={{
                                                            fontSize: 12,
                                                            color: 'var(--c-ink-2)',
                                                            lineHeight: 1.5,
                                                        }}>
                                                            {lead.aiReason}
                                                        </span>
                                                    )}
                                                </div>
                                            )}

                                            {}
                                            {contextMsgs.length > 0 && (
                                                <div>
                                                    <div style={{
                                                        fontSize: 11,
                                                        fontWeight: 700,
                                                        textTransform: 'uppercase',
                                                        letterSpacing: '.5px',
                                                        color: 'var(--c-ink-3)',
                                                        marginBottom: 8,
                                                    }}>
                                                        Контекст чата
                                                    </div>
                                                    <div style={{
                                                        display: 'flex',
                                                        flexDirection: 'column',
                                                        gap: 6,
                                                    }}>
                                                        {contextMsgs.map((msg, i) => (
                                                            <div key={i} style={{
                                                                fontSize: 13,
                                                                color: 'var(--c-ink-2)',
                                                                lineHeight: 1.5,
                                                                padding: '6px 10px',
                                                                borderLeft: '2px solid var(--c-border)',
                                                                background: 'var(--c-surface)',
                                                                borderRadius: '0 6px 6px 0',
                                                                wordBreak: 'break-word',
                                                            }}>
                                                                {msg}
                                                            </div>
                                                        ))}
                                                    </div>
                                                </div>
                                            )}
                                        </div>
                                    )}

                                    {}
                                    <div className={s.leadFooter}>
                                        <div className={s.leadAuthor}>
                                            <span style={{ color: 'var(--c-ink-3)', flexShrink: 0 }}>
                                                <IconUser />
                                            </span>
                                            <span className={s.authorName}>{lead.authorName || 'Аноним'}</span>
                                            {lead.authorUsername && (
                                                <span className={s.authorUsername}>@{lead.authorUsername}</span>
                                            )}
                                        </div>
                                        <div className={s.leadActions}>
                                            {}
                                            {lead.aiValid !== null && lead.aiValid !== undefined && (
                                                <div
                                                    className={s.aiBadge}
                                                    style={{
                                                        background: lead.aiValid
                                                            ? 'rgba(16,185,129,.12)'
                                                            : 'rgba(239,68,68,.12)',
                                                        color: lead.aiValid ? '#10b981' : '#ef4444',
                                                        display: 'flex', alignItems: 'center', gap: 4,
                                                        cursor: 'pointer',
                                                    }}
                                                    onClick={() => toggleExpand(lead.id)}
                                                    title="Нажмите чтобы увидеть причину"
                                                >
                                                    <IconAI valid={lead.aiValid} />
                                                    AI
                                                </div>
                                            )}

                                            {/* Кнопка «в архив» — только для не-архивных лидов */}
                                            {lead.status !== 'IGNORED' && (
                                                <button
                                                    onClick={() => handleIgnore(lead)}
                                                    disabled={updating.has(lead.id)}
                                                    title="В архив"
                                                    style={{
                                                        display: 'flex', alignItems: 'center', gap: 4,
                                                        padding: '4px 10px', borderRadius: 100,
                                                        border: '1.5px solid var(--c-border)',
                                                        background: 'none',
                                                        color: 'var(--c-ink-3)', fontSize: 11,
                                                        fontWeight: 600, cursor: 'pointer',
                                                        fontFamily: 'var(--font-body)',
                                                        transition: 'all .15s',
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

                                            <a
                                                href={lead.messageLink}
                                                target="_blank"
                                                rel="noopener noreferrer"
                                                className={s.openLink}
                                                style={{ display: 'flex', alignItems: 'center', gap: 4 }}
                                                onClick={() => {
                                                    if (lead.status === 'NEW') {
                                                        leadsApi.updateStatus(lead.id, 'VIEWED').then(updated => {
                                                            setData(prev => prev ? {
                                                                ...prev,
                                                                content: prev.content.map(l =>
                                                                    l.id === updated.id ? updated : l
                                                                ),
                                                            } : prev)
                                                        }).catch(() => {})
                                                    }
                                                }}
                                            >
                                                Открыть
                                                <IconExternalLink />
                                            </a>
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