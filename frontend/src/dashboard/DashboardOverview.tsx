import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import type { Lang } from '../i18n/translations'
import { authApi, referralApi, type ReferralStatsDto } from '../api/auth'
import { leadsApi, feedbackApi, chatsApi, keywordsApi } from '../api/leads'
import { useAuthContext } from '../context/AuthContext'
import s from './DashboardOverview.module.css'

const txt = {
    ru: {
        title:          'Обзор',
        welcome:        'Добро пожаловать в AIMLY',
        tgAlertTitle:   'Подключить агента для получения лидов',
        tgAlertTitleTrial:   'Запустите бота и получите 5 дней бесплатно',
        tgAlertBtn:     'Подключить через бота',
        tgAlertBtnAlt:  'Скопировать ссылку',
        howTitle:       'Как подключить Telegram',
        step1Title:     'Нажмите «Подключить через бота»',
        step1Desc:      'Откроется Telegram с нашим ботом — нажмите Start.',
        step2Title:     'Нажмите Start',
        step2Desc:      'Бот автоматически привяжет ваш аккаунт AIMLY.',
        step3Title:     'Готово',
        step3Desc:      'Ваш Telegram привязан. Выберите чаты и настройте ключевые слова.',
        setupTitle:     'Настройка системы',
        setupSub:       'Завершите настройку для начала работы',
        setupStep1:     'Подключить Telegram',
        setupStep2:     'Выбрать чаты',
        setupStep3:     'Установить ключевые слова',
        stepsLeft:      'Осталось шагов',
        stepsComplete:  'Завершите настройку для запуска',
        tgLinked:       'Telegram подключён',
        genLinkLoading: 'Генерируем ссылку...',
        linkHint:       'Откройте ссылку и нажмите Start в боте:',
        openLink:       'Открыть бота',
        copyLink:       'Скопировать',
        copied:         'Скопировано',
        linkError:      'Не удалось сгенерировать ссылку',
        statLeads:      'Всего лидов',
        statNew:        'Новых лидов',
        statChats:      'Активных чатов',
        statKeywords:   'Ключевых слов',
        lastLead:       'Последний лид',
        lastLeadUnrated:'Ожидает оценки',
        lastLeadEmpty:  'Лидов пока нет',
        lastLeadSub:    'Добавьте чаты и ключевые слова — первые лиды придут сюда',
        openMsg:        'Открыть сообщение',
        linkUnavailable:'Ссылка недоступна',
        linkNote:       'Если ссылка не открывается — проверьте настройки приватности чата',
        viewAll:        'Все лиды →',
        queueHint:      'лидов ожидают доставки в Telegram',
        rateToUnlock:   'Оцените лид, чтобы получить следующий',
        ratingGood:     'Полезный',
        ratingBad:      'Нерелевантный',
        ratingSaved:    'Оценка сохранена',
        // Referral
        refTitle:       'Реферальная программа',
        refSub:         'Приглашайте друзей — получайте бонусные дни',
        refBonus:       'Бонусных дней',
        refInvited:     'Перешли по ссылке',
        refPaid:        'Оплатили подписку',
        refLinkLabel:   'Ваша реферальная ссылка',
        refCopy:        'Скопировать',
        refCopied:      'Скопировано!',
        refBonusHint:   'Используются автоматически если автоплатёж не пройдёт',
        refPerRef:      '+7 дней за каждого оплатившего',
    },
    en: {
        title:          'Overview',
        welcome:        'Welcome to AIMLY',
        tgAlertTitle:   'Connect agent to receive leads',
        tgAlertTitleTrial:   'Launch bot and get 5 days free trial',
        tgAlertBtn:     'Connect via bot',
        tgAlertBtnAlt:  'Copy link',
        howTitle:       'How to connect Telegram',
        step1Title:     'Click "Connect via bot"',
        step1Desc:      'Telegram will open with our bot — click Start.',
        step2Title:     'Click Start',
        step2Desc:      'The bot will automatically link your AIMLY account.',
        step3Title:     'Done',
        step3Desc:      'Telegram is linked. Select chats and set keywords.',
        setupTitle:     'System setup',
        setupSub:       'Complete setup to get started',
        setupStep1:     'Connect Telegram',
        setupStep2:     'Select chats',
        setupStep3:     'Set keywords',
        stepsLeft:      'Steps left',
        stepsComplete:  'Complete setup to launch',
        tgLinked:       'Telegram connected',
        genLinkLoading: 'Generating link...',
        linkHint:       'Open the link and click Start in the bot:',
        openLink:       'Open bot',
        copyLink:       'Copy',
        copied:         'Copied',
        linkError:      'Failed to generate link',
        statLeads:      'Total leads',
        statNew:        'New leads',
        statChats:      'Active chats',
        statKeywords:   'Keywords',
        lastLead:       'Latest lead',
        lastLeadUnrated:'Awaiting rating',
        lastLeadEmpty:  'No leads yet',
        lastLeadSub:    'Add chats and keywords — your first leads will appear here',
        openMsg:        'Open message',
        linkUnavailable:'Link unavailable',
        linkNote:       'If the link does not open — check the chat privacy settings',
        viewAll:        'All leads →',
        queueHint:      'leads waiting for Telegram delivery',
        rateToUnlock:   'Rate this lead to receive the next one',
        ratingGood:     'Relevant',
        ratingBad:      'Not relevant',
        ratingSaved:    'Rating saved',
        // Referral
        refTitle:       'Referral program',
        refSub:         'Invite friends — earn bonus days',
        refBonus:       'Bonus days',
        refInvited:     'Clicked your link',
        refPaid:        'Paid subscription',
        refLinkLabel:   'Your referral link',
        refCopy:        'Copy',
        refCopied:      'Copied!',
        refBonusHint:   'Used automatically if autopayment fails',
        refPerRef:      '+7 days per paid referral',
    },
} as const

