import { useState, useEffect, useCallback } from 'react'
import type { Lang } from '../i18n/translations'
import { adminSubsApi, type SubscriptionInfo } from '../api/leads'
import s from '../pages/AdminPanel.module.css'


const PLANS    = ['MINIMUM', 'START', 'TRIAL']
const STATUSES = ['ACTIVE', 'TRIAL', 'INACTIVE']

interface Props { lang: Lang }

interface Msg { text: string; ok: boolean }

export default function SubscriptionsTab({ lang }: Props) {
    const ru = lang === 'ru'
    const [subs,      setSubs]      = useState<SubscriptionInfo[]>([])
    const [loading,   setLoading]   = useState(true)
    const [loadError, setLoadError] = useState('')
    const [working,   setWorking]   = useState<number | null>(null)


    const [grantUserId,    setGrantUserId]    = useState('')
    const [grantPlan,      setGrantPlan]      = useState('MINIMUM')  // ✅ дефолт — MINIMUM (дешевле)
    const [grantStatus,    setGrantStatus]    = useState('ACTIVE')
    const [grantDays,      setGrantDays]      = useState('30')
    const [grantLoading,   setGrantLoading]   = useState(false)
    const [grantMsg,       setGrantMsg]       = useState<Msg | null>(null)


    const [balanceUserId,  setBalanceUserId]  = useState('')
    const [balanceAmount,  setBalanceAmount]  = useState('')
    const [balanceLoading, setBalanceLoading] = useState(false)
    const [balanceMsg,     setBalanceMsg]     = useState<Msg | null>(null)


    const [editPlanId,     setEditPlanId]     = useState<number | null>(null)
    const [editPlanValue,  setEditPlanValue]  = useState('MINIMUM')  // ✅ дефолт — MINIMUM
    const [editStatusValue,setEditStatusValue]= useState('ACTIVE')


    const [editExpiryId,   setEditExpiryId]  = useState<number | null>(null)
    const [editExpiryDays, setEditExpiryDays]= useState('30')

    const load = useCallback(async () => {
        setLoading(true)
        setLoadError('')
        try {
            const data = await adminSubsApi.list()
            setSubs(data)
        } catch (e: unknown) {
            setLoadError(e instanceof Error ? e.message : 'Ошибка загрузки')
        } finally {
            setLoading(false)
        }
    }, [])

    useEffect(() => { load() }, [load])


    const handleGrant = async () => {
        const userId = Number(grantUserId)
        const days   = Number(grantDays)
        if (!userId || userId <= 0) {
            setGrantMsg({ text: ru ? 'Введите корректный ID' : 'Enter valid ID', ok: false })
            return
        }
        if (!days || days <= 0) {
            setGrantMsg({ text: ru ? 'Введите корректное кол-во дней' : 'Enter valid days', ok: false })
            return
        }
        setGrantLoading(true)
        setGrantMsg(null)
        try {
            await adminSubsApi.grant(userId, grantPlan, days)

            if (grantStatus === 'INACTIVE') {
                await adminSubsApi.revoke(userId)
            }
            setGrantUserId('')
            setGrantDays('30')
            setGrantMsg({ text: ru ? 'Подписка выдана' : 'Subscription granted', ok: true })
            await load()
        } catch (e: unknown) {
            setGrantMsg({ text: e instanceof Error ? e.message : 'Ошибка', ok: false })
        } finally {
            setGrantLoading(false)
        }
    }


    const handleBalance = async () => {
        const userId = Number(balanceUserId)
        const amount = Number(balanceAmount)
        if (!userId || userId <= 0) {
            setBalanceMsg({ text: ru ? 'Введите корректный ID' : 'Enter valid ID', ok: false })
            return
        }
        if (isNaN(amount)) {
            setBalanceMsg({ text: ru ? 'Введите сумму' : 'Enter amount', ok: false })
            return
        }
        setBalanceLoading(true)
        setBalanceMsg(null)
        try {
            await adminSubsApi.adjustBalance(userId, amount)
            setBalanceUserId('')
            setBalanceAmount('')
            setBalanceMsg({ text: ru ? 'Баланс изменён' : 'Balance updated', ok: true })
            await load()
        } catch (e: unknown) {
            setBalanceMsg({ text: e instanceof Error ? e.message : 'Ошибка', ok: false })
        } finally {
            setBalanceLoading(false)
        }
    }


    const handleRevoke = async (userId: number) => {
        if (!confirm(ru ? 'Отозвать подписку?' : 'Revoke subscription?')) return
        setWorking(userId)
        try {
            await adminSubsApi.revoke(userId)
            await load()
        } catch (e: unknown) {
            alert(e instanceof Error ? e.message : 'Ошибка')
        } finally {
            setWorking(null)
        }
    }


    const handleEditPlan = async (userId: number) => {
        const plan   = editPlanValue || 'MINIMUM'  // ✅ ИСПРАВЛЕНО: дефолт MINIMUM
        const status = editStatusValue
        setWorking(userId)
        try {

            await adminSubsApi.changePlan(userId, plan)

            if (status === 'INACTIVE') {
                await adminSubsApi.revoke(userId)
            }
            setEditPlanId(null)
            await load()
        } catch (e: unknown) {
            alert(e instanceof Error ? e.message : 'Ошибка изменения тарифа')
        } finally {
            setWorking(null)
        }
    }


    const handleEditExpiry = async (userId: number) => {
        const plan = subs.find(sub => sub.userId === userId)?.plan ?? 'MINIMUM'  // ✅ ИСПРАВЛЕНО: дефолт MINIMUM
        const days = Number(editExpiryDays) || 30
        setWorking(userId)
        try {
            await adminSubsApi.setExpiry(userId, plan, days)
            setEditExpiryId(null)
            await load()
        } catch (e: unknown) {
            setLoadError(e instanceof Error ? e.message : 'Ошибка изменения срока')
        } finally {
            setWorking(null)
        }
    }

    const statusColor = (st: string | null) => {
        if (st === 'ACTIVE') return '#10b981'
        if (st === 'TRIAL')  return '#f59e0b'
        return '#6b7280'
    }

    return (
        <div className={s.subsTab}>
            <h2 className={s.tabTitle}>{ru ? 'Управление подписками' : 'Subscription Management'}</h2>

            {}
            <div className={s.subCard}>
                <h3 className={s.subCardTitle}>{ru ? 'Выдать / продлить подписку' : 'Grant / Extend Subscription'}</h3>
                <div className={s.formRow}>
                    <input
                        className={s.formInput}
                        type="number"
                        placeholder={ru ? 'ID пользователя' : 'User ID'}
                        value={grantUserId}
                        onChange={e => setGrantUserId(e.target.value)}
                        style={{ width: 120 }}
                    />
                    {/* ✅ ИСПРАВЛЕНО: список тарифов — MINIMUM, START, TRIAL */}
                    <select
                        className={s.formSelect}
                        value={grantPlan}
                        onChange={e => setGrantPlan(e.target.value)}
                        style={{ width: 120 }}
                    >
                        {PLANS.map(p => <option key={p} value={p}>{p}</option>)}
                    </select>
                    <select
                        className={s.formSelect}
                        value={grantStatus}
                        onChange={e => setGrantStatus(e.target.value)}
                        style={{ width: 110 }}
                        title={ru ? 'Статус подписки' : 'Subscription status'}
                    >
                        {STATUSES.map(st => <option key={st} value={st}>{st}</option>)}
                    </select>
                    <input
                        className={s.formInput}
                        type="number"
                        placeholder={ru ? 'Дней' : 'Days'}
                        value={grantDays}
                        onChange={e => setGrantDays(e.target.value)}
                        style={{ width: 80 }}
                    />
                    <button
                        className={s.grantBtn}
                        onClick={handleGrant}
                        disabled={grantLoading}
                    >
                        {grantLoading ? '...' : ru ? 'Выдать' : 'Grant'}
                    </button>
                </div>
                {grantMsg && (
                    <div className={grantMsg.ok ? s.formSuccess : s.formError}>
                        {grantMsg.text}
                    </div>
                )}
            </div>

            {/* Форма: изменить баланс */}
            <div className={s.subCard}>
                <h3 className={s.subCardTitle}>{ru ? 'Изменить баланс' : 'Adjust Balance'}</h3>
                <div className={s.formRow}>
                    <input
                        className={s.formInput}
                        type="number"
                        placeholder={ru ? 'ID пользователя' : 'User ID'}
                        value={balanceUserId}
                        onChange={e => setBalanceUserId(e.target.value)}
                        style={{ width: 120 }}
                    />
                    <input
                        className={s.formInput}
                        type="number"
                        placeholder={ru ? 'Сумма (+/−)' : 'Amount (+/−)'}
                        value={balanceAmount}
                        onChange={e => setBalanceAmount(e.target.value)}
                        style={{ width: 120 }}
                    />
                    <button
                        className={s.grantBtn}
                        onClick={handleBalance}
                        disabled={balanceLoading}
                    >
                        {balanceLoading ? '...' : ru ? 'Изменить' : 'Adjust'}
                    </button>
                </div>
                {balanceMsg && (
                    <div className={balanceMsg.ok ? s.formSuccess : s.formError}>
                        {balanceMsg.text}
                    </div>
                )}
            </div>

            {loadError && <div className={s.formError}>{loadError}</div>}

            {loading ? (
                <div className={s.loading}>...</div>
            ) : subs.length === 0 ? (
                <div className={s.placeholder} style={{ minHeight: 160 }}>
                    <div className={s.placeholderIcon} style={{ fontSize: 32 }}>—</div>
                    <h2>{ru ? 'Нет активных подписок' : 'No active subscriptions'}</h2>
                    <p>{ru ? 'Выдайте первую подписку выше' : 'Grant the first subscription above'}</p>
                </div>
            ) : (
                <div className={s.tableWrapper}>
                    <table className={s.usersTable}>
                        <thead>
                        <tr>
                            <th>ID</th>
                            <th>{ru ? 'Пользователь' : 'User'}</th>
                            <th>{ru ? 'Статус' : 'Status'}</th>
                            <th>{ru ? 'Тариф' : 'Plan'}</th>
                            <th>{ru ? 'Истекает' : 'Expires'}</th>
                            <th>{ru ? 'Баланс' : 'Balance'}</th>
                            <th>{ru ? 'Действия' : 'Actions'}</th>
                        </tr>
                        </thead>
                        <tbody>
                        {subs.map(sub => (
                            <tr key={sub.userId}>
                                <td className={s.cellId}>{sub.userId}</td>
                                <td>
                                    <div style={{ fontSize: 13, color: 'var(--c-ink)', fontWeight: 500 }}>
                                        {sub.firstName || '—'}
                                    </div>
                                    <div style={{ fontSize: 11, color: 'var(--c-ink-3)', marginTop: 2 }}>
                                        {sub.email}
                                    </div>
                                </td>
                                <td>
                                    <span style={{ color: statusColor(sub.status), fontWeight: 600, fontSize: 12 }}>
                                        {sub.status || '—'}
                                    </span>
                                </td>

                                {}
                                <td>
                                    {editPlanId === sub.userId ? (
                                        <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap' }}>
                                            {/* ✅ ИСПРАВЛЕНО: селект тарифов — MINIMUM и START */}
                                            <select
                                                className={s.formSelect}
                                                value={editPlanValue}
                                                onChange={e => setEditPlanValue(e.target.value)}
                                                style={{ width: 100, fontSize: 12, padding: '4px 6px' }}
                                            >
                                                {['MINIMUM', 'START'].map(p => (
                                                    <option key={p} value={p}>{p}</option>
                                                ))}
                                            </select>
                                            <select
                                                className={s.formSelect}
                                                value={editStatusValue}
                                                onChange={e => setEditStatusValue(e.target.value)}
                                                style={{ width: 100, fontSize: 12, padding: '4px 6px' }}
                                            >
                                                {STATUSES.map(st => (
                                                    <option key={st} value={st}>{st}</option>
                                                ))}
                                            </select>
                                            <button
                                                className={s.actionBtn}
                                                onClick={() => handleEditPlan(sub.userId)}
                                                disabled={working === sub.userId}
                                                style={{ padding: '4px 8px', fontSize: 11 }}
                                            >
                                                {working === sub.userId ? '…' : '✔'}
                                            </button>
                                            <button
                                                className={s.actionBtn}
                                                onClick={() => setEditPlanId(null)}
                                                style={{ padding: '4px 8px', fontSize: 11, background: 'var(--c-surface)' }}
                                            >
                                                ✕
                                            </button>
                                        </div>
                                    ) : (
                                        <div
                                            style={{ display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer' }}
                                            onClick={() => {
                                                setEditPlanId(sub.userId)
                                                setEditPlanValue(sub.plan || 'MINIMUM')  // ✅ дефолт MINIMUM
                                                setEditStatusValue(sub.status || 'ACTIVE')
                                            }}
                                        >
                                            <span className={s.planPill}>{sub.plan || '—'}</span>
                                            <span style={{ fontSize: 10, color: 'var(--c-ink-3)' }}>✎</span>
                                        </div>
                                    )}
                                </td>

                                {/* Редактирование срока */}
                                <td>
                                    {editExpiryId === sub.userId ? (
                                        <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
                                            <input
                                                className={s.formInput}
                                                type="number"
                                                value={editExpiryDays}
                                                onChange={e => setEditExpiryDays(e.target.value)}
                                                style={{ width: 70, fontSize: 12, padding: '4px 6px' }}
                                            />
                                            <span style={{ fontSize: 11, color: 'var(--c-ink-3)' }}>
                                                {ru ? 'дней' : 'days'}
                                            </span>
                                            <button
                                                className={s.actionBtn}
                                                onClick={() => handleEditExpiry(sub.userId)}
                                                disabled={working === sub.userId}
                                                style={{ padding: '4px 8px', fontSize: 11 }}
                                            >
                                                {working === sub.userId ? '…' : '✔'}
                                            </button>
                                            <button
                                                className={s.actionBtn}
                                                onClick={() => setEditExpiryId(null)}
                                                style={{ padding: '4px 8px', fontSize: 11, background: 'var(--c-surface)' }}
                                            >
                                                ✕
                                            </button>
                                        </div>
                                    ) : (
                                        <div
                                            style={{ display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer' }}
                                            onClick={() => {
                                                setEditExpiryId(sub.userId)
                                                setEditExpiryDays('30')
                                            }}
                                        >
                                            <span style={{ fontSize: 12 }}>
                                                {sub.expiresAt
                                                    ? new Date(sub.expiresAt).toLocaleDateString(ru ? 'ru-RU' : 'en-US', {
                                                        day: 'numeric', month: 'short', year: 'numeric',
                                                    })
                                                    : '—'}
                                            </span>
                                            <span style={{ fontSize: 10, color: 'var(--c-ink-3)' }}>✎</span>
                                        </div>
                                    )}
                                </td>

                                <td className={s.cellNum}>{sub.balance} ₽</td>
                                <td>
                                    <button
                                        className={s.actionBtn}
                                        onClick={() => handleRevoke(sub.userId)}
                                        disabled={working === sub.userId}
                                        style={{
                                            background: 'rgba(239,68,68,.12)',
                                            color: '#ef4444',
                                            border: '1px solid rgba(239,68,68,.25)',
                                        }}
                                    >
                                        {working === sub.userId ? '…' : ru ? 'Отозвать' : 'Revoke'}
                                    </button>
                                </td>
                            </tr>
                        ))}
                        </tbody>
                    </table>
                </div>
            )}

            <p style={{ fontSize: 12, color: 'var(--c-ink-3)', marginTop: 8 }}>
                {ru
                    ? '💡 Нажмите на тариф или дату чтобы изменить. INACTIVE = отзыв подписки.'
                    : '💡 Click plan or date to edit. INACTIVE = revoke subscription.'}
            </p>
        </div>
    )
}