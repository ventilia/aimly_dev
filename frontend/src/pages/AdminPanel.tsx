import { useState, useEffect, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import type { Lang } from '../i18n/translations'
import SubscriptionsTab from '../dashboard/SubscriptionsTab'
import {
    adminApi, notificationsApi,
    type AdminUserDto, type NotificationDto,
} from '../api/auth'
import { useAuthContext } from '../context/AuthContext'
import s from './AdminPanel.module.css'

interface Props { lang: Lang; setLang: (l: Lang) => void }

type Tab = 'overview' | 'users' | 'subscriptions' | 'userbot' | 'notifications'

const TABS: { id: Tab; label: { ru: string; en: string } }[] = [
    { id: 'overview',      label: { ru: 'Обзор',        en: 'Overview'      } },
    { id: 'users',         label: { ru: 'Пользователи', en: 'Users'         } },
    { id: 'subscriptions', label: { ru: 'Подписки',     en: 'Subscriptions' } },
    { id: 'userbot',       label: { ru: 'Userbot',      en: 'Userbot'       } },
    { id: 'notifications', label: { ru: 'Уведомления',  en: 'Notifications' } },
]

type SortKey = 'id' | 'firstName' | 'email' | 'role' | 'leadsCount' | 'createdAt'
type SortDir = 'asc' | 'desc'

function sortUsers(users: AdminUserDto[], key: SortKey, dir: SortDir): AdminUserDto[] {
    return [...users].sort((a, b) => {
        let av: string | number = (a[key as keyof AdminUserDto] ?? '') as string | number
        let bv: string | number = (b[key as keyof AdminUserDto] ?? '') as string | number
        if (key === 'createdAt') {
            av = av ? new Date(av as string).getTime() : 0
            bv = bv ? new Date(bv as string).getTime() : 0
        }
        if (typeof av === 'string') av = av.toLowerCase()
        if (typeof bv === 'string') bv = bv.toLowerCase()
        if (av < bv) return dir === 'asc' ? -1 : 1
        if (av > bv) return dir === 'asc' ? 1 : -1
        return 0
    })
}

interface UserbotSessionStats {
    sessionId:  number
    phone:      string
    chatCount:  number
    leadsCount: number
    online:     boolean
}

interface UserbotStats {
    status:      string
    sessions:    number
    totalChats:  number
    totalUsers:  number
    totalLeads:  number
    perSession?: UserbotSessionStats[]
}

interface UserbotUserInfo {
    userId:     number
    email:      string
    leadsCount: number
    chats:      string[]
    keywords:   string[]
}



function UsersTable({ users, lang, currentUserId, onRoleChange }: {
    users:         AdminUserDto[]
    lang:          Lang
    currentUserId: number
    onRoleChange:  (id: number, role: string) => void
}) {
    const [changing, setChanging] = useState<number | null>(null)
    const [sortKey,  setSortKey]  = useState<SortKey>('id')
    const [sortDir,  setSortDir]  = useState<SortDir>('asc')
    const ru = lang === 'ru'

    const handleSort = (key: SortKey) => {
        if (sortKey === key) setSortDir(d => d === 'asc' ? 'desc' : 'asc')
        else { setSortKey(key); setSortDir('asc') }
    }

    const arrow = (key: SortKey) =>
        sortKey === key
            ? <span className={s.sortArrow}>{sortDir === 'asc' ? ' ↑' : ' ↓'}</span>
            : <span className={s.sortArrowInactive}> ↕</span>

    const Th = ({ col, label }: { col: SortKey; label: string }) => (
        <th className={s.thSortable} onClick={() => handleSort(col)}>{label}{arrow(col)}</th>
    )

    const handleToggleRole = async (u: AdminUserDto) => {
        if (u.id === currentUserId && u.role === 'ADMIN') {
            alert(ru ? 'Нельзя снять роль у себя' : 'Cannot remove your own ADMIN role')
            return
        }
        const newRole = u.role === 'ADMIN' ? 'USER' : 'ADMIN'
        setChanging(u.id)
        try {
            await adminApi.setRole(u.id, newRole)
            onRoleChange(u.id, newRole)
        } catch (e: unknown) {
            alert(e instanceof Error ? e.message : 'Ошибка')
        } finally {
            setChanging(null)
        }
    }

    const sorted = sortUsers(users, sortKey, sortDir)

    return (
        <div className={s.tableWrapper}>
            <table className={s.usersTable}>
                <thead>
                <tr>
                    <Th col="id"         label="ID" />
                    <Th col="firstName"  label={ru ? 'Имя'      : 'Name'} />
                    <Th col="email"      label="Email" />
                    <Th col="role"       label={ru ? 'Роль'     : 'Role'} />
                    <Th col="leadsCount" label={ru ? 'Лиды'     : 'Leads'} />
                    <Th col="createdAt"  label={ru ? 'Создан'   : 'Created'} />
                    <th>{ru ? 'Подписка' : 'Subscription'}</th>
                    <th>{ru ? 'Telegram' : 'Telegram'}</th>
                    <th>{ru ? 'Действие' : 'Action'}</th>
                </tr>
                </thead>
                <tbody>
                {sorted.map(u => (
                    <tr key={u.id} className={!u.isActive ? s.rowInactive : ''}>
                        <td className={s.cellId}>{u.id}</td>
                        <td>{u.firstName ?? '—'}</td>
                        <td className={s.cellEmail}>{u.email}</td>
                        <td>
                                <span className={u.role === 'ADMIN' ? s.badgeAdmin : s.badgeUser}>
                                    {u.role}
                                </span>
                        </td>
                        <td className={s.cellNum}>{u.leadsCount}</td>
                        <td className={s.cellDate}>
                            {u.createdAt ? new Date(u.createdAt).toLocaleDateString(ru ? 'ru-RU' : 'en-US') : '—'}
                        </td>
                        <td>
                            {u.subscriptionStatus
                                ? <span className={u.subscriptionStatus === 'ACTIVE' ? s.badgeActive : s.badgeTrial}>
                                        {u.subscriptionStatus} {u.subscriptionPlan ? `/ ${u.subscriptionPlan}` : ''}
                                      </span>
                                : <span className={s.badgeNone}>—</span>
                            }
                        </td>
                        <td>
                            {u.telegramId
                                ? <span className={s.badgeTg}>✓ {u.telegramUsername ? `@${u.telegramUsername}` : u.telegramId}</span>
                                : <span className={s.badgeNone}>—</span>
                            }
                        </td>
                        <td>
                            <button
                                className={s.actionBtn}
                                onClick={() => handleToggleRole(u)}
                                disabled={changing === u.id}
                            >
                                {changing === u.id ? '...' : u.role === 'ADMIN'
                                    ? (ru ? 'Снять ADMIN' : 'Remove ADMIN')
                                    : (ru ? 'Сделать ADMIN' : 'Make ADMIN')}
                            </button>
                        </td>
                    </tr>
                ))}
                </tbody>
            </table>
        </div>
    )
}



function UserbotTab({ lang }: { lang: Lang }) {
    const ru   = lang === 'ru'
    const BASE = import.meta.env.VITE_API_URL || ''

    const [stats,        setStats]        = useState<UserbotStats | null>(null)
    const [users,        setUsers]        = useState<UserbotUserInfo[]>([])
    const [loading,      setLoading]      = useState(true)
    const [error,        setError]        = useState('')
    const [expandedUser, setExpandedUser] = useState<number | null>(null)

    // ─── Фильтрация пользователей ─────────────────────────────────────────────
    const [userSearch, setUserSearch] = useState('')

    // ─── Удаление юзербота ────────────────────────────────────────────────────
    const [deletingSession, setDeletingSession] = useState<number | null>(null)
    const [deleteError,     setDeleteError]     = useState('')

    type Step = 'idle' | 'phone' | 'code' | 'done'
    const [step,       setStep]       = useState<Step>('idle')
    const [phone,      setPhone]      = useState('')
    const [apiID,      setApiID]      = useState('')
    const [apiHash,    setApiHash]    = useState('')
    const [tempId,     setTempId]     = useState('')
    const [code,       setCode]       = useState('')
    const [password,   setPassword]   = useState('')
    const [regLoading, setRegLoading] = useState(false)
    const [regError,   setRegError]   = useState('')
    const [regSuccess, setRegSuccess] = useState('')

    const load = useCallback(async () => {
        setLoading(true)
        setError('')
        try {
            const [st, us] = await Promise.all([
                fetch(`${BASE}/api/v1/admin/userbot/stats`, { credentials: 'include' })
                    .then(r => r.json()) as Promise<UserbotStats>,
                fetch(`${BASE}/api/v1/admin/userbot/users`, { credentials: 'include' })
                    .then(r => r.json()) as Promise<UserbotUserInfo[]>,
            ])
            setStats(st)
            setUsers(Array.isArray(us) ? us : [])
        } catch (e: unknown) {
            setError(e instanceof Error ? e.message : 'Ошибка')
        } finally {
            setLoading(false)
        }
    }, [BASE])

    useEffect(() => { load() }, [load])

    // ─── Удаление юзербота ────────────────────────────────────────────────────
    const handleDeleteSession = async (sessionId: number, phone: string) => {
        const confirmed = confirm(ru
            ? `Удалить юзербота #${sessionId} (${phone})?\n\nОн будет отключён от всех чатов и остановлен.`
            : `Delete userbot #${sessionId} (${phone})?\n\nIt will be disconnected from all chats and stopped.`
        )
        if (!confirmed) return

        setDeletingSession(sessionId)
        setDeleteError('')
        try {
            const res = await fetch(`${BASE}/api/v1/admin/userbot/sessions/delete`, {
                method:      'POST',
                credentials: 'include',
                headers:     { 'Content-Type': 'application/json' },
                body:        JSON.stringify({ sessionId }),
            })
            const data = await res.json()
            if (!res.ok) throw new Error(data.error ?? `Ошибка ${res.status}`)
            setTimeout(load, 600)
        } catch (e: unknown) {
            setDeleteError(e instanceof Error ? e.message : 'Ошибка удаления')
        } finally {
            setDeletingSession(null)
        }
    }

    // ─── Регистрация: шаг 1 ───────────────────────────────────────────────────
    const handleSendPhone = async () => {
        if (!phone.trim()) return
        setRegLoading(true)
        setRegError('')
        try {
            const res = await fetch(`${BASE}/api/v1/admin/userbot/sessions/register`, {
                method:      'POST',
                credentials: 'include',
                headers:     { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    phone:   phone.trim(),
                    apiID:   parseInt(apiID, 10),
                    apiHash: apiHash.trim(),
                }),
            })
            const data = await res.json()
            if (!res.ok) throw new Error(data.error ?? `Ошибка ${res.status}`)
            setTempId(data.tempId)
            setStep('code')
        } catch (e: unknown) {
            setRegError(e instanceof Error ? e.message : 'Ошибка')
        } finally {
            setRegLoading(false)
        }
    }

    // ─── Регистрация: шаг 2 ───────────────────────────────────────────────────
    const handleConfirmCode = async () => {
        if (!code.trim()) return
        setRegLoading(true)
        setRegError('')
        try {
            const res = await fetch(`${BASE}/api/v1/admin/userbot/sessions/confirm`, {
                method:      'POST',
                credentials: 'include',
                headers:     { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    tempId,
                    code:     code.trim(),
                    password: password.trim() || undefined,
                }),
            })
            const data = await res.json()
            if (!res.ok) throw new Error(data.error ?? `Ошибка ${res.status}`)
            setRegSuccess(ru
                ? `Сессия активирована! Телефон: ${data.phone}, ID: ${data.sessionId}`
                : `Session activated! Phone: ${data.phone}, ID: ${data.sessionId}`)
            setStep('done')
            setTimeout(load, 1500)
        } catch (e: unknown) {
            setRegError(e instanceof Error ? e.message : 'Ошибка')
        } finally {
            setRegLoading(false)
        }
    }

    const resetWizard = () => {
        setStep('idle'); setPhone(''); setCode(''); setPassword('')
        setTempId(''); setRegError(''); setRegSuccess('')
    }

    // ─── Фильтрация пользователей ─────────────────────────────────────────────
    const filteredUsers = userSearch.trim()
        ? users.filter(u =>
            u.email.toLowerCase().includes(userSearch.toLowerCase()) ||
            String(u.userId).includes(userSearch)
        )
        : users

    if (loading) return <div className={s.loading}>{ru ? 'Загрузка...' : 'Loading...'}</div>
    if (error)   return <div className={s.formError}>{error}</div>

    const isUp = stats?.status === 'UP'

    const statCards = [
        { label: ru ? 'Статус'        : 'Status',
            value: isUp ? 'UP' : 'DOWN',
            color: isUp ? '#10b981' : '#ef4444', isText: true },
        { label: ru ? 'Аккаунтов'     : 'Accounts',
            value: stats?.sessions    ?? 0,  color: undefined, isText: false },
        { label: ru ? 'Чатов'         : 'Chats',
            value: stats?.totalChats  ?? 0,  color: undefined, isText: false },
        { label: ru ? 'Пользователей' : 'Users',
            value: stats?.totalUsers  ?? 0,  color: undefined, isText: false },
        { label: ru ? 'Всего лидов'   : 'Total leads',
            value: stats?.totalLeads  ?? 0,  color: undefined, isText: false },
    ]

    return (
        <div className={s.subsTab}>
            {/* ─── Заголовок ─── */}
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <h2 className={s.tabTitle} style={{ margin: 0 }}>
                    {ru ? 'Userbot сервис' : 'Userbot Service'}
                </h2>
                <button className={s.grantBtn} onClick={load} style={{ padding: '7px 16px' }}>
                    {ru ? 'Обновить' : 'Refresh'}
                </button>
            </div>

            {/* ─── Статистика ─── */}
            <div className={s.statsGrid}>
                {statCards.map(item => (
                    <div key={item.label} className={s.statCard}>
                        <span
                            className={s.statVal}
                            style={item.color ? { color: item.color, fontSize: item.isText ? 16 : undefined } : {}}
                        >
                            {item.value}
                        </span>
                        <span className={s.statLabel}>{item.label}</span>
                    </div>
                ))}
            </div>

            {/* ─── Добавить аккаунт ─── */}
            <div className={s.subCard} style={{ marginTop: 20 }}>
                <h3 className={s.subCardTitle} style={{ marginBottom: 12 }}>
                    {ru ? '➕ Добавить аккаунт юзербота' : '➕ Add userbot account'}
                </h3>

                {step === 'idle' && (
                    <button className={s.grantBtn} onClick={() => setStep('phone')} style={{ padding: '8px 20px' }}>
                        {ru ? 'Добавить аккаунт' : 'Add account'}
                    </button>
                )}

                {step === 'phone' && (
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 10, maxWidth: 420 }}>
                        <div style={{
                            background: 'rgba(99,102,241,.08)', border: '1px solid rgba(99,102,241,.2)',
                            borderRadius: 8, padding: '10px 14px', fontSize: 12,
                            color: 'var(--c-ink-2)', lineHeight: 1.7,
                        }}>
                            <strong>{ru ? 'Где взять API credentials?' : 'Where to get API credentials?'}</strong><br />
                            {ru ? (
                                <>
                                    Зайдите на{' '}
                                    <a href="https://my.telegram.org/auth" target="_blank" rel="noopener noreferrer"
                                       style={{ color: 'var(--c-accent)' }}>my.telegram.org/auth</a>
                                    {' '}→ «API development tools».<br />
                                    Скопируйте <code>App api_id</code> и <code>App api_hash</code> и вставьте ниже.
                                </>
                            ) : (
                                <>
                                    Go to{' '}
                                    <a href="https://my.telegram.org/auth" target="_blank" rel="noopener noreferrer"
                                       style={{ color: 'var(--c-accent)' }}>my.telegram.org/auth</a>
                                    {' '}→ «API development tools».<br />
                                    Copy <code>App api_id</code> and <code>App api_hash</code> and paste below.
                                </>
                            )}
                        </div>

                        <input
                            className={s.formInput}
                            placeholder={ru ? 'Номер телефона (+79001234567)' : 'Phone number (+79001234567)'}
                            value={phone}
                            onChange={e => setPhone(e.target.value)}
                        />
                        <input
                            className={s.formInput}
                            placeholder="App api_id"
                            value={apiID}
                            onChange={e => setApiID(e.target.value)}
                        />
                        <input
                            className={s.formInput}
                            placeholder="App api_hash"
                            value={apiHash}
                            onChange={e => setApiHash(e.target.value)}
                        />

                        {regError && <div className={s.formError}>{regError}</div>}

                        <div style={{ display: 'flex', gap: 8 }}>
                            <button
                                className={s.grantBtn}
                                onClick={handleSendPhone}
                                disabled={regLoading || !phone.trim()}
                            >
                                {regLoading
                                    ? (ru ? 'Отправляем...' : 'Sending...')
                                    : (ru ? 'Отправить код' : 'Send code')}
                            </button>
                            <button
                                className={s.actionBtn}
                                onClick={resetWizard}
                                style={{ border: '1px solid var(--c-border)', background: 'none' }}
                            >
                                {ru ? 'Отмена' : 'Cancel'}
                            </button>
                        </div>
                    </div>
                )}

                {step === 'code' && (
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 10, maxWidth: 420 }}>
                        <div style={{
                            background: 'rgba(16,185,129,.08)', border: '1px solid rgba(16,185,129,.2)',
                            borderRadius: 8, padding: '10px 14px', fontSize: 12, color: 'var(--c-ink-2)',
                        }}>
                            {ru
                                ? `📱 Код отправлен в Telegram на номер ${phone}. Введите его ниже.`
                                : `📱 Code sent to Telegram on ${phone}. Enter it below.`}
                        </div>

                        <input
                            className={s.formInput}
                            placeholder={ru ? 'Код из Telegram (12345)' : 'Telegram code (12345)'}
                            value={code}
                            onChange={e => setCode(e.target.value)}
                        />
                        <input
                            className={s.formInput}
                            type="password"
                            placeholder={ru ? '2FA пароль (если есть)' : '2FA password (if set)'}
                            value={password}
                            onChange={e => setPassword(e.target.value)}
                        />

                        {regError && <div className={s.formError}>{regError}</div>}

                        <div style={{ display: 'flex', gap: 8 }}>
                            <button
                                className={s.grantBtn}
                                onClick={handleConfirmCode}
                                disabled={regLoading || !code.trim()}
                            >
                                {regLoading
                                    ? (ru ? 'Подтверждаем...' : 'Confirming...')
                                    : (ru ? 'Подтвердить' : 'Confirm')}
                            </button>
                            <button
                                className={s.actionBtn}
                                onClick={resetWizard}
                                style={{ border: '1px solid var(--c-border)', background: 'none' }}
                            >
                                {ru ? 'Отмена' : 'Cancel'}
                            </button>
                        </div>
                    </div>
                )}

                {step === 'done' && (
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                        <div style={{
                            background: 'rgba(16,185,129,.08)', border: '1px solid rgba(16,185,129,.2)',
                            borderRadius: 8, padding: '10px 14px', fontSize: 13, color: '#10b981',
                        }}>
                            {regSuccess}
                        </div>
                        <button
                            className={s.actionBtn}
                            onClick={resetWizard}
                            style={{ border: '1px solid var(--c-border)', background: 'none', width: 'fit-content' }}
                        >
                            {ru ? 'Добавить ещё' : 'Add another'}
                        </button>
                    </div>
                )}
            </div>

            {/* ─── Аккаунты в пуле + кнопка удаления ─── */}
            {(stats?.perSession?.length ?? 0) > 0 && (
                <>
                    <h3 className={s.subCardTitle} style={{ marginTop: 24 }}>
                        {ru ? 'Аккаунты в пуле' : 'Pool accounts'}
                    </h3>
                    {deleteError && (
                        <div className={s.formError} style={{ marginBottom: 8 }}>{deleteError}</div>
                    )}
                    <div className={s.tableWrapper}>
                        <table className={s.usersTable}>
                            <thead><tr>
                                <th>ID</th>
                                <th>{ru ? 'Телефон' : 'Phone'}</th>
                                <th>{ru ? 'Чатов' : 'Chats'}</th>
                                <th>{ru ? 'Лидов' : 'Leads'}</th>
                                <th>{ru ? 'Статус' : 'Status'}</th>
                                <th>{ru ? 'Действие' : 'Action'}</th>
                            </tr></thead>
                            <tbody>
                            {stats!.perSession!.map(sess => (
                                <tr key={sess.sessionId}>
                                    <td className={s.cellId}>#{sess.sessionId}</td>
                                    <td style={{ fontFamily: 'monospace', fontSize: 13 }}>{sess.phone}</td>
                                    <td className={s.cellNum}>{sess.chatCount}</td>
                                    <td className={s.cellNum}>{sess.leadsCount ?? '—'}</td>
                                    <td>
                                        <span style={{
                                            color:      sess.online ? '#10b981' : 'var(--c-ink-3)',
                                            fontSize:   12,
                                            fontWeight: 600,
                                        }}>
                                            {sess.online
                                                ? (ru ? 'в сети' : 'Online')
                                                : (ru ? 'не в сети' : 'Offline')}
                                        </span>
                                    </td>
                                    <td>
                                        <button
                                            onClick={() => handleDeleteSession(sess.sessionId, sess.phone)}
                                            disabled={deletingSession === sess.sessionId}
                                            style={{
                                                padding:      '4px 10px',
                                                fontSize:     11,
                                                fontWeight:   600,
                                                borderRadius: 6,
                                                border:       '1px solid rgba(239,68,68,.3)',
                                                background:   'rgba(239,68,68,.06)',
                                                color:        '#ef4444',
                                                cursor:       deletingSession === sess.sessionId ? 'default' : 'pointer',
                                                fontFamily:   'var(--font-body)',
                                                transition:   'all .15s',
                                                opacity:      deletingSession === sess.sessionId ? .5 : 1,
                                            }}
                                            onMouseEnter={e => {
                                                if (deletingSession !== sess.sessionId)
                                                    (e.currentTarget as HTMLButtonElement).style.background = 'rgba(239,68,68,.15)'
                                            }}
                                            onMouseLeave={e => {
                                                (e.currentTarget as HTMLButtonElement).style.background = 'rgba(239,68,68,.06)'
                                            }}
                                        >
                                            {deletingSession === sess.sessionId
                                                ? '...'
                                                : (ru ? 'Удалить' : 'Delete')}
                                        </button>
                                    </td>
                                </tr>
                            ))}
                            </tbody>
                        </table>
                    </div>
                </>
            )}

            {/* ─── Активные пользователи с фильтрацией ─── */}
            {users.length > 0 && (
                <>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginTop: 24, flexWrap: 'wrap' }}>
                        <h3 className={s.subCardTitle} style={{ margin: 0 }}>
                            {ru ? 'Активные пользователи' : 'Active users'}
                            <span className={s.count} style={{ marginLeft: 8 }}>{filteredUsers.length}</span>
                            {userSearch.trim() && filteredUsers.length !== users.length && (
                                <span style={{ fontSize: 11, color: 'var(--c-ink-3)', fontWeight: 400, marginLeft: 4 }}>
                                    {ru ? `из ${users.length}` : `of ${users.length}`}
                                </span>
                            )}
                        </h3>

                        {/* Поиск по email / ID */}
                        <input
                            placeholder={ru ? 'Поиск по email или ID...' : 'Search by email or ID...'}
                            value={userSearch}
                            onChange={e => setUserSearch(e.target.value)}
                            style={{
                                flex: 1, maxWidth: 260,
                                padding: '6px 10px', fontSize: 12,
                                borderRadius: 7, border: '1px solid var(--c-border)',
                                background: 'var(--c-surface)', color: 'var(--c-ink)',
                                fontFamily: 'var(--font-body)', outline: 'none',
                            }}
                        />
                        {userSearch.trim() && (
                            <button
                                onClick={() => setUserSearch('')}
                                style={{
                                    fontSize: 11, padding: '4px 8px', borderRadius: 6,
                                    border: '1px solid var(--c-border)', background: 'none',
                                    color: 'var(--c-ink-3)', cursor: 'pointer', fontFamily: 'var(--font-body)',
                                }}
                            >
                                {ru ? 'Сбросить' : 'Clear'}
                            </button>
                        )}
                    </div>

                    <div className={s.tableWrapper}>
                        <table className={s.usersTable}>
                            <thead><tr>
                                <th>ID</th>
                                <th>Email</th>
                                <th>{ru ? 'Чатов' : 'Chats'}</th>
                                <th>{ru ? 'Ключ. слов' : 'Keywords'}</th>
                                <th>{ru ? 'Лиды' : 'Leads'}</th>
                                <th>{ru ? 'Детали' : 'Details'}</th>
                            </tr></thead>
                            <tbody>
                            {filteredUsers.map(u => (
                                <>
                                    <tr key={u.userId}>
                                        <td className={s.cellId}>{u.userId}</td>
                                        <td className={s.cellEmail}>{u.email}</td>
                                        <td className={s.cellNum}>{u.chats.length}</td>
                                        <td className={s.cellNum}>{u.keywords.length}</td>
                                        <td className={s.cellNum}>{u.leadsCount}</td>
                                        <td>
                                            <button
                                                className={s.actionBtn}
                                                style={{
                                                    background: expandedUser === u.userId
                                                        ? 'var(--c-accent-soft)'
                                                        : 'var(--c-bg)',
                                                    color: expandedUser === u.userId
                                                        ? 'var(--c-accent)'
                                                        : 'var(--c-ink-2)',
                                                    border: '1px solid var(--c-border)',
                                                }}
                                                onClick={() => setExpandedUser(
                                                    expandedUser === u.userId ? null : u.userId
                                                )}
                                            >
                                                {expandedUser === u.userId
                                                    ? (ru ? 'Скрыть' : 'Hide')
                                                    : (ru ? 'Раскрыть' : 'Expand')}
                                            </button>
                                        </td>
                                    </tr>
                                    {expandedUser === u.userId && (
                                        <tr key={`${u.userId}-detail`}>
                                            <td colSpan={6} style={{ padding: '0 0 12px 0' }}>
                                                <div style={{
                                                    display:             'grid',
                                                    gridTemplateColumns: '1fr 1fr',
                                                    gap:                 12,
                                                    padding:             '12px 16px',
                                                    background:          'var(--c-bg)',
                                                    borderRadius:        10,
                                                    margin:              '0 14px',
                                                }}>
                                                    <div>
                                                        <div style={{
                                                            fontSize: 11, fontWeight: 700,
                                                            textTransform: 'uppercase', letterSpacing: '.6px',
                                                            color: 'var(--c-ink-3)', marginBottom: 8,
                                                        }}>
                                                            {ru ? 'Чаты' : 'Chats'} ({u.chats.length})
                                                        </div>
                                                        {u.chats.length === 0 ? (
                                                            <span style={{ fontSize: 12, color: 'var(--c-ink-3)' }}>—</span>
                                                        ) : (
                                                            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                                                                {u.chats.map(c => (
                                                                    <a
                                                                        key={c}
                                                                        href={c.startsWith('http') ? c : `https://t.me/${c.replace('@', '')}`}
                                                                        target="_blank"
                                                                        rel="noopener noreferrer"
                                                                        style={{
                                                                            fontSize: 12, color: 'var(--c-accent)',
                                                                            textDecoration: 'none', fontFamily: 'monospace',
                                                                        }}
                                                                    >
                                                                        {c}
                                                                    </a>
                                                                ))}
                                                            </div>
                                                        )}
                                                    </div>

                                                    <div>
                                                        <div style={{
                                                            fontSize: 11, fontWeight: 700,
                                                            textTransform: 'uppercase', letterSpacing: '.6px',
                                                            color: 'var(--c-ink-3)', marginBottom: 8,
                                                        }}>
                                                            {ru ? 'Ключевые слова' : 'Keywords'} ({u.keywords.length})
                                                        </div>
                                                        {u.keywords.length === 0 ? (
                                                            <span style={{ fontSize: 12, color: 'var(--c-ink-3)' }}>—</span>
                                                        ) : (
                                                            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 5 }}>
                                                                {u.keywords.map(kw => (
                                                                    <span key={kw} style={{
                                                                        fontSize: 11,
                                                                        background: 'var(--c-surface)',
                                                                        border: '1px solid var(--c-border)',
                                                                        borderRadius: 6, padding: '3px 8px',
                                                                        color: 'var(--c-ink-2)',
                                                                    }}>
                                                                        {kw}
                                                                    </span>
                                                                ))}
                                                            </div>
                                                        )}
                                                    </div>
                                                </div>
                                            </td>
                                        </tr>
                                    )}
                                </>
                            ))}
                            {filteredUsers.length === 0 && userSearch.trim() && (
                                <tr>
                                    <td colSpan={6} style={{
                                        textAlign: 'center', padding: '20px 0',
                                        color: 'var(--c-ink-3)', fontSize: 13,
                                    }}>
                                        {ru ? 'Ничего не найдено' : 'No results'}
                                    </td>
                                </tr>
                            )}
                            </tbody>
                        </table>
                    </div>
                </>
            )}

            {!isUp && (
                <div className={s.subCard} style={{ borderColor: 'rgba(239,68,68,.2)', marginTop: 20 }}>
                    <p style={{ margin: 0, fontSize: 13, color: 'var(--c-ink-3)', lineHeight: 1.7 }}>
                        {ru
                            ? 'Go-сервис недоступен. Проверьте что userbot запущен.'
                            : 'Go service unavailable. Make sure userbot is running.'}
                    </p>
                </div>
            )}
        </div>
    )
}



