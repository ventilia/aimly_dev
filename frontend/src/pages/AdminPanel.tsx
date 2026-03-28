import { useState, useEffect, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import type { Lang } from '../i18n/translations'
import {
    adminApi, notificationsApi,
    type AdminUserDto, type NotificationDto,
    type AdminUserDetailDto, type AdminLeadDto,
} from '../api/auth'
import { adminSubsApi, type SubscriptionInfo } from '../api/leads'
import { useAuthContext } from '../context/AuthContext'
import s from './AdminPanel.module.css'

interface Props { lang: Lang; setLang: (l: Lang) => void }

type Tab = 'overview' | 'users' | 'leads' | 'userbot' | 'notifications'

const TABS: { id: Tab; label: { ru: string; en: string } }[] = [
    { id: 'overview',       label: { ru: 'Обзор',        en: 'Overview'      } },
    { id: 'users',          label: { ru: 'Пользователи', en: 'Users'         } },
    { id: 'leads',          label: { ru: 'Лиды',         en: 'Leads'         } },
    { id: 'userbot',        label: { ru: 'Userbot',       en: 'Userbot'       } },
    { id: 'notifications',  label: { ru: 'Уведомления',  en: 'Notifications' } },
]

const PLANS    = ['START', 'BUSINESS', 'TRIAL']
const STATUSES = ['ACTIVE', 'TRIAL', 'INACTIVE']
const LEAD_STATUSES = ['', 'NEW', 'VIEWED', 'REPLIED', 'IGNORED']

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

function statusColor(st: string | null) {
    if (st === 'ACTIVE') return '#10b981'
    if (st === 'TRIAL')  return '#f59e0b'
    return '#6b7280'
}

function fmtDate(str: string | null | undefined, ru: boolean) {
    if (!str) return '—'
    return new Date(str).toLocaleDateString(ru ? 'ru-RU' : 'en-US', {
        day: 'numeric', month: 'short', year: 'numeric',
    })
}

function fmtDatetime(str: string | null | undefined, ru: boolean) {
    if (!str) return '—'
    return new Date(str).toLocaleString(ru ? 'ru-RU' : 'en-US', {
        day: 'numeric', month: 'short', year: 'numeric',
        hour: '2-digit', minute: '2-digit',
    })
}

function initials(dto: AdminUserDto | AdminUserDetailDto): string {
    const name = dto.firstName?.trim() || dto.email
    return name.charAt(0).toUpperCase()
}

interface Msg { text: string; ok: boolean }

type UserWithSub = AdminUserDto & {
    subStatus:    string | null
    subPlan:      string | null
    subExpiresAt: string | null
}

// ─── Модалка деталей пользователя ────────────────────────────────────────────

function LeadStatusBadge({ status }: { status: string }) {
    const cls: Record<string, string> = {
        NEW:     s.leadStatusNew,
        VIEWED:  s.leadStatusViewed,
        REPLIED: s.leadStatusReplied,
        IGNORED: s.leadStatusIgnored,
    }
    return <span className={cls[status] ?? s.leadStatusViewed}>{status}</span>
}

function AiBadge({ valid, ru }: { valid: boolean | null; ru: boolean }) {
    if (valid === null || valid === undefined)
        return <span className={s.aiPending}>—</span>
    if (valid)
        return <span className={s.aiValid}>{ru ? 'Да' : 'Yes'}</span>
    return <span className={s.aiInvalid}>{ru ? 'Нет' : 'No'}</span>
}

function UserDetailModal({
                             userId,
                             lang,
                             onClose,
                         }: {
    userId: number
    lang:   Lang
    onClose: () => void
}) {
    const ru = lang === 'ru'
    const [detail,  setDetail]  = useState<AdminUserDetailDto | null>(null)
    const [loading, setLoading] = useState(true)
    const [error,   setError]   = useState('')

    useEffect(() => {
        setLoading(true)
        setError('')
        adminApi.getUserDetails(userId)
            .then(setDetail)
            .catch(e => setError(e instanceof Error ? e.message : 'Ошибка'))
            .finally(() => setLoading(false))
    }, [userId])

    // Закрытие по Escape
    useEffect(() => {
        const handler = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
        document.addEventListener('keydown', handler)
        return () => document.removeEventListener('keydown', handler)
    }, [onClose])

    return (
        <div className={s.modalBackdrop} onClick={e => { if (e.target === e.currentTarget) onClose() }}>
            <div className={s.modal}>
                {loading ? (
                    <div className={s.loading} style={{ padding: 48 }}>
                        {ru ? 'Загрузка...' : 'Loading...'}
                    </div>
                ) : error ? (
                    <div style={{ padding: 32, textAlign: 'center' }}>
                        <div className={s.formError} style={{ margin: 0 }}>{error}</div>
                        <button className={s.actionBtn} onClick={onClose} style={{ marginTop: 16 }}>
                            {ru ? 'Закрыть' : 'Close'}
                        </button>
                    </div>
                ) : detail ? (
                    <>
                        {/* ── Шапка ── */}
                        <div className={s.modalHeader}>
                            <div className={s.modalAvatar}>{initials(detail)}</div>
                            <div className={s.modalHeaderInfo}>
                                <p className={s.modalTitle}>
                                    {detail.firstName || detail.email}
                                    {detail.role === 'ADMIN' && (
                                        <span className={s.adminTag} style={{ marginLeft: 8 }}>Admin</span>
                                    )}
                                </p>
                                <p className={s.modalSubtitle}>
                                    ID #{detail.id} · {detail.email}
                                    {!detail.isActive && (
                                        <span style={{ color: '#ef4444', marginLeft: 8 }}>
                                            {ru ? '(неактивен)' : '(inactive)'}
                                        </span>
                                    )}
                                </p>
                            </div>
                            <button className={s.modalClose} onClick={onClose} title="Закрыть">×</button>
                        </div>

                        <div className={s.modalBody}>

                            {/* ── Метрики лидов ── */}
                            <div className={s.detailLeadStats}>
                                {[
                                    { label: ru ? 'Всего лидов' : 'Total leads', val: detail.leadsCount, color: 'var(--c-ink)' },
                                    { label: 'NEW',     val: detail.leadsNew,     color: '#6366f1' },
                                    { label: 'REPLIED', val: detail.leadsReplied, color: '#10b981' },
                                    { label: 'IGNORED', val: detail.leadsIgnored, color: '#ef4444' },
                                ].map(item => (
                                    <div key={item.label} className={s.detailStatCard}>
                                        <span className={s.detailStatVal} style={{ color: item.color }}>
                                            {item.val}
                                        </span>
                                        <span className={s.detailStatLabel}>{item.label}</span>
                                    </div>
                                ))}
                            </div>

                            {/* ── Основная информация ── */}
                            <div className={s.detailSection}>
                                <p className={s.detailSectionTitle}>
                                    {ru ? '📋 Основные данные' : '📋 Profile'}
                                </p>
                                <div className={s.detailGrid2}>
                                    <div className={s.detailField}>
                                        <span className={s.detailFieldLabel}>Email</span>
                                        <span className={s.detailFieldValueMono}>{detail.email}</span>
                                    </div>
                                    <div className={s.detailField}>
                                        <span className={s.detailFieldLabel}>{ru ? 'Имя' : 'Name'}</span>
                                        <span className={s.detailFieldValue}>{detail.firstName || '—'}</span>
                                    </div>
                                    <div className={s.detailField}>
                                        <span className={s.detailFieldLabel}>{ru ? 'Подписка' : 'Subscription'}</span>
                                        <span className={s.detailFieldValue}>
                                            {detail.subscriptionStatus
                                                ? <span style={{ color: statusColor(detail.subscriptionStatus), fontWeight: 600 }}>
                                                    {detail.subscriptionStatus}
                                                    {detail.subscriptionPlan ? ` / ${detail.subscriptionPlan}` : ''}
                                                  </span>
                                                : '—'
                                            }
                                        </span>
                                    </div>
                                    <div className={s.detailField}>
                                        <span className={s.detailFieldLabel}>Telegram</span>
                                        <span className={s.detailFieldValue}>
                                            {detail.telegramId
                                                ? <span style={{ color: '#3b82f6' }}>
                                                    ✓ {detail.telegramUsername ? `@${detail.telegramUsername}` : `ID: ${detail.telegramId}`}
                                                  </span>
                                                : <span style={{ color: 'var(--c-ink-3)' }}>—</span>
                                            }
                                        </span>
                                    </div>
                                    <div className={s.detailField}>
                                        <span className={s.detailFieldLabel}>{ru ? 'Email верифицирован' : 'Email verified'}</span>
                                        <span className={s.detailFieldValue}>
                                            {detail.emailVerified
                                                ? <span style={{ color: '#10b981' }}>✓ {ru ? 'Да' : 'Yes'}</span>
                                                : <span style={{ color: '#ef4444' }}>✗ {ru ? 'Нет' : 'No'}</span>
                                            }
                                        </span>
                                    </div>
                                    <div className={s.detailField}>
                                        <span className={s.detailFieldLabel}>{ru ? 'Дата регистрации' : 'Registered'}</span>
                                        <span className={s.detailFieldValue}>{fmtDatetime(detail.createdAt, ru)}</span>
                                    </div>
                                    <div className={s.detailField}>
                                        <span className={s.detailFieldLabel}>{ru ? 'Обновлён' : 'Updated'}</span>
                                        <span className={s.detailFieldValue}>{fmtDatetime(detail.updatedAt, ru)}</span>
                                    </div>
                                    <div className={s.detailField}>
                                        <span className={s.detailFieldLabel}>{ru ? 'Отвечать на коммерч.' : 'Reply to offers'}</span>
                                        <span className={s.detailFieldValue}>
                                            {detail.respondToServiceOffers
                                                ? <span style={{ color: '#10b981' }}>{ru ? 'Включено' : 'On'}</span>
                                                : <span style={{ color: 'var(--c-ink-3)' }}>{ru ? 'Выключено' : 'Off'}</span>
                                            }
                                        </span>
                                    </div>
                                </div>
                            </div>

                            {/* ── AI-профиль ── */}
                            <div className={s.detailSection}>
                                <p className={s.detailSectionTitle}>
                                    🤖 {ru ? 'AI-профиль (бизнес-контекст)' : 'AI Profile (business context)'}
                                </p>
                                <div className={s.detailAiBlock}>
                                    {detail.businessContext
                                        ? <pre className={s.detailAiText}>{detail.businessContext}</pre>
                                        : <span className={s.detailAiEmpty}>
                                            {ru ? 'Пользователь не заполнил AI-профиль' : 'User has not filled in their AI profile'}
                                          </span>
                                    }
                                </div>
                            </div>

                            {/* ── Чаты ── */}
                            <div className={s.detailSection}>
                                <p className={s.detailSectionTitle}>
                                    💬 {ru ? 'Чаты' : 'Chats'}
                                    <span>{detail.chats.length}</span>
                                </p>
                                {detail.chats.length === 0
                                    ? <span className={s.detailAiEmpty}>{ru ? 'Нет подписок на чаты' : 'No chat subscriptions'}</span>
                                    : <div className={s.detailTagsWrap}>
                                        {detail.chats.map(c => (
                                            <a
                                                key={c.id}
                                                href={c.chatLink.startsWith('http') ? c.chatLink : `https://${c.chatLink}`}
                                                target="_blank"
                                                rel="noopener noreferrer"
                                                className={`${s.detailTag} ${s.detailTagLink} ${!c.isActive ? s.detailTagInactive : ''}`}
                                                title={c.chatTitle !== c.chatLink ? c.chatTitle : undefined}
                                            >
                                                {c.chatTitle !== c.chatLink ? c.chatTitle : c.chatLink}
                                            </a>
                                        ))}
                                    </div>
                                }
                            </div>

                            {/* ── Ключевые слова ── */}
                            <div className={s.detailSection}>
                                <p className={s.detailSectionTitle}>
                                    🔑 {ru ? 'Ключевые слова' : 'Keywords'}
                                    <span>{detail.keywords.filter(k => k.isActive).length}</span>
                                </p>
                                {detail.keywords.length === 0
                                    ? <span className={s.detailAiEmpty}>{ru ? 'Нет ключевых слов' : 'No keywords'}</span>
                                    : <div className={s.detailTagsWrap}>
                                        {detail.keywords.map(kw => (
                                            <span
                                                key={kw.id}
                                                className={`${s.detailTag} ${!kw.isActive ? s.detailTagInactive : ''}`}
                                                title={kw.variants.length > 0
                                                    ? `${ru ? 'Варианты' : 'Variants'}: ${kw.variants.join(', ')}`
                                                    : undefined}
                                            >
                                                {kw.keyword}
                                                {kw.variants.length > 0 && (
                                                    <span style={{ color: 'var(--c-ink-3)', marginLeft: 4, fontSize: 10 }}>
                                                        +{kw.variants.length}
                                                    </span>
                                                )}
                                            </span>
                                        ))}
                                    </div>
                                }
                            </div>

                            {/* ── Последние лиды ── */}
                            <div className={s.detailSection}>
                                <p className={s.detailSectionTitle}>
                                    📥 {ru ? 'Последние лиды' : 'Recent leads'}
                                    <span>{detail.recentLeads.length}</span>
                                </p>
                                {detail.recentLeads.length === 0
                                    ? <span className={s.detailAiEmpty}>{ru ? 'Лидов нет' : 'No leads yet'}</span>
                                    : <div className={s.tableWrapper} style={{ borderRadius: 8 }}>
                                        <table className={s.detailLeadsTable}>
                                            <thead>
                                            <tr>
                                                <th>ID</th>
                                                <th>{ru ? 'Чат' : 'Chat'}</th>
                                                <th>{ru ? 'Автор' : 'Author'}</th>
                                                <th>{ru ? 'Сообщение' : 'Message'}</th>
                                                <th>{ru ? 'Ключ. слово' : 'Keyword'}</th>
                                                <th>{ru ? 'Статус' : 'Status'}</th>
                                                <th>AI</th>
                                                <th>{ru ? 'Дата' : 'Date'}</th>
                                            </tr>
                                            </thead>
                                            <tbody>
                                            {detail.recentLeads.map(lead => (
                                                <tr key={lead.id}>
                                                    <td style={{ fontFamily: 'monospace', fontSize: 11, color: 'var(--c-ink-3)' }}>
                                                        #{lead.id}
                                                    </td>
                                                    <td style={{ fontSize: 11, maxWidth: 100, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                                                        {lead.chatTitle || '—'}
                                                    </td>
                                                    <td style={{ fontSize: 11, whiteSpace: 'nowrap' }}>
                                                        {lead.authorUsername
                                                            ? `@${lead.authorUsername}`
                                                            : lead.authorName || '—'}
                                                    </td>
                                                    <td>
                                                        <div style={{
                                                            fontSize: 11, maxWidth: 200,
                                                            overflow: 'hidden', display: '-webkit-box',
                                                            WebkitLineClamp: 2, WebkitBoxOrient: 'vertical',
                                                            lineHeight: 1.4,
                                                        }}>
                                                            {lead.messageLink
                                                                ? <a href={lead.messageLink} target="_blank"
                                                                     rel="noopener noreferrer"
                                                                     className={s.leadLink}>
                                                                    {lead.messageText}
                                                                </a>
                                                                : lead.messageText
                                                            }
                                                        </div>
                                                    </td>
                                                    <td>
                                                        <span className={s.leadKeywordPill}>{lead.matchedKeyword}</span>
                                                    </td>
                                                    <td><LeadStatusBadge status={lead.status} /></td>
                                                    <td><AiBadge valid={lead.aiValid} ru={ru} /></td>
                                                    <td style={{ fontSize: 11, color: 'var(--c-ink-3)', whiteSpace: 'nowrap' }}>
                                                        {fmtDate(lead.foundAt, ru)}
                                                    </td>
                                                </tr>
                                            ))}
                                            </tbody>
                                        </table>
                                    </div>
                                }
                            </div>
                        </div>
                    </>
                ) : null}
            </div>
        </div>
    )
}

// ─── Таблица пользователей ────────────────────────────────────────────────────

function UsersTable({
                        users, subs, lang, currentUserId,
                        onRoleChange, onSubChange, onOpenDetails,
                    }: {
    users:          AdminUserDto[]
    subs:           SubscriptionInfo[]
    lang:           Lang
    currentUserId:  number
    onRoleChange:   (id: number, role: string) => void
    onSubChange:    () => void
    onOpenDetails:  (id: number) => void
}) {
    const [changing,        setChanging]        = useState<number | null>(null)
    const [working,         setWorking]         = useState<number | null>(null)
    const [sortKey,         setSortKey]         = useState<SortKey>('createdAt')
    const [sortDir,         setSortDir]         = useState<SortDir>('desc')
    const [editPlanId,      setEditPlanId]      = useState<number | null>(null)
    const [editPlanValue,   setEditPlanValue]   = useState('START')
    const [editStatusValue, setEditStatusValue] = useState('ACTIVE')
    const [editExpiryId,    setEditExpiryId]    = useState<number | null>(null)
    const [editExpiryDays,  setEditExpiryDays]  = useState('30')

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

    const handleRevoke = async (userId: number) => {
        if (!confirm(ru ? 'Отозвать подписку?' : 'Revoke subscription?')) return
        setWorking(userId)
        try { await adminSubsApi.revoke(userId); onSubChange() }
        catch (e: unknown) { alert(e instanceof Error ? e.message : 'Ошибка') }
        finally { setWorking(null) }
    }

    const handleEditPlan = async (userId: number) => {
        setWorking(userId)
        try {
            await adminSubsApi.changePlan(userId, editPlanValue || 'START')
            if (editStatusValue === 'INACTIVE') await adminSubsApi.revoke(userId)
            setEditPlanId(null)
            onSubChange()
        } catch (e: unknown) { alert(e instanceof Error ? e.message : 'Ошибка') }
        finally { setWorking(null) }
    }

    const handleEditExpiry = async (userId: number, currentPlan: string | null) => {
        const plan = currentPlan ?? 'START'
        const days = Number(editExpiryDays) || 30
        setWorking(userId)
        try { await adminSubsApi.setExpiry(userId, plan, days); setEditExpiryId(null); onSubChange() }
        catch (e: unknown) { alert(e instanceof Error ? e.message : 'Ошибка') }
        finally { setWorking(null) }
    }

    const subsMap = new Map(subs.map(sub => [sub.userId, sub]))

    const merged: UserWithSub[] = users.map(u => {
        const sub = subsMap.get(u.id)
        return {
            ...u,
            subStatus:    sub?.status    ?? u.subscriptionStatus ?? null,
            subPlan:      sub?.plan      ?? u.subscriptionPlan   ?? null,
            subExpiresAt: sub?.expiresAt ?? null,
        }
    })

    const sorted = sortUsers(merged as unknown as AdminUserDto[], sortKey, sortDir) as unknown as UserWithSub[]

    const inlineInput: React.CSSProperties = {
        height: 28, padding: '0 8px', fontSize: 12,
        border: '1px solid var(--c-border)', borderRadius: 6,
        background: 'var(--c-bg)', color: 'var(--c-ink)',
        fontFamily: 'inherit', outline: 'none',
    }

    return (
        <div className={s.tableWrapper}>
            <table className={s.usersTable}>
                <thead>
                <tr>
                    <Th col="id"         label="ID" />
                    <Th col="firstName"  label={ru ? 'Имя'    : 'Name'} />
                    <Th col="email"      label="Email" />
                    <Th col="role"       label={ru ? 'Роль'   : 'Role'} />
                    <Th col="leadsCount" label={ru ? 'Лиды'   : 'Leads'} />
                    <Th col="createdAt"  label={ru ? 'Создан' : 'Created'} />
                    <th>{ru ? 'Тариф / Статус' : 'Plan / Status'}</th>
                    <th>{ru ? 'Истекает' : 'Expires'}</th>
                    <th>{ru ? 'Telegram' : 'Telegram'}</th>
                    <th>{ru ? 'Действия' : 'Actions'}</th>
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

                        {/* ── Тариф + Статус ── */}
                        <td>
                            {editPlanId === u.id ? (
                                <div style={{ display: 'flex', gap: 4, alignItems: 'center', flexWrap: 'wrap' }}>
                                    <select value={editPlanValue} onChange={e => setEditPlanValue(e.target.value)} style={{ ...inlineInput, width: 90 }}>
                                        {['START', 'BUSINESS'].map(p => <option key={p} value={p}>{p}</option>)}
                                    </select>
                                    <select value={editStatusValue} onChange={e => setEditStatusValue(e.target.value)} style={{ ...inlineInput, width: 95 }}>
                                        {STATUSES.map(st => <option key={st} value={st}>{st}</option>)}
                                    </select>
                                    <button className={s.actionBtn} onClick={() => handleEditPlan(u.id)} disabled={working === u.id} style={{ padding: '3px 8px', fontSize: 11 }}>
                                        {working === u.id ? '…' : '✔'}
                                    </button>
                                    <button className={s.actionBtn} onClick={() => setEditPlanId(null)} style={{ padding: '3px 8px', fontSize: 11, background: 'var(--c-surface)' }}>
                                        ✕
                                    </button>
                                </div>
                            ) : (
                                <div style={{ display: 'flex', alignItems: 'center', gap: 5, cursor: 'pointer' }}
                                     onClick={() => { setEditPlanId(u.id); setEditPlanValue(u.subPlan || 'START'); setEditStatusValue(u.subStatus || 'ACTIVE') }}>
                                    {u.subStatus ? (
                                        <span style={{ fontWeight: 600, fontSize: 12, color: statusColor(u.subStatus) }}>
                                                {u.subStatus}{u.subPlan ? ` / ${u.subPlan}` : ''}
                                            </span>
                                    ) : (
                                        <span className={s.badgeNone}>—</span>
                                    )}
                                    <span style={{ fontSize: 10, color: 'var(--c-ink-3)' }}>✎</span>
                                </div>
                            )}
                        </td>

                        {/* ── Срок ── */}
                        <td>
                            {editExpiryId === u.id ? (
                                <div style={{ display: 'flex', gap: 4, alignItems: 'center' }}>
                                    <input type="number" value={editExpiryDays} onChange={e => setEditExpiryDays(e.target.value)} style={{ ...inlineInput, width: 60 }} />
                                    <span style={{ fontSize: 11, color: 'var(--c-ink-3)' }}>{ru ? 'дн.' : 'd.'}</span>
                                    <button className={s.actionBtn} onClick={() => handleEditExpiry(u.id, u.subPlan)} disabled={working === u.id} style={{ padding: '3px 8px', fontSize: 11 }}>
                                        {working === u.id ? '…' : '✔'}
                                    </button>
                                    <button className={s.actionBtn} onClick={() => setEditExpiryId(null)} style={{ padding: '3px 8px', fontSize: 11, background: 'var(--c-surface)' }}>
                                        ✕
                                    </button>
                                </div>
                            ) : (
                                <div style={{ display: 'flex', alignItems: 'center', gap: 5, cursor: 'pointer' }}
                                     onClick={() => { setEditExpiryId(u.id); setEditExpiryDays('30') }}>
                                        <span style={{ fontSize: 12 }}>
                                            {u.subExpiresAt ? new Date(u.subExpiresAt).toLocaleDateString(ru ? 'ru-RU' : 'en-US', { day: 'numeric', month: 'short', year: 'numeric' }) : '—'}
                                        </span>
                                    <span style={{ fontSize: 10, color: 'var(--c-ink-3)' }}>✎</span>
                                </div>
                            )}
                        </td>

                        {/* ── Telegram ── */}
                        <td>
                            {u.telegramId
                                ? <span className={s.badgeTg}>✓ {u.telegramUsername ? `@${u.telegramUsername}` : u.telegramId}</span>
                                : <span className={s.badgeNone}>—</span>
                            }
                        </td>

                        {/* ── Действия ── */}
                        <td>
                            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                                {/* Детали */}
                                <button
                                    className={s.actionBtn}
                                    onClick={() => onOpenDetails(u.id)}
                                    style={{ background: 'var(--c-accent-soft)', color: 'var(--c-accent)', borderColor: 'rgba(99,102,241,.3)', fontWeight: 600 }}
                                >
                                    {ru ? 'Детали' : 'Details'}
                                </button>
                                {/* Роль */}
                                <button className={s.actionBtn} onClick={() => handleToggleRole(u)} disabled={changing === u.id}>
                                    {changing === u.id ? '...' : u.role === 'ADMIN'
                                        ? (ru ? 'Снять ADMIN' : 'Remove ADMIN')
                                        : (ru ? 'ADMIN' : 'Make ADMIN')}
                                </button>
                                {u.subStatus && u.subStatus !== 'INACTIVE' && (
                                    <button
                                        className={s.actionBtn}
                                        onClick={() => handleRevoke(u.id)}
                                        disabled={working === u.id}
                                        style={{ color: '#ef4444', borderColor: 'rgba(239,68,68,.3)', background: 'rgba(239,68,68,.06)' }}
                                    >
                                        {working === u.id ? '…' : ru ? 'Отозвать' : 'Revoke'}
                                    </button>
                                )}
                            </div>
                        </td>
                    </tr>
                ))}
                </tbody>
            </table>
        </div>
    )
}

