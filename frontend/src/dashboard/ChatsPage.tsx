import { useState, useEffect } from 'react'
import { chatsApi, type ChatSubscription } from '../api/leads.ts'
import s from './Chatspage.module.css'

function TgIcon() {
    return (
        <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor">
            <path d="M11.944 0A12 12 0 0 0 0 12a12 12 0 0 0 12 12 12 12 0 0 0 12-12A12 12 0 0 0 12 0a12 12 0 0 0-.056 0zm4.962 7.224c.1-.002.321.023.465.14a.506.506 0 0 1 .171.325c.016.093.036.306.02.472-.18 1.898-.96 6.502-1.36 8.627-.168.9-.499 1.201-.82 1.23-.696.065-1.225-.46-1.9-.902-1.056-.693-1.653-1.124-2.678-1.8-1.185-.78-.417-1.21.258-1.91.177-.184 3.247-2.977 3.307-3.23.007-.032.014-.15-.056-.212s-.174-.041-.249-.024c-.106.024-1.793 1.14-5.061 3.345-.48.33-.913.49-1.302.48-.428-.008-1.252-.241-1.865-.44-.752-.245-1.349-.374-1.297-.789.027-.216.325-.437.893-.663 3.498-1.524 5.83-2.529 6.998-3.014 3.332-1.386 4.025-1.627 4.476-1.635z"/>
        </svg>
    )
}

function StatusDot({ active }: { active: boolean }) {
    return (
        <span
            className={s.statusDot}
            style={{ background: active ? '#10b981' : 'var(--c-border)' }}
            title={active ? 'Мониторинг активен' : 'Ожидание подключения'}
        />
    )
}

function buildTelegramUrl(link: string): string {
    const raw = link.trim()
    if (raw.startsWith('https://t.me/') || raw.startsWith('http://t.me/')) return raw
    if (raw.startsWith('t.me/')) return `https://${raw}`
    if (raw.startsWith('@')) return `https://t.me/${raw.slice(1)}`
    return `https://t.me/${raw}`
}

export default function ChatsPage() {
    const [chats,    setChats]    = useState<ChatSubscription[]>([])
    const [loading,  setLoading]  = useState(true)
    const [input,    setInput]    = useState('')
    const [adding,   setAdding]   = useState(false)
    const [error,    setError]    = useState('')
    const [removing, setRemoving] = useState<number | null>(null)

    const fetchChats = () =>
        chatsApi.list()
            .then(setChats)
            .catch((e: unknown) => setError(e instanceof Error ? e.message : 'Ошибка загрузки'))

    useEffect(() => {
        fetchChats().finally(() => setLoading(false))
    }, [])

    const add = async () => {
        const link = input.trim()
        if (!link) return
        setAdding(true)
        setError('')
        try {
            await chatsApi.add(link)
            setInput('')
            // сразу обновляем список
            await fetchChats()
            // userbot вступает в чат асинхронно — повторяем через 5 и 15 секунд
            // чтобы подхватить обновлённый chatTgId и зелёный статус
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
        } finally {
            setRemoving(null)
        }
    }

    const formatDate = (iso: string) => {
        try {
            return new Date(iso).toLocaleDateString('ru-RU', { day: '2-digit', month: 'short' })
        } catch { return '' }
    }

    return (
        <div className={s.page}>
            <div className={s.pageHead}>
                <div>
                    <h1 className={s.title}>Чаты для мониторинга</h1>
                    <p className={s.sub}>
                        Userbot вступает в чат и читает сообщения в реальном времени
                    </p>
                </div>
                {chats.length > 0 && (
                    <div className={s.counter}>
                        <span className={s.counterNum}>{chats.length}</span>
                        <span className={s.counterLabel}>чатов</span>
                    </div>
                )}
            </div>

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
                    <button
                        className={s.addBtn}
                        onClick={add}
                        disabled={adding || !input.trim()}
                    >
                        {adding ? <span className={s.spinner} /> : '+ Добавить'}
                    </button>
                </div>
                <p className={s.addHint}>
                    Поддерживаются публичные каналы, группы и invite-ссылки
                </p>
            </div>

            {error && (
                <div className={s.error}>
                    <span>{error}</span>
                    <button className={s.errorClose} onClick={() => setError('')}>✕</button>
                </div>
            )}

            {loading ? (
                <div className={s.skels}>
                    {[...Array(3)].map((_, i) => <div key={i} className={s.skel} />)}
                </div>
            ) : chats.length === 0 ? (
                <div className={s.empty}>
                    <div className={s.emptyIcon}><TgIcon /></div>
                    <p className={s.emptyTitle}>Нет добавленных чатов</p>
                    <span className={s.emptySub}>Введите ссылку выше, чтобы начать мониторинг</span>
                </div>
            ) : (
                <div className={s.list}>
                    {chats.map(c => {
                        const tgUrl = buildTelegramUrl(c.chatLink)
                        const displayTitle = c.chatTitle && c.chatTitle !== c.chatLink
                            ? c.chatTitle
                            : c.chatLink

                        return (
                            <div key={c.id} className={s.card}>
                                <div className={s.cardLeft}>
                                    <div className={s.cardIcon}>
                                        <TgIcon />
                                    </div>
                                    <div className={s.cardInfo}>
                                        <div className={s.cardTitle}>
                                            <StatusDot active={c.chatTgId !== 0} />
                                            {displayTitle}
                                        </div>
                                        <div className={s.cardMeta}>
                                            <a
                                                href={tgUrl}
                                                target="_blank"
                                                rel="noopener noreferrer"
                                                className={s.cardLink}
                                                onClick={e => e.stopPropagation()}
                                            >
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