function NotificationsTab({ lang }: { lang: Lang }) {
    const [title,       setTitle]       = useState('')
    const [body,        setBody]        = useState('')
    const [scheduledAt, setScheduledAt] = useState('')
    const [sending,     setSending]     = useState(false)
    const [error,       setError]       = useState<string | null>(null)
    const [success,     setSuccess]     = useState(false)
    const [history,     setHistory]     = useState<NotificationDto[]>([])
    const ru = lang === 'ru'

    const loadHistory = useCallback(() => {
        notificationsApi.getAllAdmin().then(setHistory).catch(() => {})
    }, [])

    useEffect(() => { loadHistory() }, [loadHistory])
    useEffect(() => {
        if (success) loadHistory()
    }, [success, loadHistory])

    const handleSend = async () => {
        if (!title.trim() || !body.trim()) {
            setError(ru ? 'Заполните заголовок и текст' : 'Fill in title and body')
            return
        }
        setSending(true)
        setError(null)
        try {
            await notificationsApi.createNotification({
                title,
                body,
                target:      'BOT',
                scheduledAt: scheduledAt || undefined,
            })
            setSuccess(true)
            setTitle('')
            setBody('')
            setScheduledAt('')
            setTimeout(() => setSuccess(false), 3000)
        } catch (e: unknown) {
            setError(e instanceof Error ? e.message : 'Ошибка')
        } finally {
            setSending(false)
        }
    }

    return (
        <div className={s.notifTab}>
            <div className={s.notifForm}>
                <h3 className={s.sectionTitle}>
                    {ru ? 'Отправить в Telegram-бот' : 'Send via Telegram Bot'}
                </h3>
                <p className={s.notifSubtitle}>
                    {ru
                        ? 'Получат все пользователи с привязанным Telegram'
                        : 'All users with linked Telegram will receive this'}
                </p>
                <div className={s.formGroup}>
                    <label className={s.formLabel}>{ru ? 'Заголовок' : 'Title'}</label>
                    <input
                        className={s.formInput}
                        value={title}
                        onChange={e => setTitle(e.target.value)}
                        placeholder={ru ? 'Введите заголовок' : 'Enter title'}
                    />
                </div>
                <div className={s.formGroup}>
                    <label className={s.formLabel}>{ru ? 'Текст' : 'Body'}</label>
                    <textarea
                        className={s.formTextarea}
                        value={body}
                        onChange={e => setBody(e.target.value)}
                        rows={4}
                        placeholder={ru ? 'Введите текст...' : 'Enter text...'}
                    />
                </div>
                <div className={s.formGroup}>
                    <label className={s.formLabel}>
                        {ru ? 'Дата отправки (необязательно)' : 'Schedule (optional)'}
                    </label>
                    <input
                        className={s.formInput}
                        type="datetime-local"
                        value={scheduledAt}
                        onChange={e => setScheduledAt(e.target.value)}
                    />
                </div>
                {error   && <div className={s.formError}>{error}</div>}
                {success && <div className={s.formSuccess}>{ru ? 'Отправлено!' : 'Sent!'}</div>}
                <button className={s.sendBtn} onClick={handleSend} disabled={sending}>
                    {sending ? '...' : ru ? 'Отправить' : 'Send'}
                </button>
            </div>

            {history.length > 0 && (
                <div className={s.notifHistory}>
                    <h3 className={s.sectionTitle}>{ru ? 'История' : 'History'}</h3>
                    <div className={s.historyList}>
                        {history.map(n => (
                            <div key={n.id} className={s.historyItem}>
                                <div className={s.historyHead}>
                                    <span className={s.historyTitle}>{n.title}</span>
                                    <span className={n.sent ? s.statusSent : s.statusPending}>
                                        {n.sent
                                            ? (ru ? 'Отправлено' : 'Sent')
                                            : (ru ? 'Ожидает'    : 'Pending')}
                                    </span>
                                </div>
                                <div className={s.historyBody}>{n.body}</div>
                                <div className={s.historyDate}>
                                    {new Date(n.scheduledAt).toLocaleString(ru ? 'ru-RU' : 'en-US')}
                                </div>
                            </div>
                        ))}
                    </div>
                </div>
            )}
        </div>
    )
}



