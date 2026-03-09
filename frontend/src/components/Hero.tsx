import type { Lang } from '../i18n/translations'
import { t } from '../i18n/translations'
import s from './Hero.module.css'

export default function Hero({ lang }: { lang: Lang }) {
    const tr = (key: string) => t[lang][key] ?? key

    const stats = [
        { val: tr('stat1.val'), label: tr('stat1.label'), d: ''         },
        { val: tr('stat2.val'), label: tr('stat2.label'), d: 'fade-in-d1' },
        { val: tr('stat3.val'), label: tr('stat3.label'), d: 'fade-in-d2' },
    ]

    return (
        <section className={s.hero}>
            <div className="container">
                <div className={s.inner}>

                    {}
                    <div className={s.left}>
                        <div className={s.badge}>{tr('hero.badge')}</div>

                        <h1 className={s.title}>
                            {tr('hero.title1')}
                            <br />
                            <em className={s.accent}>{tr('hero.title2')}</em>
                        </h1>

                        <p className={s.sub}>{tr('hero.sub')}</p>

                        <div className={s.callout}>
                            <span className={s.calloutIcon} aria-hidden="true">🔑</span>
                            <p>{tr('hero.callout')}</p>
                        </div>

                        <div className={s.social}>
                            <div className={s.avatars} aria-hidden="true">
                                {['😊','🧑‍💼','👩‍💻','🎯'].map(e => (
                                    <span key={e} className={s.avatar}>{e}</span>
                                ))}
                            </div>
                            <span className={s.socialText}>{tr('hero.social')}</span>
                        </div>
                    </div>

                    {}
                    <div className={`${s.videoWrap} fade-in`}>
                        <div className={s.liveBadge}>{tr('hero.live')}</div>
                        <div className={s.videoCard}>
                            <div className={s.ytEmbed}>
                                <iframe
                                    src="https://www.youtube.com/embed/MQFzdhg0yGk"
                                    title="AIMLY — как работает сервис"
                                    allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture"
                                    allowFullScreen
                                    loading="lazy"
                                />
                            </div>
                        </div>
                    </div>

                </div>
            </div>

            {}
            <div className={s.statsBar}>
                <div className="container">
                    <div className={s.statsGrid}>
                        {stats.map(item => (
                            <div key={item.val} className={`${s.statItem} fade-in ${item.d}`}>
                                <div className={s.statVal}>{item.val}</div>
                                <div className={s.statLabel}>{item.label}</div>
                            </div>
                        ))}
                    </div>
                </div>
            </div>
        </section>
    )
}