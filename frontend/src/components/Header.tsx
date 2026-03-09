import { useEffect, useRef, useState } from 'react'
import type { Lang } from '../i18n/translations'
import { t } from '../i18n/translations'
import { useAuthContext } from '../context/AuthContext.tsx'
import AuthModal from './AuthModal'
import UserMenu  from './UserMenu'
import s from './Header.module.css'



const IconWallet = () => (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none"
         stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <rect x="2" y="7" width="20" height="14" rx="2"/>
        <path d="M16 21V5a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v16"/>
        <circle cx="17" cy="14" r="1" fill="currentColor" stroke="none"/>
    </svg>
)

const IconBell = () => (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none"
         stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"/>
        <path d="M13.73 21a2 2 0 0 1-3.46 0"/>
    </svg>
)

const IconShield = () => (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none"
         stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
        <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>
    </svg>
)



function BalanceChip({ balance, lang }: { balance: number; lang: Lang }) {
    const [open, setOpen] = useState(false)
    const ref = useRef<HTMLDivElement>(null)
    const topUpTxt   = lang === 'ru' ? 'Пополнить' : 'Top up'
    const balanceTxt = lang === 'ru' ? 'Баланс'    : 'Balance'

    useEffect(() => {
        const fn = (e: MouseEvent) => {
            if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false)
        }
        document.addEventListener('mousedown', fn)
        return () => document.removeEventListener('mousedown', fn)
    }, [])

    return (
        <div ref={ref} style={{ position: 'relative' }}>
            <button className={s.balanceChip} onClick={() => setOpen(v => !v)}>
                <IconWallet />
                <span>{balance} ₽</span>
            </button>
            {open && (
                <div className={s.balancePop}>
                    <div className={s.balancePopLabel}>{balanceTxt}</div>
                    <div className={s.balancePopAmount}>{balance} ₽</div>
                    <a
                        href="https://t.me/yar0309"
                        target="_blank"
                        rel="noopener noreferrer"
                        className={s.balancePopBtn}
                        onClick={() => setOpen(false)}
                    >
                        {topUpTxt}
                    </a>
                </div>
            )}
        </div>
    )
}



function NotifBell() {
    return (
        <button
            className={s.bellBtn}
            disabled
            aria-label="Уведомления"
            title="Уведомления приходят в Telegram-бот"
            style={{ cursor: 'default', opacity: 0.5 }}
        >
            <IconBell />
        </button>
    )
}



interface Props {
    lang:   Lang
    onLang: (l: Lang) => void
}

function Header({ lang, onLang }: Props) {
    const tr = (key: string) => t[lang][key] ?? key
    const { user, loading } = useAuthContext()

    const [scrolled,    setScrolled]    = useState(false)
    const [menuOpen,    setMenuOpen]    = useState(false)
    const [authVisible, setAuthVisible] = useState(false)

    useEffect(() => {
        const fn = () => setScrolled(window.scrollY > 10)
        window.addEventListener('scroll', fn, { passive: true })
        return () => window.removeEventListener('scroll', fn)
    }, [])

    useEffect(() => {
        document.body.style.overflow = menuOpen ? 'hidden' : ''
        return () => { document.body.style.overflow = '' }
    }, [menuOpen])

    const links = [
        { key: 'nav.how',     href: '#how'      },
        { key: 'nav.who',     href: '#audience' },
        { key: 'nav.pricing', href: '#pricing'  },
        { key: 'nav.faq',     href: '#faq'      },
    ]

    const close   = () => setMenuOpen(false)
    const isAdmin = user?.role === 'ADMIN'

    const authBlock = loading ? (
        <div style={{ width: 72, height: 34, borderRadius: 10, background: 'var(--c-border)' }} />
    ) : user ? (
        <div className={s.authRow}>
            {isAdmin && (
                <a href="/admin" className={s.adminChip}>
                    <IconShield />
                    <span>{lang === 'ru' ? 'Админ' : 'Admin'}</span>
                </a>
            )}
            <BalanceChip balance={user.balance ?? 0} lang={lang} />
            <NotifBell />
            <UserMenu lang={lang} />
        </div>
    ) : (
        <button className="btn-ghost" onClick={() => setAuthVisible(true)}>
            {tr('nav.login')}
        </button>
    )

    return (
        <>
            <header className={`${s.header} ${scrolled ? s.scrolled : ''}`}>
                <div className={`container ${s.inner}`}>
                    <a href="/" className={s.logo} aria-label="AIMLY — на главную">
                        <img src="/AIMLY.png" alt="AIMLY" className={s.owl} />
                        AIMLY
                        {isAdmin && <span className={s.adminTag}>admin</span>}
                    </a>

                    <nav className={s.nav} aria-label="Навигация">
                        {links.map(l => (
                            <a key={l.key} href={l.href} className={s.navLink}>{tr(l.key)}</a>
                        ))}
                    </nav>

                    <div className={s.right}>
                        {authBlock}

                        <div className={s.langSwitch} role="group" aria-label="Язык">
                            {(['ru', 'en'] as Lang[]).map(l => (
                                <button
                                    key={l}
                                    className={`${s.langBtn} ${lang === l ? s.langActive : ''}`}
                                    onClick={() => onLang(l)}
                                    aria-pressed={lang === l}
                                >
                                    {l.toUpperCase()}
                                </button>
                            ))}
                        </div>

                        <button
                            className={`${s.burger} ${menuOpen ? s.burgerOpen : ''}`}
                            onClick={() => setMenuOpen(v => !v)}
                            aria-label={menuOpen ? 'Закрыть меню' : 'Открыть меню'}
                            aria-expanded={menuOpen}
                        >
                            <span /><span /><span />
                        </button>
                    </div>
                </div>
            </header>

            <div className={`${s.mobile} ${menuOpen ? s.mobileOpen : ''}`} aria-hidden={!menuOpen}>
                {links.map(l => (
                    <a key={l.key} href={l.href} className={s.mobileLink} onClick={close}>
                        {tr(l.key)}
                    </a>
                ))}
                {!user && (
                    <button className="btn-ghost"
                            onClick={() => { setAuthVisible(true); close() }}
                            style={{ alignSelf: 'flex-start', marginTop: 8 }}>
                        {tr('nav.login')}
                    </button>
                )}
                <div className={s.langSwitch} style={{ marginTop: 12 }}>
                    {(['ru', 'en'] as Lang[]).map(l => (
                        <button key={l}
                                className={`${s.langBtn} ${lang === l ? s.langActive : ''}`}
                                onClick={() => { onLang(l); close() }}>
                            {l.toUpperCase()}
                        </button>
                    ))}
                </div>
            </div>

            {authVisible && <AuthModal lang={lang} onClose={() => setAuthVisible(false)} />}
        </>
    )
}

export default Header