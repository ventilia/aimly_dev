import { useState } from 'react'
import { Link } from 'react-router-dom'
import type { Lang } from '../i18n/translations'
import { t } from '../i18n/translations'
import s from './Sections.module.css'

const tr = (lang: Lang) => (key: string) => t[lang][key] ?? key


export function Problems({ lang }: { lang: Lang }) {
    const _ = tr(lang)

    const items = [
        { bad: _('p1.bad'), good: _('p1.good'), d: ''           },
        { bad: _('p2.bad'), good: _('p2.good'), d: 'fade-in-d1' },
        { bad: _('p3.bad'), good: _('p3.good'), d: 'fade-in-d2' },
        { bad: _('p4.bad'), good: _('p4.good'), d: 'fade-in-d3' },
    ]

    return (
        <section className={`section ${s.problems}`}>
            <div className="container">
                <div className="section__head center">
                    <h2 className="section__title">{_('problems.title')}</h2>
                    <p className="section__sub">{_('problems.sub')}</p>
                </div>
                <div className={s.problemsGrid}>
                    {items.map(item => (
                        <div key={item.bad} className={`${s.problemCard} fade-in ${item.d}`}>
                            <span className={s.problemBad}>{item.bad}</span>
                            <span className={s.problemArrow} aria-hidden="true">→</span>
                            <div className={s.problemGoodWrap}>
                                <span className={s.check} aria-hidden="true">✓</span>
                                <span className={s.problemGood}>{item.good}</span>
                            </div>
                        </div>
                    ))}
                </div>
            </div>
        </section>
    )
}


export function Features({ lang }: { lang: Lang }) {
    const _ = tr(lang)

    const cards = [
        { icon: '🔍', title: _('feat1.title'), adv: _('feat1.adv'), ben: _('feat1.ben'), d: ''           },
        { icon: '💬', title: _('feat2.title'), adv: _('feat2.adv'), ben: _('feat2.ben'), d: 'fade-in-d1' },
        { icon: '📊', title: _('feat3.title'), adv: _('feat3.adv'), ben: _('feat3.ben'), d: 'fade-in-d2' },
    ]

    return (
        <section className="section">
            <div className="container">
                <div className="section__head center">
                    <h2 className="section__title">{_('features.title')}</h2>
                </div>
                <div className={s.featGrid}>
                    {cards.map(c => (
                        <div key={c.title} className={`${s.featCard} fade-in ${c.d}`}>
                            <div className={s.featIcon} aria-hidden="true">{c.icon}</div>
                            <p className={s.featSmall}>{lang === 'ru' ? 'Функция' : 'Feature'}</p>
                            <h3 className={s.featTitle}>{c.title}</h3>
                            <p className={s.featSmall} style={{ marginTop: 12 }}>{lang === 'ru' ? 'Преимущество' : 'Advantage'}</p>
                            <p className={s.featAdv}>{c.adv}</p>
                            <p className={s.featSmall} style={{ marginTop: 12 }}>{lang === 'ru' ? 'Выгода' : 'Benefit'}</p>
                            <p className={s.featBen}>{c.ben}</p>
                        </div>
                    ))}
                </div>
            </div>
        </section>
    )
}


export function HowItWorks({ lang }: { lang: Lang }) {
    const _ = tr(lang)

    const steps = [
        { num: '01', title: _('step1.title'), desc: _('step1.desc'), d: ''           },
        { num: '02', title: _('step2.title'), desc: _('step2.desc'), d: 'fade-in-d1' },
        { num: '03', title: _('step3.title'), desc: _('step3.desc'), d: 'fade-in-d2' },
    ]

    return (
        <section className={`section ${s.howSection}`} id="how">
            <div className="container">
                <div className="section__head center">
                    <p className="section__label">{_('how.label')}</p>
                    <h2 className="section__title">{_('how.title')}</h2>
                </div>
                <div className={s.stepsGrid}>
                    {steps.map(step => (
                        <div key={step.num} className={`${s.step} fade-in ${step.d}`}>
                            <div className={s.stepNum} aria-hidden="true">{step.num}</div>
                            <h3 className={s.stepTitle}>{step.title}</h3>
                            <p className={s.stepDesc}>{step.desc}</p>
                        </div>
                    ))}
                </div>
            </div>
        </section>
    )
}


