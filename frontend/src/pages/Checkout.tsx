import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import type { Lang } from '../i18n/translations'

import { subscriptionApi } from '../api/auth'
import { useAuthContext } from '../context/AuthContext'
import s from './Checkout.module.css'

interface Props { lang: Lang; setLang: (l: Lang) => void }

const PRICES = { MINIMUM: 4999, START: 4999 }

const txt = {
    ru: {
        back:       '← Назад',
        buyBtn:     (price: number) => `Оплатить — ${price.toLocaleString('ru-RU')} ₽`,
        noBalance:  'Недостаточно средств на балансе',
        buying:     'Оформляем...',
        successMsg: (plan: string, date: string) => `✅ Тариф ${plan} активирован! Действует до ${date}`,
        errMsg:     'Ошибка оплаты. Попробуйте через поддержку.',
        title:      'Оформление подписки',
        sub:        'Выберите подходящий тариф',
        perMonth:   '/мес',
        comingSoon: 'В разработке',
        comingSoonDesc: 'Тариф находится в разработке и будет доступен в ближайшее время.',
        skip:       'Пропустить',
    },
    en: {
        back:       '← Back',
        buyBtn:     (price: number) => `Pay — ${price} ₽`,
        noBalance:  'Insufficient balance',
        buying:     'Purchasing...',
        successMsg: (plan: string, date: string) => `✅ ${plan} plan activated! Valid until ${date}`,
        errMsg:     'Payment error. Please contact support.',
        title:      'Checkout',
        sub:        'Choose the right plan',
        perMonth:   '/month',
        comingSoon: 'Coming soon',
        comingSoonDesc: 'This plan is in development and will be available soon.',
        skip:       'Skip for now',
    },
} as const

const PLANS = {
    ru: [
        {
            id: 'MINIMUM',
            name: 'Минимум',
            price: 4999,
            badge: 'РЕКОМЕНДУЕМ',
            desc: 'Мониторинг с AI и персонализацией',
            features: [
                '✔ Добавление Telegram-чатов',
                '✔ Мониторинг по ключевым словам',
                '✔ Telegram-уведомления о лидах',
                '✔ Лиды без ограничений',
                '✔ AI-семантический поиск лидов',
                '✔ AI-фильтрация контекста сообщений',
                '✔ Персонализация под ваш бизнес',
            ],
            accent: true,
            disabled: false,
        },
        {
            id: 'START',
            name: 'Старт',
            price: 4990,
            badge: null,
            desc: null,
            features: [],
            accent: false,
            disabled: true,
        },
    ],
    en: [
        {
            id: 'MINIMUM',
            name: 'Minimum',
            price: 4999,
            badge: 'RECOMMENDED',
            desc: 'Monitoring with AI and personalization',
            features: [
                '✔ Add Telegram chats',
                '✔ Keyword monitoring',
                '✔ Telegram lead notifications',
                '✔ Unlimited leads',
                '✔ AI semantic lead search',
                '✔ AI context message filtering',
                '✔ Business personalization',
            ],
            accent: true,
            disabled: false,
        },
        {
            id: 'START',
            name: 'Start',
            price: 4990,
            badge: null,
            desc: null,
            features: [],
            accent: false,
            disabled: true,
        },
    ],
}

