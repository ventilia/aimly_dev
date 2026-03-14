import { useState, useEffect } from 'react'
import { chatsApi, keywordsApi, type ChatSubscription } from '../api/leads.ts'
import { useAuthContext } from '../context/AuthContext'
import s from './Chatspage.module.css'

// ─── Icons ────────────────────────────────────────────────────────────────────
function TgIcon() {
    return (
        <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor">
            <path d="M11.944 0A12 12 0 0 0 0 12a12 12 0 0 0 12 12 12 12 0 0 0 12-12A12 12 0 0 0 12 0a12 12 0 0 0-.056 0zm4.962 7.224c.1-.002.321.023.465.14a.506.506 0 0 1 .171.325c.016.093.036.306.02.472-.18 1.898-.96 6.502-1.36 8.627-.168.9-.499 1.201-.82 1.23-.696.065-1.225-.46-1.9-.902-1.056-.693-1.653-1.124-2.678-1.8-1.185-.78-.417-1.21.258-1.91.177-.184 3.247-2.977 3.307-3.23.007-.032.014-.15-.056-.212s-.174-.041-.249-.024c-.106.024-1.793 1.14-5.061 3.345-.48.33-.913.49-1.302.48-.428-.008-1.252-.241-1.865-.44-.752-.245-1.349-.374-1.297-.789.027-.216.325-.437.893-.663 3.498-1.524 5.83-2.529 6.998-3.014 3.332-1.386 4.025-1.627 4.476-1.635z" />
        </svg>
    )
}

function SparkleIcon() {
    return (
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#7c3aed"
             strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M12 2l2.4 7.4H22l-6.2 4.5 2.4 7.4L12 17 5.8 21.3l2.4-7.4L2 9.4h7.6z" />
        </svg>
    )
}

function SearchIcon() {
    return (
        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor"
             strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <circle cx="11" cy="11" r="8" /><path d="m21 21-4.35-4.35" />
        </svg>
    )
}

function UsersIcon() {
    return (
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor"
             strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
            <circle cx="9" cy="7" r="4" />
            <path d="M23 21v-2a4 4 0 0 0-3-3.87" />
            <path d="M16 3.13a4 4 0 0 1 0 7.75" />
        </svg>
    )
}

function PlusIcon() {
    return (
        <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor"
             strokeWidth="2.5" strokeLinecap="round">
            <line x1="12" y1="5" x2="12" y2="19" /><line x1="5" y1="12" x2="19" y2="12" />
        </svg>
    )
}

function LockIcon() {
    return (
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
             strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
            <path d="M7 11V7a5 5 0 0 1 10 0v4" />
        </svg>
    )
}

function ChevronIcon({ open }: { open: boolean }) {
    return (
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
             strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round"
             style={{ transition: 'transform .2s', transform: open ? 'rotate(180deg)' : 'rotate(0)' }}>
            <polyline points="6 9 12 15 18 9" />
        </svg>
    )
}

function CheckIcon() {
    return (
        <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor"
             strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
            <polyline points="20 6 9 17 4 12" />
        </svg>
    )
}

function CloseIcon() {
    return (
        <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor"
             strokeWidth="2.5" strokeLinecap="round">
            <line x1="18" y1="6" x2="6" y2="18" /><line x1="6" y1="6" x2="18" y2="18" />
        </svg>
    )
}

function HashIcon() {
    return (
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor"
             strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
            <line x1="4" y1="9" x2="20" y2="9"/>
            <line x1="4" y1="15" x2="20" y2="15"/>
            <line x1="10" y1="3" x2="8" y2="21"/>
            <line x1="16" y1="3" x2="14" y2="21"/>
        </svg>
    )
}

// ─── Types ────────────────────────────────────────────────────────────────────
interface TgstatResult {
    title: string
    username: string | null
    description: string | null
    participantsCount: number
    link: string
    tgstatLink: string | null
    peerType?: string
}

interface TgstatSearchResponse {
    results: TgstatResult[]
    queries: string[]
}

// ─── Персистентное состояние AI-поиска ────────────────────────────────────────
const SESSION_KEY = 'aimly_chat_search_state'

interface SearchState {
    results: TgstatResult[]
    queries: string[]
    addedLinks: string[]
    dismissedLinks: string[]
    searchQuery: string
}

