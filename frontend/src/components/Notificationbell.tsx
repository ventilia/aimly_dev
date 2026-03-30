import { useState, useEffect, useRef } from 'react'
import { notificationsApi, type UserNotificationDto } from '../api/auth'

interface Props {
    lang: 'ru' | 'en'
}

export default function NotificationBell({ lang }: Props) {
    const [open,          setOpen]          = useState(false)
    const [notifications, setNotifications] = useState<UserNotificationDto[]>([])
    const [unread,        setUnread]        = useState(0)
    const [loading,       setLoading]       = useState(false)
    const ref = useRef<HTMLDivElement>(null)

    const txt = {
        ru: { title: 'Уведомления', empty: 'Нет уведомлений', markAll: 'Прочитать все' },
        en: { title: 'Notifications', empty: 'No notifications', markAll: 'Mark all read' },
    }[lang]

    const fetchUnread = async () => {
        try {
            const res = await notificationsApi.getUnreadCount()
            setUnread(res.unread)
        } catch {
            // ff

        }
    }

    const fetchAll = async () => {
        setLoading(true)
        try {
            const data = await notificationsApi.getAll()
            setNotifications(data)
            setUnread(data.filter(n => !n.read).length)
        } catch {
            // ff

        } finally {
            setLoading(false)
        }
    }

    useEffect(() => {
        fetchUnread()
        const interval = setInterval(fetchUnread, 30_000) // каждые 30 сек
        return () => clearInterval(interval)
    }, [])

    useEffect(() => {
        if (open) fetchAll()
    }, [open])

    useEffect(() => {
        const handler = (e: MouseEvent) => {
            if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false)
        }
        document.addEventListener('mousedown', handler)
        return () => document.removeEventListener('mousedown', handler)
    }, [])

    const handleMarkAll = async () => {
        await notificationsApi.markAllRead()
        setNotifications(prev => prev.map(n => ({ ...n, read: true })))
        setUnread(0)
    }

    const handleMarkOne = async (n: UserNotificationDto) => {
        if (n.read) return
        await notificationsApi.markOneRead(n.id)
        setNotifications(prev => prev.map(x => x.id === n.id ? { ...x, read: true } : x))
        setUnread(prev => Math.max(0, prev - 1))
    }

    const formatDate = (iso: string) => {
        const d = new Date(iso)
        return d.toLocaleDateString(lang === 'ru' ? 'ru-RU' : 'en-US', {
            day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit'
        })
    }

    return (
        <div ref={ref} style={{ position: 'relative' }}>
            <button
                onClick={() => setOpen(v => !v)}
                aria-label={txt.title}
                style={{
                    position:   'relative',
                    width:       36,
                    height:      36,
                    borderRadius: 10,
                    border:      '1.5px solid var(--c-border)',
                    background:  'var(--c-surface)',
                    cursor:      'pointer',
                    display:     'flex',
                    alignItems:  'center',
                    justifyContent: 'center',
                    fontSize:    18,
                    transition:  'border-color .15s',
                }}
            >
                🔔
                {unread > 0 && (
                    <span style={{
                        position:   'absolute',
                        top:         -5,
                        right:       -5,
                        background:  '#ef4444',
                        color:       '#fff',
                        fontSize:    10,
                        fontWeight:  700,
                        borderRadius: 100,
                        minWidth:    18,
                        height:      18,
                        display:     'flex',
                        alignItems:  'center',
                        justifyContent: 'center',
                        padding:     '0 4px',
                        fontFamily:  'var(--font-head)',
                    }}>
                        {unread > 99 ? '99+' : unread}
                    </span>
                )}
            </button>

            {open && (
                <div style={{
                    position:   'absolute',
                    top:        'calc(100% + 8px)',
                    right:      0,
                    background: 'var(--c-surface)',
                    border:     '1px solid var(--c-border)',
                    borderRadius: 14,
                    boxShadow:  '0 12px 40px rgba(0,0,0,.14)',
                    width:       340,
                    maxHeight:   480,
                    zIndex:      200,
                    display:     'flex',
                    flexDirection: 'column',
                    overflow:    'hidden',
                    animation:   'popIn .15s ease',
                }}>
                    {/* шапка */}
                    <div style={{
                        display:        'flex',
                        alignItems:     'center',
                        justifyContent: 'space-between',
                        padding:        '14px 16px 10px',
                        borderBottom:   '1px solid var(--c-border)',
                        flexShrink:     0,
                    }}>
                        <span style={{ fontSize: 14, fontWeight: 700, color: 'var(--c-ink)' }}>
                            {txt.title} {unread > 0 && <span style={{ color: 'var(--c-accent)' }}>({unread})</span>}
                        </span>
                        {unread > 0 && (
                            <button
                                onClick={handleMarkAll}
                                style={{
                                    fontSize:   12,
                                    color:      'var(--c-accent)',
                                    background: 'none',
                                    border:     'none',
                                    cursor:     'pointer',
                                    fontFamily: 'var(--font-body)',
                                    fontWeight: 500,
                                }}
                            >
                                {txt.markAll}
                            </button>
                        )}
                    </div>

                    {/* список */}
                    <div style={{ overflowY: 'auto', flex: 1 }}>
                        {loading && (
                            <div style={{ padding: '20px', textAlign: 'center', color: 'var(--c-ink-3)', fontSize: 13 }}>
                                ...
                            </div>
                        )}

                        {!loading && notifications.length === 0 && (
                            <div style={{ padding: '24px 16px', textAlign: 'center', color: 'var(--c-ink-3)', fontSize: 13 }}>
                                {txt.empty}
                            </div>
                        )}

                        {!loading && notifications.map(n => (
                            <div
                                key={n.id}
                                onClick={() => handleMarkOne(n)}
                                style={{
                                    padding:    '12px 16px',
                                    borderBottom: '1px solid var(--c-border)',
                                    background: n.read ? 'transparent' : 'rgba(92,57,223,.04)',
                                    cursor:     n.read ? 'default' : 'pointer',
                                    transition: 'background .12s',
                                }}
                            >
                                <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8 }}>
                                    {!n.read && (
                                        <div style={{
                                            width:        8,
                                            height:       8,
                                            borderRadius: '50%',
                                            background:   'var(--c-accent)',
                                            marginTop:    5,
                                            flexShrink:   0,
                                        }} />
                                    )}
                                    <div style={{ flex: 1, minWidth: 0 }}>
                                        <div style={{ fontSize: 13.5, fontWeight: 600, color: 'var(--c-ink)', marginBottom: 3 }}>
                                            {n.title}
                                        </div>
                                        <div style={{ fontSize: 12.5, color: 'var(--c-ink-2)', lineHeight: 1.45, marginBottom: 4 }}>
                                            {n.body}
                                        </div>
                                        <div style={{ fontSize: 11, color: 'var(--c-ink-3)' }}>
                                            {formatDate(n.createdAt)}
                                        </div>
                                    </div>
                                </div>
                            </div>
                        ))}
                    </div>
                </div>
            )}
        </div>
    )
}