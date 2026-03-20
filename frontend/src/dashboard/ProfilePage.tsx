import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import type { Lang } from '../i18n/translations'
import { authApi } from '../api/auth'
import { leadsApi, businessContextApi } from '../api/leads'
import { useAuthContext } from '../context/AuthContext'
import s from './ProfilePage.module.css'

function SparkleIcon() {
    return (
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M12 2L15.09 8.26L22 9.27L17 14.14L18.18 21.02L12 17.77L5.82 21.02L7 14.14L2 9.27L8.91 8.26L12 2Z"/>
        </svg>
    )
}

const txt = {
    ru: {
        title:         'Профиль',
        sub:           'Информация об аккаунте и подписке',
        accountCard:   'Аккаунт',
        emailVerified: 'Email подтверждён',
        tgLinked:      'Telegram привязан',
        tgNotLinked:   'Telegram не привязан',
        subCard:       'Подписка',
        expires:       'Действует до',
        noPlan:        'Нет подписки',
        trial:         'Trial',
        active:        'Активна',
        choosePlan:    'Выбрать тариф',
        statsCard:     'Статистика',
        totalLeads:    'Всего лидов',
        newLeads:      'Новых лидов',
        memberSince:   'В сервисе с',
        tgCard:        'Telegram',
        tgConnected:   'Аккаунт привязан',
        tgConnect:     'Запустить бота',
        tgUnlink:      'Отвязать Telegram',
        tgUnlinkConfirm: 'Отвязать аккаунт? Мониторинг остановится.',
        linkHint:      'Откройте ссылку и нажмите Start в боте:',
        copyLink:      'Скопировать',
        copied:        'Скопировано',
        openBot:       'Открыть бота',
        generating:    'Генерируем ссылку...',
        linkError:     'Не удалось получить ссылку',
        unlinkError:   'Не удалось отвязать Telegram',
        daysLeft:      (n: number) => `Осталось ${n} дн.`,
        expired:       'Истекла',
        aiCard:        'AI-персонализация',
        aiCardSub:     'Опишите ваш бизнес — AI будет точнее находить лидов и фильтровать сообщения',
        aiPlaceholder: 'Например: продаём CRM-системы для малого бизнеса, целевая аудитория — владельцы интернет-магазинов...',
        aiSave:        'Сохранить',
        aiSaving:      'Сохраняем...',
        aiSaved:       '✓ Сохранено',
        aiError:       'Ошибка сохранения',
        aiNeedPlan:    'Доступно на тарифе START и выше',
        aiNeedPlanTrial: 'Доступно на тарифе START — попробуйте 5 дней бесплатно',
        trialBtn:      '🚀 Запустить бота бесплатно',
        trialBtnLoading: 'Открываем...',
    },
    en: {
        title:         'Profile',
        sub:           'Account and subscription info',
        accountCard:   'Account',
        emailVerified: 'Email verified',
        tgLinked:      'Telegram linked',
        tgNotLinked:   'Telegram not linked',
        subCard:       'Subscription',
        expires:       'Expires',
        noPlan:        'No subscription',
        trial:         'Trial',
        active:        'Active',
        choosePlan:    'Choose a plan',
        statsCard:     'Stats',
        totalLeads:    'Total leads',
        newLeads:      'New leads',
        memberSince:   'Member since',
        tgCard:        'Telegram',
        tgConnected:   'Account linked',
        tgConnect:     'Link Telegram',
        tgUnlink:      'Unlink Telegram',
        tgUnlinkConfirm: 'Unlink account? Monitoring will stop.',
        linkHint:      'Open the link and click Start in the bot:',
        copyLink:      'Copy',
        copied:        'Copied',
        openBot:       'Open bot',
        generating:    'Generating link...',
        linkError:     'Failed to generate link',
        unlinkError:   'Failed to unlink Telegram',
        daysLeft:      (n: number) => `${n} days left`,
        expired:       'Expired',
        aiCard:        'AI Personalization',
        aiCardSub:     'Describe your business — AI will find better leads and filter messages more accurately',
        aiPlaceholder: 'E.g.: we sell CRM systems for small businesses, targeting online store owners...',
        aiSave:        'Save',
        aiSaving:      'Saving...',
        aiSaved:       '✓ Saved',
        aiError:       'Save error',
        aiNeedPlan:    'Available on Minimum plan and above',
        aiNeedPlanTrial: 'Available on START plan — try 5 days for free',
        trialBtn:      '🚀 Launch bot for free',
        trialBtnLoading: 'Opening...',
    },
} as const

