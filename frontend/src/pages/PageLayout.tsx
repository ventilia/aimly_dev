import { type ReactNode, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import type { Lang } from '../i18n/translations'
import { t } from '../i18n/translations'
import s from './Page.module.css'

interface Props {
    children: ReactNode
    lang: Lang
    setLang: (l: Lang) => void
}


export default function PageLayout({ children, lang, setLang }: Props) {
    const tr = (key: string) => t[lang][key] ?? key

    const [scrolled, setScrolled] = useState(false)

    useEffect(() => {
        const fn = () => setScrolled(window.scrollY > 10)
        window.addEventListener('scroll', fn, { passive: true })
        return () => window.removeEventListener('scroll', fn)
    }, [])

    return (
        <div className={s.root}>
            <header className={`${s.header} ${scrolled ? s.scrolled : ''}`}>
                <div className={`container ${s.inner}`}>
                    {}
                    <Link to="/" className={s.logo} aria-label="AIMLY — на главную">
                        <img src="/AIMLY.png" alt="AIMLY" className={s.logoImg} />
                        AIMLY
                    </Link>

                    {}
                    <div className={s.headerRight}>
                        {}
                        <div className={s.langSwitch} role="group" aria-label="язык">
                            {(['ru', 'en'] as Lang[]).map(l => (
                                <button
                                    key={l}
                                    className={`${s.langBtn} ${lang === l ? s.langActive : ''}`}
                                    onClick={() => setLang(l)}
                                    aria-pressed={lang === l}
                                >
                                    {l.toUpperCase()}
                                </button>
                            ))}
                        </div>

                        {}
                        <Link to="/" className={s.back}>
                            ← {tr('nav.back') ?? 'Главная'}
                        </Link>
                    </div>
                </div>
            </header>

            <main className={s.main}>
                <div className="container">
                    {children}
                </div>
            </main>

            <footer className={s.footer}>
                <div className={`container ${s.footerInner}`}>
                    <span className={s.copy}>{tr('footer.copy')}</span>
                    <a
                        href="https://t.me/aimly_support"
                        className={s.support}
                        target="_blank"
                        rel="noopener noreferrer"
                    >
                        {tr('footer.support')}
                    </a>
                </div>
                {}
                <div className={`container ${s.footerPay}`}>
                    <img src="/freekassa.png" alt="Способы оплаты" />
                </div>
            </footer>
        </div>
    )
}