export function Audience({ lang }: { lang: Lang }) {
    const _ = tr(lang)

    const cards = [
        { icon: '🎓', title: _('aud1.title'), desc: _('aud1.desc') },
        { icon: '🚀', title: _('aud2.title'), desc: _('aud2.desc') },
        { icon: '🏠', title: _('aud3.title'), desc: _('aud3.desc') },
        { icon: '💼', title: _('aud4.title'), desc: _('aud4.desc') },
    ]

    return (
        <section className="section" id="audience">
            <div className="container">
                <div className="section__head center">
                    <h2 className="section__title">{_('audience.title')}</h2>
                </div>
                <div className={s.audienceGrid}>
                    {cards.map((c, i) => (
                        <div
                            key={c.title}
                            className={`${s.audienceCard} fade-in`}
                            style={{ transitionDelay: `${i * 0.1}s` }}
                        >
                            <div className={s.audienceIcon} aria-hidden="true">{c.icon}</div>
                            <h3 className={s.audienceTitle}>{c.title}</h3>
                            <p className={s.audienceDesc}>{c.desc}</p>
                        </div>
                    ))}
                </div>
            </div>
        </section>
    )
}


export function Compare({ lang }: { lang: Lang }) {
    const _ = tr(lang)

    type Color = 'bad' | 'warn' | 'good' | 'ok'

    const rows: Array<{ n: string; m: string; mc: Color; b: string; bc: Color; a: string }> = [
        { n: _('r1.n'), m: _('r1.m'), mc: 'bad',  b: _('r1.b'), bc: 'ok',   a: _('r1.a') },
        { n: _('r2.n'), m: _('r2.m'), mc: 'ok',   b: _('r2.b'), bc: 'warn', a: _('r2.a') },
        { n: _('r3.n'), m: _('r3.m'), mc: 'warn', b: _('r3.b'), bc: 'warn', a: _('r3.a') },
        { n: _('r4.n'), m: _('r4.m'), mc: 'ok',   b: _('r4.b'), bc: 'warn', a: _('r4.a') },
    ]

    const colorClass: Record<Color, string> = {
        bad:  s.colBad,
        warn: s.colWarn,
        good: s.colGood,
        ok:   s.colOk,
    }

    return (
        <section className={`section ${s.compareSection}`}>
            <div className="container">
                <div className="section__head center">
                    <h2 className="section__title">{_('compare.title')}</h2>
                </div>
                <div className="fade-in" style={{ overflowX: 'auto' }}>
                    <table className={s.table}>
                        <thead>
                        <tr>
                            <th className={s.thCrit}>{_('cmp.crit')}</th>
                            <th>{_('cmp.manual')}</th>
                            <th>{_('cmp.bot')}</th>
                            <th className={s.colAimlyHead}>{_('cmp.aim')}</th>
                        </tr>
                        </thead>
                        <tbody>
                        {rows.map(row => (
                            <tr key={row.n}>
                                <td className={s.rowName}>{row.n}</td>
                                <td className={colorClass[row.mc]}>{row.m}</td>
                                <td className={colorClass[row.bc]}>{row.b}</td>
                                <td className={`${s.colAimlyCell} ${s.colGood}`}>{row.a}</td>
                            </tr>
                        ))}
                        </tbody>
                    </table>
                </div>
            </div>
        </section>
    )
}





const MINIMUM_FEATURES: Record<Lang, string[]> = {
    ru: [
        'Мониторинг чатов по ключевым словам',
        'Чаты без ограничений',
        'Лиды без ограничений',
        'Точное совпадение ключевых слов',
    ],
    en: [
        'Chat monitoring by keywords',
        'Unlimited chats',
        'Unlimited leads',
        'Exact keyword matching',
    ],
}