interface Props { lang: Lang }

function getSubProgress(expiresAt: string | null): { days: number; pct: number; expired: boolean } {
    if (!expiresAt) return { days: 0, pct: 0, expired: false }
    const now  = Date.now()
    const exp  = new Date(expiresAt).getTime()
    const diff = exp - now
    const days = Math.ceil(diff / 86_400_000)
    if (days <= 0) return { days: 0, pct: 0, expired: true }
    return { days, pct: Math.min(100, (days / 30) * 100), expired: false }
}

export default function ProfilePage({ lang }: Props) {
    const l = txt[lang]
    const { user, refreshUser } = useAuthContext()
    const navigate = useNavigate()

    const [totalLeads,   setTotalLeads]   = useState<number | null>(null)
    const [newLeads,     setNewLeads]     = useState<number | null>(null)
    const [tgLink,       setTgLink]       = useState<string | null>(null)
    const [tgLoading,    setTgLoading]    = useState(false)
    const [tgError,      setTgError]      = useState<string | null>(null)
    const [copied,       setCopied]       = useState(false)
    const [unlinking,    setUnlinking]    = useState(false)
    const [unlinkError,  setUnlinkError]  = useState<string | null>(null)

    // AI персонализация
    const [bizContext,      setBizContext]      = useState('')
    const [bizContextSaved, setBizContextSaved] = useState('')
    const [bizSaving,       setBizSaving]       = useState(false)
    const [bizSuccess,      setBizSuccess]      = useState(false)
    const [bizError,        setBizError]        = useState('')
    const [bizLoading,      setBizLoading]      = useState(true)

    // Trial bot
    const [trialBotLoading, setTrialBotLoading] = useState(false)

    const plan   = user?.subscriptionPlan   ?? null
    const status = user?.subscriptionStatus ?? null
    const hasAiFeatures = (
        plan === 'START' ||
        plan === 'BUSINESS'   ||
        status === 'TRIAL'
    )

    useEffect(() => {
        leadsApi.list({ size: 1 }).then(p => {
            setTotalLeads(p.totalElements)
            setNewLeads(p.newCount)
        }).catch(() => {})

        if (hasAiFeatures) {
            businessContextApi.get().then(r => {
                const v = r.businessContext ?? ''
                setBizContext(v)
                setBizContextSaved(v)
            }).catch(() => {}).finally(() => setBizLoading(false))
        } else {
            setBizLoading(false)
        }
    }, [hasAiFeatures])

    const saveBizContext = async () => {
        setBizSaving(true)
        setBizError('')
        setBizSuccess(false)
        try {
            const res = await businessContextApi.save(bizContext)
            const v   = res.businessContext ?? ''
            setBizContext(v)
            setBizContextSaved(v)
            setBizSuccess(true)
            setTimeout(() => setBizSuccess(false), 2500)
        } catch (e: unknown) {
            setBizError(e instanceof Error ? e.message : l.aiError)
        } finally {
            setBizSaving(false)
        }
    }

    // Открыть бота для trial
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

    const bizContextChanged = bizContext !== bizContextSaved

    if (!user) return null

    const initials    = user.firstName
        ? user.firstName.charAt(0).toUpperCase()
        : user.email.charAt(0).toUpperCase()
    const displayName = user.firstName ?? user.email.split('@')[0]

    const memberSince = user.createdAt
        ? new Date(user.createdAt).toLocaleDateString(lang === 'ru' ? 'ru-RU' : 'en-US', {
            day: 'numeric', month: 'short', year: 'numeric',
        })
        : '—'

    const sub = {
        plan:      user.subscriptionPlan   ?? null,
        status:    user.subscriptionStatus ?? null,
        expiresAt: user.subscriptionExpiresAt ?? null,
    }

    const { days, pct, expired } = getSubProgress(sub.expiresAt)

    const handleConnectTg = async () => {
        setTgLoading(true)
        setTgError(null)
        try {
            const res  = await authApi.getTelegramLink()
            const link = `https://t.me/${res.botUsername}?start=${res.linkToken}`
            setTgLink(link)
            window.open(link, '_blank', 'noopener,noreferrer')
            const iv = setInterval(async () => { await refreshUser() }, 3000)
            setTimeout(() => clearInterval(iv), 60_000)
        } catch {
            setTgError(l.linkError)
        } finally {
            setTgLoading(false)
        }
    }

    const handleUnlinkTg = async () => {
        if (!confirm(l.tgUnlinkConfirm)) return
        setUnlinking(true)
        setUnlinkError(null)
        try {
            await authApi.unlinkTelegram()
            await refreshUser()
        } catch {
            setUnlinkError(l.unlinkError)
        } finally {
            setUnlinking(false)
        }
    }

    const handleCopy = async () => {
        if (!tgLink) return
        try {
            await navigator.clipboard.writeText(tgLink)
            setCopied(true)
            setTimeout(() => setCopied(false), 2000)
            setTimeout(() => refreshUser(), 5000)
        } catch {
            window.open(tgLink, '_blank')
        }
    }

    const progressClass = expired
        ? s.progressFillBad
        : pct > 40
            ? s.progressFillOk
            : pct > 15
                ? s.progressFillWarn
                : s.progressFillBad

    let planBadgeClass = s.planBadgeNone
    let planBadgeText: string = l.noPlan
    if (sub.status === 'ACTIVE') { planBadgeClass = s.planBadgeActive; planBadgeText = l.active }
    if (sub.status === 'TRIAL')  { planBadgeClass = s.planBadgeTrial;  planBadgeText = l.trial  }

    return (
        <div className={s.page}>
            <div className={s.pageHead}>
                <h1 className={s.title}>{l.title}</h1>
                <p className={s.sub}>{l.sub}</p>
            </div>

            <div className={s.grid}>

                {/* аккаунт */}
                <div className={s.card}>
                    <p className={s.cardTitle}>{l.accountCard}</p>
                    <div className={s.userBlock}>
                        <div className={s.avatar}>{initials}</div>
                        <div className={s.userInfo}>
                            <div className={s.userName}>{displayName}</div>
                            <div className={s.userEmail}>{user.email}</div>
                            <div className={s.badges}>
                                {user.emailVerified && (
                                    <span className={`${s.badge} ${s.badgeVerified}`}>
                                        {l.emailVerified}
                                    </span>
                                )}
                                <span className={`${s.badge} ${user.telegramLinked ? s.badgeTg : s.badgeNoTg}`}>
                                    {user.telegramLinked ? l.tgLinked : l.tgNotLinked}
                                </span>
                            </div>
                        </div>
                    </div>
                </div>

                {/* карточка подписки */}
                <div className={s.card}>
                    <p className={s.cardTitle}>{l.subCard}</p>
                    <div className={s.subBlock}>
                        <div className={s.planRow}>
                            <span className={s.planName}>
                                {sub.plan ?? l.noPlan}
                            </span>
                            <span className={`${s.planBadge} ${planBadgeClass}`}>
                                {planBadgeText}
                            </span>
                        </div>

                        {sub.expiresAt && (
                            <>
                                <div className={s.expiry}>
                                    <span>{l.expires}:</span>
                                    <span className={expired ? s.expiryBad : days <= 5 ? s.expiryWarn : undefined}>
                                        {expired
                                            ? l.expired
                                            : new Date(sub.expiresAt).toLocaleDateString(
                                                lang === 'ru' ? 'ru-RU' : 'en-US',
                                                { day: 'numeric', month: 'long', year: 'numeric' }
                                            )
                                        }
                                    </span>
                                    {!expired && (
                                        <span style={{ color: 'var(--c-ink-3)', fontSize: 12 }}>
                                            ({l.daysLeft(days)})
                                        </span>
                                    )}
                                </div>
                                <div className={s.progressBar}>
                                    <div
                                        className={`${s.progressFill} ${progressClass}`}
                                        style={{ width: `${pct}%` }}
                                    />
                                </div>
                            </>
                        )}

                        {(!sub.status || sub.status === 'TRIAL' || expired) && (
                            <button
                                className={s.upgradeBtn}
                                onClick={() => navigate('/checkout')}
                            >
                                {l.choosePlan}
                            </button>
                        )}
                    </div>
                </div>

                {/* статистика */}
                <div className={`${s.card} ${s.gridFull}`}>
                    <p className={s.cardTitle}>{l.statsCard}</p>
                    <div className={s.statsGrid}>
                        <div className={s.statItem}>
                            <span className={s.statVal}>{totalLeads === null ? '—' : totalLeads}</span>
                            <span className={s.statLabel}>{l.totalLeads}</span>
                        </div>
                        <div className={s.statItem}>
                            <span className={s.statVal}>{newLeads === null ? '—' : newLeads}</span>
                            <span className={s.statLabel}>{l.newLeads}</span>
                        </div>
                        <div className={s.statItem}>
                            <span className={s.statVal} style={{ fontSize: 15, paddingTop: 5 }}>{memberSince}</span>
                            <span className={s.statLabel}>{l.memberSince}</span>
                        </div>
                    </div>
                </div>

                {/* AI персонализация */}
                <div className={`${s.card} ${s.gridFull}`}>
                    <p className={s.cardTitle}>{l.aiCard}</p>

                    {!hasAiFeatures ? (
                        <div style={{
                            padding: '14px 16px',
                            borderRadius: 10,
                            background: 'var(--c-surface)',
                            border: '1.5px solid var(--c-border)',
                            fontSize: 13,
                            color: 'var(--c-ink-3)',
                            display: 'flex',
                            alignItems: 'center',
                            gap: 8,
                            flexWrap: 'wrap',
                        }}>
                            <SparkleIcon />
                            {!user.trialUsed ? l.aiNeedPlanTrial : l.aiNeedPlan}
                            {!user.trialUsed ? (
                                <button
                                    onClick={handleTrialBotOpen}
                                    disabled={trialBotLoading}
                                    style={{
                                        marginLeft: 8,
                                        padding: '4px 12px',
                                        borderRadius: 7,
                                        background: 'var(--c-accent)',
                                        border: 'none',
                                        color: '#fff',
                                        fontSize: 12,
                                        fontWeight: 600,
                                        cursor: trialBotLoading ? 'default' : 'pointer',
                                        fontFamily: 'var(--font-body)',
                                        opacity: trialBotLoading ? 0.7 : 1,
                                        transition: 'opacity .15s',
                                    }}
                                >
                                    {trialBotLoading ? l.trialBtnLoading : l.trialBtn}
                                </button>
                            ) : (
                                <button
                                    onClick={() => navigate('/checkout')}
                                    style={{
                                        marginLeft: 8,
                                        padding: '4px 12px',
                                        borderRadius: 7,
                                        background: 'var(--c-accent)',
                                        border: 'none',
                                        color: '#fff',
                                        fontSize: 12,
                                        fontWeight: 600,
                                        cursor: 'pointer',
                                        fontFamily: 'var(--font-body)',
                                    }}
                                >
                                    {l.choosePlan}
                                </button>
                            )}
                        </div>
                    ) : bizLoading ? (
                        <div style={{ height: 80, background: 'var(--c-surface)', borderRadius: 10, animation: 'pulse 1.5s ease-in-out infinite' }} />
                    ) : (
                        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                            <p style={{ fontSize: 13, color: 'var(--c-ink-3)', margin: 0 }}>{l.aiCardSub}</p>

                            <textarea
                                value={bizContext}
                                onChange={e => setBizContext(e.target.value.slice(0, 2000))}
                                placeholder={l.aiPlaceholder}
                                rows={4}
                                style={{
                                    width: '100%',
                                    border: '1.5px solid var(--c-border)',
                                    borderRadius: 10,
                                    padding: '12px 14px',
                                    fontSize: 13,
                                    color: 'var(--c-ink)',
                                    fontFamily: 'var(--font-body)',
                                    lineHeight: 1.55,
                                    resize: 'vertical',
                                    outline: 'none',
                                    boxSizing: 'border-box',
                                    background: 'var(--c-surface)',
                                    transition: 'border-color .15s',
                                }}
                                onFocus={e => (e.target.style.borderColor = 'var(--c-accent)')}
                                onBlur={e => (e.target.style.borderColor = 'var(--c-border)')}
                            />

                            <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
                                <button
                                    onClick={saveBizContext}
                                    disabled={bizSaving || !bizContextChanged}
                                    style={{
                                        padding: '9px 20px', borderRadius: 9,
                                        background: bizContextChanged ? 'var(--c-accent)' : 'var(--c-surface)',
                                        border: `1.5px solid ${bizContextChanged ? 'var(--c-accent)' : 'var(--c-border)'}`,
                                        color: bizContextChanged ? '#fff' : 'var(--c-ink-3)',
                                        fontSize: 13, fontWeight: 600,
                                        cursor: bizContextChanged ? 'pointer' : 'default',
                                        fontFamily: 'var(--font-body)', transition: 'all .15s',
                                    }}
                                >
                                    {bizSaving ? l.aiSaving : bizSuccess ? l.aiSaved : l.aiSave}
                                </button>

                                <span style={{ fontSize: 12, color: 'var(--c-ink-3)' }}>
                                    {bizContext.length} / 2000
                                </span>
                            </div>

                            {bizError && (
                                <div style={{
                                    padding: '10px 14px', borderRadius: 9,
                                    background: 'rgba(239,68,68,.08)',
                                    border: '1.5px solid rgba(239,68,68,.25)',
                                    color: '#dc2626', fontSize: 13,
                                }}>
                                    {bizError}
                                </div>
                            )}
                        </div>
                    )}
                </div>

                {/* Telegram — не привязан */}
                {!user.telegramLinked && (
                    <div className={`${s.card} ${s.gridFull}`}>
                        <p className={s.cardTitle}>{l.tgCard}</p>
                        <div className={s.tgBlock}>
                            <button className={s.tgConnectBtn} onClick={handleConnectTg} disabled={tgLoading}>
                                {tgLoading ? l.generating : l.tgConnect}
                            </button>
                            {tgError && <span className={s.linkError}>{tgError}</span>}
                            {tgLink && !tgLoading && (
                                <div className={s.linkBox}>
                                    <p className={s.linkBoxHint}>{l.linkHint}</p>
                                    <div className={s.linkRow}>
                                        <div className={s.linkValue}>{tgLink}</div>
                                        <button className={s.copyBtn} onClick={handleCopy}>
                                            {copied ? l.copied : l.copyLink}
                                        </button>
                                        <a href={tgLink} target="_blank" rel="noopener noreferrer" className={s.openBtn}>
                                            {l.openBot}
                                        </a>
                                    </div>
                                </div>
                            )}
                        </div>
                    </div>
                )}

                {/* Telegram — привязан */}
                {user.telegramLinked && (
                    <div className={s.card}>
                        <p className={s.cardTitle}>{l.tgCard}</p>
                        <div className={s.tgBlock}>
                            <div className={s.tgLinkedRow}>
                                <div className={s.tgLinkedIcon}>TG</div>
                                <div>
                                    <div className={s.tgLinkedText}>{l.tgConnected}</div>
                                    <div className={s.tgLinkedSub}>
                                        {user.telegramUsername
                                            ? `@${user.telegramUsername}`
                                            : l.tgLinked}
                                    </div>
                                </div>
                            </div>
                            <button
                                onClick={handleUnlinkTg}
                                disabled={unlinking}
                                style={{
                                    alignSelf:    'flex-start',
                                    padding:      '7px 14px',
                                    borderRadius: 8,
                                    border:       '1.5px solid rgba(239,68,68,.3)',
                                    background:   'rgba(239,68,68,.06)',
                                    color:        '#dc2626',
                                    fontSize:     13,
                                    fontWeight:   600,
                                    cursor:       unlinking ? 'default' : 'pointer',
                                    fontFamily:   'var(--font-body)',
                                    transition:   'all .15s',
                                    marginTop:    8,
                                    opacity:      unlinking ? 0.5 : 1,
                                }}
                            >
                                {unlinking ? '...' : l.tgUnlink}
                            </button>
                            {unlinkError && (
                                <span style={{ fontSize: 13, color: '#dc2626' }}>{unlinkError}</span>
                            )}
                        </div>
                    </div>
                )}

            </div>
        </div>
    )
}