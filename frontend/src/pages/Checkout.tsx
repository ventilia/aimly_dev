import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import type { Lang } from '../i18n/translations'

import { subscriptionApi } from '../api/auth'
import { useAuthContext } from '../context/AuthContext'
import s from './Checkout.module.css'

interface Props { lang: Lang; setLang: (l: Lang) => void }


const PRICES = { MINIMUM: 2900, START: 19990 }

const txt = {
    ru: {
        back:        '← Назад',
        balanceBtn:  (price: number) => `Оплатить с баланса — ${price} ₽`,
        supportBtn:  'Оплатить через поддержку',
        supportNote: 'Свяжитесь с нами для оплаты',
        noBalance:   'Недостаточно средств на балансе',
        buying:      'Оформляем...',
        successMsg:  (plan: string, date: string) => `✅ Тариф ${plan} активирован! Действует до ${date}`,
        errMsg:      'Ошибка оплаты. Попробуйте через поддержку.',
        title:       'Оформление подписки',
        sub:         'Выберите подходящий тариф',
        perMonth:    '/мес',
    },
    en: {
        back:        '← Back',
        balanceBtn:  (price: number) => `Pay from balance — ${price} ₽`,
        supportBtn:  'Pay via support',
        supportNote: 'Contact us to complete payment',
        noBalance:   'Insufficient balance',
        buying:      'Purchasing...',
        successMsg:  (plan: string, date: string) => `✅ ${plan} plan activated! Valid until ${date}`,
        errMsg:      'Payment error. Try via support.',
        title:       'Checkout',
        sub:         'Choose the right plan',
        perMonth:    '/month',
    },
} as const

const PLANS = {
    ru: [
        {
            id: 'MINIMUM',
            name: 'Минимум',
            price: 2900,
            badge: null,
            desc: 'Базовый мониторинг по ключевым словам',
            features: [
                '✔ Добавление Telegram-чатов',
                '✔ Мониторинг по ключевым словам',
                '✔ Telegram-уведомления о лидах',
                '✔ Лиды без ограничений',
                '✗ AI-фильтрация и проверка лидов',
                '✗ Семантический поиск и синонимы',
                '✗ Персонализация под ваш бизнес',
                '✗ AI-расширение ключевых слов',
            ],
            accent: false,
        },
        {
            id: 'START',
            name: 'Старт',
            price: 19990,
            badge: 'РЕКОМЕНДУЕМ',
            desc: 'Умный поиск с AI-фильтрацией',
            features: [
                '✔ Всё из тарифа Минимум',
                '✔ AI-фильтрация нерелевантных лидов',
                '✔ Семантический поиск (синонимы, морфология)',
                '✔ AI-расширение ключевых слов',
                '✔ Персонализация под ваш бизнес',
                '✔ Умная фильтрация контекста',
                '✔ Telegram-уведомления с AI-оценкой',
            ],
            accent: true,
        },
    ],
    en: [
        {
            id: 'MINIMUM',
            name: 'Minimum',
            price: 2900,
            badge: null,
            desc: 'Basic keyword monitoring',
            features: [
                '✔ Add Telegram chats',
                '✔ Keyword monitoring',
                '✔ Telegram lead notifications',
                '✔ Unlimited leads',
                '✗ AI filtering and lead validation',
                '✗ Semantic search and synonyms',
                '✗ Business personalization',
                '✗ AI keyword expansion',
            ],
            accent: false,
        },
        {
            id: 'START',
            name: 'Start',
            price: 19990,
            badge: 'RECOMMENDED',
            desc: 'Smart search with AI filtering',
            features: [
                '✔ Everything in Minimum',
                '✔ AI filtering of irrelevant leads',
                '✔ Semantic search (synonyms, morphology)',
                '✔ AI keyword expansion',
                '✔ Business personalization',
                '✔ Smart context filtering',
                '✔ Telegram notifications with AI score',
            ],
            accent: true,
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

    const balance = user?.balance ?? 0
    const plans = PLANS[lang]

    const handleBuy = async (planId: string) => {
        const price = PRICES[planId as keyof typeof PRICES]
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
                                    className={s.planCard}
                                    style={plan.accent ? {
                                        borderColor: 'rgba(92,57,223,.5)',
                                        boxShadow: '0 8px 32px rgba(92,57,223,.18)',
                                    } : undefined}
                                >
                                    {plan.badge && (
                                        <div className={s.planBadge}>{plan.badge}</div>
                                    )}
                                    <h3 className={s.planName}>{plan.name}</h3>
                                    <p className={s.planDesc}>{plan.desc}</p>
                                    <div className={s.planPrice}>
                                        <span className={s.priceVal}>
                                            {plan.price.toLocaleString(lang === 'ru' ? 'ru-RU' : 'en-US')}
                                        </span>
                                        <span className={s.currency}>₽</span>
                                        <span className={s.period}>{l.perMonth}</span>
                                    </div>
                                    <ul className={s.planFeatures}>
                                        {plan.features.map((f, i) => (
                                            <li key={i} style={{
                                                color: f.startsWith('✗') ? '#6b7280' : '#ccc',
                                                opacity: f.startsWith('✗') ? 0.65 : 1,
                                            }}>
                                                {f}
                                            </li>
                                        ))}
                                    </ul>
                                    <button
                                        className={s.buyBtn}
                                        onClick={() => handleBuy(plan.id)}
                                        disabled={buying === plan.id}
                                        style={!plan.accent ? {
                                            background: 'rgba(255,255,255,.08)',
                                            color: 'rgba(255,255,255,.7)',
                                        } : undefined}
                                    >
                                        {buying === plan.id ? l.buying : l.balanceBtn(plan.price)}
                                    </button>
                                    {msg[plan.id] && (
                                        <div className={isError[plan.id] ? s.errorMsg : s.successMsg}>
                                            {msg[plan.id]}
                                        </div>
                                    )}
                                </div>
                            ))}
                        </div>

                        <div className={s.supportBlock}>
                            <p className={s.supportBtnText}>{l.supportBtn}</p>
                            <a
                                href="https://t.me/yar0309"
                                target="_blank"
                                rel="noopener noreferrer"
                                className={s.supportLink}
                            >
                                Telegram →
                            </a>
                            <p className={s.supportNote}>{l.supportNote}</p>
                        </div>
                    </div>
                </div>
            </main>
        </div>
    )
}