const START_FEATURES: Record<Lang, string[]> = {
    ru: [
        'Всё из тарифа Минимум',
        'AI-семантический поиск лидов',
        'AI-фильтрация контекста сообщений',
        'Персонализация под ваш бизнес',
        'Приоритетная поддержка',
    ],
    en: [
        'Everything in Minimum',
        'AI semantic lead search',
        'AI context filtering',
        'Business personalization',
        'Priority support',
    ],
}

const START_NAMES: Record<Lang, string> = { ru: 'Старт', en: 'Start' }
const START_DESCS: Record<Lang, string> = { ru: 'С AI-обработкой и персонализацией', en: 'With AI processing and personalization' }
const START_PRICES: Record<Lang, string> = { ru: '19 990', en: '$199' }

export function Pricing({ lang }: { lang: Lang }) {
    const _ = tr(lang)
    const ru = lang === 'ru'

    return (
        <section className={`section ${s.pricingSection}`} id="pricing">
            <div className="container">
                <div className="section__head center">
                    <h2 className="section__title">{_('pricing.title')}</h2>
                    <p className="section__sub">{_('pricing.sub')}</p>
                </div>

                <div className={s.pricingWrap}>
                    {/* ── тариф Минимум ── */}
                    <div className={`${s.pricingCard} fade-in`}>
                        <h3 className={s.pricingName}>{ru ? 'Минимум' : 'Minimum'}</h3>
                        <p className={s.pricingDesc}>{ru ? 'Мониторинг без AI' : 'Monitoring without AI'}</p>

                        <div className={s.priceRow}>
                            <span className={s.priceOld}>{_('plan.old')}</span>
                            <span className={s.price}>{_('plan.price')}</span>
                            <span className={s.priceCurrency}>{_('plan.currency')}</span>
                            <span className={s.pricePeriod}>{_('plan.period')}</span>
                        </div>

                        <ul className={s.pricingFeatures}>
                            {MINIMUM_FEATURES[lang].map((f, i) => (
                                <li key={i} className={s.pricingFeature}>✓ {f}</li>
                            ))}
                        </ul>

                        <Link
                            to="/checkout"
                            className={`${s.pricingCta} ${s.pricingCtaGhost}`}
                        >
                            {ru ? 'Начать' : 'Start'}
                        </Link>
                    </div>

                    {/* ── тариф Старт (с AI) ── */}
                    <div className={`${s.pricingCard} ${s.pricingCardFeatured} fade-in`} style={{ animationDelay: '.1s' }}>
                        <div className={s.pricingBadge}>{_('plan.badge')}</div>
                        <h3 className={s.pricingName}>{START_NAMES[lang]}</h3>
                        <p className={s.pricingDesc}>{START_DESCS[lang]}</p>

                        <div className={s.priceRow}>
                            <span className={s.price}>{START_PRICES[lang]}</span>
                            <span className={s.priceCurrency}>{_('plan.currency')}</span>
                            <span className={s.pricePeriod}>{_('plan.period')}</span>
                        </div>

                        <ul className={s.pricingFeatures}>
                            {START_FEATURES[lang].map((f, i) => (
                                <li key={i} className={s.pricingFeature}>✓ {f}</li>
                            ))}
                        </ul>

                        <Link
                            to="/checkout"
                            className={`${s.pricingCta} ${s.pricingCtaPrimary}`}
                        >
                            {ru ? 'Начать' : 'Start'}
                        </Link>
                    </div>
                </div>
            </div>
        </section>
    )
}