export default function Checkout({ lang, setLang }: Props) {
    const l = txt[lang]
    const { user, refreshUser } = useAuthContext()
    const navigate = useNavigate()
    const [buying,   setBuying]  = useState<string | null>(null)
    const [msg,      setMsg]     = useState<Record<string, string>>({})
    const [isError,  setIsError] = useState<Record<string, boolean>>({})

    const plans = PLANS[lang]

    const handleBuy = async (planId: string) => {
        const price = PRICES[planId as keyof typeof PRICES]
        const balance = user?.balance ?? 0
        if (balance < price) {
            setIsError(prev => ({ ...prev, [planId]: true }))
            setMsg(prev => ({ ...prev, [planId]: l.noBalance }))
            return
        }
        setBuying(planId)
        setIsError(prev => ({ ...prev, [planId]: false }))
        setMsg(prev => ({ ...prev, [planId]: '' }))
        try {
            const res = await subscriptionApi.purchase(planId)
            await refreshUser()
            const date = new Date(res.expiresAt).toLocaleDateString(
                lang === 'ru' ? 'ru-RU' : 'en-US',
                { day: 'numeric', month: 'long', year: 'numeric' }
            )
            setMsg(prev => ({ ...prev, [planId]: l.successMsg(planId, date) }))
        } catch (e: unknown) {
            setIsError(prev => ({ ...prev, [planId]: true }))
            setMsg(prev => ({ ...prev, [planId]: e instanceof Error ? e.message : l.errMsg }))
        } finally {
            setBuying(null)
        }
    }

    return (
        <div className={s.root}>
            <header className={s.header}>
                <a href="/" className={s.logo}>
                    <img src="/AIMLY.png" alt="AIMLY" className={s.logoImg} />
                    <span>AIMLY</span>
                </a>
                <div className={s.headerRight}>
                    {(['ru', 'en'] as Lang[]).map(lng => (
                        <button
                            key={lng}
                            className={`${s.langBtn} ${lang === lng ? s.langActive : ''}`}
                            onClick={() => setLang(lng)}
                        >
                            {lng.toUpperCase()}
                        </button>
                    ))}
                    <button className={s.backBtn} onClick={() => navigate('/dashboard')}>
                        {l.back}
                    </button>
                </div>
            </header>

            <main className={s.main}>
                <div className={s.wrap} style={{ maxWidth: 680, margin: '0 auto' }}>
                    <div className={s.left} style={{ width: '100%' }}>
                        <h1 className={s.title}>{l.title}</h1>
                        <p className={s.sub}>{l.sub}</p>

                        <div className={s.plansGrid}>
                            {plans.map(plan => (
                                <div
                                    key={plan.id}
                                    className={`${s.planCard} ${plan.disabled ? s.planCardDisabled : ''}`}
                                    style={plan.accent && !plan.disabled ? {
                                        borderColor: 'rgba(92,57,223,.5)',
                                        boxShadow: '0 8px 32px rgba(92,57,223,.18)',
                                    } : undefined}
                                >
                                    {plan.badge && <div className={s.planBadge}>{plan.badge}</div>}
                                    <h3 className={s.planName}>{plan.name}</h3>

                                    {plan.disabled ? (
                                        <div className={s.comingSoonBlock}>
                                            <span className={s.comingSoonLabel}>{l.comingSoon}</span>
                                            <p className={s.comingSoonText}>{l.comingSoonDesc}</p>
                                        </div>
                                    ) : (
                                        <>
                                            {plan.desc && <p className={s.planDesc}>{plan.desc}</p>}
                                            <div className={s.planPrice}>
                                                <span className={s.priceVal}>{plan.price.toLocaleString(lang === 'ru' ? 'ru-RU' : 'en-US')}</span>
                                                <span className={s.currency}>₽</span>
                                                <span className={s.period}>{l.perMonth}</span>
                                            </div>
                                            <ul className={s.planFeatures}>
                                                {plan.features.map((f, i) => (
                                                    <li key={i} style={{ color: f.startsWith('✗') ? '#6b7280' : '#ccc', opacity: f.startsWith('✗') ? 0.65 : 1 }}>{f}</li>
                                                ))}
                                            </ul>
                                            <button
                                                className={s.buyBtn}
                                                onClick={() => handleBuy(plan.id)}
                                                disabled={buying === plan.id}
                                                style={!plan.accent ? { background: 'rgba(255,255,255,.08)', color: 'rgba(255,255,255,.7)' } : undefined}
                                            >
                                                {buying === plan.id ? l.buying : l.buyBtn(plan.price)}
                                            </button>
                                            {msg[plan.id] && (
                                                <div className={isError[plan.id] ? s.errorMsg : s.successMsg}>
                                                    {msg[plan.id]}
                                                </div>
                                            )}
                                        </>
                                    )}
                                </div>
                            ))}
                        </div>

                        {/* Пропустить — ведёт в личный кабинет без тарифа */}
                        <div style={{ textAlign: 'center', marginTop: 20 }}>
                            <button
                                onClick={() => navigate('/dashboard')}
                                style={{
                                    background: 'none',
                                    border: 'none',
                                    color: 'var(--c-ink-3)',
                                    fontSize: 13,
                                    cursor: 'pointer',
                                    padding: '6px 12px',
                                    fontFamily: 'var(--font-body)',
                                    transition: 'color .15s',
                                    textDecoration: 'underline',
                                    textUnderlineOffset: 3,
                                }}
                                onMouseEnter={e => (e.currentTarget as HTMLButtonElement).style.color = 'var(--c-ink-2)'}
                                onMouseLeave={e => (e.currentTarget as HTMLButtonElement).style.color = 'var(--c-ink-3)'}
                            >
                                {l.skip}
                            </button>
                        </div>
                    </div>
                </div>
            </main>
        </div>
    )
}