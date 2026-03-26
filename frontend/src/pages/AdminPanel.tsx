import { useState, useEffect, useCallback, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import type { Lang } from '../i18n/translations'
import SubscriptionsTab from '../dashboard/SubscriptionsTab'
import {
    adminApi, notificationsApi,
    type AdminUserDto, type NotificationDto,
} from '../api/auth'
import { adminSubsApi, type SubscriptionInfo } from '../api/leads'
import { useAuthContext } from '../context/AuthContext'
import s from './AdminPanel.module.css'

const BASE: string = import.meta.env.VITE_API_URL || ''

interface Props { lang: Lang; setLang: (l: Lang) => void }

// ── Убрана вкладка 'subscriptions', её функционал перенесён в 'users' ────────
type Tab = 'overview' | 'users' | 'leads_all' | 'userbot' | 'notifications'

const TABS: { id: Tab; label: { ru: string; en: string } }[] = [
    { id: 'overview',       label: { ru: 'Обзор',           en: 'Overview'       } },
    { id: 'users',          label: { ru: 'Пользователи',    en: 'Users'          } },
    { id: 'leads_all',      label: { ru: 'Лиды',            en: 'All Leads'      } },
    { id: 'userbot',        label: { ru: 'Userbot',         en: 'Userbot'        } },
    { id: 'notifications',  label: { ru: 'Уведомления',     en: 'Notifications'  } },
]

// ─── Типы ─────────────────────────────────────────────────────────────────────

interface AdminUserDetailDto {
    id: number; email: string; firstName: string | null
    telegramId: number | null; telegramUsername: string | null
    emailVerified: boolean; isActive: boolean; role: string; createdAt: string | null
    subscriptionStatus: string | null; subscriptionPlan: string | null
    subscriptionExpiresAt: string | null; bonusDaysBuffer: number; trialUsed: boolean
    leadsCount: number; newLeadsCount: number; chatCount: number; keywordCount: number
    totalReferrals: number; paidReferrals: number
    businessContext: string | null
    chats: { id: number; chatLink: string; chatTitle: string; isActive: boolean; createdAt: string | null }[]
    keywords: { id: number; keyword: string; isActive: boolean }[]
    recentLeads: AdminLeadDto[]
}

interface AdminLeadDto {
    id: number; chatTitle: string; chatLink: string
    authorName: string; authorUsername: string
    messageText: string; messageLink: string; matchedKeyword: string
    status: string; foundAt: string; aiValid: boolean | null; aiReason: string | null
    userId: number | null; userEmail: string | null; userFirstName: string | null
}

interface AdminLeadsPageDto {
    content: AdminLeadDto[]; totalElements: number; totalPages: number; page: number; size: number
}

interface UserbotSessionStats { sessionId: number; phone: string; chatCount: number; leadsCount: number; online: boolean }
interface UserbotStats { status: string; sessions: number; totalChats: number; totalUsers: number; totalLeads?: number; perSession?: UserbotSessionStats[] }
interface UserbotUserInfo { userId: number; email: string; leadsCount: number; chats: string[]; keywords: string[] }

type SortKey = 'id' | 'firstName' | 'email' | 'role' | 'leadsCount' | 'createdAt' | 'chatCount' | 'keywordCount' | 'subscriptionStatus'
type SortDir = 'asc' | 'desc'

function sortUsers(users: AdminUserDto[], key: SortKey, dir: SortDir): AdminUserDto[] {
    return [...users].sort((a, b) => {
        let av: string | number = ''
        let bv: string | number = ''
        if (key === 'createdAt') {
            av = a.createdAt ? new Date(a.createdAt).getTime() : 0
            bv = b.createdAt ? new Date(b.createdAt).getTime() : 0
        } else {
            av = ((a as Record<string, unknown>)[key] ?? '') as string | number
            bv = ((b as Record<string, unknown>)[key] ?? '') as string | number
        }
        if (typeof av === 'string') av = av.toLowerCase()
        if (typeof bv === 'string') bv = bv.toLowerCase()
        if (av < bv) return dir === 'asc' ? -1 : 1
        if (av > bv) return dir === 'asc' ? 1 : -1
        return 0
    })
}

// ─── Детальная карточка пользователя ─────────────────────────────────────────

function UserDetailModal({ userId, lang, onClose }: { userId: number; lang: Lang; onClose: () => void }) {
    const ru = lang === 'ru'
    const [detail, setDetail] = useState<AdminUserDetailDto | null>(null)
    const [loading, setLoading] = useState(true)
    const [error, setError]     = useState('')
    const [activeTab, setActiveTab] = useState<'info' | 'leads' | 'chats' | 'keywords'>('info')

    useEffect(() => {
        setLoading(true)
        fetch(`${BASE}/api/v1/admin/users/${userId}/detail`, { credentials: 'include' })
            .then(r => r.json())
            .then(d => { setDetail(d); setLoading(false) })
            .catch(e => { setError(e.message); setLoading(false) })
    }, [userId])

    const subColor = (st: string | null) => {
        if (st === 'ACTIVE') return '#10b981'
        if (st === 'TRIAL')  return '#f59e0b'
        return '#6b7280'
    }

    return (
        <div className={s.modalOverlay} onClick={onClose}>
            <div className={s.modalPanel} onClick={e => e.stopPropagation()}>
                <div className={s.modalHeader}>
                    <div>
                        <h2 className={s.modalTitle}>
                            {loading ? '...' : detail ? (detail.firstName || detail.email) : `User #${userId}`}
                        </h2>
                        {detail && <div className={s.modalSubtitle}>{detail.email} · ID {detail.id}</div>}
                    </div>
                    <button className={s.modalClose} onClick={onClose}>✕</button>
                </div>

                {loading && <div className={s.loading}>{ru ? 'Загрузка...' : 'Loading...'}</div>}
                {error   && <div className={s.formError}>{error}</div>}

                {detail && (
                    <>
                        {/* Мини-tabs внутри модала */}
                        <div className={s.modalTabs}>
                            {(['info', 'leads', 'chats', 'keywords'] as const).map(t => (
                                <button key={t}
                                        className={`${s.modalTab} ${activeTab === t ? s.modalTabActive : ''}`}
                                        onClick={() => setActiveTab(t)}>
                                    {t === 'info'     && (ru ? 'Профиль' : 'Profile')}
                                    {t === 'leads'    && `${ru ? 'Лиды' : 'Leads'} (${detail.leadsCount})`}
                                    {t === 'chats'    && `${ru ? 'Чаты' : 'Chats'} (${detail.chatCount})`}
                                    {t === 'keywords' && `${ru ? 'Ключевые слова' : 'Keywords'} (${detail.keywordCount})`}
                                </button>
                            ))}
                        </div>

                        {/* Профиль */}
                        {activeTab === 'info' && (
                            <div className={s.modalBody}>
                                <div className={s.detailGrid}>
                                    <InfoRow label="ID"            value={String(detail.id)} mono />
                                    <InfoRow label="Email"         value={detail.email} />
                                    <InfoRow label={ru ? 'Имя' : 'Name'} value={detail.firstName || '—'} />
                                    <InfoRow label="Telegram"
                                             value={detail.telegramId
                                                 ? `✅ ${detail.telegramUsername ? '@' + detail.telegramUsername : detail.telegramId}`
                                                 : '❌ ' + (ru ? 'не привязан' : 'not linked')} />
                                    <InfoRow label={ru ? 'Email верифицирован' : 'Email verified'}
                                             value={detail.emailVerified ? '✅' : '❌'} />
                                    <InfoRow label={ru ? 'Статус аккаунта' : 'Account status'}
                                             value={detail.isActive ? (ru ? '✅ Активен' : '✅ Active') : (ru ? '❌ Заблокирован' : '❌ Blocked')} />
                                    <InfoRow label={ru ? 'Роль' : 'Role'} value={detail.role} />
                                    <InfoRow label={ru ? 'Создан' : 'Created'}
                                             value={detail.createdAt ? new Date(detail.createdAt).toLocaleString(ru ? 'ru-RU' : 'en-US') : '—'} />
                                    <div className={s.detailDivider} />
                                    <InfoRow label={ru ? 'Подписка' : 'Subscription'}
                                             value={<span style={{ color: subColor(detail.subscriptionStatus), fontWeight: 600 }}>
                                            {detail.subscriptionStatus || '—'}
                                                 {detail.subscriptionPlan ? ` / ${detail.subscriptionPlan}` : ''}
                                        </span>} />
                                    <InfoRow label={ru ? 'Истекает' : 'Expires'}
                                             value={detail.subscriptionExpiresAt
                                                 ? new Date(detail.subscriptionExpiresAt).toLocaleDateString(ru ? 'ru-RU' : 'en-US')
                                                 : '—'} />
                                    <InfoRow label={ru ? 'Бонусных дней' : 'Bonus days'} value={String(detail.bonusDaysBuffer)} />
                                    <InfoRow label={ru ? 'Trial использован' : 'Trial used'} value={detail.trialUsed ? '✅' : '❌'} />
                                    <div className={s.detailDivider} />
                                    <InfoRow label={ru ? 'Всего лидов' : 'Total leads'} value={String(detail.leadsCount)} />
                                    <InfoRow label={ru ? 'Новых лидов' : 'New leads'} value={String(detail.newLeadsCount)} />
                                    <InfoRow label={ru ? 'Чатов' : 'Chats'} value={String(detail.chatCount)} />
                                    <InfoRow label={ru ? 'Ключевых слов' : 'Keywords'} value={String(detail.keywordCount)} />
                                    <div className={s.detailDivider} />
                                    <InfoRow label={ru ? 'Реф. переходов' : 'Total referrals'} value={String(detail.totalReferrals)} />
                                    <InfoRow label={ru ? 'Реф. оплатили' : 'Paid referrals'} value={String(detail.paidReferrals)} />
                                    {detail.businessContext && (
                                        <>
                                            <div className={s.detailDivider} />
                                            <div className={s.detailFullRow}>
                                                <span className={s.detailLabel}>{ru ? 'AI-контекст' : 'AI context'}</span>
                                                <div className={s.detailContextBox}>{detail.businessContext}</div>
                                            </div>
                                        </>
                                    )}
                                </div>
                            </div>
                        )}

                        {/* Лиды */}
                        {activeTab === 'leads' && (
                            <div className={s.modalBody}>
                                {detail.recentLeads.length === 0 ? (
                                    <div className={s.emptyState}>{ru ? 'Нет лидов' : 'No leads'}</div>
                                ) : (
                                    <>
                                        <div className={s.leadsNote}>
                                            {ru ? `Последние ${detail.recentLeads.length} лидов` : `Last ${detail.recentLeads.length} leads`}
                                        </div>
                                        {detail.recentLeads.map(l => (
                                            <div key={l.id} className={s.leadCard}>
                                                <div className={s.leadCardHeader}>
                                                    <span className={s.leadKeyword}>{l.matchedKeyword}</span>
                                                    <span className={s.leadChat}>{l.chatTitle || l.chatLink}</span>
                                                    <span className={`${s.leadStatus} ${s[`leadStatus${l.status}`] || ''}`}>{l.status}</span>
                                                    {l.aiValid !== null && (
                                                        <span style={{ fontSize: 11, color: l.aiValid ? '#10b981' : '#ef4444' }}>
                                                            {l.aiValid ? '✓ AI' : '✗ AI'}
                                                        </span>
                                                    )}
                                                    <span className={s.leadDate}>
                                                        {new Date(l.foundAt).toLocaleString(ru ? 'ru-RU' : 'en-US', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' })}
                                                    </span>
                                                </div>
                                                <div className={s.leadAuthor}>
                                                    {l.authorName}{l.authorUsername ? ` (@${l.authorUsername})` : ''}
                                                </div>
                                                <div className={s.leadText}>{l.messageText.slice(0, 300)}{l.messageText.length > 300 ? '…' : ''}</div>
                                                {l.messageLink && (
                                                    <a href={l.messageLink} target="_blank" rel="noreferrer" className={s.leadLink}>
                                                        {ru ? '🔗 Открыть сообщение' : '🔗 Open message'}
                                                    </a>
                                                )}
                                            </div>
                                        ))}
                                    </>
                                )}
                            </div>
                        )}

                        {/* Чаты */}
                        {activeTab === 'chats' && (
                            <div className={s.modalBody}>
                                {detail.chats.length === 0 ? (
                                    <div className={s.emptyState}>{ru ? 'Нет подписок на чаты' : 'No chat subscriptions'}</div>
                                ) : (
                                    <div className={s.chipList}>
                                        {detail.chats.map(c => (
                                            <div key={c.id} className={s.chipItem}>
                                                <span className={s.chipTitle}>{c.chatTitle || c.chatLink}</span>
                                                <a href={c.chatLink} target="_blank" rel="noreferrer" className={s.chipLink}>↗</a>
                                            </div>
                                        ))}
                                    </div>
                                )}
                            </div>
                        )}

                        {/* Ключевые слова */}
                        {activeTab === 'keywords' && (
                            <div className={s.modalBody}>
                                {detail.keywords.length === 0 ? (
                                    <div className={s.emptyState}>{ru ? 'Нет ключевых слов' : 'No keywords'}</div>
                                ) : (
                                    <div className={s.kwWrap}>
                                        {detail.keywords.map(k => (
                                            <span key={k.id} className={s.kwBadge}>{k.keyword}</span>
                                        ))}
                                    </div>
                                )}
                            </div>
                        )}
                    </>
                )}
            </div>
        </div>
    )
}

function InfoRow({ label, value, mono }: { label: string; value: React.ReactNode; mono?: boolean }) {
    return (
        <div className={s.detailRow}>
            <span className={s.detailLabel}>{label}</span>
            <span className={`${s.detailValue} ${mono ? s.detailMono : ''}`}>{value}</span>
        </div>
    )
}

// ─── Улучшенная таблица пользователей + встроенные подписки ──────────────────

const PLANS    = ['START', 'BUSINESS', 'TRIAL']
const STATUSES = ['ACTIVE', 'TRIAL', 'INACTIVE']

function UsersTab({
                      users, lang, currentUserId, onRoleChange, subs, subsLoading, onSubsReload,
                  }: {
    users:         AdminUserDto[]
    lang:          Lang
    currentUserId: number
    onRoleChange:  (id: number, role: string) => void
    subs:          SubscriptionInfo[]
    subsLoading:   boolean
    onSubsReload:  () => void
}) {
    const ru = lang === 'ru'

    // ── Сортировка — по умолчанию новые аккаунты первыми ───────────────────
    const [sortKey, setSortKey] = useState<SortKey>('createdAt')
    const [sortDir, setSortDir] = useState<SortDir>('desc')

    // ── Фильтры ─────────────────────────────────────────────────────────────
    const [search,       setSearch]       = useState('')
    const [filterSub,    setFilterSub]    = useState('all')
    const [filterVerify, setFilterVerify] = useState('all')
    const [filterTg,     setFilterTg]     = useState('all')

    // ── Изменение роли ───────────────────────────────────────────────────────
    const [changing, setChanging] = useState<number | null>(null)

    // ── Детальная карточка ───────────────────────────────────────────────────
    const [selectedUserId, setSelectedUserId] = useState<number | null>(null)

    // ── Форма выдачи подписки ────────────────────────────────────────────────
    const [grantUserId,  setGrantUserId]  = useState('')
    const [grantPlan,    setGrantPlan]    = useState('START')
    const [grantDays,    setGrantDays]    = useState('30')
    const [grantLoading, setGrantLoading] = useState(false)
    const [grantMsg,     setGrantMsg]     = useState<{ text: string; ok: boolean } | null>(null)

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

    const filtered = sortUsers(
        users.filter(u => {
            const q = search.trim().toLowerCase()
            const matchSearch = !q
                || u.email.toLowerCase().includes(q)
                || String(u.id).includes(q)
                || (u.firstName || '').toLowerCase().includes(q)
                || (u.telegramUsername || '').toLowerCase().includes(q)
            const matchSub    = filterSub    === 'all' || u.subscriptionStatus === filterSub || (filterSub === 'NONE' && !u.subscriptionStatus)
            const matchVerify = filterVerify === 'all' || (filterVerify === 'yes' ? u.emailVerified : !u.emailVerified)
            const matchTg     = filterTg     === 'all' || (filterTg === 'yes' ? !!u.telegramId : !u.telegramId)
            return matchSearch && matchSub && matchVerify && matchTg
        }),
        sortKey, sortDir,
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
        } finally { setChanging(null) }
    }

    const handleGrant = async () => {
        const uid  = Number(grantUserId)
        const days = Number(grantDays)
        if (!uid || uid <= 0)   { setGrantMsg({ text: ru ? 'Введите корректный ID' : 'Enter valid ID', ok: false }); return }
        if (!days || days <= 0) { setGrantMsg({ text: ru ? 'Введите кол-во дней' : 'Enter valid days', ok: false }); return }
        setGrantLoading(true); setGrantMsg(null)
        try {
            await adminSubsApi.grant(uid, grantPlan, days)
            setGrantUserId(''); setGrantDays('30')
            setGrantMsg({ text: ru ? 'Подписка выдана' : 'Subscription granted', ok: true })
            onSubsReload()
        } catch (e: unknown) {
            setGrantMsg({ text: e instanceof Error ? e.message : 'Ошибка', ok: false })
        } finally { setGrantLoading(false) }
    }

    const handleRevoke = async (userId: number) => {
        if (!confirm(ru ? 'Отозвать подписку?' : 'Revoke subscription?')) return
        try { await adminSubsApi.revoke(userId); onSubsReload() }
        catch (e: unknown) { alert(e instanceof Error ? e.message : 'Ошибка') }
    }

    const subColor = (st: string | null) => {
        if (st === 'ACTIVE') return '#10b981'
        if (st === 'TRIAL')  return '#f59e0b'
        return '#6b7280'
    }

    const inputSt: React.CSSProperties = {
        height: 32, padding: '0 10px', fontSize: 12,
        border: '1px solid var(--c-border)', borderRadius: 6,
        background: 'var(--c-bg)', color: 'var(--c-ink)',
        fontFamily: 'inherit', outline: 'none',
    }

    return (
        <div className={s.subsTab}>
            {selectedUserId !== null && (
                <UserDetailModal userId={selectedUserId} lang={lang} onClose={() => setSelectedUserId(null)} />
            )}

            <h2 className={s.tabTitle}>
                {ru ? 'Пользователи' : 'Users'}
                {<span className={s.count}>{filtered.length}{filtered.length !== users.length && ` / ${users.length}`}</span>}
            </h2>

            {/* ── Блок выдачи подписки (перенесён из вкладки Подписки) ─────── */}
            <div className={s.subCard}>
                <h3 className={s.subCardTitle}>{ru ? 'Выдать / продлить подписку' : 'Grant / Extend Subscription'}</h3>
                <div className={s.formRow}>
                    <input
                        className={s.formInput} type="number"
                        placeholder={ru ? 'ID пользователя' : 'User ID'}
                        value={grantUserId} onChange={e => setGrantUserId(e.target.value)}
                        style={{ width: 130 }}
                    />
                    <select className={s.formSelect} value={grantPlan} onChange={e => setGrantPlan(e.target.value)} style={{ width: 120 }}>
                        {PLANS.map(p => <option key={p} value={p}>{p}</option>)}
                    </select>
                    <input
                        className={s.formInput} type="number"
                        placeholder={ru ? 'Дней' : 'Days'}
                        value={grantDays} onChange={e => setGrantDays(e.target.value)}
                        style={{ width: 80 }}
                    />
                    <button className={s.grantBtn} onClick={handleGrant} disabled={grantLoading}>
                        {grantLoading ? '...' : ru ? 'Выдать' : 'Grant'}
                    </button>
                </div>
                {grantMsg && <div className={grantMsg.ok ? s.formSuccess : s.formError}>{grantMsg.text}</div>}
            </div>

            {/* ── Фильтры ──────────────────────────────────────────────────── */}
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
                <input
                    style={{ ...inputSt, flex: 1, minWidth: 160, maxWidth: 260 }}
                    placeholder={ru ? 'Поиск по email, ID, имени, TG...' : 'Search by email, ID, name, TG...'}
                    value={search} onChange={e => setSearch(e.target.value)}
                />
                <select style={inputSt} value={filterSub} onChange={e => setFilterSub(e.target.value)}>
                    <option value="all">{ru ? 'Все подписки' : 'All subscriptions'}</option>
                    <option value="ACTIVE">ACTIVE</option>
                    <option value="TRIAL">TRIAL</option>
                    <option value="NONE">{ru ? 'Нет подписки' : 'No subscription'}</option>
                </select>
                <select style={inputSt} value={filterTg} onChange={e => setFilterTg(e.target.value)}>
                    <option value="all">{ru ? 'Любой TG' : 'Any TG'}</option>
                    <option value="yes">{ru ? 'TG привязан' : 'TG linked'}</option>
                    <option value="no">{ru ? 'TG нет' : 'No TG'}</option>
                </select>
                <select style={inputSt} value={filterVerify} onChange={e => setFilterVerify(e.target.value)}>
                    <option value="all">{ru ? 'Любой email' : 'Any email'}</option>
                    <option value="yes">{ru ? 'Email верифицирован' : 'Email verified'}</option>
                    <option value="no">{ru ? 'Не верифицирован' : 'Not verified'}</option>
                </select>
                {(search || filterSub !== 'all' || filterTg !== 'all' || filterVerify !== 'all') && (
                    <button
                        onClick={() => { setSearch(''); setFilterSub('all'); setFilterTg('all'); setFilterVerify('all') }}
                        style={{ ...inputSt, cursor: 'pointer', color: 'var(--c-ink-3)', padding: '0 10px', whiteSpace: 'nowrap' }}>
                        {ru ? '✕ Сбросить' : '✕ Clear'}
                    </button>
                )}
            </div>

            {/* ── Таблица ──────────────────────────────────────────────────── */}
            <div className={s.tableWrapper}>
                <table className={s.usersTable}>
                    <thead>
                    <tr>
                        <Th col="id"                 label="ID" />
                        <Th col="firstName"          label={ru ? 'Имя / Email'    : 'Name / Email'} />
                        <Th col="subscriptionStatus" label={ru ? 'Подписка'       : 'Subscription'} />
                        <Th col="leadsCount"         label={ru ? 'Лиды'           : 'Leads'} />
                        <Th col="chatCount"          label={ru ? 'Чаты'           : 'Chats'} />
                        <Th col="keywordCount"       label={ru ? 'KW'             : 'KW'} />
                        <th>{ru ? 'Telegram'         : 'Telegram'}</th>
                        <Th col="createdAt"          label={ru ? 'Создан'         : 'Created'} />
                        <Th col="role"               label={ru ? 'Роль'           : 'Role'} />
                        <th>{ru ? 'Действия'         : 'Actions'}</th>
                    </tr>
                    </thead>
                    <tbody>
                    {filtered.map(u => {
                        const sub = subs.find(s => s.userId === u.id)
                        return (
                            <tr
                                key={u.id}
                                className={`${!u.isActive ? s.rowInactive : ''} ${s.rowClickable}`}
                                onClick={() => setSelectedUserId(u.id)}
                            >
                                <td className={s.cellId}>{u.id}</td>
                                <td>
                                    <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--c-ink)' }}>
                                        {u.firstName || <span style={{ color: 'var(--c-ink-3)' }}>—</span>}
                                    </div>
                                    <div style={{ fontSize: 11, color: 'var(--c-ink-3)', marginTop: 1 }}>{u.email}</div>
                                    {!u.emailVerified && (
                                        <div style={{ fontSize: 10, color: '#f59e0b', marginTop: 2 }}>
                                            {ru ? '⚠️ email не верифицирован' : '⚠️ email unverified'}
                                        </div>
                                    )}
                                </td>
                                <td>
                                    {u.subscriptionStatus ? (
                                        <div>
                                            <span style={{ color: subColor(u.subscriptionStatus), fontWeight: 600, fontSize: 12 }}>
                                                {u.subscriptionStatus}
                                            </span>
                                            {u.subscriptionPlan && (
                                                <span style={{ fontSize: 11, color: 'var(--c-ink-3)', marginLeft: 4 }}>
                                                    {u.subscriptionPlan}
                                                </span>
                                            )}
                                            {u.subscriptionExpiresAt && (
                                                <div style={{ fontSize: 10, color: 'var(--c-ink-3)', marginTop: 2 }}>
                                                    {new Date(u.subscriptionExpiresAt).toLocaleDateString(ru ? 'ru-RU' : 'en-US', { day: 'numeric', month: 'short' })}
                                                </div>
                                            )}
                                            {u.bonusDaysBuffer > 0 && (
                                                <div style={{ fontSize: 10, color: '#f59e0b', marginTop: 2 }}>+{u.bonusDaysBuffer}д бонус</div>
                                            )}
                                        </div>
                                    ) : (
                                        <span className={s.badgeNone}>—</span>
                                    )}
                                </td>
                                <td className={s.cellNum}>
                                    <span style={{ fontWeight: u.leadsCount > 0 ? 600 : 400 }}>{u.leadsCount}</span>
                                </td>
                                <td className={s.cellNum}>{u.chatCount}</td>
                                <td className={s.cellNum}>{u.keywordCount}</td>
                                <td>
                                    {u.telegramId
                                        ? <span className={s.badgeTg}>✓ {u.telegramUsername ? `@${u.telegramUsername}` : u.telegramId}</span>
                                        : <span className={s.badgeNone}>—</span>
                                    }
                                </td>
                                <td className={s.cellDate}>
                                    {u.createdAt ? new Date(u.createdAt).toLocaleDateString(ru ? 'ru-RU' : 'en-US') : '—'}
                                </td>
                                <td>
                                    <span className={u.role === 'ADMIN' ? s.badgeAdmin : s.badgeUser}>{u.role}</span>
                                </td>
                                <td onClick={e => e.stopPropagation()}>
                                    <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }}>
                                        <button className={s.actionBtn} onClick={() => handleToggleRole(u)} disabled={changing === u.id}>
                                            {changing === u.id ? '...' : u.role === 'ADMIN'
                                                ? (ru ? 'Снять ADMIN' : '↓ ADMIN')
                                                : (ru ? 'ADMIN' : '↑ ADMIN')}
                                        </button>
                                        {u.subscriptionStatus && (
                                            <button
                                                className={s.actionBtn}
                                                style={{ color: '#ef4444', borderColor: 'rgba(239,68,68,.3)', background: 'rgba(239,68,68,.06)' }}
                                                onClick={() => handleRevoke(u.id)}>
                                                {ru ? 'Откл.' : 'Revoke'}
                                            </button>
                                        )}
                                    </div>
                                </td>
                            </tr>
                        )
                    })}
                    </tbody>
                </table>
            </div>
        </div>
    )
}

