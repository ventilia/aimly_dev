import { useNavigate } from 'react-router-dom'
import type { Lang } from '../i18n/translations'
import s from './Checkout.module.css'

interface Props { lang: Lang; setLang: (l: Lang) => void }

const txt = {
    ru: {
        back:           '← Назад',
        title:          'Оформление подписки',
        sub:            'Выберите подходящий тариф',
        perMonth:       '/мес',
        comingSoon:     'В разработке',
        comingSoonDesc: 'Тариф находится в разработке и будет доступен в ближайшее время.',
        contactSupport: 'Для оформления подписки напишите нам:',
        supportLink:    '@aimly_support',
        supportNote:    'Ответим и активируем доступ в течение рабочего дня',
    },
    en: {
        back:           '← Back',
        title:          'Checkout',
        sub:            'Choose the right plan',
        perMonth:       '/month',
        comingSoon:     'Coming soon',
        comingSoonDesc: 'This plan is in development and will be available soon.',
        contactSupport: 'To subscribe, contact us:',
        supportLink:    '@aimly_support',
        supportNote:    'We will respond and activate your access within one business day',
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
    const navigate = useNavigate()

    const plans = PLANS[lang]

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
                                                    <li key={i}>{f}</li>
                                                ))}
                                            </ul>
                                        </>
                                    )}
                                </div>
                            ))}
                        </div>

                        {}
                        <div className={s.supportBlock}>
                            <p className={s.supportBtnText}>{l.contactSupport}</p>
                            <a
                                href="https://t.me/aimly_support"
                                target="_blank"
                                rel="noopener noreferrer"
                                className={s.supportLink}
                            >
                                {l.supportLink} →
                            </a>
                            <p className={s.supportNote}>{l.supportNote}</p>
                        </div>
                    </div>
                </div>
            </main>
        </div>
    )
}