const REVIEWS = [
    { emoji: '👨‍💻', name: 'Алексей',  role: 'Владелец SMM-агентства',     niche: 'Маркетинг и продвижение в Telegram',       text: '«Мы раньше держали отдельного человека, который просто сидел в чатах и отсматривал запросы. Всё равно половину пропускали. С AIMLY я просто подключил рабочий аккаунт и пару десятков чатов. Теперь заявки прилетают стабильно каждую неделю.»' },
    { emoji: '👩‍💻', name: 'Мария',    role: 'Основатель онлайн-школы',    niche: 'Онлайн-образование и курсы',                text: '«У нас сильная воронка, но страдали от того, что не успеваем реагировать в чатах. AIMLY отвечает практически сразу, и люди сами идут в личку.»' },
    { emoji: '🎧',  name: 'Ольга',    role: 'Продюсер экспертов',          niche: 'Продюсирование и запуски инфопродуктов',   text: '«Раньше я сама мониторила десяток чатов, постоянно боялась что-то пропустить. AIMLY стал для меня ночной сменой.»' },
    { emoji: '📈',  name: 'Сергей',   role: 'Таргетолог-фрилансер',        niche: 'Реклама и лидогенерация',                  text: '«Я работаю один, и времени разрываться на все чаты нет. Теперь мой аккаунт появляется в обсуждении сам.»' },
    { emoji: '💅',  name: 'Анна',     role: 'Владелец студии маникюра',    niche: 'Бьюти и локальный сервисный бизнес',       text: '«У нас несколько районных чатов. AIMLY теперь отрабатывает запросы за нас — аккуратно, без спама, вежливо.»' },
    { emoji: '🏢',  name: 'Илья',     role: 'Сооснователь B2B SaaS',       niche: 'SaaS для бизнеса',                         text: '«Мы продаём сервис для бизнеса. AIMLY оказался удобным способом не упускать запросы в чатах предпринимателей.»' },
    { emoji: '🏙️', name: 'Роман',    role: 'Агент по недвижимости',        niche: 'Продажа и аренда недвижимости',            text: '«Я в десятке чатов по новостройкам. AIMLY подсвечивает сообщения с потенциальными клиентами и пишет первым.»' },
    { emoji: '🦉',  name: 'Светлана', role: 'Руководитель отдела продаж',  niche: 'Онлайн-школа английского',                 text: '«Ребята в отделе продаж уже шутят, что у нас появился ещё один менеджер — ночная сова AIMLY.»' },
]

const VISIBLE = 3


export function Reviews({ lang }: { lang: Lang }) {
    const _ = tr(lang)
    const [idx, setIdx] = useState(0)

    const max  = REVIEWS.length - VISIBLE
    const prev = () => setIdx(i => Math.max(0, i - 1))
    const next = () => setIdx(i => Math.min(max, i + 1))

    const offset = `calc(-${idx} * (100% / ${VISIBLE} + 20px / ${VISIBLE}))`

    return (
        <section className="section" id="reviews">
            <div className="container">
                <div className="section__head center">
                    <h2 className="section__title">{_('reviews.title')}</h2>
                    <p className="section__sub">{_('reviews.sub')}</p>
                </div>

                <div className={s.reviewsWrap}>
                    <div className={s.reviewsTrack} style={{ transform: `translateX(${offset})` }}>
                        {REVIEWS.map(r => (
                            <div key={r.name} className={s.reviewCard}>
                                <div className={s.stars} aria-label="5 звёзд">★★★★★</div>
                                <p className={s.reviewText}>{r.text}</p>
                                <div className={s.reviewAuthor}>
                                    <span className={s.reviewAvatar} aria-hidden="true">{r.emoji}</span>
                                    <div>
                                        <div className={s.reviewName}>{r.name}, {r.role}</div>
                                        <div className={s.reviewRole}>{r.niche}</div>
                                    </div>
                                </div>
                            </div>
                        ))}
                    </div>
                </div>

                <div className={s.reviewsNav}>
                    <button className={s.navBtn} onClick={prev} disabled={idx === 0} aria-label="Назад">←</button>
                    <div className={s.dots}>
                        {Array.from({ length: max + 1 }).map((_, i) => (
                            <button
                                key={i}
                                className={`${s.dot} ${i === idx ? s.dotActive : ''}`}
                                onClick={() => setIdx(i)}
                                aria-label={`Показать отзыв ${i + 1}`}
                            />
                        ))}
                    </div>
                    <button className={s.navBtn} onClick={next} disabled={idx === max} aria-label="Вперёд">→</button>
                </div>
            </div>
        </section>
    )
}


