import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import type { Lang } from '../i18n/translations'
import { useAuthContext } from '../context/AuthContext'
import { authApi } from '../api/auth'
import AuthModal from '../components/AuthModal'
import s from './Checkout.module.css'

interface Props { lang: Lang; setLang: (l: Lang) => void }


const txt = {
    ru: {
        back:           '← Назад',
        title:          'Оформление подписки',
        sub:            'Выберите тариф и перейдите к оплате',
        perMonth:       '/мес',
        comingSoon:     'В разработке',
        comingSoonDesc: 'Скоро станет доступным.',
        skip:           'Пропустить',
        buyBtn:         (price: number) => `Оплатить — ${price.toLocaleString('ru-RU')} ₽`,
        autoActivate:   'После оплаты подписка активируется автоматически в течение минуты.',
    },
    en: {
        back:           '← Back',
        title:          'Subscription',
        sub:            'Choose a plan and proceed to payment',
        perMonth:       '/month',
        comingSoon:     'Coming soon',
        comingSoonDesc: 'Available soon.',
        skip:           'Skip for now',
        buyBtn:         (price: number) => `Pay — ${price.toLocaleString('en-US')} ₽`,
        autoActivate:   'After payment your subscription activates automatically within a minute.',
    },
} as const


const PLANS = {
    ru: [
        {
            id:       'START',
            name:     'Start',
            price:    4990,
            badge:    'РЕКОМЕНДУЕМ',
            desc:     'Мониторинг с AI и персонализацией',
            features: [
                '✔ Добавление Telegram-чатов',
                '✔ Мониторинг по ключевым словам',
                '✔ Telegram-уведомления о лидах',
                '✔ Лиды без ограничений',
                '✔ AI-семантический поиск лидов',
                '✔ AI-фильтрация контекста сообщений',
                '✔ Персонализация под ваш бизнес',
            ],
            accent:   true,
            disabled: false,
        },
        {
            id:       'BUSINESS',
            name:     'Business',
            price:    9990,
            badge:    null,
            desc:     'Всё из Start плюс расширенные возможности',
            features: [
                '✔ Добавление Telegram-чатов',
                '✔ Мониторинг по ключевым словам',
                '✔ Telegram-уведомления о лидах',
                '✔ Лиды без ограничений',
                '✔ AI-семантический поиск лидов',
                '✔ AI-фильтрация контекста сообщений',
                '✔ Персонализация под ваш бизнес',
                '✔ ИИ продавец',
                '✔ Круглосуточная поддержка',
                '✔ Улучшенный AI',
            ],
            accent:   false,
            disabled: true,
        },
    ],
    en: [
        {
            id:       'START',
            name:     'Start',
            price:    4990,
            badge:    'RECOMMENDED',
            desc:     'Monitoring with AI and personalization',
            features: [
                '✔ Add Telegram chats',
                '✔ Keyword monitoring',
                '✔ Telegram lead notifications',
                '✔ Unlimited leads',
                '✔ AI semantic lead search',
                '✔ AI context message filtering',
                '✔ Business personalization',
            ],
            accent:   true,
            disabled: false,
        },
        {
            id:       'BUSINESS',
            name:     'Business',
            price:    9990,
            badge:    null,
            desc:     'Everything in Start plus advanced features',
            features: [
                '✔ Add Telegram chats',
                '✔ Keyword monitoring',
                '✔ Telegram lead notifications',
                '✔ Unlimited leads',
                '✔ AI semantic lead search',
                '✔ AI context message filtering',
                '✔ Business personalization',
                '✔ AI sales agent',
                '✔ 24/7 support',
                '✔ Enhanced AI',
            ],
            accent:   false,
            disabled: true,
        },
    ],
}

const START_FEATURE_COUNT = 7