function saveSearchState(state: SearchState) {
    try {
        sessionStorage.setItem(SESSION_KEY, JSON.stringify(state))
    } catch { /* ignore */ }
}

function loadSearchState(): SearchState | null {
    try {
        const raw = sessionStorage.getItem(SESSION_KEY)
        if (!raw) return null
        return JSON.parse(raw) as SearchState
    } catch {
        return null
    }
}

function clearSearchState() {
    try { sessionStorage.removeItem(SESSION_KEY) } catch { /* ignore */ }
}

// ─── API ──────────────────────────────────────────────────────────────────────
const BASE: string = import.meta.env.VITE_API_URL || ''

async function searchChatsApi(query: string): Promise<TgstatSearchResponse> {
    const res = await fetch(`${BASE}/api/v1/chats/search`, {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ query }),
    })
    if (!res.ok) {
        const body = await res.json().catch(() => ({})) as { error?: string; message?: string }
        throw new Error(body.error ?? body.message ?? `Ошибка ${res.status}`)
    }
    return res.json() as Promise<TgstatSearchResponse>
}

// ─── Helpers ──────────────────────────────────────────────────────────────────
function buildTelegramUrl(link: string): string {
    const raw = link.trim()
    if (raw.startsWith('https://t.me/') || raw.startsWith('http://t.me/')) return raw
    if (raw.startsWith('t.me/')) return `https://${raw}`
    if (raw.startsWith('@')) return `https://t.me/${raw.slice(1)}`
    return `https://t.me/${raw}`
}

