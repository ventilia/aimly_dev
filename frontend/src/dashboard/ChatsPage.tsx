import { useState, useEffect } from 'react'
import { chatsApi, keywordsApi, type ChatSubscription } from '../api/leads.ts'
import { authApi } from '../api/auth'
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
            <path d="M23 21v-2a4 4 0 0 0-3-3.87" /><path d="M16 3.13a4 4 0 0 1 0 7.75" />
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
            <rect x="3" y="11" width="18" height="11" rx="2" ry="2" /><path d="M7 11V7a5 5 0 0 1 10 0v4" />
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
            <line x1="4" y1="9" x2="20" y2="9"/><line x1="4" y1="15" x2="20" y2="15"/>
            <line x1="10" y1="3" x2="8" y2="21"/><line x1="16" y1="3" x2="14" y2="21"/>
        </svg>
    )
}

// ─── Types ────────────────────────────────────────────────────────────────────
type PeerTypeFilter = 'all' | 'chat' | 'channel'

interface TgstatResult {
    title:             string
    username:          string | null
    description:       string | null
    participantsCount: number
    link:              string
    peerType?:         string
}

interface TgstatSearchResponse {
    results: TgstatResult[]
    queries: string[]
}

const SESSION_KEY = 'aimly_chat_search_state'

interface SearchState {
    results:        TgstatResult[]
    queries:        string[]
    addedLinks:     string[]
    dismissedLinks: string[]
    searchQuery:    string
    peerType:       PeerTypeFilter
}

function saveSearchState(state: SearchState) {
    try { sessionStorage.setItem(SESSION_KEY, JSON.stringify(state)) } catch { /* ignore */ }
}

function loadSearchState(): SearchState | null {
    try {
        const raw = sessionStorage.getItem(SESSION_KEY)
        if (!raw) return null
        return JSON.parse(raw) as SearchState
    } catch { return null }
}

function clearSearchState() {
    try { sessionStorage.removeItem(SESSION_KEY) } catch { /* ignore */ }
}

import { saveChatSearchQueryForKeywords } from './chatSearchShared'

// ─── API ──────────────────────────────────────────────────────────────────────
const BASE: string = import.meta.env.VITE_API_URL || ''

