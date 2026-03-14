import { useState, useEffect } from 'react'
import { chatsApi, type ChatSubscription } from '../api/leads.ts'
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
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
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

// ─── Types ────────────────────────────────────────────────────────────────────

interface TgstatResult {
    title: string
    username: string | null
    description: string | null
    participantsCount: number
    link: string
    tgstatLink: string | null
}

interface TgstatSearchResponse {
    results: TgstatResult[]
    queries: string[]
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
    if (n >= 1_000) return `${(n / 1_000).toFixed(1)}K`
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
                        isAdding,
                        isAdded,
                    }: {
    result: TgstatResult
    onAdd: (link: string) => void
    isAdding: boolean
    isAdded: boolean
}) {
    const cardCls = `${s.resultCard}${isAdded ? ` ${s.resultAdded}` : ''}`
    const btnCls  = `${s.resultAddBtn} ${isAdding ? s.isAdding : isAdded ? s.isDone : s.isDefault}`

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
                    className={btnCls}
                    onClick={() => !isAdded && !isAdding && result.link && onAdd(result.link)}
                    disabled={isAdding || isAdded || !result.link}
                >
                    {isAdding
                        ? <><span className={s.spinnerAccent} /> Добавляем…</>
                        : isAdded
                            ? '✓ Добавлен'
                            : <><PlusIcon /> Добавить в мониторинг</>
                    }
                </button>

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

    const [searchQuery,   setSearchQuery]   = useState('')
    const [searching,     setSearching]     = useState(false)
    const [searchResults, setSearchResults] = useState<TgstatResult[] | null>(null)
    const [searchError,   setSearchError]   = useState('')
    const [searchQueries, setSearchQueries] = useState<string[]>([])
    const [addingLink,    setAddingLink]    = useState<string | null>(null)
    const [addedLinks,    setAddedLinks]    = useState<Set<string>>(new Set())

    const plan  = user?.subscriptionPlan   ?? null
    const st    = user?.subscriptionStatus ?? null
    const hasAi = plan === 'MINIMUM' || plan === 'START' || st === 'TRIAL'

    const fetchChats = () =>
        chatsApi.list()
            .then(setChats)
            .catch((e: unknown) => setError(e instanceof Error ? e.message : 'Ошибка загрузки'))

    useEffect(() => { fetchChats().finally(() => setLoading(false)) }, [])

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

    const handleSearch = async () => {
        setSearching(true); setSearchError(''); setSearchResults(null); setSearchQueries([])
        try {
            const resp = await searchChatsApi(searchQuery.trim())
            setSearchResults(resp.results)
            setSearchQueries(resp.queries)
        } catch (e: unknown) {
            setSearchError(e instanceof Error ? e.message : 'Ошибка поиска')
        } finally { setSearching(false) }
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

            {/* ─── AI-поиск чатов через TGStat ───────────────── */}
            <div className={s.searchBlock}>
                <div className={s.searchHeader}>
                    <SparkleIcon />
                    <p className={s.searchTitle}>Найти чаты по AI</p>
                    <span className={s.searchBadge}>New</span>
                </div>

                {hasAi ? (
                    <>
                        <p className={s.searchDesc}>
                            Опишите себя или нишу — AI подберёт подходящие Telegram-чаты через TGStat.
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
                                        : 'Например: я SMM-специалист, ищу заказчиков...'
                                }
                                disabled={searching}
                            />
                            <button
                                className={s.searchBtn}
                                onClick={handleSearch}
                                disabled={searching || (!searchQuery.trim() && !user?.businessContext)}
                            >
                                {searching
                                    ? <><span className={s.spinnerAccent} /> Ищем…</>
                                    : <><SearchIcon /> Найти чаты</>
                                }
                            </button>
                        </div>

                        {searchError && <div className={s.searchError}>{searchError}</div>}

                        {searchQueries.length > 0 && (
                            <div className={s.searchUsed}>
                                <span className={s.searchUsedLabel}>AI искал по:</span>
                                {searchQueries.map(q => <span key={q} className={s.searchTag}>{q}</span>)}
                            </div>
                        )}

                        {searchResults !== null && (
                            searchResults.length === 0
                                ? <div className={s.searchEmpty}>Чаты не найдены — попробуйте другой запрос</div>
                                : <>
                                    <p className={s.searchResultsTitle}>
                                        Найдено {searchResults.length} {searchResults.length === 1 ? 'чат' : 'чатов'}:
                                    </p>
                                    <div className={s.searchResults}>
                                        {searchResults.map(r => (
                                            <ResultCard
                                                key={r.link || r.title}
                                                result={r}
                                                onAdd={handleAddFromSearch}
                                                isAdding={addingLink === r.link}
                                                isAdded={addedLinks.has(r.link)}
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
                                AI проанализирует ваш профиль и подберёт подходящие Telegram-чаты
                                через TGStat — где сидит ваша целевая аудитория
                            </p>
                        </div>
                        <a href="/checkout" className={s.searchLockedBtn}>Подключить →</a>
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
                    <span className={s.emptySub}>Введите ссылку выше или найдите чаты через AI</span>
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
        </div>
    )
}