function formatCount(n: number): string {
    if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`
    if (n >= 1_000)     return `${(n / 1_000).toFixed(1)}K`
    return String(n)
}

// ─── Sub-components ───────────────────────────────────────────────────────────
function StatusDot({ active }: { active: boolean }) {
    return (
        <span
            className={s.statusDot}
            style={{ background: active ? '#10b981' : 'var(--c-border)' }}
            title={active ? 'Мониторинг активен' : 'Ожидание подключения'}
        />
    )
}

function ResultCard({
                        result,
                        onAdd,
                        onDismiss,
                        isAdding,
                        isAdded,
                        isDismissed,
                    }: {
    result: TgstatResult
    onAdd: (link: string) => void
    onDismiss: (link: string) => void
    isAdding: boolean
    isAdded: boolean
    isDismissed: boolean
}) {
    if (isDismissed) return null

    const cardCls = `${s.resultCard}${isAdded ? ` ${s.resultAdded}` : ''}`

    return (
        <div className={cardCls}>
            <div className={s.resultTop}>
                <div className={s.resultTitleWrap}>
                    <p className={s.resultTitle}>{result.title}</p>
                    {result.username && (
                        <a href={result.link} target="_blank" rel="noopener noreferrer"
                           className={s.resultUsername}>
                            {result.username}
                        </a>
                    )}
                </div>
                {result.participantsCount > 0 && (
                    <div className={s.resultMembers}>
                        <UsersIcon />
                        {formatCount(result.participantsCount)}
                    </div>
                )}
            </div>

            {result.description && (
                <p className={s.resultDesc}>{result.description}</p>
            )}

            <div className={s.resultActions}>
                <button
                    style={{
                        display: 'inline-flex', alignItems: 'center', gap: 5,
                        padding: '7px 14px', borderRadius: 8, border: 'none',
                        fontSize: 12, fontWeight: 600, cursor: isAdded || isAdding ? 'default' : 'pointer',
                        fontFamily: 'var(--font-body)', transition: 'opacity .15s',
                        background: isAdded ? 'rgba(16,185,129,.12)' : 'var(--c-accent)',
                        color: isAdded ? '#10b981' : '#fff',
                        opacity: isAdded || isAdding ? 0.8 : 1,
                    }}
                    onClick={() => !isAdded && !isAdding && result.link && onAdd(result.link)}
                    disabled={isAdding || isAdded || !result.link}
                >
                    {isAdding
                        ? <><span className={s.spinnerAccent} /> Добавляем…</>
                        : isAdded
                            ? <><CheckIcon /> Добавлен</>
                            : <><PlusIcon /> Добавить</>
                    }
                </button>

                {!isAdded && (
                    <button
                        onClick={() => onDismiss(result.link)}
                        title="Не показывать этот чат"
                        style={{
                            display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                            width: 30, height: 30, borderRadius: 8,
                            border: '1.5px solid var(--c-border)', background: 'transparent',
                            color: 'var(--c-ink-3)', cursor: 'pointer', transition: 'all .15s',
                        }}
                        onMouseEnter={e => {
                            (e.currentTarget as HTMLButtonElement).style.borderColor = '#ef4444'
                            ;(e.currentTarget as HTMLButtonElement).style.color = '#ef4444'
                        }}
                        onMouseLeave={e => {
                            (e.currentTarget as HTMLButtonElement).style.borderColor = 'var(--c-border)'
                            ;(e.currentTarget as HTMLButtonElement).style.color = 'var(--c-ink-3)'
                        }}
                    >
                        <CloseIcon />
                    </button>
                )}

                {result.tgstatLink && (
                    <a href={result.tgstatLink} target="_blank" rel="noopener noreferrer"
                       className={s.resultStatBtn}>
                        Статистика ↗
                    </a>
                )}
            </div>
        </div>
    )
}

// ─── Main ─────────────────────────────────────────────────────────────────────
export default function ChatsPage() {
    const { user } = useAuthContext()

    const [chats,    setChats]    = useState<ChatSubscription[]>([])
    const [loading,  setLoading]  = useState(true)
    const [input,    setInput]    = useState('')
    const [adding,   setAdding]   = useState(false)
    const [error,    setError]    = useState('')
    const [removing, setRemoving] = useState<number | null>(null)

    // AI-поиск — состояние (восстанавливается из sessionStorage)
    const [searchOpen,     setSearchOpen]     = useState(false)
    const [searchQuery,    setSearchQuery]    = useState('')
    const [searching,      setSearching]      = useState(false)
    const [searchResults,  setSearchResults]  = useState<TgstatResult[] | null>(null)
    const [searchError,    setSearchError]    = useState('')
    const [searchQueries,  setSearchQueries]  = useState<string[]>([])
    const [addingLink,     setAddingLink]     = useState<string | null>(null)
    const [addedLinks,     setAddedLinks]     = useState<Set<string>>(new Set())
    const [dismissedLinks, setDismissedLinks] = useState<Set<string>>(new Set())

    // Поиск по ключевым словам
    const [userKeywords,        setUserKeywords]        = useState<string[]>([])
    const [loadingKeywords,     setLoadingKeywords]     = useState(false)
    const [searchingByKeywords, setSearchingByKeywords] = useState(false)

    const plan  = user?.subscriptionPlan   ?? null
    const st    = user?.subscriptionStatus ?? null
    const hasAi = plan === 'MINIMUM' || plan === 'START' || st === 'TRIAL'

    // ─── Восстановление состояния из sessionStorage ───────────────────────────
    useEffect(() => {
        const saved = loadSearchState()
        if (saved && saved.results.length > 0) {
            setSearchResults(saved.results)
            setSearchQueries(saved.queries)
            setAddedLinks(new Set(saved.addedLinks))
            setDismissedLinks(new Set(saved.dismissedLinks))
            setSearchQuery(saved.searchQuery)
            setSearchOpen(true)
        }
    }, [])

    // ─── Персистентное сохранение при изменении ───────────────────────────────
    useEffect(() => {
        if (searchResults === null) return
        saveSearchState({
            results:       searchResults,
            queries:       searchQueries,
            addedLinks:    Array.from(addedLinks),
            dismissedLinks: Array.from(dismissedLinks),
            searchQuery,
        })
    }, [searchResults, searchQueries, addedLinks, dismissedLinks, searchQuery])

    const fetchChats = () =>
        chatsApi.list()
            .then(setChats)
            .catch((e: unknown) => setError(e instanceof Error ? e.message : 'Ошибка загрузки'))

    useEffect(() => { fetchChats().finally(() => setLoading(false)) }, [])

    // Загружаем ключевые слова пользователя
    useEffect(() => {
        if (!hasAi) return
        setLoadingKeywords(true)
        keywordsApi.list()
            .then(kws => setUserKeywords(kws.map(k => k.keyword)))
            .catch(() => {})
            .finally(() => setLoadingKeywords(false))
    }, [hasAi])

    const add = async () => {
        const link = input.trim()
        if (!link) return
        setAdding(true); setError('')
        try {
            await chatsApi.add(link)
            setInput('')
            await fetchChats()
            setTimeout(() => fetchChats(), 5_000)
            setTimeout(() => fetchChats(), 15_000)
        } catch (e: unknown) {
            setError(e instanceof Error ? e.message : 'Не удалось добавить чат')
        } finally {
            setAdding(false)
        }
    }

    const remove = async (id: number) => {
        setRemoving(id)
        try {
            await chatsApi.remove(id)
            setChats(prev => prev.filter(c => c.id !== id))
        } catch (e: unknown) {
            setError(e instanceof Error ? e.message : 'Ошибка удаления')
        } finally { setRemoving(null) }
    }

    const runSearch = async (query: string) => {
        setSearching(true)
        setSearchError('')
        setSearchResults(null)
        setSearchQueries([])
        setDismissedLinks(new Set())
        setAddedLinks(new Set())
        clearSearchState()
        try {
            const resp = await searchChatsApi(query)
            setSearchResults(resp.results)
            setSearchQueries(resp.queries)
        } catch (e: unknown) {
            setSearchError(e instanceof Error ? e.message : 'Ошибка поиска')
        } finally {
            setSearching(false)
        }
    }

    const handleSearch = async () => {
        await runSearch(searchQuery.trim())
    }

    // Поиск по ключевым словам пользователя — передаём их как общий запрос
    const handleSearchByKeywords = async () => {
        if (userKeywords.length === 0) return
        setSearchingByKeywords(true)
        // Берём первые 5 ключевых слов как запрос — AI сам расширит
        const query = userKeywords.slice(0, 5).join(', ')
        await runSearch(query)
        setSearchingByKeywords(false)
    }

    const handleAddFromSearch = async (link: string) => {
        if (addedLinks.has(link) || addingLink === link) return
        setAddingLink(link); setError('')
        try {
            await chatsApi.add(link)
            setAddedLinks(prev => new Set([...prev, link]))
            await fetchChats()
            setTimeout(() => fetchChats(), 5_000)
            setTimeout(() => fetchChats(), 15_000)
        } catch (e: unknown) {
            setError(e instanceof Error ? e.message : 'Не удалось добавить чат')
        } finally { setAddingLink(null) }
    }

    const handleDismiss = (link: string) => {
        setDismissedLinks(prev => new Set([...prev, link]))
    }

    const handleDismissAll = () => {
        if (!searchResults) return
        setDismissedLinks(new Set(searchResults.map(r => r.link)))
    }

    const handleAddAll = async () => {
        if (!searchResults) return
        const toAdd = searchResults.filter(r => !addedLinks.has(r.link) && !dismissedLinks.has(r.link) && r.link)
        for (const result of toAdd) {
            await handleAddFromSearch(result.link)
        }
    }

    const handleClearResults = () => {
        setSearchResults(null)
        setSearchQueries([])
        setDismissedLinks(new Set())
        setAddedLinks(new Set())
        setSearchError('')
        setSearchOpen(false)
        clearSearchState()
    }

    const visibleResults = searchResults?.filter(r => !dismissedLinks.has(r.link)) ?? []
    const pendingCount   = visibleResults.filter(r => !addedLinks.has(r.link)).length

    // Блок можно скрыть/раскрыть только когда есть результаты
    const canToggle = searchResults !== null && searchResults.length > 0

    const formatDate = (iso: string) => {
        try { return new Date(iso).toLocaleDateString('ru-RU', { day: '2-digit', month: 'short' }) }
        catch { return '' }
    }

    return (
        <div className={s.page}>

            {/* ─── Заголовок ─────────────────────────────────── */}
            <div className={s.pageHead}>
                <div>
                    <h1 className={s.title}>Чаты для мониторинга</h1>
                    <p className={s.sub}>Userbot вступает в чат и читает сообщения в реальном времени</p>
                </div>
                {chats.length > 0 && (
                    <div className={s.counter}>
                        <span className={s.counterNum}>{chats.length}</span>
                        <span className={s.counterLabel}>чатов</span>
                    </div>
                )}
            </div>

            {/* ─── Ручное добавление ─────────────────────────── */}
            <div className={s.addBlock}>
                <div className={s.addRow}>
                    <div className={s.inputWrap}>
                        <span className={s.inputIcon}><TgIcon /></span>
                        <input
                            className={s.input}
                            value={input}
                            onChange={e => setInput(e.target.value)}
                            onKeyDown={e => e.key === 'Enter' && add()}
                            placeholder="t.me/smm_russia или @channel_name"
                            disabled={adding}
                            autoComplete="off"
                        />
                    </div>
                    <button className={s.addBtn} onClick={add} disabled={adding || !input.trim()}>
                        {adding ? <span className={s.spinner} /> : '+ Добавить'}
                    </button>
                </div>
                <p className={s.addHint}>Поддерживаются публичные каналы, группы и invite-ссылки</p>
            </div>

            {error && (
                <div className={s.error}>
                    <span>{error}</span>
                    <button className={s.errorClose} onClick={() => setError('')}>✕</button>
                </div>
            )}

            {/* ─── Список чатов ──────────────────────────────── */}
            {loading ? (
                <div className={s.skels}>
                    {[...Array(3)].map((_, i) => <div key={i} className={s.skel} />)}
                </div>
            ) : chats.length === 0 ? (
                <div className={s.empty}>
                    <div className={s.emptyIcon}><TgIcon /></div>
                    <p className={s.emptyTitle}>Нет добавленных чатов</p>
                    <span className={s.emptySub}>Введите ссылку выше или найдите чаты через AI поиск ниже</span>
                </div>
            ) : (
                <div className={s.list}>
                    {chats.map(c => {
                        const tgUrl = buildTelegramUrl(c.chatLink)
                        const displayTitle = c.chatTitle && c.chatTitle !== c.chatLink
                            ? c.chatTitle : c.chatLink
                        return (
                            <div key={c.id} className={s.card}>
                                <div className={s.cardLeft}>
                                    <div className={s.cardIcon}><TgIcon /></div>
                                    <div className={s.cardInfo}>
                                        <div className={s.cardTitle}>
                                            <StatusDot active={c.chatTgId !== 0} />
                                            {displayTitle}
                                        </div>
                                        <div className={s.cardMeta}>
                                            <a href={tgUrl} target="_blank" rel="noopener noreferrer"
                                               className={s.cardLink} onClick={e => e.stopPropagation()}>
                                                {c.chatLink}
                                            </a>
                                            {c.chatTgId !== 0 && (
                                                <span className={s.cardId}>ID: {c.chatTgId}</span>
                                            )}
                                            {c.createdAt && (
                                                <span className={s.cardDate}>
                                                    добавлен {formatDate(c.createdAt)}
                                                </span>
                                            )}
                                        </div>
                                    </div>
                                </div>
                                <button
                                    className={s.removeBtn}
                                    onClick={() => remove(c.id)}
                                    disabled={removing === c.id}
                                    title="Удалить из мониторинга"
                                >
                                    {removing === c.id ? <span className={s.spinnerSm} /> : '✕'}
                                </button>
                            </div>
                        )
                    })}
                </div>
            )}

            {/* ─── AI-поиск чатов (внизу, сворачиваемый) ────── */}
            <div className={s.searchBlock}>
                {/* Заголовок — кликабелен для сворачивания только когда есть результаты */}
                <div
                    className={s.searchToggleBtn}
                    style={{ cursor: canToggle ? 'pointer' : 'default' }}
                    onClick={() => canToggle && setSearchOpen(v => !v)}
                    role={canToggle ? 'button' : undefined}
                    aria-expanded={canToggle ? searchOpen : undefined}
                >
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                        <SparkleIcon />
                        <span className={s.searchTitle}>Найти чаты по AI</span>
                        {hasAi && (
                            <span style={{
                                fontSize: 11, fontWeight: 700, padding: '3px 8px',
                                borderRadius: 6, background: 'var(--c-accent-soft)',
                                color: 'var(--c-accent)', letterSpacing: '0.5px',
                            }}>
                                AI-функция
                            </span>
                        )}
                    </div>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                        {/* Кнопка очистить — только когда есть результаты */}
                        {searchResults !== null && (
                            <button
                                onClick={e => { e.stopPropagation(); handleClearResults() }}
                                title="Сбросить результаты"
                                style={{
                                    display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                                    padding: '4px 10px', borderRadius: 7,
                                    border: '1.5px solid var(--c-border)',
                                    background: 'transparent', color: 'var(--c-ink-3)',
                                    fontSize: 11, fontWeight: 600, cursor: 'pointer',
                                    fontFamily: 'var(--font-body)', transition: 'all .15s',
                                    gap: 4,
                                }}
                                onMouseEnter={e => {
                                    (e.currentTarget as HTMLButtonElement).style.borderColor = '#ef4444'
                                    ;(e.currentTarget as HTMLButtonElement).style.color = '#ef4444'
                                }}
                                onMouseLeave={e => {
                                    (e.currentTarget as HTMLButtonElement).style.borderColor = 'var(--c-border)'
                                    ;(e.currentTarget as HTMLButtonElement).style.color = 'var(--c-ink-3)'
                                }}
                            >
                                <CloseIcon /> Сбросить
                            </button>
                        )}
                        {canToggle && <ChevronIcon open={searchOpen} />}
                    </div>
                </div>

                {/* Контент — виден когда открыт (или если нет результатов — всегда открыт) */}
                {(!canToggle || searchOpen) && (
                    <>
                        {hasAi ? (
                            <>
                                <p className={s.searchDesc}>
                                    Опишите себя или нишу — AI подберёт подходящие Telegram-чаты.
                                    {user?.businessContext ? ' Оставьте поле пустым для поиска по AI-профилю.' : ''}
                                </p>

                                <div className={s.searchRow}>
                                    <input
                                        className={s.searchInput}
                                        value={searchQuery}
                                        onChange={e => setSearchQuery(e.target.value)}
                                        onKeyDown={e => e.key === 'Enter' && !searching && handleSearch()}
                                        placeholder={
                                            user?.businessContext
                                                ? 'Пусто = поиск по AI-профилю, или введите запрос...'
                                                : 'Например: дизайн, smm, веб-разработка...'
                                        }
                                        disabled={searching || searchingByKeywords}
                                    />
                                    <button
                                        className={s.searchBtn}
                                        onClick={handleSearch}
                                        disabled={searching || searchingByKeywords || (!searchQuery.trim() && !user?.businessContext)}
                                    >
                                        {searching && !searchingByKeywords
                                            ? <><span className={s.spinnerAccent} /> Ищем…</>
                                            : <><SearchIcon /> Найти чаты</>
                                        }
                                    </button>
                                </div>

                                {/* Кнопка поиска по ключевым словам */}
                                {userKeywords.length > 0 && (
                                    <button
                                        onClick={handleSearchByKeywords}
                                        disabled={searching || searchingByKeywords || loadingKeywords}
                                        style={{
                                            display: 'inline-flex', alignItems: 'center', gap: 7,
                                            padding: '9px 16px', borderRadius: 10,
                                            border: '1.5px solid var(--c-border)',
                                            background: 'var(--c-bg)',
                                            color: 'var(--c-ink-2)',
                                            fontSize: 13, fontWeight: 600,
                                            cursor: (searching || searchingByKeywords) ? 'default' : 'pointer',
                                            fontFamily: 'var(--font-body)', transition: 'all .15s',
                                            opacity: (searching || searchingByKeywords) ? 0.6 : 1,
                                            width: 'fit-content',
                                        }}
                                        onMouseEnter={e => {
                                            if (!searching && !searchingByKeywords)
                                                (e.currentTarget as HTMLButtonElement).style.borderColor = 'var(--c-accent)'
                                        }}
                                        onMouseLeave={e => {
                                            (e.currentTarget as HTMLButtonElement).style.borderColor = 'var(--c-border)'
                                        }}
                                    >
                                        {searchingByKeywords
                                            ? <><span className={s.spinnerAccent} style={{ borderTopColor: 'var(--c-accent)', borderColor: 'rgba(92,57,223,.2)' }} /> Ищем по ключевым словам…</>
                                            : <><HashIcon /> Найти чаты по моим ключевым словам ({userKeywords.length})</>
                                        }
                                    </button>
                                )}

                                {userKeywords.length === 0 && !loadingKeywords && (
                                    <p style={{ fontSize: 12, color: 'var(--c-ink-3)', margin: 0 }}>
                                        Добавьте ключевые слова в разделе «Ключевые слова» — AI найдёт чаты, где ваша аудитория общается на эти темы
                                    </p>
                                )}

                                {searchError && <div className={s.searchError}>{searchError}</div>}

                                {searchResults !== null && (
                                    visibleResults.length === 0 && dismissedLinks.size === 0
                                        ? <div className={s.searchEmpty}>Чаты не найдены — попробуйте другой запрос</div>
                                        : <>
                                            {visibleResults.length > 0 && (
                                                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap' }}>
                                                    <p className={s.searchResultsTitle}>
                                                        Найдено {visibleResults.length} {visibleResults.length === 1 ? 'чат' : visibleResults.length < 5 ? 'чата' : 'чатов'}
                                                        {searchResults.length !== visibleResults.length && ` (из ${searchResults.length})`}
                                                    </p>
                                                    {pendingCount > 1 && (
                                                        <div style={{ display: 'flex', gap: 8 }}>
                                                            <button
                                                                onClick={handleAddAll}
                                                                style={{
                                                                    padding: '6px 14px', borderRadius: 8,
                                                                    background: 'var(--c-accent)', color: '#fff',
                                                                    border: 'none', fontSize: 12, fontWeight: 600,
                                                                    cursor: 'pointer', fontFamily: 'var(--font-body)',
                                                                    display: 'inline-flex', alignItems: 'center', gap: 5,
                                                                }}
                                                            >
                                                                <CheckIcon />
                                                                Добавить все
                                                            </button>
                                                            <button
                                                                onClick={handleDismissAll}
                                                                style={{
                                                                    padding: '6px 14px', borderRadius: 8,
                                                                    background: 'transparent', color: 'var(--c-ink-3)',
                                                                    border: '1.5px solid var(--c-border)',
                                                                    fontSize: 12, fontWeight: 600,
                                                                    cursor: 'pointer', fontFamily: 'var(--font-body)',
                                                                    display: 'inline-flex', alignItems: 'center', gap: 5,
                                                                    transition: 'all .15s',
                                                                }}
                                                                onMouseEnter={e => {
                                                                    (e.currentTarget as HTMLButtonElement).style.borderColor = '#ef4444'
                                                                    ;(e.currentTarget as HTMLButtonElement).style.color = '#ef4444'
                                                                }}
                                                                onMouseLeave={e => {
                                                                    (e.currentTarget as HTMLButtonElement).style.borderColor = 'var(--c-border)'
                                                                    ;(e.currentTarget as HTMLButtonElement).style.color = 'var(--c-ink-3)'
                                                                }}
                                                            >
                                                                <CloseIcon />
                                                                Отклонить все
                                                            </button>
                                                        </div>
                                                    )}
                                                </div>
                                            )}

                                            {searchQueries.length > 0 && (
                                                <div className={s.searchUsed}>
                                                    <span className={s.searchUsedLabel}>AI искал по:</span>
                                                    {searchQueries.map(q => <span key={q} className={s.searchTag}>{q}</span>)}
                                                </div>
                                            )}

                                            <div className={s.searchResults}>
                                                {searchResults.map(r => (
                                                    <ResultCard
                                                        key={r.link || r.title}
                                                        result={r}
                                                        onAdd={handleAddFromSearch}
                                                        onDismiss={handleDismiss}
                                                        isAdding={addingLink === r.link}
                                                        isAdded={addedLinks.has(r.link)}
                                                        isDismissed={dismissedLinks.has(r.link)}
                                                    />
                                                ))}
                                            </div>
                                        </>
                                )}
                            </>
                        ) : (
                            <div className={s.searchLockedInner}>
                                <div className={s.searchLockedIcon}><LockIcon /></div>
                                <div className={s.searchLockedText}>
                                    <p className={s.searchLockedTitle}>Доступно на тарифе МИНИМУМ</p>
                                    <p className={s.searchLockedSub}>
                                        AI проанализирует ваш профиль и подберёт подходящие Telegram-чаты —
                                        где сидит ваша целевая аудитория
                                    </p>
                                </div>
                                <a href="/checkout" className={s.searchLockedBtn}>Подключить →</a>
                            </div>
                        )}
                    </>
                )}
            </div>
        </div>
    )
}