async function searchChatsApi(query: string, peerType: PeerTypeFilter): Promise<TgstatSearchResponse> {
    const res = await fetch(`${BASE}/api/v1/chats/search`, {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ query, peerType: peerType === 'all' ? undefined : peerType }),
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

function StatusBadge({ active }: { active: boolean }) {
    return (
        <span
            style={{
                fontSize: 10, fontWeight: 700, padding: '2px 6px', borderRadius: 4,
                background: active ? 'rgba(16,185,129,.12)' : 'rgba(255,255,255,.06)',
                color: active ? '#10b981' : 'var(--c-ink-3)', flexShrink: 0,
                letterSpacing: '.3px', whiteSpace: 'nowrap',
            }}
            title={active ? 'Мониторинг активен' : 'Ожидание подключения юзербота'}
        >
            {active ? 'в сети' : 'не в сети'}
        </span>
    )
}

function PeerTypeBadge({ peerType }: { peerType?: string }) {
    if (!peerType) return null
    const isChat = peerType === 'chat'
    return (
        <span style={{
            fontSize: 10, fontWeight: 600, padding: '2px 6px', borderRadius: 4,
            background: isChat ? 'rgba(59,130,246,.1)' : 'rgba(139,92,246,.1)',
            color: isChat ? '#3b82f6' : '#8b5cf6',
            flexShrink: 0, letterSpacing: '.2px',
        }}>
            {isChat ? '💬 Группа' : '📢 Канал'}
        </span>
    )
}

function ResultCard({
                        result, onAdd, onDismiss, isAdding, isAdded, isDismissed,
                    }: {
    result: TgstatResult; onAdd: (link: string) => void; onDismiss: (link: string) => void
    isAdding: boolean; isAdded: boolean; isDismissed: boolean
}) {
    if (isDismissed) return null
    const cardCls = `${s.resultCard}${isAdded ? ` ${s.resultAdded}` : ''}`
    return (
        <div className={cardCls}>
            <div className={s.resultTop}>
                <div className={s.resultTitleWrap}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
                        <p className={s.resultTitle}>{result.title}</p>
                        <PeerTypeBadge peerType={result.peerType} />
                    </div>
                    {result.username && (
                        <a href={result.link} target="_blank" rel="noopener noreferrer"
                           className={s.resultUsername}>
                            {result.username}
                        </a>
                    )}
                </div>
                {result.participantsCount > 0 && (
                    <div className={s.resultMembers}><UsersIcon />{formatCount(result.participantsCount)}</div>
                )}
            </div>
            {result.description && <p className={s.resultDesc}>{result.description}</p>}
            <div className={s.resultActions}>
                <button
                    style={{
                        display: 'inline-flex', alignItems: 'center', gap: 5,
                        padding: '7px 14px', borderRadius: 8, border: 'none',
                        fontSize: 12, fontWeight: 600,
                        cursor: isAdded || isAdding ? 'default' : 'pointer',
                        fontFamily: 'var(--font-body)', transition: 'opacity .15s',
                        background: isAdded ? 'rgba(16,185,129,.12)' : 'var(--c-accent)',
                        color: isAdded ? '#10b981' : '#fff',
                        opacity: isAdded || isAdding ? 0.8 : 1,
                    }}
                    onClick={() => !isAdded && !isAdding && result.link && onAdd(result.link)}
                    disabled={isAdding || isAdded || !result.link}
                >
                    {isAdding ? <><span className={s.spinnerAccent} /> Добавляем…</>
                        : isAdded ? <><CheckIcon /> Добавлен</>
                            : <><PlusIcon /> Добавить</>}
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
            </div>
        </div>
    )
}

function PeerTypeSelector({ value, onChange, disabled }: {
    value: PeerTypeFilter; onChange: (v: PeerTypeFilter) => void; disabled: boolean
}) {
    const options: { value: PeerTypeFilter; label: string }[] = [
        { value: 'all',     label: 'Всё' },
        { value: 'chat',    label: '💬 Группы' },
        { value: 'channel', label: '📢 Каналы' },
    ]
    return (
        <div style={{ display: 'flex', gap: 4, flexShrink: 0 }}>
            {options.map(opt => (
                <button
                    key={opt.value}
                    onClick={() => !disabled && onChange(opt.value)}
                    disabled={disabled}
                    style={{
                        padding: '7px 12px', borderRadius: 9, border: '1.5px solid',
                        borderColor: value === opt.value ? 'var(--c-accent)' : 'var(--c-border)',
                        background: value === opt.value ? 'var(--c-accent-soft)' : 'transparent',
                        color: value === opt.value ? 'var(--c-accent)' : 'var(--c-ink-2)',
                        fontSize: 12, fontWeight: 600, cursor: disabled ? 'default' : 'pointer',
                        fontFamily: 'var(--font-body)', transition: 'all .15s',
                        opacity: disabled ? 0.6 : 1,
                        whiteSpace: 'nowrap',
                    }}
                >
                    {opt.label}
                </button>
            ))}
        </div>
    )
}

// ─── Main ─────────────────────────────────────────────────────────────────────
export default function ChatsPage() {
    const { user, refreshUser } = useAuthContext()

    const [chats,    setChats]    = useState<ChatSubscription[]>([])
    const [loading,  setLoading]  = useState(true)
    const [input,    setInput]    = useState('')
    const [adding,   setAdding]   = useState(false)
    const [error,    setError]    = useState('')
    const [removing, setRemoving] = useState<number | null>(null)

    const [searchOpen,     setSearchOpen]     = useState(false)
    const [searchQuery,    setSearchQuery]    = useState('')
    const [searching,      setSearching]      = useState(false)
    const [searchResults,  setSearchResults]  = useState<TgstatResult[] | null>(null)
    const [searchError,    setSearchError]    = useState('')
    const [searchQueries,  setSearchQueries]  = useState<string[]>([])
    const [addingLink,     setAddingLink]     = useState<string | null>(null)
    const [addedLinks,     setAddedLinks]     = useState<Set<string>>(new Set())
    const [dismissedLinks, setDismissedLinks] = useState<Set<string>>(new Set())

    const [peerType, setPeerType] = useState<PeerTypeFilter>('chat')

    const [userKeywords,        setUserKeywords]        = useState<string[]>([])
    const [loadingKeywords,     setLoadingKeywords]     = useState(false)
    const [searchingByKeywords, setSearchingByKeywords] = useState(false)

    // Trial bot
    const [trialBotLoading, setTrialBotLoading] = useState(false)

    const plan  = user?.subscriptionPlan   ?? null
    const st    = user?.subscriptionStatus ?? null
    const hasAi = plan === 'START' || plan === 'BUSINESS' || st === 'TRIAL'

    // Открыть бота для получения trial-периода
    const handleTrialBotOpen = async () => {
        setTrialBotLoading(true)
        try {
            const res = await authApi.getTelegramLink()
            const link = `https://t.me/${res.botUsername}?start=${res.linkToken}`
            window.open(link, '_blank', 'noopener,noreferrer')
            setTimeout(() => refreshUser(), 5000)
        } catch {
            window.open('https://t.me/aimlyAIbot', '_blank', 'noopener,noreferrer')
        } finally {
            setTrialBotLoading(false)
        }
    }

    useEffect(() => {
        const saved = loadSearchState()
        if (saved && saved.results.length > 0) {
            setSearchResults(saved.results)
            setSearchQueries(saved.queries)
            setAddedLinks(new Set(saved.addedLinks))
            setDismissedLinks(new Set(saved.dismissedLinks))
            setSearchQuery(saved.searchQuery)
            setPeerType(saved.peerType ?? 'chat')
            setSearchOpen(true)
        }
    }, [])

    useEffect(() => {
        if (searchResults === null) return
        saveSearchState({
            results:        searchResults,
            queries:        searchQueries,
            addedLinks:     Array.from(addedLinks),
            dismissedLinks: Array.from(dismissedLinks),
            searchQuery,
            peerType,
        })
    }, [searchResults, searchQueries, addedLinks, dismissedLinks, searchQuery, peerType])

    const fetchChats = () =>
        chatsApi.list()
            .then(setChats)
            .catch((e: unknown) => setError(e instanceof Error ? e.message : 'Ошибка загрузки'))

    useEffect(() => { fetchChats().finally(() => setLoading(false)) }, [])

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
        } finally { setAdding(false) }
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

    const runSearch = async (query: string, pt: PeerTypeFilter = peerType) => {
        setSearching(true)
        setSearchError('')
        setSearchResults(null)
        setSearchQueries([])
        setDismissedLinks(new Set())
        setAddedLinks(new Set())
        clearSearchState()

        if (query.trim()) {
            saveChatSearchQueryForKeywords(query.trim())
        }

        try {
            const resp = await searchChatsApi(query, pt)
            setSearchResults(resp.results)
            setSearchQueries(resp.queries)
            if (resp.results.length > 0) setSearchOpen(true)
        } catch (e: unknown) {
            setSearchError(e instanceof Error ? e.message : 'Ошибка поиска')
        } finally { setSearching(false) }
    }

    const handleSearch = async () => { await runSearch(searchQuery.trim(), peerType) }

    const handleSearchByKeywords = async () => {
        if (userKeywords.length === 0) return
        setSearchingByKeywords(true)
        await runSearch(userKeywords.slice(0, 5).join(', '), peerType)
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

    const handleDismiss    = (link: string) => setDismissedLinks(prev => new Set([...prev, link]))
    const handleDismissAll = () => { if (searchResults) setDismissedLinks(new Set(searchResults.map(r => r.link))) }
    const handleAddAll     = async () => {
        if (!searchResults) return
        for (const r of searchResults.filter(r => !addedLinks.has(r.link) && !dismissedLinks.has(r.link) && r.link))
            await handleAddFromSearch(r.link)
    }

    const handleClearResults = () => {
        setSearchResults(null); setSearchQueries([])
        setDismissedLinks(new Set()); setAddedLinks(new Set())
        setSearchError(''); setSearchOpen(false)
        clearSearchState()
    }

    const visibleResults = searchResults?.filter(r => !dismissedLinks.has(r.link)) ?? []
    const pendingCount   = visibleResults.filter(r => !addedLinks.has(r.link)).length
    const canToggle      = searchResults !== null && searchResults.length > 0

    const formatDate = (iso: string) => {
        try { return new Date(iso).toLocaleDateString('ru-RU', { day: '2-digit', month: 'short' }) }
        catch { return '' }
    }

    return (
        <div className={s.page}>
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

            {/* ─── AI-поиск чатов ────────────────────────── */}
            <div className={s.searchBlock}>
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
                                fontSize: 11, fontWeight: 700, padding: '3px 8px', borderRadius: 6,
                                background: 'var(--c-accent-soft)', color: 'var(--c-accent)', letterSpacing: '0.5px',
                            }}>AI-функция</span>
                        )}
                    </div>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                        {searchResults !== null && (
                            <button onClick={e => { e.stopPropagation(); handleClearResults() }}
                                    title="Сбросить результаты"
                                    style={{
                                        display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                                        padding: '4px 10px', borderRadius: 7, border: '1.5px solid var(--c-border)',
                                        background: 'transparent', color: 'var(--c-ink-3)',
                                        fontSize: 11, fontWeight: 600, cursor: 'pointer',
                                        fontFamily: 'var(--font-body)', transition: 'all .15s', gap: 4,
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
                                            : <><SearchIcon /> Найти</>}
                                    </button>
                                </div>
                                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                                    <span style={{ fontSize: 12, color: 'var(--c-ink-3)', flexShrink: 0 }}>Искать:</span>
                                    <PeerTypeSelector
                                        value={peerType}
                                        onChange={setPeerType}
                                        disabled={searching || searchingByKeywords}
                                    />
                                </div>

                                {userKeywords.length > 0 && (
                                    <button
                                        onClick={handleSearchByKeywords}
                                        disabled={searching || searchingByKeywords || loadingKeywords}
                                        style={{
                                            display: 'inline-flex', alignItems: 'center', gap: 7,
                                            padding: '9px 16px', borderRadius: 10,
                                            border: '1.5px solid var(--c-border)',
                                            background: 'var(--c-bg)', color: 'var(--c-ink-2)',
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
                                            : <><HashIcon /> Найти чаты по моим ключевым словам ({userKeywords.length})</>}
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
                                        ? <div className={s.searchEmpty}>Чаты не найдены — попробуйте другой запрос или выберите другой тип</div>
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
                                                            ><CheckIcon /> Добавить все</button>
                                                            <button
                                                                onClick={handleDismissAll}
                                                                style={{
                                                                    padding: '6px 14px', borderRadius: 8,
                                                                    background: 'transparent', color: 'var(--c-ink-3)',
                                                                    border: '1.5px solid var(--c-border)',
                                                                    fontSize: 12, fontWeight: 600,
                                                                    cursor: 'pointer', fontFamily: 'var(--font-body)',
                                                                    display: 'inline-flex', alignItems: 'center', gap: 5, transition: 'all .15s',
                                                                }}
                                                                onMouseEnter={e => {
                                                                    (e.currentTarget as HTMLButtonElement).style.borderColor = '#ef4444'
                                                                    ;(e.currentTarget as HTMLButtonElement).style.color = '#ef4444'
                                                                }}
                                                                onMouseLeave={e => {
                                                                    (e.currentTarget as HTMLButtonElement).style.borderColor = 'var(--c-border)'
                                                                    ;(e.currentTarget as HTMLButtonElement).style.color = 'var(--c-ink-3)'
                                                                }}
                                                            ><CloseIcon /> Отклонить все</button>
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
                                                        key={r.link || r.title} result={r}
                                                        onAdd={handleAddFromSearch} onDismiss={handleDismiss}
                                                        isAdding={addingLink === r.link} isAdded={addedLinks.has(r.link)}
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
                                    <p className={s.searchLockedTitle}>
                                        {!user?.trialUsed
                                            ? 'Доступно на тарифе START — попробуйте trial период на 5 дней'
                                            : 'Доступно на тарифе START'}
                                    </p>
                                    <p className={s.searchLockedSub}>
                                        AI проанализирует ваш профиль и подберёт подходящие Telegram-чаты —
                                        где сидит ваша целевая аудитория
                                    </p>
                                </div>
                                {!user?.trialUsed ? (
                                    <button
                                        className={s.searchLockedBtn}
                                        onClick={handleTrialBotOpen}
                                        disabled={trialBotLoading}
                                    >
                                        {trialBotLoading ? 'Открываем...' : '🚀 Запустить бота бесплатно'}
                                    </button>
                                ) : (
                                    <a href="/checkout" className={s.searchLockedBtn}>Подключить →</a>
                                )}
                            </div>
                        )}
                    </>
                )}
            </div>

            {/* ─── Ручное добавление ────────────────────── */}
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
                        />
                    </div>
                    <button className={s.addBtn} onClick={add} disabled={adding || !input.trim()}>
                        {adding ? <span className={s.spinnerSm} /> : <PlusIcon />}
                        {adding ? 'Добавляем…' : 'Добавить вручную'}
                    </button>
                </div>
                {error && <div className={s.error}>{error}</div>}
            </div>

            {loading ? (
                <div className={s.skels}>
                    {[...Array(3)].map((_, i) => <div key={i} className={s.skel} />)}
                </div>
            ) : chats.length === 0 ? (
                <div className={s.empty}>
                    <div className={s.emptyIcon}><TgIcon /></div>
                    <p className={s.emptyTitle}>Нет добавленных чатов</p>
                    <span className={s.emptySub}>Найдите чаты через AI поиск выше или введите ссылку вручную</span>
                </div>
            ) : (
                <div className={s.list}>
                    {chats.map(c => {
                        const tgUrl    = buildTelegramUrl(c.chatLink)
                        const isOnline = c.chatTgId !== 0
                        const hasRealTitle =
                            c.chatTitle && c.chatTitle.trim() !== '' &&
                            c.chatTitle !== c.chatLink &&
                            !c.chatTitle.startsWith('https://') &&
                            !c.chatTitle.startsWith('http://') &&
                            !c.chatTitle.startsWith('t.me/')
                        const displayTitle = hasRealTitle ? c.chatTitle : c.chatLink
                        return (
                            <div key={c.id} className={s.card}>
                                <div className={s.cardLeft}>
                                    <div className={s.cardIcon}><TgIcon /></div>
                                    <div className={s.cardInfo}>
                                        <div className={s.cardTitle} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
                                            <span style={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis' }}>
                                                {displayTitle}
                                            </span>
                                            <StatusBadge active={isOnline} />
                                        </div>
                                        <div className={s.cardMeta}>
                                            <a href={tgUrl} target="_blank" rel="noopener noreferrer"
                                               className={s.cardLink} onClick={e => e.stopPropagation()}>
                                                {c.chatLink}
                                            </a>
                                            {c.createdAt && (
                                                <span className={s.cardDate}>добавлен {formatDate(c.createdAt)}</span>
                                            )}
                                        </div>
                                    </div>
                                </div>
                                <button className={s.removeBtn} onClick={() => remove(c.id)}
                                        disabled={removing === c.id} title="Удалить из мониторинга">
                                    {removing === c.id ? <span className={s.spinnerSm} /> : '✕'}
                                </button>
                            </div>
                        )
                    })}
                </div>
            )}
        </div>
    )
}