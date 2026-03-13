import { useState, useEffect, useRef } from 'react'
import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import type { Lang } from '../i18n/translations'
import { useAuthContext } from '../context/AuthContext'
import s from './DashboardLayout.module.css'

const txt = {
    ru: {
        nav: {
            overview: 'Главная',
            leads:    'Лиды',
            keywords: 'Ключевые слова',
            chats:    'Чаты',
            profile:  'Профиль',
        },
        logout:     'Выход',
        noSub:      'Нет активной подписки',
        noSubBtn:   'Выбрать тариф',
        balance:    'Баланс',
        topUp:      'Пополнить',
        adminPanel: 'Админ-панель',
    },
    en: {
        nav: {
            overview: 'Home',
            leads:    'Leads',
            keywords: 'Keywords',
            chats:    'Chats',
            profile:  'Profile',
        },
        logout:     'Sign out',
        noSub:      'No active subscription',
        noSubBtn:   'Choose plan',
        balance:    'Balance',
        topUp:      'Top up',
        adminPanel: 'Admin Panel',
    },
} as const

interface Props { lang: Lang; onLang: (l: Lang) => void }

interface NavLabels {
    overview: string
    leads: string
    keywords: string
    chats: string
    profile: string
}

// Ключевые слова идут перед Чатами
const NAV_ITEMS = (l: NavLabels) => [
    { to: '/dashboard',          label: l.overview, exact: true  },
    { to: '/dashboard/leads',    label: l.leads,    exact: false },
    { to: '/dashboard/keywords', label: l.keywords, exact: false },
    { to: '/dashboard/chats',    label: l.chats,    exact: false },
    { to: '/dashboard/profile',  label: l.profile,  exact: false },
]



const IconWallet = () => (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
        <rect x="2" y="7" width="20" height="14" rx="2"/>
        <path d="M16 21V5a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v16"/>
        <circle cx="17" cy="14" r="1" fill="currentColor" stroke="none"/>
    </svg>
)

const IconShield = () => (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
        <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>
    </svg>
)

const IconBell = () => (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
        <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"/>
        <path d="M13.73 21a2 2 0 0 1-3.46 0"/>
    </svg>
)

const IconAlertCircle = () => (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <circle cx="12" cy="12" r="10"/>
        <line x1="12" y1="8" x2="12" y2="12"/>
        <line x1="12" y1="16" x2="12.01" y2="16"/>
    </svg>
)

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