// ─── Вкладка всех лидов ───────────────────────────────────────────────────────

function AllLeadsTab({ lang }: { lang: Lang }) {
    const ru = lang === 'ru'

    const [leads,   setLeads]   = useState<AdminLeadDto[]>([])
    const [total,   setTotal]   = useState(0)
    const [pages,   setPages]   = useState(0)
    const [page,    setPage]    = useState(0)
    const [loading, setLoading] = useState(true)
    const [error,   setError]   = useState('')

    const [filterUser,    setFilterUser]    = useState('')
    const [filterStatus,  setFilterStatus]  = useState('')
    const [filterKeyword, setFilterKeyword] = useState('')

    const PAGE_SIZE = 50

    const load = useCallback(async (pg = 0) => {
        setLoading(true); setError('')
        const params = new URLSearchParams({ page: String(pg), size: String(PAGE_SIZE) })
        if (filterUser.trim())    params.set('userId',  filterUser.trim())
        if (filterStatus)         params.set('status',  filterStatus)
        if (filterKeyword.trim()) params.set('keyword', filterKeyword.trim())
        try {
            const res  = await fetch(`${BASE}/api/v1/admin/leads?${params}`, { credentials: 'include' })
            const data = await res.json() as AdminLeadsPageDto
            setLeads(data.content)
            setTotal(data.totalElements)
            setPages(data.totalPages)
            setPage(pg)
        } catch (e: unknown) {
            setError(e instanceof Error ? e.message : 'Ошибка')
        } finally { setLoading(false) }
    }, [filterUser, filterStatus, filterKeyword])

    useEffect(() => { load(0) }, [load])

    const statusColor = (st: string) => {
        if (st === 'NEW')     return '#3b82f6'
        if (st === 'VIEWED')  return '#10b981'
        if (st === 'REPLIED') return '#8b5cf6'
        if (st === 'IGNORED') return '#6b7280'
        return 'var(--c-ink-3)'
    }

    const inputSt: React.CSSProperties = {
        height: 32, padding: '0 10px', fontSize: 12,
        border: '1px solid var(--c-border)', borderRadius: 6,
        background: 'var(--c-bg)', color: 'var(--c-ink)',
        fontFamily: 'inherit', outline: 'none',
    }

    return (
        <div className={s.subsTab}>
            <h2 className={s.tabTitle}>
                {ru ? 'Лиды пользователей' : 'User Leads'}
                <span className={s.count}>{total.toLocaleString()}</span>
            </h2>

            {/* ── Фильтры ── */}
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
                <input
                    style={{ ...inputSt, width: 100 }}
                    placeholder={ru ? 'User ID' : 'User ID'}
                    value={filterUser} onChange={e => setFilterUser(e.target.value)}
                    type="number"
                />
                <input
                    style={{ ...inputSt, flex: 1, minWidth: 140, maxWidth: 220 }}
                    placeholder={ru ? 'Ключевое слово...' : 'Keyword...'}
                    value={filterKeyword} onChange={e => setFilterKeyword(e.target.value)}
                />
                <select style={inputSt} value={filterStatus} onChange={e => setFilterStatus(e.target.value)}>
                    <option value="">{ru ? 'Все статусы' : 'All statuses'}</option>
                    <option value="NEW">NEW</option>
                    <option value="VIEWED">VIEWED</option>
                    <option value="REPLIED">REPLIED</option>
                    <option value="IGNORED">IGNORED</option>
                </select>
                <button className={s.grantBtn} style={{ height: 32, padding: '0 14px', fontSize: 12 }} onClick={() => load(0)}>
                    {ru ? 'Найти' : 'Search'}
                </button>
                {(filterUser || filterStatus || filterKeyword) && (
                    <button
                        onClick={() => { setFilterUser(''); setFilterStatus(''); setFilterKeyword('') }}
                        style={{ ...inputSt, cursor: 'pointer', color: 'var(--c-ink-3)', padding: '0 10px' }}>
                        {ru ? '✕ Сбросить' : '✕ Clear'}
                    </button>
                )}
            </div>

            {loading && <div className={s.loading}>{ru ? 'Загрузка...' : 'Loading...'}</div>}
            {error   && <div className={s.formError}>{error}</div>}

            {!loading && leads.length === 0 && (
                <div className={s.placeholder}>
                    <div className={s.placeholderIcon}>📭</div>
                    <h2>{ru ? 'Лидов не найдено' : 'No leads found'}</h2>
                </div>
            )}

            {!loading && leads.length > 0 && (
                <>
                    <div className={s.tableWrapper}>
                        <table className={s.usersTable}>
                            <thead>
                            <tr>
                                <th>ID</th>
                                <th>{ru ? 'Пользователь' : 'User'}</th>
                                <th>{ru ? 'Чат' : 'Chat'}</th>
                                <th>{ru ? 'Автор' : 'Author'}</th>
                                <th>{ru ? 'Ключевое слово' : 'Keyword'}</th>
                                <th>{ru ? 'Статус' : 'Status'}</th>
                                <th>AI</th>
                                <th>{ru ? 'Дата' : 'Date'}</th>
                                <th>{ru ? 'Текст' : 'Text'}</th>
                            </tr>
                            </thead>
                            <tbody>
                            {leads.map(l => (
                                <tr key={l.id}>
                                    <td className={s.cellId}>{l.id}</td>
                                    <td>
                                        <div style={{ fontSize: 12, fontWeight: 500 }}>{l.userFirstName || '—'}</div>
                                        <div style={{ fontSize: 11, color: 'var(--c-ink-3)' }}>{l.userEmail}</div>
                                        <div style={{ fontSize: 10, color: 'var(--c-ink-3)' }}>ID {l.userId}</div>
                                    </td>
                                    <td style={{ maxWidth: 140 }}>
                                        <div style={{ fontSize: 12, fontWeight: 500, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                                            {l.chatTitle || '—'}
                                        </div>
                                        {l.chatLink && (
                                            <a href={l.chatLink} target="_blank" rel="noreferrer"
                                               style={{ fontSize: 10, color: '#3b82f6' }}>↗</a>
                                        )}
                                    </td>
                                    <td style={{ maxWidth: 120 }}>
                                        <div style={{ fontSize: 12 }}>{l.authorName || '—'}</div>
                                        {l.authorUsername && (
                                            <div style={{ fontSize: 11, color: 'var(--c-ink-3)' }}>@{l.authorUsername}</div>
                                        )}
                                    </td>
                                    <td>
                                        <span style={{
                                            background: 'var(--c-accent-soft)',
                                            color: 'var(--c-accent)',
                                            fontSize: 11, fontWeight: 600,
                                            padding: '2px 6px', borderRadius: 4,
                                        }}>
                                            {l.matchedKeyword}
                                        </span>
                                    </td>
                                    <td>
                                        <span style={{ color: statusColor(l.status), fontWeight: 600, fontSize: 12 }}>
                                            {l.status}
                                        </span>
                                    </td>
                                    <td style={{ fontSize: 12 }}>
                                        {l.aiValid === null ? '—'
                                            : l.aiValid
                                                ? <span style={{ color: '#10b981' }}>✓</span>
                                                : <span style={{ color: '#ef4444' }}>✗</span>
                                        }
                                        {l.aiReason && (
                                            <div style={{ fontSize: 10, color: 'var(--c-ink-3)', maxWidth: 100, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
                                                 title={l.aiReason}>
                                                {l.aiReason}
                                            </div>
                                        )}
                                    </td>
                                    <td className={s.cellDate}>
                                        {new Date(l.foundAt).toLocaleString(ru ? 'ru-RU' : 'en-US', {
                                            day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit',
                                        })}
                                    </td>
                                    <td style={{ maxWidth: 200 }}>
                                        <div style={{ fontSize: 12, color: 'var(--c-ink-2)', overflow: 'hidden', display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical' }}>
                                            {l.messageText}
                                        </div>
                                        {l.messageLink && (
                                            <a href={l.messageLink} target="_blank" rel="noreferrer"
                                               style={{ fontSize: 10, color: '#3b82f6' }}>
                                                {ru ? '↗ открыть' : '↗ open'}
                                            </a>
                                        )}
                                    </td>
                                </tr>
                            ))}
                            </tbody>
                        </table>
                    </div>

                    {/* Пагинация */}
                    {pages > 1 && (
                        <div style={{ display: 'flex', gap: 6, justifyContent: 'center', flexWrap: 'wrap' }}>
                            <button className={s.actionBtn} onClick={() => load(0)} disabled={page === 0}>«</button>
                            <button className={s.actionBtn} onClick={() => load(page - 1)} disabled={page === 0}>‹</button>
                            <span style={{ padding: '6px 12px', fontSize: 13, color: 'var(--c-ink-2)' }}>
                                {page + 1} / {pages}
                            </span>
                            <button className={s.actionBtn} onClick={() => load(page + 1)} disabled={page >= pages - 1}>›</button>
                            <button className={s.actionBtn} onClick={() => load(pages - 1)} disabled={page >= pages - 1}>»</button>
                        </div>
                    )}
                </>
            )}
        </div>
    )
}

// ─── UserbotTab (без изменений) ───────────────────────────────────────────────

function UserbotTab({ lang }: { lang: Lang }) {
    const ru   = lang === 'ru'
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
            setStats(st); setUsers(Array.isArray(us) ? us : [])
        } catch (e: unknown) { setError(e instanceof Error ? e.message : 'Ошибка') }
        finally { setLoading(false) }
    }, [])

    useEffect(() => { load() }, [load])

    const handleDeleteSession = async (sessionId: number, phone: string) => {
        if (!confirm(ru ? `Удалить юзербота #${sessionId} (${phone})?` : `Delete userbot #${sessionId} (${phone})?`)) return
        setDeletingSession(sessionId); setDeleteError('')
        try {
            const res  = await fetch(`${BASE}/api/v1/admin/userbot/sessions/delete`, { method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ sessionId }) })
            const data = await res.json()
            if (!res.ok) throw new Error(data.error ?? `Ошибка ${res.status}`)
            setTimeout(load, 600)
        } catch (e: unknown) { setDeleteError(e instanceof Error ? e.message : 'Ошибка') }
        finally { setDeletingSession(null) }
    }

    const handleSendPhone = async () => {
        if (!phone.trim()) return
        setRegLoading(true); setRegError('')
        try {
            const res  = await fetch(`${BASE}/api/v1/admin/userbot/sessions/register`, { method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ phone: phone.trim(), apiID: parseInt(apiID, 10), apiHash: apiHash.trim() }) })
            const data = await res.json()
            if (!res.ok) throw new Error(data.error ?? `Ошибка ${res.status}`)
            setTempId(data.tempId); setStep('code')
        } catch (e: unknown) { setRegError(e instanceof Error ? e.message : 'Ошибка') }
        finally { setRegLoading(false) }
    }

    const handleConfirmCode = async () => {
        if (!code.trim()) return
        setRegLoading(true); setRegError('')
        try {
            const res  = await fetch(`${BASE}/api/v1/admin/userbot/sessions/confirm`, { method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ tempId, code: code.trim(), password: password.trim() || undefined }) })
            const data = await res.json()
            if (!res.ok) throw new Error(data.error ?? `Ошибка ${res.status}`)
            setRegSuccess(ru ? `✅ Юзербот зарегистрирован! SessionId: ${data.sessionId}` : `✅ Userbot registered! SessionId: ${data.sessionId}`)
            setStep('done'); setTimeout(load, 1500)
        } catch (e: unknown) { setRegError(e instanceof Error ? e.message : 'Ошибка') }
        finally { setRegLoading(false) }
    }

    const resetWizard = () => { setStep('idle'); setPhone(''); setCode(''); setPassword(''); setTempId(''); setRegError(''); setRegSuccess('') }
    const filteredUsers = userSearch.trim() ? users.filter(u => u.email.toLowerCase().includes(userSearch.toLowerCase()) || String(u.userId).includes(userSearch)) : users
    if (loading) return <div className={s.loading}>{ru ? 'Загрузка...' : 'Loading...'}</div>
    if (error)   return <div className={s.formError}>{error}</div>
    const isUp = stats?.status === 'UP'
    const statCards = [
        { label: ru ? 'Статус' : 'Status', value: isUp ? '✅ UP' : '❌ DOWN' },
        { label: ru ? 'Сессий' : 'Sessions', value: stats?.sessions ?? 0 },
        { label: ru ? 'Чатов' : 'Chats', value: stats?.totalChats ?? 0 },
        { label: ru ? 'Юзеров' : 'Users', value: stats?.totalUsers ?? 0 },
    ]
    return (
        <div className={s.subsTab}>
            <h2 className={s.tabTitle}>{ru ? 'Userbot' : 'Userbot'}</h2>
            <div className={s.statsGrid}>{statCards.map(c => (
                <div key={c.label} className={s.statCard}><span className={s.statVal}>{c.value}</span><span className={s.statLabel}>{c.label}</span></div>
            ))}</div>
            {deleteError && <div className={s.formError}>{deleteError}</div>}
            {stats?.perSession && stats.perSession.length > 0 && (
                <div className={s.subCard}>
                    <h3 className={s.subCardTitle}>{ru ? 'Активные сессии' : 'Active Sessions'}</h3>
                    <div className={s.tableWrapper}>
                        <table className={s.usersTable}>
                            <thead><tr><th>ID</th><th>{ru ? 'Телефон' : 'Phone'}</th><th>{ru ? 'Чатов' : 'Chats'}</th><th>{ru ? 'Лидов' : 'Leads'}</th><th>{ru ? 'Онлайн' : 'Online'}</th><th></th></tr></thead>
                            <tbody>{stats.perSession.map(sess => (
                                <tr key={sess.sessionId}>
                                    <td className={s.cellId}>{sess.sessionId}</td>
                                    <td>{sess.phone}</td><td className={s.cellNum}>{sess.chatCount}</td><td className={s.cellNum}>{sess.leadsCount}</td>
                                    <td><span style={{ color: sess.online ? '#10b981' : '#6b7280' }}>{sess.online ? '✅' : '❌'}</span></td>
                                    <td><button className={s.actionBtn} style={{ color: '#ef4444', borderColor: 'rgba(239,68,68,.3)', background: 'rgba(239,68,68,.06)' }} onClick={() => handleDeleteSession(sess.sessionId, sess.phone)} disabled={deletingSession === sess.sessionId}>{deletingSession === sess.sessionId ? '...' : ru ? 'Удалить' : 'Delete'}</button></td>
                                </tr>
                            ))}</tbody>
                        </table>
                    </div>
                </div>
            )}
            <div className={s.subCard}>
                <h3 className={s.subCardTitle}>{ru ? 'Добавить юзербота' : 'Add Userbot'}</h3>
                {step === 'idle' && (<div className={s.formRow} style={{ flexDirection: 'column', alignItems: 'flex-start' }}>
                    <div className={s.formRow}><input className={s.formInput} placeholder="+7..." value={phone} onChange={e => setPhone(e.target.value)} style={{ width: 140 }} /><input className={s.formInput} placeholder="API ID" value={apiID} onChange={e => setApiID(e.target.value)} style={{ width: 100 }} type="number" /><input className={s.formInput} placeholder="API Hash" value={apiHash} onChange={e => setApiHash(e.target.value)} style={{ width: 200 }} /></div>
                    <button className={s.grantBtn} onClick={handleSendPhone} disabled={regLoading}>{regLoading ? '...' : ru ? 'Отправить код' : 'Send Code'}</button>
                    {regError && <div className={s.formError}>{regError}</div>}
                </div>)}
                {step === 'code' && (<div className={s.formRow} style={{ flexDirection: 'column', alignItems: 'flex-start' }}>
                    <p style={{ fontSize: 13, color: 'var(--c-ink-2)', margin: 0 }}>{ru ? `Код отправлен на ${phone}. Введите его:` : `Code sent to ${phone}. Enter it:`}</p>
                    <div className={s.formRow}><input className={s.formInput} placeholder={ru ? 'Код из Telegram' : 'Code from Telegram'} value={code} onChange={e => setCode(e.target.value)} style={{ width: 140 }} /><input className={s.formInput} placeholder={ru ? '2FA пароль (если есть)' : '2FA password (if any)'} value={password} onChange={e => setPassword(e.target.value)} type="password" style={{ width: 180 }} /></div>
                    <div className={s.formRow}><button className={s.grantBtn} onClick={handleConfirmCode} disabled={regLoading}>{regLoading ? '...' : ru ? 'Подтвердить' : 'Confirm'}</button><button className={s.actionBtn} onClick={resetWizard}>{ru ? 'Отмена' : 'Cancel'}</button></div>
                    {regError && <div className={s.formError}>{regError}</div>}
                </div>)}
                {step === 'done' && (<div><div className={s.formSuccess}>{regSuccess}</div><button className={s.actionBtn} style={{ marginTop: 8 }} onClick={resetWizard}>{ru ? 'Добавить ещё' : 'Add more'}</button></div>)}
            </div>
            <div className={s.subCard}>
                <h3 className={s.subCardTitle}>{ru ? 'Пользователи юзербота' : 'Userbot Users'} ({filteredUsers.length})</h3>
                <input className={s.formInput} placeholder={ru ? 'Поиск по email / ID...' : 'Search by email / ID...'} value={userSearch} onChange={e => setUserSearch(e.target.value)} style={{ marginBottom: 12, width: '100%', maxWidth: 300 }} />
                {filteredUsers.map(u => (
                    <div key={u.userId} className={s.subCard} style={{ marginBottom: 8, cursor: 'pointer' }} onClick={() => setExpandedUser(expandedUser === u.userId ? null : u.userId)}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                            <span style={{ fontWeight: 600, fontSize: 13 }}>{u.email}</span>
                            <span style={{ fontSize: 12, color: 'var(--c-ink-3)' }}>{ru ? `лидов: ${u.leadsCount}` : `leads: ${u.leadsCount}`}</span>
                        </div>
                        {expandedUser === u.userId && (
                            <div style={{ marginTop: 10, display: 'flex', gap: 12, flexWrap: 'wrap' }}>
                                <div><div style={{ fontSize: 11, color: 'var(--c-ink-3)', marginBottom: 4 }}>{ru ? 'Чаты' : 'Chats'}</div>{u.chats.map((c, i) => <div key={i} style={{ fontSize: 12 }}>{c}</div>)}</div>
                                <div><div style={{ fontSize: 11, color: 'var(--c-ink-3)', marginBottom: 4 }}>{ru ? 'Ключевые слова' : 'Keywords'}</div>{u.keywords.map((k, i) => <span key={i} className={s.planPill} style={{ marginRight: 4 }}>{k}</span>)}</div>
                            </div>
                        )}
                    </div>
                ))}
            </div>
        </div>
    )
}