// ─── Вкладка: все лиды ───────────────────────────────────────────────────────

function LeadsTab({ lang }: { lang: Lang }) {
    const ru = lang === 'ru'

    const [leads,        setLeads]        = useState<AdminLeadDto[]>([])
    const [loading,      setLoading]      = useState(true)
    const [error,        setError]        = useState('')
    const [totalPages,   setTotalPages]   = useState(1)
    const [totalCount,   setTotalCount]   = useState(0)
    const [page,         setPage]         = useState(0)

    // Фильтры
    const [filterUser,   setFilterUser]   = useState('')
    const [filterStatus, setFilterStatus] = useState('')
    const [appliedUser,  setAppliedUser]  = useState<number | undefined>(undefined)
    const [appliedStatus,setAppliedStatus]= useState<string | undefined>(undefined)

    const PAGE_SIZE = 50

    const load = useCallback(async (pg: number, userId?: number, status?: string) => {
        setLoading(true)
        setError('')
        try {
            const res = await adminApi.getAllLeads({
                userId: userId,
                status: status || undefined,
                page:   pg,
                size:   PAGE_SIZE,
            })
            setLeads(res.content)
            setTotalPages(res.totalPages)
            setTotalCount(res.totalElements)
        } catch (e: unknown) {
            setError(e instanceof Error ? e.message : 'Ошибка')
        } finally {
            setLoading(false)
        }
    }, [])

    useEffect(() => { load(0, appliedUser, appliedStatus) }, [load, appliedUser, appliedStatus])

    const handleApply = () => {
        const uid = filterUser.trim() ? Number(filterUser.trim()) : undefined
        const st  = filterStatus || undefined
        setAppliedUser(uid)
        setAppliedStatus(st)
        setPage(0)
    }

    const handleClear = () => {
        setFilterUser('')
        setFilterStatus('')
        setAppliedUser(undefined)
        setAppliedStatus(undefined)
        setPage(0)
    }

    const handlePage = (pg: number) => {
        setPage(pg)
        load(pg, appliedUser, appliedStatus)
    }

    const hasFilter = !!filterUser.trim() || !!filterStatus

    return (
        <div className={s.leadsTab}>
            {/* Заголовок */}
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 10 }}>
                <h2 className={s.tabTitle}>
                    {ru ? 'Все лиды' : 'All Leads'}
                    <span className={s.count}>{totalCount}</span>
                </h2>
                <button className={s.grantBtn} onClick={() => load(page, appliedUser, appliedStatus)} style={{ padding: '7px 16px' }}>
                    {loading ? (ru ? 'Загрузка...' : 'Loading...') : (ru ? 'Обновить' : 'Refresh')}
                </button>
            </div>

            {/* Фильтры */}
            <div className={s.leadsFilterBar}>
                <span className={s.leadsFilterLabel}>{ru ? 'Фильтр:' : 'Filter:'}</span>
                <input
                    className={s.leadsFilterInput}
                    placeholder={ru ? 'ID пользователя...' : 'User ID...'}
                    value={filterUser}
                    onChange={e => setFilterUser(e.target.value)}
                    onKeyDown={e => { if (e.key === 'Enter') handleApply() }}
                    type="number"
                    style={{ maxWidth: 160 }}
                />
                <select className={s.leadsFilterSelect} value={filterStatus} onChange={e => setFilterStatus(e.target.value)}>
                    <option value="">{ru ? 'Все статусы' : 'All statuses'}</option>
                    {['NEW', 'VIEWED', 'REPLIED', 'IGNORED'].map(st => (
                        <option key={st} value={st}>{st}</option>
                    ))}
                </select>
                <button className={s.grantBtn} onClick={handleApply} style={{ height: 36, padding: '0 16px' }}>
                    {ru ? 'Применить' : 'Apply'}
                </button>
                {hasFilter && (
                    <button className={s.leadsFilterClear} onClick={handleClear}>
                        {ru ? 'Сбросить' : 'Clear'}
                    </button>
                )}
                {(appliedUser || appliedStatus) && (
                    <span style={{ fontSize: 12, color: 'var(--c-ink-3)' }}>
                        {appliedUser ? `User #${appliedUser}` : ''}
                        {appliedUser && appliedStatus ? ' · ' : ''}
                        {appliedStatus || ''}
                    </span>
                )}
            </div>

            {/* Ошибка */}
            {error && <div className={s.formError}>{error}</div>}

            {/* Таблица */}
            {loading && leads.length === 0 ? (
                <div className={s.loading}>{ru ? 'Загрузка...' : 'Loading...'}</div>
            ) : leads.length === 0 ? (
                <div className={s.placeholder}>
                    <div className={s.placeholderIcon}>📭</div>
                    <h2>{ru ? 'Лидов нет' : 'No leads'}</h2>
                    <p>{ru ? 'По выбранному фильтру лидов не найдено' : 'No leads match the selected filter'}</p>
                </div>
            ) : (
                <div className={s.tableWrapper}>
                    <table className={s.leadsTable}>
                        <thead>
                        <tr>
                            <th>ID</th>
                            <th>{ru ? 'Пользователь' : 'User'}</th>
                            <th>{ru ? 'Чат' : 'Chat'}</th>
                            <th>{ru ? 'Автор' : 'Author'}</th>
                            <th>{ru ? 'Сообщение' : 'Message'}</th>
                            <th>{ru ? 'Ключ. слово' : 'Keyword'}</th>
                            <th>{ru ? 'Статус' : 'Status'}</th>
                            <th>AI</th>
                            <th>{ru ? 'Дата' : 'Date'}</th>
                        </tr>
                        </thead>
                        <tbody>
                        {leads.map(lead => (
                            <tr key={lead.id}>
                                <td style={{ fontFamily: 'monospace', fontSize: 11, color: 'var(--c-ink-3)', whiteSpace: 'nowrap' }}>
                                    #{lead.id}
                                </td>
                                <td>
                                    <div className={s.leadUserCell} title={lead.userEmail}>
                                            <span style={{ fontFamily: 'monospace', fontSize: 10, color: 'var(--c-ink-3)', marginRight: 4 }}>
                                                #{lead.userId}
                                            </span>
                                        {lead.userEmail}
                                    </div>
                                </td>
                                <td style={{ fontSize: 12, maxWidth: 130, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                                    {lead.chatTitle || lead.chatLink || '—'}
                                </td>
                                <td style={{ fontSize: 12, whiteSpace: 'nowrap' }}>
                                    {lead.authorUsername
                                        ? `@${lead.authorUsername}`
                                        : lead.authorName || '—'}
                                </td>
                                <td>
                                    <div className={s.leadMsgText}>
                                        {lead.messageLink
                                            ? <a href={lead.messageLink} target="_blank" rel="noopener noreferrer" className={s.leadLink}>
                                                {lead.messageText}
                                            </a>
                                            : lead.messageText
                                        }
                                    </div>
                                </td>
                                <td><span className={s.leadKeywordPill}>{lead.matchedKeyword}</span></td>
                                <td><LeadStatusBadge status={lead.status} /></td>
                                <td><AiBadge valid={lead.aiValid} ru={ru} /></td>
                                <td className={s.cellDate}>{fmtDate(lead.foundAt, ru)}</td>
                            </tr>
                        ))}
                        </tbody>
                    </table>
                </div>
            )}

            {/* Пагинация */}
            {totalPages > 1 && (
                <div className={s.pagination}>
                    <button className={s.pageBtn} onClick={() => handlePage(0)} disabled={page === 0}>«</button>
                    <button className={s.pageBtn} onClick={() => handlePage(page - 1)} disabled={page === 0}>‹</button>

                    {Array.from({ length: Math.min(totalPages, 7) }, (_, i) => {
                        // показываем страницы вокруг текущей
                        let pg: number
                        if (totalPages <= 7) {
                            pg = i
                        } else if (page < 4) {
                            pg = i
                        } else if (page > totalPages - 4) {
                            pg = totalPages - 7 + i
                        } else {
                            pg = page - 3 + i
                        }
                        return (
                            <button
                                key={pg}
                                className={`${s.pageBtn} ${pg === page ? s.pageBtnActive : ''}`}
                                onClick={() => handlePage(pg)}
                            >
                                {pg + 1}
                            </button>
                        )
                    })}

                    <button className={s.pageBtn} onClick={() => handlePage(page + 1)} disabled={page >= totalPages - 1}>›</button>
                    <button className={s.pageBtn} onClick={() => handlePage(totalPages - 1)} disabled={page >= totalPages - 1}>»</button>

                    <span className={s.pageInfo}>
                        {ru
                            ? `стр. ${page + 1} из ${totalPages} (${totalCount} лидов)`
                            : `page ${page + 1} of ${totalPages} (${totalCount} leads)`}
                    </span>
                </div>
            )}
        </div>
    )
}