// ─── SVG-иконки ───────────────────────────────────────────────────────────────

const IconWarning = () => (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/>
        <line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/>
    </svg>
)

const IconCheck = () => (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
        <polyline points="20 6 9 17 4 12"/>
    </svg>
)

const IconGear = () => (
    <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <circle cx="12" cy="12" r="3"/>
        <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/>
    </svg>
)

const IconInfo = () => (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/>
    </svg>
)

const IconExternal = () => (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/>
        <polyline points="15 3 21 3 21 9"/><line x1="10" y1="14" x2="21" y2="3"/>
    </svg>
)

const IconThumbUp = () => (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M14 9V5a3 3 0 0 0-3-3l-4 9v11h11.28a2 2 0 0 0 2-1.7l1.38-9a2 2 0 0 0-2-2.3H14z"/>
        <path d="M7 22H4a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2h3"/>
    </svg>
)

const IconThumbDown = () => (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M10 15v4a3 3 0 0 0 3 3l4-9V2H5.72a2 2 0 0 0-2 1.7l-1.38 9a2 2 0 0 0 2 2.3H10z"/>
        <path d="M17 2h2.67A2.31 2.31 0 0 1 22 4v7a2.31 2.31 0 0 1-2.33 2H17"/>
    </svg>
)

const IconClock = () => (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <circle cx="12" cy="12" r="10"/>
        <polyline points="12 6 12 12 16 14"/>
    </svg>
)

// ─── Компонент ────────────────────────────────────────────────────────────────

interface Props { lang: Lang }