// ─── Main AdminPanel ──────────────────────────────────────────────────────────

export default function AdminPanel({ lang }: Props) {
    const ru = lang === 'ru'
    const navigate = useNavigate()
    const { auth } = useAuthContext()

    const [tab,         setTab]         = useState<Tab>('overview')
    const [users,       setUsers]       = useState<AdminUserDto[]>([])
    const [usersError,  setUsersError]  = useState('')
    const [usersLoaded, setUsersLoaded] = useState(false)
    const [sidebarOpen, setSidebarOpen] = useState(false)

    // Подписки для UsersTab
    const [subs,       setSubs]       = useState<SubscriptionInfo[]>([])
    const [subsLoading,setSubsLoading] = useState(false)

    // Уведомления
    const [notifs,       setNotifs]       = useState<NotificationDto[]>([])
    const [notifsLoaded, setNotifsLoaded] = useState(false)
    const [notifTitle,   setNotifTitle]   = useState('')
    const [notifBody,    setNotifBody]    = useState('')
    const [notifTarget,  setNotifTarget]  = useState('ALL')
    const [notifLoading, setNotifLoading] = useState(false)
    const [notifMsg,     setNotifMsg]     = useState('')

    const loadUsers = useCallback(async () => {
        try {
            const data = await adminApi.getUsers()
            setUsers(data)
            setUsersLoaded(true)
        } catch (e: unknown) {
            setUsersError(e instanceof Error ? e.message : 'Ошибка')
        }
    }, [])

    const loadSubs = useCallback(async () => {
        setSubsLoading(true)
        try { setSubs(await adminSubsApi.list()) }
        catch { /* ignore */ }
        finally { setSubsLoading(false) }
    }, [])

    const loadNotifs = useCallback(async () => {
        try {
            const data = await notificationsApi.getAllAdmin()
            setNotifs(Array.isArray(data) ? data : [])
            setNotifsLoaded(true)
        } catch { /* ignore */ }
    }, [])

    useEffect(() => { loadUsers() }, [loadUsers])
    useEffect(() => {
        if (tab === 'users')    loadSubs()
        if (tab === 'notifications' && !notifsLoaded) loadNotifs()
    }, [tab, loadSubs, loadNotifs, notifsLoaded])

    const handleRoleChange = (id: number, role: string) => {
        setUsers(prev => prev.map(u => u.id === id ? { ...u, role } : u))
    }

    const handleSendNotif = async () => {
        if (!notifTitle.trim() || !notifBody.trim()) return
        setNotifLoading(true); setNotifMsg('')
        try {
            await notificationsApi.createNotification({ title: notifTitle.trim(), body: notifBody.trim(), target: notifTarget })
            setNotifTitle(''); setNotifBody('')
            setNotifMsg(ru ? 'Уведомление создано!' : 'Notification created!')
            loadNotifs()
        } catch (e: unknown) {
            setNotifMsg(e instanceof Error ? e.message : 'Ошибка')
        } finally { setNotifLoading(false) }
    }

    const totalLeads = users.reduce((s, u) => s + u.leadsCount, 0)
    const activeUsers = users.filter(u => u.subscriptionStatus === 'ACTIVE').length
    const trialUsers  = users.filter(u => u.subscriptionStatus === 'TRIAL').length
    const tgLinked    = users.filter(u => !!u.telegramId).length

    return (
        <div className={s.root}>
            {/* Шапка */}
            <header className={s.header}>
                <a href="/" className={s.logo}>
                    <img src="/logo.png" alt="" className={s.logoImg} onError={e => { (e.target as HTMLImageElement).style.display = 'none' }} />
                    <span className={s.logoText}>AIMLY<span className={s.adminTag}>ADMIN</span></span>
                </a>
                <div className={s.headerRight}>
                    <button className={s.backBtn} onClick={() => navigate('/dashboard')}>
                        <span className={s.backBtnFull}>← {ru ? 'Кабинет' : 'Dashboard'}</span>
                        <span className={s.backBtnShort}>←</span>
                    </button>
                </div>
                <button className={`${s.burger} ${sidebarOpen ? s.burgerOpen : ''}`} onClick={() => setSidebarOpen(o => !o)} aria-label="Menu">
                    <span /><span /><span />
                </button>
            </header>

            <div className={s.body}>
                {/* Сайдбар */}
                {sidebarOpen && <div className={s.mobileOverlay} onClick={() => setSidebarOpen(false)} />}
                <nav className={`${s.sidebar} ${sidebarOpen ? s.sidebarOpen : ''}`}>
                    <div className={s.nav}>
                        {TABS.map(t => (
                            <button key={t.id}
                                    className={`${s.navItem} ${tab === t.id ? s.navItemActive : ''}`}
                                    onClick={() => { setTab(t.id); setSidebarOpen(false) }}>
                                {t.id === 'overview'      && '📊 '}
                                {t.id === 'users'         && '👥 '}
                                {t.id === 'leads_all'     && '📋 '}
                                {t.id === 'userbot'       && '🤖 '}
                                {t.id === 'notifications' && '🔔 '}
                                {t.label[lang === 'ru' ? 'ru' : 'en']}
                            </button>
                        ))}
                    </div>
                </nav>

                {/* Контент */}
                <main className={s.main}>

                    {/* ── Обзор ── */}
                    {tab === 'overview' && (
                        <div className={s.subsTab}>
                            <h2 className={s.tabTitle}>{ru ? 'Обзор' : 'Overview'}</h2>
                            <div className={s.statsGrid}>
                                {[
                                    { label: ru ? 'Пользователей' : 'Users',         value: users.length },
                                    { label: ru ? 'Активных'      : 'Active',         value: activeUsers },
                                    { label: ru ? 'Trial'         : 'Trial',          value: trialUsers },
                                    { label: ru ? 'TG привязан'   : 'TG linked',      value: tgLinked },
                                    { label: ru ? 'Лидов всего'   : 'Total leads',    value: totalLeads.toLocaleString() },
                                ].map(c => (
                                    <div key={c.label} className={s.statCard}>
                                        <span className={s.statVal}>{c.value}</span>
                                        <span className={s.statLabel}>{c.label}</span>
                                    </div>
                                ))}
                            </div>
                            {usersError && <div className={s.formError}>{usersError}</div>}
                            {!usersLoaded && !usersError && <div className={s.loading}>...</div>}
                        </div>
                    )}

                    {/* ── Пользователи (с блоком подписок) ── */}
                    {tab === 'users' && (
                        <UsersTab
                            users={users}
                            lang={lang}
                            currentUserId={auth?.userId ?? 0}
                            onRoleChange={handleRoleChange}
                            subs={subs}
                            subsLoading={subsLoading}
                            onSubsReload={loadSubs}
                        />
                    )}

                    {/* ── Все лиды ── */}
                    {tab === 'leads_all' && <AllLeadsTab lang={lang} />}

                    {/* ── Userbot ── */}
                    {tab === 'userbot' && <UserbotTab lang={lang} />}

                    {/* ── Уведомления ── */}
                    {tab === 'notifications' && (
                        <div className={s.subsTab}>
                            <h2 className={s.tabTitle}>{ru ? 'Уведомления' : 'Notifications'}</h2>
                            <div className={s.subCard}>
                                <h3 className={s.subCardTitle}>{ru ? 'Создать уведомление' : 'Create Notification'}</h3>
                                <div className={s.formRow} style={{ flexDirection: 'column', alignItems: 'stretch', maxWidth: 480 }}>
                                    <input className={s.formInput} placeholder={ru ? 'Заголовок' : 'Title'} value={notifTitle} onChange={e => setNotifTitle(e.target.value)} />
                                    <textarea
                                        style={{ resize: 'vertical', minHeight: 80, padding: '8px 12px', border: '1px solid var(--c-border)', borderRadius: 6, background: 'var(--c-bg)', color: 'var(--c-ink)', fontSize: 13, fontFamily: 'inherit', outline: 'none' }}
                                        placeholder={ru ? 'Текст уведомления...' : 'Notification body...'}
                                        value={notifBody} onChange={e => setNotifBody(e.target.value)}
                                    />
                                    <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                                        <select className={s.formSelect} value={notifTarget} onChange={e => setNotifTarget(e.target.value)}>
                                            <option value="ALL">{ru ? 'Всем' : 'All'}</option>
                                            <option value="ACTIVE">{ru ? 'Активным' : 'Active'}</option>
                                        </select>
                                        <button className={s.grantBtn} onClick={handleSendNotif} disabled={notifLoading}>
                                            {notifLoading ? '...' : ru ? 'Отправить' : 'Send'}
                                        </button>
                                    </div>
                                    {notifMsg && <div className={s.formSuccess}>{notifMsg}</div>}
                                </div>
                            </div>
                            {notifsLoaded && notifs.length > 0 && (
                                <div className={s.subCard}>
                                    <h3 className={s.subCardTitle}>{ru ? 'История уведомлений' : 'Notification History'}</h3>
                                    {notifs.slice().reverse().map(n => (
                                        <div key={n.id} className={s.notifHistoryItem}>
                                            <div className={s.notifHistoryHeader}>
                                                <span className={s.historyTitle}>{n.title}</span>
                                                <span className={n.sent ? s.statusSent : s.statusPending}>
                                                    {n.sent ? (ru ? '✓ Отправлено' : '✓ Sent') : (ru ? '⏳ Ожидает' : '⏳ Pending')}
                                                </span>
                                            </div>
                                            <div className={s.historyBody}>{n.body}</div>
                                            <div className={s.historyDate}>{new Date(n.createdAt).toLocaleString(ru ? 'ru-RU' : 'en-US')} · target: {n.target}</div>
                                        </div>
                                    ))}
                                </div>
                            )}
                        </div>
                    )}

                </main>
            </div>
        </div>
    )
}