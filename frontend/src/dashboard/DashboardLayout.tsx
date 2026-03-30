import { useState, useEffect } from 'react'
import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import type { Lang } from '../i18n/translations'
import { useAuthContext } from '../context/AuthContext'
import { leadsApi } from '../api/leads'
import s from './DashboardLayout.module.css'

const txt = {
    ru: {
        nav: {
            overview: 'Главная',
            leads:    'Лиды',
            chats:    'Чаты',
            keywords: 'Ключевые слова',
            import:   'Импорт чата',
            profile:  'Профиль',
        },
        logout:     'Выход',
        noSub:      'Нет активной подписки',
        noSubBtn:   'Выбрать тариф',
        adminPanel: 'Админ-панель',
    },
    en: {
        nav: {
            overview: 'Home',
            leads:    'Leads',
            chats:    'Chats',
            keywords: 'Keywords',
            import:   'Chat Import',
            profile:  'Profile',
        },
        logout:     'Sign out',
        noSub:      'No active subscription',
        noSubBtn:   'Choose plan',
        adminPanel: 'Admin Panel',
    },
} as const

interface NavLabels {
    overview: string
    leads:    string
    chats:    string
    keywords: string
    import:   string
    profile:  string
}

const NAV_ITEMS = (l: NavLabels) => [
    { to: '/dashboard',          label: l.overview, exact: true  },
    { to: '/dashboard/leads',    label: l.leads,    exact: false },
    { to: '/dashboard/chats',    label: l.chats,    exact: false },
    { to: '/dashboard/keywords', label: l.keywords, exact: false },
    { to: '/dashboard/import',   label: l.import,   exact: false },
    { to: '/dashboard/profile',  label: l.profile,  exact: false },
]

export const LEADS_COUNT_CHANGED = 'aimly:leads-count-changed'

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

interface DashboardLayoutProps { lang: Lang; onLang: (l: Lang) => void }

export default function DashboardLayout({ lang, onLang }: DashboardLayoutProps) {
    const l = txt[lang]
    const { user, logout } = useAuthContext()
    const navigate = useNavigate()
    const [mobileOpen,    setMobileOpen]    = useState(false)
    const [newLeadsCount, setNewLeadsCount] = useState(0)

    const hasSubscription = user?.subscriptionStatus === 'ACTIVE' || user?.subscriptionStatus === 'TRIAL'

    const refreshNewCount = () => {
        leadsApi.list({ status: 'NEW', size: 1 })
            .then(p => setNewLeadsCount(p.totalElements))
            .catch(() => {})
    }

    useEffect(() => {
        refreshNewCount()
        const interval = setInterval(refreshNewCount, 30_000)

        const handleCountChanged = (e: Event) => {
            const detail = (e as CustomEvent<{ newCount: number }>).detail
            if (typeof detail?.newCount === 'number') {
                setNewLeadsCount(detail.newCount)
            } else {
                refreshNewCount()
            }
        }

        window.addEventListener(LEADS_COUNT_CHANGED, handleCountChanged)

        return () => {
            clearInterval(interval)
            window.removeEventListener(LEADS_COUNT_CHANGED, handleCountChanged)
        }
    }, [])

    useEffect(() => {
        const onResize = () => {
            if (window.innerWidth > 860) setMobileOpen(false)
        }
        window.addEventListener('resize', onResize)
        return () => window.removeEventListener('resize', onResize)
    }, [])

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
            {!hasSubscription && (
                <div className={s.noSubTopBanner}>
                    <span className={s.noSubTopIcon}><IconAlertCircle /></span>
                    <span className={s.noSubTopText}>{l.noSub}</span>
                    <button className={s.noSubTopBtn} onClick={() => navigate('/checkout')}>
                        {l.noSubBtn}
                    </button>
                </div>
            )}

            <header className={s.topbar}>
                <a href="/dashboard" className={s.topbarLogo}>
                    <img src="/AIMLY.png" alt="AIMLY" className={s.topbarLogoImg} />
                    <span className={s.topbarLogoText}>AIMLY</span>
                    {isAdmin && (
                        <span style={{ fontSize: 10, fontWeight: 700, background: 'var(--c-accent)', color: '#fff', padding: '2px 6px', borderRadius: 5, marginLeft: 4, letterSpacing: '.5px', textTransform: 'uppercase' }}>
                            Админ
                        </span>
                    )}
                </a>

                <button className={s.burger} onClick={() => setMobileOpen(v => !v)} aria-label="Меню">
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
                {mobileOpen && <div className={s.mobileOverlay} onClick={() => setMobileOpen(false)} />}

                <aside className={`${s.sidebar} ${mobileOpen ? s.sidebarOpen : ''}`}>
                    <nav className={s.nav}>
                        {navItems.map(item => (
                            <NavLink
                                key={item.to}
                                to={item.to}
                                end={item.exact}
                                className={({ isActive }) => `${s.navItem} ${isActive ? s.navItemActive : ''}`}
                                onClick={() => setMobileOpen(false)}
                            >
                                <span style={{ flex: 1 }}>{item.label}</span>
                                {item.to === '/dashboard/leads' && newLeadsCount > 0 && (
                                    <span style={{ display: 'inline-flex', alignItems: 'center', justifyContent: 'center', minWidth: 18, height: 18, padding: '0 5px', borderRadius: 9, background: '#dc2626', color: '#fff', fontSize: 10, fontWeight: 700, lineHeight: 1, flexShrink: 0 }}>
                                        {newLeadsCount > 99 ? '99+' : newLeadsCount}
                                    </span>
                                )}
                            </NavLink>
                        ))}

                        {isAdmin && (
                            <a
                                href="/admin"
                                className={s.navItem}
                                style={{ color: 'var(--c-accent)', fontWeight: 600 }}
                                onClick={() => setMobileOpen(false)}
                            >
                                <span style={{ flex: 1 }}>{l.adminPanel}</span>
                            </a>
                        )}
                    </nav>

                    <div className={s.sidebarUser}>
                        <div className={s.sidebarAvatar}>{initials}</div>
                        <div className={s.sidebarUserInfo}>
                            <div className={s.sidebarUserName}>{displayName}</div>
                        </div>
                        <button className={s.sidebarLogout} onClick={handleLogout}>
                            {l.logout}
                        </button>
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