export function Faq({ lang }: { lang: Lang }) {
    const _ = tr(lang)
    const [open, setOpen] = useState<number | null>(null)

    const items = [
        { q: _('faq.q1'), a: _('faq.a1') },
        { q: _('faq.q2'), a: _('faq.a2') },
        { q: _('faq.q3'), a: _('faq.a3') },
        { q: _('faq.q4'), a: _('faq.a4') },
        { q: _('faq.q5'), a: _('faq.a5') },
        { q: _('faq.q6'), a: _('faq.a6') },
    ]

    return (
        <section className="section" id="faq">
            <div className="container">
                <div className="section__head center">
                    <h2 className="section__title">{_('faq.title')}</h2>
                </div>
                <ul className={s.faqList}>
                    {items.map((item, i) => (
                        <li key={i} className={s.faqItem}>
                            <button
                                className={s.faqQ}
                                onClick={() => setOpen(open === i ? null : i)}
                                aria-expanded={open === i}
                            >
                                {item.q}
                                <span
                                    className={`${s.faqIcon} ${open === i ? s.faqIconOpen : ''}`}
                                    aria-hidden="true"
                                >
                                    +
                                </span>
                            </button>
                            {open === i && (
                                <div className={s.faqA}>{item.a}</div>
                            )}
                        </li>
                    ))}
                </ul>
            </div>
        </section>
    )
}


export function CtaFinal({ lang }: { lang: Lang }) {
    const _ = tr(lang)

    return (
        <section className={`section ${s.ctaSection}`}>
            <div className="container">
                <div className={s.ctaInner}>
                    <h2 className="section__title">{_('cta.title')}</h2>
                    <p className="section__sub" style={{ margin: '0 auto 36px' }}>{_('cta.sub')}</p>
                    <Link to="/checkout" className={`btn-primary ${s.ctaBtn}`}>
                        {_('cta.btn')}
                    </Link>
                </div>
            </div>
        </section>
    )
}


export function Footer({ lang }: { lang: Lang }) {
    const _ = tr(lang)

    const productLinks = [
        { key: 'footer.how',     to: '/#how'     },
        { key: 'footer.pricing', to: '/#pricing'  },
        { key: 'footer.faq',     to: '/#faq'      },
    ]
    const companyLinks = [
        { key: 'footer.about',    to: '/about'    },
        { key: 'footer.blog',     to: '/blog'     },
        { key: 'footer.contacts', to: '/contacts' },
    ]
    const legalLinks = [
        { key: 'footer.privacy', to: '/privacy' },
        { key: 'footer.terms',   to: '/terms'   },
        { key: 'footer.refund',  to: '/refund'  },
    ]

    return (
        <footer className={s.footer}>
            <div className="container">
                <div className={s.footerGrid}>
                    <div>
                        <div className={s.footerBrand}>
                            <img src="/AIMLY.png" alt="AIMLY" className={s.footerOwl} />
                            AIMLY
                        </div>
                        <p className={s.footerDesc}>{_('footer.desc')}</p>
                    </div>
                    <div>
                        <p className={s.footerColTitle}>{_('footer.product')}</p>
                        <ul className={s.footerLinks}>
                            {productLinks.map(({ key, to }) => (
                                <li key={key}>
                                    <Link to={to} className={s.footerLink}>{_(key)}</Link>
                                </li>
                            ))}
                        </ul>
                    </div>
                    <div>
                        <p className={s.footerColTitle}>{_('footer.company')}</p>
                        <ul className={s.footerLinks}>
                            {companyLinks.map(({ key, to }) => (
                                <li key={key}>
                                    <Link to={to} className={s.footerLink}>{_(key)}</Link>
                                </li>
                            ))}
                        </ul>
                    </div>
                    <div>
                        <p className={s.footerColTitle}>{_('footer.legal')}</p>
                        <ul className={s.footerLinks}>
                            {legalLinks.map(({ key, to }) => (
                                <li key={key}>
                                    <Link to={to} className={s.footerLink}>{_(key)}</Link>
                                </li>
                            ))}
                        </ul>
                    </div>
                </div>

                <div className={s.footerBottom}>
                    <span className={s.footerCopy}>{_('footer.copy')}</span>
                    <a
                        href="https://t.me/aimly_support"
                        className={s.footerSupport}
                        target="_blank"
                        rel="noopener noreferrer"
                    >
                        {_('footer.support')}
                    </a>
                </div>
            </div>
        </footer>
    )
}