// ─── Userbot ──────────────────────────────────────────────────────────────────
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

function UserbotTab({ lang }: { lang: Lang }) {
    const ru   = lang === 'ru'
    const BASE = import.meta.env.VITE_API_URL || ''

    const [stats,        setStats]        = useState<UserbotStats | null>(null)
    const [users,        setUsers]        = useState<UserbotUserInfo[]>([])
    const [loading,      setLoading]      = useState(true)
    const [error,        setError]        = useState('')
    const [expandedUser, setExpandedUser] = useState<number | null>(null)
    const [userSearch,   setUserSearch]   = useState('')
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
        setLoading(true); setError('')
        try {
            const [st, us] = await Promise.all([
                fetch(`${BASE}/api/v1/admin/userbot/stats`, { credentials: 'include' }).then(r => r.json()) as Promise<UserbotStats>,
                fetch(`${BASE}/api/v1/admin/userbot/users`, { credentials: 'include' }).then(r => r.json()) as Promise<UserbotUserInfo[]>,
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

    const handleDeleteSession = async (sessionId: number, phone: string) => {
        const confirmed = confirm(ru
            ? `Удалить юзербота #${sessionId} (${phone})?\n\nОн будет отключён от всех чатов и остановлен.`
            : `Delete userbot #${sessionId} (${phone})?\n\nIt will be disconnected from all chats and stopped.`)
        if (!confirmed) return
        setDeletingSession(sessionId); setDeleteError('')
        try {
            const res = await fetch(`${BASE}/api/v1/admin/userbot/sessions/delete`, {
                method: 'POST', credentials: 'include',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ sessionId }),
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

    const handleSendPhone = async () => {
        if (!phone.trim()) return
        setRegLoading(true); setRegError('')
        try {
            const res = await fetch(`${BASE}/api/v1/admin/userbot/sessions/register`, {
                method: 'POST', credentials: 'include',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ phone: phone.trim(), apiID: parseInt(apiID, 10), apiHash: apiHash.trim() }),
            })
            const data = await res.json()
            if (!res.ok) throw new Error(data.error ?? `Ошибка ${res.status}`)
            setTempId(data.tempId); setStep('code')
        } catch (e: unknown) {
            setRegError(e instanceof Error ? e.message : 'Ошибка')
        } finally {
            setRegLoading(false)
        }
    }

    const handleConfirmCode = async () => {
        if (!code.trim()) return
        setRegLoading(true); setRegError('')
        try {
            const res = await fetch(`${BASE}/api/v1/admin/userbot/sessions/confirm`, {
                method: 'POST', credentials: 'include',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ tempId, code: code.trim(), password: password.trim() || undefined }),
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

    const filteredUsers = userSearch.trim()
        ? users.filter(u =>
            u.email.toLowerCase().includes(userSearch.toLowerCase()) ||
            String(u.userId).includes(userSearch))
        : users

    if (loading) return <div className={s.loading}>{ru ? 'Загрузка...' : 'Loading...'}</div>
    if (error)   return <div className={s.formError}>{error}</div>

    const isUp = stats?.status === 'UP'

    const statCards = [
        { label: ru ? 'Статус' : 'Status', value: isUp ? 'UP' : 'DOWN', color: isUp ? '#10b981' : '#ef4444', isText: true },
        { label: ru ? 'Аккаунтов' : 'Accounts', value: stats?.sessions ?? 0, color: undefined, isText: false },
        { label: ru ? 'Чатов' : 'Chats', value: stats?.totalChats ?? 0, color: undefined, isText: false },
        { label: ru ? 'Пользователей' : 'Users', value: stats?.totalUsers ?? 0, color: undefined, isText: false },
        { label: ru ? 'Всего лидов' : 'Total leads', value: stats?.totalLeads ?? 0, color: undefined, isText: false },
    ]

    return (
        <div className={s.subsTab}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <h2 className={s.tabTitle} style={{ margin: 0 }}>{ru ? 'Userbot сервис' : 'Userbot Service'}</h2>
                <button className={s.grantBtn} onClick={load} style={{ padding: '7px 16px' }}>{ru ? 'Обновить' : 'Refresh'}</button>
            </div>

            <div className={s.statsGrid}>
                {statCards.map(item => (
                    <div key={item.label} className={s.statCard}>
                        <span className={s.statVal} style={item.color ? { color: item.color, fontSize: item.isText ? 16 : undefined } : {}}>{item.value}</span>
                        <span className={s.statLabel}>{item.label}</span>
                    </div>
                ))}
            </div>

            <div className={s.subCard} style={{ marginTop: 20 }}>
                <h3 className={s.subCardTitle} style={{ marginBottom: 12 }}>{ru ? '➕ Добавить аккаунт юзербота' : '➕ Add userbot account'}</h3>

                {step === 'idle' && (
                    <button className={s.grantBtn} onClick={() => setStep('phone')} style={{ padding: '8px 20px' }}>
                        {ru ? 'Добавить аккаунт' : 'Add account'}
                    </button>
                )}
                {step === 'phone' && (
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 10, maxWidth: 420 }}>
                        <div style={{ background: 'rgba(99,102,241,.08)', border: '1px solid rgba(99,102,241,.2)', borderRadius: 8, padding: '10px 14px', fontSize: 12, color: 'var(--c-ink-2)', lineHeight: 1.7 }}>
                            <strong>{ru ? 'Где взять API credentials?' : 'Where to get API credentials?'}</strong><br />
                            {ru
                                ? <><a href="https://my.telegram.org/auth" target="_blank" rel="noopener noreferrer" style={{ color: 'var(--c-accent)' }}>my.telegram.org/auth</a> → «API development tools». Скопируйте <code>App api_id</code> и <code>App api_hash</code>.</>
                                : <><a href="https://my.telegram.org/auth" target="_blank" rel="noopener noreferrer" style={{ color: 'var(--c-accent)' }}>my.telegram.org/auth</a> → «API development tools». Copy <code>App api_id</code> and <code>App api_hash</code>.</>
                            }
                        </div>
                        <input className={s.formInput} placeholder={ru ? 'Номер телефона (+79001234567)' : 'Phone number (+79001234567)'} value={phone} onChange={e => setPhone(e.target.value)} />
                        <input className={s.formInput} placeholder="App api_id" value={apiID} onChange={e => setApiID(e.target.value)} />
                        <input className={s.formInput} placeholder="App api_hash" value={apiHash} onChange={e => setApiHash(e.target.value)} />
                        {regError && <div className={s.formError}>{regError}</div>}
                        <div style={{ display: 'flex', gap: 8 }}>
                            <button className={s.grantBtn} onClick={handleSendPhone} disabled={regLoading || !phone.trim()}>
                                {regLoading ? (ru ? 'Отправляем...' : 'Sending...') : (ru ? 'Отправить код' : 'Send code')}
                            </button>
                            <button className={s.actionBtn} onClick={resetWizard}>{ru ? 'Отмена' : 'Cancel'}</button>
                        </div>
                    </div>
                )}
                {step === 'code' && (
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 10, maxWidth: 420 }}>
                        <div style={{ background: 'rgba(16,185,129,.08)', border: '1px solid rgba(16,185,129,.2)', borderRadius: 8, padding: '10px 14px', fontSize: 12, color: 'var(--c-ink-2)' }}>
                            {ru ? `📱 Код отправлен в Telegram на номер ${phone}.` : `📱 Code sent to Telegram on ${phone}.`}
                        </div>
                        <input className={s.formInput} placeholder={ru ? 'Код из Telegram (12345)' : 'Telegram code (12345)'} value={code} onChange={e => setCode(e.target.value)} />
                        <input className={s.formInput} type="password" placeholder={ru ? '2FA пароль (если есть)' : '2FA password (if set)'} value={password} onChange={e => setPassword(e.target.value)} />
                        {regError && <div className={s.formError}>{regError}</div>}
                        <div style={{ display: 'flex', gap: 8 }}>
                            <button className={s.grantBtn} onClick={handleConfirmCode} disabled={regLoading || !code.trim()}>
                                {regLoading ? (ru ? 'Подтверждаем...' : 'Confirming...') : (ru ? 'Подтвердить' : 'Confirm')}
                            </button>
                            <button className={s.actionBtn} onClick={resetWizard}>{ru ? 'Отмена' : 'Cancel'}</button>
                        </div>
                    </div>
                )}
                {step === 'done' && (
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                        <div style={{ background: 'rgba(16,185,129,.08)', border: '1px solid rgba(16,185,129,.2)', borderRadius: 8, padding: '10px 14px', fontSize: 13, color: '#10b981' }}>
                            {regSuccess}
                        </div>
                        <button className={s.actionBtn} onClick={resetWizard} style={{ width: 'fit-content' }}>
                            {ru ? 'Добавить ещё' : 'Add another'}
                        </button>
                    </div>
                )}
            </div>

            {(stats?.perSession?.length ?? 0) > 0 && (
                <>
                    <h3 className={s.subCardTitle} style={{ marginTop: 24 }}>{ru ? 'Аккаунты в пуле' : 'Pool accounts'}</h3>
                    {deleteError && <div className={s.formError} style={{ marginBottom: 8 }}>{deleteError}</div>}
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
                                            <span style={{ color: sess.online ? '#10b981' : 'var(--c-ink-3)', fontSize: 12, fontWeight: 600 }}>
                                                {sess.online ? (ru ? 'в сети' : 'Online') : (ru ? 'не в сети' : 'Offline')}
                                            </span>
                                    </td>
                                    <td>
                                        <button onClick={() => handleDeleteSession(sess.sessionId, sess.phone)} disabled={deletingSession === sess.sessionId}
                                                style={{ padding: '4px 10px', fontSize: 11, fontWeight: 600, borderRadius: 6, border: '1px solid rgba(239,68,68,.3)', background: 'rgba(239,68,68,.06)', color: '#ef4444', cursor: deletingSession === sess.sessionId ? 'default' : 'pointer', fontFamily: 'var(--font-body)', transition: 'all .15s', opacity: deletingSession === sess.sessionId ? .5 : 1 }}>
                                            {deletingSession === sess.sessionId ? '...' : (ru ? 'Удалить' : 'Delete')}
                                        </button>
                                    </td>
                                </tr>
                            ))}
                            </tbody>
                        </table>
                    </div>
                </>
            )}

            {users.length > 0 && (
                <>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginTop: 24, flexWrap: 'wrap' }}>
                        <h3 className={s.subCardTitle} style={{ margin: 0 }}>
                            {ru ? 'Активные пользователи' : 'Active users'}
                            <span className={s.count} style={{ marginLeft: 8 }}>{filteredUsers.length}</span>
                        </h3>
                        <input
                            placeholder={ru ? 'Поиск по email или ID...' : 'Search by email or ID...'}
                            value={userSearch} onChange={e => setUserSearch(e.target.value)}
                            style={{ flex: 1, maxWidth: 260, padding: '6px 10px', fontSize: 12, borderRadius: 7, border: '1px solid var(--c-border)', background: 'var(--c-surface)', color: 'var(--c-ink)', fontFamily: 'var(--font-body)', outline: 'none' }}
                        />
                        {userSearch.trim() && (
                            <button onClick={() => setUserSearch('')} style={{ fontSize: 11, padding: '4px 8px', borderRadius: 6, border: '1px solid var(--c-border)', background: 'none', color: 'var(--c-ink-3)', cursor: 'pointer', fontFamily: 'var(--font-body)' }}>
                                {ru ? 'Сбросить' : 'Clear'}
                            </button>
                        )}
                    </div>
                    <div className={s.tableWrapper}>
                        <table className={s.usersTable}>
                            <thead><tr>
                                <th>ID</th><th>Email</th>
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
                                            <button className={s.actionBtn}
                                                    style={{ background: expandedUser === u.userId ? 'var(--c-accent-soft)' : 'var(--c-bg)', color: expandedUser === u.userId ? 'var(--c-accent)' : 'var(--c-ink-2)', border: '1px solid var(--c-border)' }}
                                                    onClick={() => setExpandedUser(expandedUser === u.userId ? null : u.userId)}>
                                                {expandedUser === u.userId ? (ru ? 'Скрыть' : 'Hide') : (ru ? 'Раскрыть' : 'Expand')}
                                            </button>
                                        </td>
                                    </tr>
                                    {expandedUser === u.userId && (
                                        <tr key={`${u.userId}-detail`}>
                                            <td colSpan={6} style={{ padding: '0 0 12px 0' }}>
                                                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, padding: '12px 16px', background: 'var(--c-bg)', borderRadius: 10, margin: '0 14px' }}>
                                                    <div>
                                                        <div style={{ fontSize: 11, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '.6px', color: 'var(--c-ink-3)', marginBottom: 8 }}>
                                                            {ru ? 'Чаты' : 'Chats'} ({u.chats.length})
                                                        </div>
                                                        {u.chats.length === 0
                                                            ? <span style={{ fontSize: 12, color: 'var(--c-ink-3)' }}>—</span>
                                                            : <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                                                                {u.chats.map(c => (
                                                                    <a key={c} href={c.startsWith('http') ? c : `https://t.me/${c.replace('@', '')}`} target="_blank" rel="noopener noreferrer"
                                                                       style={{ fontSize: 12, color: 'var(--c-accent)', textDecoration: 'none', fontFamily: 'monospace' }}>{c}</a>
                                                                ))}
                                                            </div>
                                                        }
                                                    </div>
                                                    <div>
                                                        <div style={{ fontSize: 11, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '.6px', color: 'var(--c-ink-3)', marginBottom: 8 }}>
                                                            {ru ? 'Ключевые слова' : 'Keywords'} ({u.keywords.length})
                                                        </div>
                                                        {u.keywords.length === 0
                                                            ? <span style={{ fontSize: 12, color: 'var(--c-ink-3)' }}>—</span>
                                                            : <div style={{ display: 'flex', flexWrap: 'wrap', gap: 5 }}>
                                                                {u.keywords.map(kw => (
                                                                    <span key={kw} style={{ fontSize: 11, background: 'var(--c-surface)', border: '1px solid var(--c-border)', borderRadius: 6, padding: '3px 8px', color: 'var(--c-ink-2)' }}>{kw}</span>
                                                                ))}
                                                            </div>
                                                        }
                                                    </div>
                                                </div>
                                            </td>
                                        </tr>
                                    )}
                                </>
                            ))}
                            {filteredUsers.length === 0 && userSearch.trim() && (
                                <tr><td colSpan={6} style={{ textAlign: 'center', padding: '20px 0', color: 'var(--c-ink-3)', fontSize: 13 }}>
                                    {ru ? 'Ничего не найдено' : 'No results'}
                                </td></tr>
                            )}
                            </tbody>
                        </table>
                    </div>
                </>
            )}

            {!isUp && (
                <div className={s.subCard} style={{ borderColor: 'rgba(239,68,68,.2)', marginTop: 20 }}>
                    <p style={{ margin: 0, fontSize: 13, color: 'var(--c-ink-3)', lineHeight: 1.7 }}>
                        {ru ? 'Go-сервис недоступен. Проверьте что userbot запущен.' : 'Go service unavailable. Make sure userbot is running.'}
                    </p>
                </div>
            )}
        </div>
    )
}