export default function DashboardLayout({ lang, onLang }: Props) {
    const l = txt[lang]
    const { user, logout } = useAuthContext()
    const navigate = useNavigate()
    const [mobileOpen, setMobileOpen] = useState(false)

    const hasSubscription = user?.subscriptionStatus === 'ACTIVE' || user?.subscriptionStatus === 'TRIAL'

    const handleLogout = async () => {
        await logout()
        navigate('/')
    }

    const initials    = user?.firstName
        ? user.firstName.charAt(0).toUpperCase()
        : user?.email?.charAt(0).toUpperCase() || 'U'
    const displayName = user?.firstName || user?.email?.split('@')[0] || 'User'
    const isAdmin     = user?.role === 'ADMIN'
    const navItems    = NAV_ITEMS(txt[lang].nav as NavLabels)

    return (
        <div className={s.root}>
            {}
            {!hasSubscription && (
                <div className={s.noSubTopBanner}>
                    <span className={s.noSubTopIcon}><IconAlertCircle /></span>
                    <span className={s.noSubTopText}>{l.noSub}</span>
                    <button
                        className={s.noSubTopBtn}
                        onClick={() => navigate('/dashboard/profile')}
                    >
                        {l.noSubBtn}
                    </button>
                </div>
            )}

            <header className={s.topbar}>
                <a href="/dashboard" className={s.topbarLogo}>
                    <img src="/AIMLY.png" alt="AIMLY" className={s.topbarLogoImg} />
                    <span className={s.topbarLogoText}>AIMLY</span>
                    {isAdmin && (
                        <span style={{
                            fontSize: 10, fontWeight: 700,
                            background: 'var(--c-accent)', color: '#fff',
                            padding: '2px 6px', borderRadius: 5,
                            marginLeft: 4, letterSpacing: '.5px',
                            textTransform: 'uppercase',
                        }}>
                            Админ
                        </span>
                    )}
                </a>

                <button
                    className={s.burger}
                    onClick={() => setMobileOpen(v => !v)}
                    aria-label="Меню"
                >
                    <span /><span /><span />
                </button>

                <div className={s.topbarRight}>
                    <div className={s.langSwitch}>
                        {(['ru', 'en'] as Lang[]).map(lng => (
                            <button
                                key={lng}
                                className={`${s.langBtn} ${lang === lng ? s.langActive : ''}`}
                                onClick={() => onLang(lng)}
                            >
                                {lng.toUpperCase()}
                            </button>
                        ))}
                    </div>

                    {isAdmin && (
                        <a href="/admin" className={s.adminChip}>
                            <IconShield />
                            <span>{l.adminPanel}</span>
                        </a>
                    )}

                    <BalanceChip balance={user?.balance ?? 0} lang={lang} hasSubscription={!!hasSubscription} />
                    <NotifBell />

                    <div className={s.userChip}>
                        <div className={s.userChipAvatar}>{initials}</div>
                        <span className={s.userChipName}>{displayName}</span>
                    </div>

                    <button className={s.topbarLogout} onClick={handleLogout}>
                        {l.logout}
                    </button>
                </div>
            </header>

            <div className={s.body}>
                {mobileOpen && (
                    <div className={s.mobileOverlay} onClick={() => setMobileOpen(false)} />
                )}

                <aside className={`${s.sidebar} ${mobileOpen ? s.sidebarOpen : ''}`}>
                    <nav className={s.nav}>
                        {navItems.map(item => (
                            <NavLink
                                key={item.to}
                                to={item.to}
                                end={item.exact}
                                className={({ isActive }) =>
                                    `${s.navItem} ${isActive ? s.navItemActive : ''}`
                                }
                                onClick={() => setMobileOpen(false)}
                            >
                                {item.label}
                            </NavLink>
                        ))}
                    </nav>

                    {}
                    <div className={s.sidebarUser}>
                        <div className={s.sidebarAvatar}>{initials}</div>
                        <div className={s.sidebarUserInfo}>
                            <div className={s.sidebarUserName}>{displayName}</div>
                            <div className={s.sidebarUserEmail}>{user?.email}</div>
                        </div>
                    </div>
                </aside>

                <main className={s.main}>
                    <div className={s.content}>
                        <Outlet />
                    </div>
                </main>
            </div>
        </div>
    )
}



function BalanceChip({
                         balance,
                         lang,
                         hasSubscription,
                     }: {
    balance: number
    lang: Lang
    hasSubscription: boolean
}) {
    const [open, setOpen] = useState(false)
    const ref = useRef<HTMLDivElement>(null)
    const ru = lang === 'ru'

    useEffect(() => {
        if (!open) return
        const handler = (e: MouseEvent) => {
            if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false)
        }
        document.addEventListener('mousedown', handler)
        return () => document.removeEventListener('mousedown', handler)
    }, [open])

    return (
        <div className={s.balanceWrap} ref={ref}>
            <button className={s.balanceChip} onClick={() => setOpen(v => !v)}>
                <IconWallet />
                <span className={s.balanceVal}>{balance} ₽</span>
            </button>
            {open && (
                <div className={s.balancePop}>
                    <div className={s.balanceRow}>
                        <span className={s.balanceLabel}>{ru ? 'Баланс' : 'Balance'}</span>
                        <span className={s.balanceAmount}>{balance} ₽</span>
                    </div>
                    <div className={s.balanceSep} />
                    {!hasSubscription && (
                        <a href="/checkout" className={s.balancePopBtn}>
                            {ru ? 'Купить подписку' : 'Buy subscription'}
                        </a>
                    )}
                    <div className={s.balanceSep} />
                    <a
                        href="https://t.me/yar0309"
                        target="_blank"
                        rel="noopener noreferrer"
                        className={s.balancePopBtnPrimary}
                    >
                        {ru ? 'Пополнить баланс' : 'Top up balance'}
                    </a>
                </div>
            )}
        </div>
    )
}