export default function AdminPanel({ lang, setLang }: Props) {
    const { user, loading } = useAuthContext()
    const navigate = useNavigate()
    const [activeTab,    setActiveTab]    = useState<Tab>('overview')
    const [users,        setUsers]        = useState<AdminUserDto[]>([])
    const [usersLoading, setUsersLoading] = useState(false)


    const [sidebarOpen, setSidebarOpen] = useState(false)

    useEffect(() => {
        if (!loading && (!user || user.role !== 'ADMIN')) navigate('/')
    }, [user, loading, navigate])


    useEffect(() => {
        const onResize = () => {
            if (window.innerWidth > 860) setSidebarOpen(false)
        }
        window.addEventListener('resize', onResize)
        return () => window.removeEventListener('resize', onResize)
    }, [])

    const fetchUsers = useCallback(async () => {
        setUsersLoading(true)
        try {
            const data = await adminApi.getUsers()
            setUsers(data)
        } catch {
            // dkddk

        } finally {
            setUsersLoading(false)
        }
    }, [])

    useEffect(() => {
        if (activeTab !== 'users') return
        void fetchUsers()
    }, [activeTab, fetchUsers])

    if (loading || !user || user.role !== 'ADMIN') return null

    const ru = lang === 'ru'

    const handleTabChange = (tab: Tab) => {
        setActiveTab(tab)
        setSidebarOpen(false)
    }

    return (
        <div className={s.root}>
            <header className={s.header}>
                <a href="/" className={s.logo}>
                    <img src="/AIMLY.png" alt="AIMLY" className={s.logoImg} />
                    <span className={s.logoText}>AIMLY</span>
                    <span className={s.adminTag}>Admin</span>
                </a>

                {}
                <button
                    className={`${s.burger} ${sidebarOpen ? s.burgerOpen : ''}`}
                    onClick={() => setSidebarOpen(v => !v)}
                    aria-label={sidebarOpen ? (ru ? 'Закрыть меню' : 'Close menu') : (ru ? 'Открыть меню' : 'Open menu')}
                    aria-expanded={sidebarOpen}
                >
                    <span /><span /><span />
                </button>

                <div className={s.headerRight}>
                    <div className={s.langSwitch}>
                        {(['ru', 'en'] as Lang[]).map(lng => (
                            <button
                                key={lng}
                                className={`${s.langBtn} ${lang === lng ? s.langActive : ''}`}
                                onClick={() => setLang(lng)}
                            >
                                {lng.toUpperCase()}
                            </button>
                        ))}
                    </div>
                    <button className={s.backBtn} onClick={() => navigate('/dashboard')}>
                        <span className={s.backBtnFull}>{ru ? '← Кабинет' : '← Dashboard'}</span>
                        <span className={s.backBtnShort}>←</span>
                    </button>
                </div>
            </header>

            {}
            {sidebarOpen && (
                <div className={s.mobileOverlay} onClick={() => setSidebarOpen(false)} />
            )}

            <div className={s.body}>
                <aside className={`${s.sidebar} ${sidebarOpen ? s.sidebarOpen : ''}`}>
                    <nav className={s.nav}>
                        {TABS.map(tab => (
                            <button
                                key={tab.id}
                                className={`${s.navItem} ${activeTab === tab.id ? s.navItemActive : ''}`}
                                onClick={() => handleTabChange(tab.id)}
                            >
                                {tab.label[lang]}
                            </button>
                        ))}
                    </nav>
                </aside>

                <main className={s.main}>
                    {activeTab === 'overview' && (
                        <div className={s.placeholder}>
                            <div className={s.placeholderIcon}>—</div>
                            <h2>{ru ? 'Обзор' : 'Overview'}</h2>
                            <p>{ru ? 'Статистика появится здесь' : 'Statistics will appear here'}</p>
                        </div>
                    )}

                    {activeTab === 'users' && (
                        <div className={s.usersTab}>
                            {}
                            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 10 }}>
                                <h2 className={s.tabTitle}>
                                    {ru ? 'Пользователи' : 'Users'}
                                    <span className={s.count}>{users.length}</span>
                                </h2>
                                <button
                                    className={s.grantBtn}
                                    onClick={fetchUsers}
                                    disabled={usersLoading}
                                    style={{ padding: '7px 16px' }}
                                >
                                    {usersLoading
                                        ? (ru ? 'Загрузка...' : 'Loading...')
                                        : (ru ? 'Обновить' : 'Refresh')}
                                </button>
                            </div>
                            {usersLoading && users.length === 0 ? (
                                <div className={s.loading}>...</div>
                            ) : (
                                <UsersTable
                                    users={users}
                                    lang={lang}
                                    currentUserId={user.id}
                                    onRoleChange={(id, role) =>
                                        setUsers(prev => prev.map(u => u.id === id ? { ...u, role } : u))
                                    }
                                />
                            )}
                        </div>
                    )}

                    {activeTab === 'subscriptions' && <SubscriptionsTab lang={lang} />}
                    {activeTab === 'userbot'       && <UserbotTab       lang={lang} />}
                    {activeTab === 'notifications' && <NotificationsTab lang={lang} />}
                </main>
            </div>
        </div>
    )
}