// ─── Уведомления ─────────────────────────────────────────────────────────────
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
    useEffect(() => { if (success) loadHistory() }, [success, loadHistory])

    const handleSend = async () => {
        if (!title.trim() || !body.trim()) { setError(ru ? 'Заполните заголовок и текст' : 'Fill in title and body'); return }
        setSending(true); setError(null)
        try {
            await notificationsApi.createNotification({ title, body, target: 'BOT', scheduledAt: scheduledAt || undefined })
            setSuccess(true); setTitle(''); setBody(''); setScheduledAt('')
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
                <h3 className={s.sectionTitle}>{ru ? 'Отправить в Telegram-бот' : 'Send via Telegram Bot'}</h3>
                <p className={s.notifSubtitle}>{ru ? 'Получат все пользователи с привязанным Telegram' : 'All users with linked Telegram will receive this'}</p>
                <div className={s.formGroup}>
                    <label className={s.formLabel}>{ru ? 'Заголовок' : 'Title'}</label>
                    <input className={s.formInput} value={title} onChange={e => setTitle(e.target.value)} placeholder={ru ? 'Введите заголовок' : 'Enter title'} />
                </div>
                <div className={s.formGroup}>
                    <label className={s.formLabel}>{ru ? 'Текст' : 'Body'}</label>
                    <textarea className={s.formTextarea} value={body} onChange={e => setBody(e.target.value)} rows={4} placeholder={ru ? 'Введите текст...' : 'Enter text...'} />
                </div>
                <div className={s.formGroup}>
                    <label className={s.formLabel}>{ru ? 'Дата отправки (необязательно)' : 'Schedule (optional)'}</label>
                    <input className={s.formInput} type="datetime-local" value={scheduledAt} onChange={e => setScheduledAt(e.target.value)} />
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
                                        {n.sent ? (ru ? 'Отправлено' : 'Sent') : (ru ? 'Ожидает' : 'Pending')}
                                    </span>
                                </div>
                                <div className={s.historyBody}>{n.body}</div>
                                <div className={s.historyDate}>{new Date(n.scheduledAt).toLocaleString(ru ? 'ru-RU' : 'en-US')}</div>
                            </div>
                        ))}
                    </div>
                </div>
            )}
        </div>
    )
}