export default function Checkout({ lang, setLang }: Props) {
    const l                     = txt[lang]
    const { user, loading }     = useAuthContext()
    const navigate              = useNavigate()
    const plans                 = PLANS[lang]
    const [showAuthModal, setShowAuthModal] = useState(false)
    const [botLoading, setBotLoading]       = useState(false)

    const openBotForPayment = async () => {
        setBotLoading(true)
        try {
            const res  = await authApi.getTelegramLink()
            const link = `https://t.me/${res.botUsername}?start=pay_${res.linkToken}`
            window.open(link, '_blank', 'noopener,noreferrer')
        } catch {
            window.open('https://t.me/aimlyAIbot?start=pay', '_blank', 'noopener,noreferrer')
        } finally {
            setBotLoading(false)
        }
    }

    const handleBuy = () => {
        if (loading || botLoading) return
        if (!user) {
            setShowAuthModal(true)
            return
        }
        openBotForPayment()
    }

    const handleAuthSuccess = () => {
        setShowAuthModal(false)
        setTimeout(openBotForPayment, 300)
    }

    return (
        <div className={s.root}>

            {showAuthModal && (
                <AuthModal
                    lang={lang}
                    onClose={() => setShowAuthModal(false)}
                    onSuccess={handleAuthSuccess}
                    initialTab="register"
                />
            )}

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
                <div className={s.wrap} style={{ maxWidth: 720, margin: '0 auto' }}>
                    <div className={s.left} style={{ width: '100%' }}>
                        <h1 className={s.title}>{l.title}</h1>
                        <p className={s.sub}>{l.sub}</p>

                        <div className={s.plansGrid}>
                            {plans.map(plan => (
                                <div
                                    key={plan.id}
                                    className={`${s.planCard} ${plan.disabled ? s.planCardDisabled : ''}`}
                                    style={{
                                        ...(plan.accent && !plan.disabled ? {
                                            borderColor: 'rgba(92,57,223,.5)',
                                            boxShadow:   '0 8px 32px rgba(92,57,223,.18)',
                                        } : {}),
                                        ...(plan.disabled ? { opacity: 0.6 } : {}),
                                    }}
                                >
                                    {plan.badge && <div className={s.planBadge}>{plan.badge}</div>}
                                    <h3 className={s.planName}>{plan.name}</h3>

                                    {plan.disabled ? (
                                        <>
                                            {plan.desc && <p className={s.planDesc}>{plan.desc}</p>}

                                            <ul className={s.planFeatures}>
                                                {plan.features.map((f, i) => (
                                                    <li
                                                        key={i}
                                                        style={{
                                                            color:   '#ccc',
                                                            opacity: i < START_FEATURE_COUNT ? 0.6 : 1,
                                                        }}
                                                    >
                                                        {f}
                                                    </li>
                                                ))}
                                            </ul>

                                            <div className={s.comingSoonBlock}>
                                                <span className={s.comingSoonLabel}>{l.comingSoon}</span>
                                                <p className={s.comingSoonText}>{l.comingSoonDesc}</p>
                                            </div>
                                        </>
                                    ) : (
                                        <>
                                            {plan.desc && <p className={s.planDesc}>{plan.desc}</p>}

                                            <div className={s.planPrice}>
                                                <span className={s.priceVal}>
                                                    {plan.price.toLocaleString(lang === 'ru' ? 'ru-RU' : 'en-US')}
                                                </span>
                                                <span className={s.currency}>₽</span>
                                                <span className={s.period}>{l.perMonth}</span>
                                            </div>

                                            <ul className={s.planFeatures}>
                                                {plan.features.map((f, i) => (
                                                    <li
                                                        key={i}
                                                        style={{
                                                            color:   f.startsWith('✗') ? '#6b7280' : '#ccc',
                                                            opacity: f.startsWith('✗') ? 0.65 : 1,
                                                        }}
                                                    >
                                                        {f}
                                                    </li>
                                                ))}
                                            </ul>

                                            <button
                                                className={s.buyBtn}
                                                onClick={handleBuy}
                                                disabled={loading || botLoading}
                                            >
                                                {botLoading ? '...' : l.buyBtn(plan.price)}
                                            </button>

                                            <p style={{
                                                fontSize:   12,
                                                color:      'var(--c-ink-3)',
                                                textAlign:  'center',
                                                marginTop:  10,
                                                lineHeight: 1.55,
                                            }}>
                                                {l.autoActivate}
                                            </p>
                                        </>
                                    )}
                                </div>
                            ))}
                        </div>

                        <div style={{ textAlign: 'center', marginTop: 20 }}>
                            <button
                                onClick={() => navigate('/dashboard')}
                                style={{
                                    background:          'none',
                                    border:              'none',
                                    color:               'var(--c-ink-3)',
                                    fontSize:            13,
                                    cursor:              'pointer',
                                    padding:             '6px 12px',
                                    fontFamily:          'var(--font-body)',
                                    transition:          'color .15s',
                                    textDecoration:      'underline',
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