export default function DashboardOverview({ lang }: Props) {
    const l = txt[lang]
    const {user, refreshUser} = useAuthContext()
    const navigate = useNavigate()

    const [tgLink, setTgLink] = useState<string | null>(null)
    const [tgLoading, setTgLoading] = useState(false)
    const [tgError, setTgError] = useState<string | null>(null)
    const [copied, setCopied] = useState(false)

    const [totalLeads, setTotalLeads] = useState<number | null>(null)
    const [newLeads, setNewLeads] = useState<number | null>(null)
    const [chatsCount, setChatsCount] = useState<number | null>(null)
    const [keywordsCount, setKeywordsCount] = useState<number | null>(null)

    // Последний лид для отображения: приоритет — последний неоценённый уведомлённый,
    // иначе — просто первый активный
    const [lastLead, setLastLead] = useState<import('../api/leads').Lead | null>(null)

    // Состояние оценки лида на дашборде
    const [dashRating, setDashRating] = useState<'GOOD' | 'BAD' | null>(null)
    const [dashRatingBusy, setDashRatingBusy] = useState(false)
    const [dashRatingSaved, setDashRatingSaved] = useState(false)

    // Счётчик очереди
    const [queueSize, setQueueSize] = useState(0)

    // ── Referral ────────────────────────────────────────────────────────────
    const [referralStats, setReferralStats] = useState<ReferralStatsDto | null>(null)
    const [refCopied, setRefCopied] = useState(false)

    useEffect(() => {
        // Загружаем лиды и статус очереди параллельно
        Promise.all([
            leadsApi.list({size: 20}),
            feedbackApi.getStatus(),
        ]).then(([p, status]) => {
            setTotalLeads(p.totalElements)
            setNewLeads(p.newCount)
            setQueueSize(status.queueSize)

            // Ищем последний лид, который был уведомлён в TG, но ещё не оценён —
            // именно он «блокирует» следующий лид в очереди
            const unratedNotified = p.content.find(l =>
                l.tgNotifiedAt !== null && (l.userRating ?? null) === null
            ) ?? null

            // Если такого нет — показываем просто первый активный
            const firstActive = p.content.find(l => l.status !== 'IGNORED') ?? null

            const target = unratedNotified ?? firstActive
            setLastLead(target)
            if (target) setDashRating(target.userRating ?? null)
        }).catch(() => {
        })

        chatsApi.list()
            .then(list => setChatsCount(list.filter(c => c.isActive).length))
            .catch(() => {
            })

        keywordsApi.list()
            .then(list => setKeywordsCount(list.filter(k => k.isActive).length))
            .catch(() => {
            })

        referralApi.getStats()
            .then(setReferralStats)
            .catch(() => {
            })
    }, [])

    if (!user) return null

    const tgLinked = user.telegramLinked
    const steps = [tgLinked, (chatsCount ?? 0) > 0, (keywordsCount ?? 0) > 0]
    const stepsLeft = steps.filter(v => !v).length
    const stepRoutes = ['/dashboard/profile', '/dashboard/chats', '/dashboard/keywords']

    // ── Handlers ────────────────────────────────────────────────────────────

    const handleConnectBot = async () => {
        setTgLoading(true)
        setTgError(null)
        try {
            const res = await authApi.getTelegramLink()
            const link = `https://t.me/${res.botUsername}?start=${res.linkToken}`
            setTgLink(link)
            window.open(link, '_blank', 'noopener,noreferrer')
            const check = setInterval(async () => {
                await refreshUser()
            }, 3000)
            setTimeout(() => clearInterval(check), 60_000)
        } catch {
            setTgError(l.linkError)
        } finally {
            setTgLoading(false)
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

    const handleCopyReferral = async () => {
        if (!referralStats?.referralLink) return
        try {
            await navigator.clipboard.writeText(referralStats.referralLink)
            setRefCopied(true)
            setTimeout(() => setRefCopied(false), 2500)
        } catch {
            // fff
        }
        }

        /**
         * Оценка лида прямо с дашборда.
         * После успешной оценки обновляет счётчик очереди.
         */
        const handleDashRating = async (rating: 'GOOD' | 'BAD') => {
            if (!lastLead || dashRatingBusy) return
            if (dashRating === rating) return   // не делаем лишний запрос на ту же оценку

            setDashRatingBusy(true)
            // Оптимистичное обновление
            const prev = dashRating
            setDashRating(rating)
            try {
                const res = await feedbackApi.submit(lastLead.id, rating)
                setDashRatingSaved(true)
                setTimeout(() => setDashRatingSaved(false), 2000)
                if (res.queueEmpty) {
                    setQueueSize(0)
                } else {
                    const s = await feedbackApi.getStatus()
                    setQueueSize(s.queueSize)
                }
            } catch {
                // Откат
                setDashRating(prev)
            } finally {
                setDashRatingBusy(false)
            }
        }

        const fmt = (v: number | null) => v === null ? '—' : String(v)

        const bonusDays = referralStats?.bonusDaysLeft ?? 0
        const totalReferrals = referralStats?.totalReferrals ?? 0
        const paidReferrals = referralStats?.paidReferrals ?? 0

        // Лид неоценённый и был уведомлён в TG — нужна оценка для разблокировки очереди
        const isLastLeadBlocking = lastLead
            ? lastLead.tgNotifiedAt !== null && dashRating === null
            : false

        return (
            <div className={s.page}>
                <div className={s.pageHead}>
                    <h1 className={s.pageTitle}>{l.title}</h1>
                    <p className={s.pageSub}>{l.welcome}</p>
                </div>

                {/* ─── Статистика ─── */}
                <div className={s.statsRow}>
                    <div className={s.statCard}>
                        <div className={s.statVal}>{fmt(totalLeads)}</div>
                        <div className={s.statLabel}>{l.statLeads}</div>
                    </div>
                    <div className={s.statCard}>
                        <div
                            className={`${s.statVal} ${(newLeads ?? 0) > 0 ? s.statValAccent : ''}`}>{fmt(newLeads)}</div>
                        <div className={s.statLabel}>{l.statNew}</div>
                    </div>
                    <div className={s.statCard}>
                        <div className={s.statVal}>{fmt(chatsCount)}</div>
                        <div className={s.statLabel}>{l.statChats}</div>
                    </div>
                    <div className={s.statCard}>
                        <div className={s.statVal}>{fmt(keywordsCount)}</div>
                        <div className={s.statLabel}>{l.statKeywords}</div>
                    </div>
                </div>

                {/* ─── Последний лид (с логикой оценки) ─── */}
                <div style={{
                    background: 'var(--c-surface)',
                    border: `1.5px solid ${isLastLeadBlocking ? 'rgba(245,158,11,.4)' : 'var(--c-border)'}`,
                    borderRadius: 16,
                    padding: '22px 24px',
                    display: 'flex',
                    flexDirection: 'column',
                    gap: 14,
                    transition: 'border-color .2s',
                }}>
                    {/* Заголовок блока */}
                    <div style={{display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12}}>
                        <div style={{display: 'flex', alignItems: 'center', gap: 8}}>
                        <span style={{
                            fontSize: 13, fontWeight: 700,
                            color: 'var(--c-ink-2)',
                            textTransform: 'uppercase', letterSpacing: '.5px',
                        }}>
                            {isLastLeadBlocking ? l.lastLeadUnrated : l.lastLead}
                        </span>

                            {/* Счётчик очереди */}
                            {queueSize > 0 && (
                                <span style={{
                                    display: 'inline-flex', alignItems: 'center', gap: 4,
                                    fontSize: 11, fontWeight: 600,
                                    padding: '2px 8px', borderRadius: 100,
                                    background: 'rgba(245,158,11,.12)',
                                    color: '#d97706',
                                    border: '1px solid rgba(245,158,11,.25)',
                                }}>
                                <IconClock/>
                                +{queueSize} {lang === 'ru' ? 'в очереди' : 'in queue'}
                            </span>
                            )}
                        </div>
                        <a href="/dashboard/leads"
                           style={{fontSize: 13, color: 'var(--c-accent)', fontWeight: 600, textDecoration: 'none'}}>
                            {l.viewAll}
                        </a>
                    </div>

                    {lastLead ? (
                        <div style={{display: 'flex', flexDirection: 'column', gap: 14}}>
                            {/* Автор + текст */}
                            <div style={{display: 'flex', alignItems: 'flex-start', gap: 14}}>
                                <div style={{
                                    width: 44, height: 44, borderRadius: '50%', flexShrink: 0,
                                    background: isLastLeadBlocking ? 'rgba(245,158,11,.12)' : 'var(--c-accent-soft)',
                                    color: isLastLeadBlocking ? '#d97706' : 'var(--c-accent)',
                                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                                    fontWeight: 800, fontSize: 18, fontFamily: 'var(--font-head)',
                                }}>
                                    {(lastLead.authorName || lastLead.authorUsername || lastLead.chatTitle || '?')
                                        .replace('@', '').charAt(0).toUpperCase()}
                                </div>
                                <div style={{flex: 1, minWidth: 0}}>
                                    <div style={{
                                        display: 'flex',
                                        alignItems: 'center',
                                        gap: 7,
                                        flexWrap: 'wrap',
                                        marginBottom: 2
                                    }}>
                                        {lastLead.authorName?.trim() && (
                                            <span style={{fontSize: 15, fontWeight: 700, color: 'var(--c-ink)'}}>
                                            {lastLead.authorName}
                                        </span>
                                        )}
                                        {lastLead.authorUsername && (
                                            <span style={{fontSize: 13, color: 'var(--c-ink-3)'}}>
                                            @{lastLead.authorUsername}
                                        </span>
                                        )}
                                        {lastLead.matchedKeyword && (
                                            <span style={{
                                                fontSize: 11, fontWeight: 700,
                                                padding: '2px 7px', borderRadius: 100,
                                                background: 'rgba(99,102,241,.1)', color: '#4f46e5',
                                                border: '1px solid rgba(99,102,241,.2)',
                                            }}>
                                            {lastLead.matchedKeyword}
                                        </span>
                                        )}
                                    </div>
                                    <p style={{
                                        fontSize: 14, color: 'var(--c-ink-2)', lineHeight: 1.55,
                                        margin: 0,
                                        display: '-webkit-box',
                                        WebkitLineClamp: 3,
                                        WebkitBoxOrient: 'vertical',
                                        overflow: 'hidden',
                                    }}>
                                        {lastLead.messageText}
                                    </p>
                                </div>
                            </div>

                            {/* AI-причина */}
                            {lastLead.aiValid !== null && lastLead.aiValid !== undefined && (
                                <div style={{
                                    display: 'flex', alignItems: 'flex-start', gap: 8,
                                    fontSize: 12, padding: '7px 11px', borderRadius: 8,
                                    background: lastLead.aiValid ? 'rgba(16,185,129,.07)' : 'rgba(239,68,68,.07)',
                                    border: `1px solid ${lastLead.aiValid ? 'rgba(16,185,129,.18)' : 'rgba(239,68,68,.18)'}`,
                                }}>
                                <span style={{
                                    fontWeight: 700,
                                    flexShrink: 0,
                                    color: lastLead.aiValid ? '#10b981' : '#ef4444'
                                }}>
                                    AI {lastLead.aiValid ? '✓' : '✗'}
                                </span>
                                    {lastLead.aiReason && (
                                        <span style={{fontSize: 12, color: 'var(--c-ink-2)', lineHeight: 1.45}}>
                                        {lastLead.aiReason}
                                    </span>
                                    )}
                                </div>
                            )}

                            {/* ── Блок оценки ── */}
                            <div style={{
                                display: 'flex',
                                flexDirection: 'column',
                                gap: 10,
                                padding: '14px 16px',
                                background: 'var(--c-bg)',
                                border: `1px solid ${isLastLeadBlocking ? 'rgba(245,158,11,.3)' : 'var(--c-border)'}`,
                                borderRadius: 12,
                            }}>
                                {/* Заголовок блока оценки */}
                                <div style={{
                                    display: 'flex',
                                    alignItems: 'center',
                                    justifyContent: 'space-between',
                                    gap: 8
                                }}>
                                <span style={{
                                    fontSize: 12, fontWeight: 700,
                                    color: isLastLeadBlocking ? '#d97706' : 'var(--c-ink-3)',
                                    display: 'flex', alignItems: 'center', gap: 5,
                                    textTransform: 'uppercase', letterSpacing: '.35px',
                                }}>
                                    {isLastLeadBlocking && <IconClock/>}
                                    {isLastLeadBlocking
                                        ? l.rateToUnlock
                                        : (lang === 'ru' ? 'Оценка лида' : 'Lead rating')
                                    }
                                </span>

                                    {dashRatingSaved && (
                                        <span style={{
                                            fontSize: 12, fontWeight: 600, color: '#059669',
                                            display: 'flex', alignItems: 'center', gap: 4,
                                        }}>
                                        <IconCheck/>
                                            {l.ratingSaved}
                                    </span>
                                    )}
                                </div>

                                {/* Кнопки оценки */}
                                <div style={{display: 'flex', gap: 8}}>
                                    {/* GOOD */}
                                    <button
                                        onClick={() => void handleDashRating('GOOD')}
                                        disabled={dashRatingBusy}
                                        style={{
                                            flex: 1,
                                            display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6,
                                            padding: '9px 16px', borderRadius: 10,
                                            border: dashRating === 'GOOD'
                                                ? '1.5px solid rgba(16,185,129,.6)'
                                                : '1.5px solid var(--c-border)',
                                            background: dashRating === 'GOOD'
                                                ? 'rgba(16,185,129,.12)'
                                                : 'var(--c-surface)',
                                            color: dashRating === 'GOOD' ? '#059669' : 'var(--c-ink-2)',
                                            fontSize: 13, fontWeight: 600,
                                            cursor: dashRatingBusy ? 'default' : 'pointer',
                                            transition: 'all .15s',
                                            fontFamily: 'var(--font-body)',
                                            opacity: dashRatingBusy ? 0.6 : 1,
                                        }}
                                        onMouseEnter={e => {
                                            if (dashRatingBusy || dashRating === 'GOOD') return
                                            const el = e.currentTarget
                                            el.style.borderColor = 'rgba(16,185,129,.4)'
                                            el.style.color = '#059669'
                                        }}
                                        onMouseLeave={e => {
                                            if (dashRatingBusy || dashRating === 'GOOD') return
                                            const el = e.currentTarget
                                            el.style.borderColor = 'var(--c-border)'
                                            el.style.color = 'var(--c-ink-2)'
                                        }}
                                    >
                                        <IconThumbUp/>
                                        {l.ratingGood}
                                    </button>

                                    {/* BAD */}
                                    <button
                                        onClick={() => void handleDashRating('BAD')}
                                        disabled={dashRatingBusy}
                                        style={{
                                            flex: 1,
                                            display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6,
                                            padding: '9px 16px', borderRadius: 10,
                                            border: dashRating === 'BAD'
                                                ? '1.5px solid rgba(239,68,68,.5)'
                                                : '1.5px solid var(--c-border)',
                                            background: dashRating === 'BAD'
                                                ? 'rgba(239,68,68,.1)'
                                                : 'var(--c-surface)',
                                            color: dashRating === 'BAD' ? '#dc2626' : 'var(--c-ink-2)',
                                            fontSize: 13, fontWeight: 600,
                                            cursor: dashRatingBusy ? 'default' : 'pointer',
                                            transition: 'all .15s',
                                            fontFamily: 'var(--font-body)',
                                            opacity: dashRatingBusy ? 0.6 : 1,
                                        }}
                                        onMouseEnter={e => {
                                            if (dashRatingBusy || dashRating === 'BAD') return
                                            const el = e.currentTarget
                                            el.style.borderColor = 'rgba(239,68,68,.4)'
                                            el.style.color = '#dc2626'
                                        }}
                                        onMouseLeave={e => {
                                            if (dashRatingBusy || dashRating === 'BAD') return
                                            const el = e.currentTarget
                                            el.style.borderColor = 'var(--c-border)'
                                            el.style.color = 'var(--c-ink-2)'
                                        }}
                                    >
                                        <IconThumbDown/>
                                        {l.ratingBad}
                                    </button>
                                </div>

                                {/* Подсказка когда есть очередь */}
                                {queueSize > 0 && dashRating === null && (
                                    <p style={{
                                        margin: 0, fontSize: 12, color: '#d97706',
                                        lineHeight: 1.5,
                                    }}>
                                        {lang === 'ru'
                                            ? `Ещё ${queueSize} ${queueSize === 1 ? 'лид ожидает' : 'лидов ожидают'} — оцените текущий, чтобы получить следующий`
                                            : `${queueSize} more ${queueSize === 1 ? 'lead is' : 'leads are'} waiting — rate this one to receive the next`
                                        }
                                    </p>
                                )}
                            </div>

                            {/* Действия */}
                            <div style={{display: 'flex', gap: 10, flexWrap: 'wrap'}}>
                                {lastLead.messageLink ? (
                                    <a
                                        href={lastLead.messageLink}
                                        target="_blank"
                                        rel="noopener noreferrer"
                                        title={l.linkNote}
                                        style={{
                                            display: 'inline-flex', alignItems: 'center', gap: 6,
                                            padding: '9px 18px', borderRadius: 9,
                                            background: 'var(--c-accent)', color: '#fff',
                                            fontSize: 13, fontWeight: 600,
                                            textDecoration: 'none', transition: 'opacity .15s',
                                        }}
                                        onMouseEnter={e => (e.currentTarget as HTMLAnchorElement).style.opacity = '0.85'}
                                        onMouseLeave={e => (e.currentTarget as HTMLAnchorElement).style.opacity = '1'}
                                    >
                                        {l.openMsg}<IconExternal/>
                                    </a>
                                ) : (
                                    <span title={l.linkNote} style={{
                                        display: 'inline-flex', alignItems: 'center', gap: 6,
                                        padding: '9px 18px', borderRadius: 9,
                                        border: '1.5px solid var(--c-border)',
                                        color: 'var(--c-ink-3)', fontSize: 13, fontWeight: 600, cursor: 'default',
                                    }}>
                                    {l.linkUnavailable}
                                </span>
                                )}
                                <a
                                    href="/dashboard/leads"
                                    style={{
                                        display: 'inline-flex', alignItems: 'center', gap: 6,
                                        padding: '9px 18px', borderRadius: 9,
                                        border: '1.5px solid var(--c-border)',
                                        color: 'var(--c-ink-2)', fontSize: 13, fontWeight: 600,
                                        textDecoration: 'none', transition: 'all .15s',
                                    }}
                                    onMouseEnter={e => {
                                        const el = e.currentTarget as HTMLAnchorElement
                                        el.style.borderColor = 'var(--c-accent)'
                                        el.style.color = 'var(--c-accent)'
                                    }}
                                    onMouseLeave={e => {
                                        const el = e.currentTarget as HTMLAnchorElement
                                        el.style.borderColor = 'var(--c-border)'
                                        el.style.color = 'var(--c-ink-2)'
                                    }}
                                >
                                    {l.viewAll}
                                </a>
                            </div>
                        </div>
                    ) : (
                        <div style={{
                            display: 'flex',
                            flexDirection: 'column',
                            alignItems: 'center',
                            gap: 8,
                            padding: '24px 0',
                            textAlign: 'center'
                        }}>
                            <div style={{
                                width: 48, height: 48, borderRadius: '50%',
                                background: 'var(--c-bg)', border: '1.5px solid var(--c-border)',
                                display: 'flex', alignItems: 'center', justifyContent: 'center',
                                fontSize: 22, marginBottom: 4,
                            }}>
                                <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                                     strokeWidth="1.5">
                                    <path d="M22 12h-4l-3 9L9 3l-3 9H2"/>
                                </svg>
                            </div>
                            <span
                                style={{fontSize: 15, fontWeight: 600, color: 'var(--c-ink)'}}>{l.lastLeadEmpty}</span>
                            <span style={{fontSize: 13, color: 'var(--c-ink-3)', maxWidth: 320}}>{l.lastLeadSub}</span>
                        </div>
                    )}
                </div>

                {/* ─── Telegram ─── */}
                {!tgLinked ? (
                    <div className={s.tgAlert}>
                        <div className={s.tgAlertLeft}>
                            <div className={s.tgAlertIcon}><IconWarning/></div>
                            <div className={s.tgAlertTitle}>
                                {!user.trialUsed ? l.tgAlertTitleTrial : l.tgAlertTitle}
                            </div>
                        </div>
                        <div style={{display: 'flex', gap: 8, flexWrap: 'wrap'}}>
                            <button className={s.tgAlertBtn} onClick={handleConnectBot} disabled={tgLoading}>
                                {tgLoading ? l.genLinkLoading : l.tgAlertBtn}
                            </button>
                            {tgLink && (
                                <button onClick={handleCopy} style={{
                                    padding: '11px 18px',
                                    borderRadius: 10,
                                    border: '1.5px solid #fca5a5',
                                    background: '#fff',
                                    color: '#991b1b',
                                    fontSize: 14,
                                    fontWeight: 600,
                                    cursor: 'pointer',
                                    fontFamily: 'var(--font-body)'
                                }}>
                                    {copied ? l.copied : l.tgAlertBtnAlt}
                                </button>
                            )}
                        </div>
                    </div>
                ) : (
                    <div className={s.tgSuccess}>
                        <span className={s.tgSuccessIcon}><IconCheck/></span>
                        <span className={s.tgSuccessText}>{l.tgLinked}</span>
                    </div>
                )}

                {tgError && <div style={{color: '#dc2626', fontSize: 13, padding: '4px 0'}}>{tgError}</div>}

                {!tgLinked && tgLink && (
                    <div className={s.instrCard}>
                        <h3 className={s.instrTitle}>{l.howTitle}</h3>
                        <div className={s.instrSteps}>
                            {[
                                {title: l.step1Title, desc: l.step1Desc},
                                {title: l.step2Title, desc: l.step2Desc},
                                {title: l.step3Title, desc: l.step3Desc},
                            ].map((step, i) => (
                                <div key={i} className={s.instrStep}>
                                    <div className={s.instrNum}>{i + 1}</div>
                                    <div>
                                        <div className={s.instrStepTitle}>{step.title}</div>
                                        <div className={s.instrStepDesc}>{step.desc}</div>
                                    </div>
                                </div>
                            ))}
                        </div>
                        <div className={s.linkBlock}>
                            <p className={s.linkHint}>{l.linkHint}</p>
                            <div className={s.linkRow}>
                                <div className={s.linkValue}>{tgLink}</div>
                                <button className={s.copyBtn}
                                        onClick={handleCopy}>{copied ? l.copied : l.copyLink}</button>
                                <a href={tgLink} target="_blank" rel="noopener noreferrer"
                                   className={s.openBtn}>{l.openLink}</a>
                            </div>
                        </div>
                    </div>
                )}

                {/* ─── Реферальная программа ─── */}
                <div style={{
                    background: 'var(--c-surface)',
                    border: '1.5px solid var(--c-border)',
                    borderRadius: 16,
                    padding: '22px 24px',
                    display: 'flex',
                    flexDirection: 'column',
                    gap: 18,
                }}>
                    <div style={{display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 12}}>
                        <div>
                            <div style={{display: 'flex', alignItems: 'center', gap: 8, marginBottom: 3}}>
                                <span style={{fontSize: 15, fontWeight: 700, color: 'var(--c-ink)'}}>{l.refTitle}</span>
                            </div>
                            <span style={{fontSize: 13, color: 'var(--c-ink-3)'}}>{l.refPerRef}</span>
                        </div>
                        {bonusDays > 0 && (
                            <div style={{
                                display: 'flex', flexDirection: 'column', alignItems: 'center',
                                background: 'rgba(16,185,129,.08)',
                                border: '1.5px solid rgba(16,185,129,.25)',
                                borderRadius: 12, padding: '8px 16px', minWidth: 72,
                                textAlign: 'center', flexShrink: 0,
                            }}>
                            <span style={{
                                fontFamily: 'var(--font-head)',
                                fontSize: 26,
                                fontWeight: 900,
                                color: 'var(--c-green)',
                                lineHeight: 1
                            }}>
                                {bonusDays}
                            </span>
                                <span style={{
                                    fontSize: 11,
                                    color: 'var(--c-green)',
                                    fontWeight: 600,
                                    marginTop: 2
                                }}>{l.refBonus}</span>
                            </div>
                        )}
                    </div>

                    <div style={{display: 'flex', gap: 12}}>
                        <div style={{
                            flex: 1,
                            background: 'var(--c-bg)',
                            border: '1px solid var(--c-border)',
                            borderRadius: 12,
                            padding: '14px 16px',
                            textAlign: 'center'
                        }}>
                            <div style={{
                                fontFamily: 'var(--font-head)',
                                fontSize: 26,
                                fontWeight: 900,
                                color: 'var(--c-ink)',
                                lineHeight: 1
                            }}>
                                {totalReferrals}
                            </div>
                            <div style={{
                                fontSize: 12,
                                color: 'var(--c-ink-3)',
                                marginTop: 4,
                                fontWeight: 500
                            }}>{l.refInvited}</div>
                        </div>
                        <div style={{
                            flex: 1,
                            background: 'var(--c-bg)',
                            border: '1px solid var(--c-border)',
                            borderRadius: 12,
                            padding: '14px 16px',
                            textAlign: 'center'
                        }}>
                            <div style={{
                                fontFamily: 'var(--font-head)',
                                fontSize: 26,
                                fontWeight: 900,
                                color: paidReferrals > 0 ? 'var(--c-accent)' : 'var(--c-ink)',
                                lineHeight: 1
                            }}>
                                {paidReferrals}
                            </div>
                            <div style={{
                                fontSize: 12,
                                color: 'var(--c-ink-3)',
                                marginTop: 4,
                                fontWeight: 500
                            }}>{l.refPaid}</div>
                        </div>
                        {bonusDays === 0 && (
                            <div style={{
                                flex: 1,
                                background: 'var(--c-bg)',
                                border: '1px solid var(--c-border)',
                                borderRadius: 12,
                                padding: '14px 16px',
                                textAlign: 'center'
                            }}>
                                <div style={{
                                    fontFamily: 'var(--font-head)',
                                    fontSize: 26,
                                    fontWeight: 900,
                                    color: 'var(--c-ink)',
                                    lineHeight: 1
                                }}>0
                                </div>
                                <div style={{
                                    fontSize: 12,
                                    color: 'var(--c-ink-3)',
                                    marginTop: 4,
                                    fontWeight: 500
                                }}>{l.refBonus}</div>
                            </div>
                        )}
                    </div>

                    <div>
                        <div style={{
                            fontSize: 12,
                            fontWeight: 600,
                            color: 'var(--c-ink-3)',
                            textTransform: 'uppercase',
                            letterSpacing: '.4px',
                            marginBottom: 8
                        }}>
                            {l.refLinkLabel}
                        </div>
                        <div style={{display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap'}}>
                            <div style={{
                                flex: 1, minWidth: 0, fontSize: 12.5, color: 'var(--c-ink-2)',
                                background: 'var(--c-bg)', border: '1px solid var(--c-border)',
                                borderRadius: 8, padding: '9px 12px',
                                overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                                fontFamily: 'monospace', cursor: 'text', userSelect: 'all',
                            } as React.CSSProperties}>
                                {referralStats?.referralLink ?? '—'}
                            </div>
                            <button
                                onClick={handleCopyReferral}
                                disabled={!referralStats}
                                style={{
                                    padding: '9px 18px', borderRadius: 8,
                                    border: refCopied ? '1.5px solid var(--c-green)' : '1.5px solid var(--c-border)',
                                    background: refCopied ? 'var(--c-green-soft)' : 'var(--c-surface)',
                                    fontSize: 13, fontWeight: 600,
                                    color: refCopied ? 'var(--c-green)' : 'var(--c-ink-2)',
                                    cursor: referralStats ? 'pointer' : 'default',
                                    fontFamily: 'var(--font-body)', transition: 'all .15s',
                                    whiteSpace: 'nowrap', flexShrink: 0,
                                }}
                            >
                                {refCopied ? l.refCopied : l.refCopy}
                            </button>
                        </div>
                        {bonusDays > 0 && (
                            <div style={{
                                marginTop: 8,
                                fontSize: 12,
                                color: 'var(--c-green)',
                                display: 'flex',
                                alignItems: 'center',
                                gap: 5
                            }}>
                                <IconCheck/>
                                <span>{l.refBonusHint}</span>
                            </div>
                        )}
                    </div>
                </div>

                {/* ─── Шаги настройки ─── */}
                {stepsLeft > 0 && (
                    <div className={s.setupRow}>
                        <div className={s.setupCard}>
                            <div className={s.setupCardHead}>
                                <div className={s.setupCardIcon}><IconGear/></div>
                                <div>
                                    <div className={s.setupCardTitle}>{l.setupTitle}</div>
                                    <div className={s.setupCardSub}>{l.setupSub}</div>
                                </div>
                            </div>
                            <div className={s.setupSteps}>
                                {[l.setupStep1, l.setupStep2, l.setupStep3].map((step, i) => (
                                    <div
                                        key={i}
                                        className={`${s.setupStep} ${steps[i] ? s.setupStepDone : ''}`}
                                        onClick={() => !steps[i] && navigate(stepRoutes[i])}
                                        style={{cursor: steps[i] ? 'default' : 'pointer', transition: 'opacity .15s'}}
                                        onMouseEnter={e => {
                                            if (!steps[i]) (e.currentTarget as HTMLDivElement).style.opacity = '0.75'
                                        }}
                                        onMouseLeave={e => {
                                            (e.currentTarget as HTMLDivElement).style.opacity = '1'
                                        }}
                                    >
                                        <div className={s.setupStepNum}>
                                            {steps[i] ? <IconCheck/> : i + 1}
                                        </div>
                                        <span>{step}</span>
                                        {!steps[i] && (
                                            <span style={{
                                                marginLeft: 'auto',
                                                fontSize: 11,
                                                color: 'var(--c-accent)',
                                                fontWeight: 600,
                                                flexShrink: 0
                                            }}>
                                            {lang === 'ru' ? 'Перейти →' : 'Go →'}
                                        </span>
                                        )}
                                    </div>
                                ))}
                            </div>
                        </div>

                        <div className={s.progressCard}>
                            <div className={s.progressNum}>{stepsLeft}</div>
                            <div className={s.progressLabel}>{l.stepsLeft}</div>
                            <div className={s.progressSub}>{l.stepsComplete}</div>
                        </div>
                    </div>
                )}

                {stepsLeft === 0 && !lastLead && (
                    <div style={{
                        display: 'flex', alignItems: 'center', gap: 10,
                        background: 'var(--c-surface)', border: '1px solid var(--c-border)',
                        borderRadius: 12, padding: '14px 18px',
                        color: 'var(--c-green)', fontSize: 13.5, fontWeight: 600,
                    }}>
                    <span style={{
                        width: 28, height: 28, borderRadius: '50%',
                        background: 'var(--c-green-soft)',
                        display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0,
                    }}>
                        <IconInfo/>
                    </span>
                        {lang === 'ru'
                            ? 'Система настроена и работает. Лиды поступают в реальном времени.'
                            : 'System is set up and running. Leads are coming in real time.'
                        }
                    </div>
                )}
            </div>
        )
    }