// ─── Главный компонент ────────────────────────────────────────────────────────
export default function AdminPanel({ lang, setLang }: Props) {
    const { user, loading } = useAuthContext()
    const navigate = useNavigate()
    const [activeTab,    setActiveTab]    = useState<Tab>('overview')
    const [users,        setUsers]        = useState<AdminUserDto[]>([])
    const [usersLoading, setUsersLoading] = useState(false)
    const [subs,         setSubs]         = useState<SubscriptionInfo[]>([])
    const [subsLoading,  setSubsLoading]  = useState(false)
    const [grantUserId,  setGrantUserId]  = useState('')
    const [grantPlan,    setGrantPlan]    = useState('START')
    const [grantStatus,  setGrantStatus]  = useState('ACTIVE')
    const [grantDays,    setGrantDays]    = useState('30')
    const [grantLoading, setGrantLoading] = useState(false)
    const [grantMsg,     setGrantMsg]     = useState<Msg | null>(null)
    const [sidebarOpen,  setSidebarOpen]  = useState(false)

    // Модалка деталей
    const [detailUserId, setDetailUserId] = useState<number | null>(null)

    useEffect(() => {
        if (!loading && (!user || user.role !== 'ADMIN')) navigate('/')
    }, [user, loading, navigate])

    useEffect(() => {
        const onResize = () => { if (window.innerWidth > 860) setSidebarOpen(false) }
        window.addEventListener('resize', onResize)
        return () => window.removeEventListener('resize', onResize)
    }, [])

    const fetchUsers = useCallback(async () => {
        setUsersLoading(true)
        try { const data = await adminApi.getUsers(); setUsers(data) }
        catch { /* ignore */ }
        finally { setUsersLoading(false) }
    }, [])

    const fetchSubs = useCallback(async () => {
        setSubsLoading(true)
        try { const data = await adminSubsApi.list(); setSubs(data) }
        catch { /* ignore */ }
        finally { setSubsLoading(false) }
    }, [])

    const fetchAll = useCallback(async () => {
        await Promise.all([fetchUsers(), fetchSubs()])
    }, [fetchUsers, fetchSubs])

    useEffect(() => {
        if (activeTab !== 'users') return
        void fetchAll()
    }, [activeTab, fetchAll])

    if (loading || !user || user.role !== 'ADMIN') return null

    const ru = lang === 'ru'

    const handleTabChange = (tab: Tab) => {
        setActiveTab(tab)
        setSidebarOpen(false)
    }

    const handleGrant = async () => {
        const userId = Number(grantUserId)
        const days   = Number(grantDays)
        if (!userId || userId <= 0) { setGrantMsg({ text: ru ? 'Введите корректный ID' : 'Enter valid ID', ok: false }); return }
        if (!days || days <= 0)     { setGrantMsg({ text: ru ? 'Введите кол-во дней' : 'Enter valid days', ok: false }); return }
        setGrantLoading(true); setGrantMsg(null)
        try {
            await adminSubsApi.grant(userId, grantPlan, days)
            if (grantStatus === 'INACTIVE') await adminSubsApi.revoke(userId)
            setGrantUserId(''); setGrantDays('30')
            setGrantMsg({ text: ru ? 'Подписка выдана' : 'Subscription granted', ok: true })
            await fetchSubs()
        } catch (e: unknown) {
            setGrantMsg({ text: e instanceof Error ? e.message : 'Ошибка', ok: false })
        } finally {
            setGrantLoading(false)
        }
    }

    return (
        <div className={s.root}>
            {/* Детали пользователя — модалка */}
            {detailUserId !== null && (
                <UserDetailModal
                    userId={detailUserId}
                    lang={lang}
                    onClose={() => setDetailUserId(null)}
                />
            )}

            <header className={s.header}>
                <a href="/" className={s.logo}>
                    <img src="/AIMLY.png" alt="AIMLY" className={s.logoImg} />
                    <span className={s.logoText}>AIMLY</span>
                    <span className={s.adminTag}>Admin</span>
                </a>

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
                            <button key={lng} className={`${s.langBtn} ${lang === lng ? s.langActive : ''}`} onClick={() => setLang(lng)}>
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

            {sidebarOpen && <div className={s.mobileOverlay} onClick={() => setSidebarOpen(false)} />}

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
                            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 10 }}>
                                <h2 className={s.tabTitle}>
                                    {ru ? 'Пользователи' : 'Users'}
                                    <span className={s.count}>{users.length}</span>
                                </h2>
                                <button className={s.grantBtn} onClick={fetchAll} disabled={usersLoading || subsLoading} style={{ padding: '7px 16px' }}>
                                    {(usersLoading || subsLoading) ? (ru ? 'Загрузка...' : 'Loading...') : (ru ? 'Обновить' : 'Refresh')}
                                </button>
                            </div>

                            {/* Форма выдачи подписки */}
                            <div className={s.subCard}>
                                <h3 className={s.subCardTitle}>{ru ? 'Выдать / продлить подписку' : 'Grant / Extend Subscription'}</h3>
                                <div className={s.formRow}>
                                    <input className={s.formInput} type="number" placeholder={ru ? 'ID пользователя' : 'User ID'} value={grantUserId} onChange={e => setGrantUserId(e.target.value)} style={{ width: 120 }} />
                                    <select className={s.formSelect} value={grantPlan} onChange={e => setGrantPlan(e.target.value)} style={{ width: 120 }}>
                                        {PLANS.map(p => <option key={p} value={p}>{p}</option>)}
                                    </select>
                                    <select className={s.formSelect} value={grantStatus} onChange={e => setGrantStatus(e.target.value)} style={{ width: 110 }}>
                                        {STATUSES.map(st => <option key={st} value={st}>{st}</option>)}
                                    </select>
                                    <input className={s.formInput} type="number" placeholder={ru ? 'Дней' : 'Days'} value={grantDays} onChange={e => setGrantDays(e.target.value)} style={{ width: 80 }} />
                                    <button className={s.grantBtn} onClick={handleGrant} disabled={grantLoading}>
                                        {grantLoading ? '...' : ru ? 'Выдать' : 'Grant'}
                                    </button>
                                </div>
                                {grantMsg && <div className={grantMsg.ok ? s.formSuccess : s.formError}>{grantMsg.text}</div>}
                            </div>

                            {usersLoading && users.length === 0 ? (
                                <div className={s.loading}>...</div>
                            ) : (
                                <UsersTable
                                    users={users}
                                    subs={subs}
                                    lang={lang}
                                    currentUserId={user.id}
                                    onRoleChange={(id, role) => setUsers(prev => prev.map(u => u.id === id ? { ...u, role } : u))}
                                    onSubChange={fetchSubs}
                                    onOpenDetails={setDetailUserId}
                                />
                            )}
                        </div>
                    )}

                    {activeTab === 'leads' && <LeadsTab lang={lang} />}
                    {activeTab === 'userbot' && <UserbotTab lang={lang} />}
                    {activeTab === 'notifications' && <NotificationsTab lang={lang} />}
                </main>
            </div>
        </div>
    )
}