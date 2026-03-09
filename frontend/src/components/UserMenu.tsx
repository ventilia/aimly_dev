import { useState, useRef, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import type { Lang } from '../i18n/translations'
import { authApi } from '../api/auth'
import { useAuthContext } from '../context/AuthContext.tsx'
import s from './UserMenu.module.css'

const txt = {
    ru: {
        dashboard:   'Личный кабинет',
        logout:      'Выйти',
        linkTg:      'Привязать Telegram',
        tgLinked:    'Telegram привязан ✓',
        tgSection:   'Telegram',
        tgHint:      'Откройте ссылку и нажмите Start в боте',
        tgLoading:   'Генерируем ссылку...',
        emailOk:     'Email подтверждён',
        tgOk:        'TG привязан',
        tgNo:        'TG не привязан',
        copy:        'Скопировать',
        copied:      'Скопировано!',
    },
    en: {
        dashboard:   'Dashboard',
        logout:      'Sign out',
        linkTg:      'Connect Telegram',
        tgLinked:    'Telegram connected ✓',
        tgSection:   'Telegram',
        tgHint:      'Open the link and click Start in the bot',
        tgLoading:   'Generating link...',
        emailOk:     'Email verified',
        tgOk:        'TG connected',
        tgNo:        'TG not linked',
        copy:        'Copy',
        copied:      'Copied!',
    },
} as const

interface Props { lang: Lang }

export default function UserMenu({ lang }: Props) {
    const l = txt[lang]
    // token убран — он в httpOnly куке, недоступен на клиенте
    const { user, logout, refreshUser } = useAuthContext()
    const navigate = useNavigate()
    const [open, setOpen] = useState(false)

    const [tgLink,    setTgLink]    = useState<string | null>(null)
    const [tgLoading, setTgLoading] = useState(false)
    const [copied,    setCopied]    = useState(false)

    const ref = useRef<HTMLDivElement>(null)

    useEffect(() => {
        const handler = (e: MouseEvent) => {
            if (ref.current && !ref.current.contains(e.target as Node)) {
                setOpen(false)
            }
        }
        document.addEventListener('mousedown', handler)
        return () => document.removeEventListener('mousedown', handler)
    }, [])

    if (!user) return null

    const initials    = user.firstName
        ? user.firstName.charAt(0).toUpperCase()
        : user.email.charAt(0).toUpperCase()
    const displayName = user.firstName ?? user.email.split('@')[0]

    const handleLinkTg = async () => {
        if (tgLoading) return
        setTgLoading(true)
        setTgLink(null)
        try {
            // токен в куке — не передаём вручную
            const res = await authApi.getTelegramLink()
            setTgLink(`https://t.me/${res.botUsername}?start=${res.linkToken}`)
        } catch (e: unknown) {
            console.error('ошибка генерации TG-ссылки:', e)
        } finally {
            setTgLoading(false)
        }
    }

    const handleCopyLink = async () => {
        if (!tgLink) return
        try {
            await navigator.clipboard.writeText(tgLink)
            setCopied(true)
            setTimeout(() => setCopied(false), 2000)
            // через 5 сек проверяем — вдруг пользователь уже прошёл по ссылке
            setTimeout(() => refreshUser(), 5000)
        } catch {
            window.open(tgLink, '_blank')
        }
    }

    const handleDashboard = () => {
        setOpen(false)
        navigate('/dashboard')
    }

    const handleLogout = async () => {
        await logout()
        setOpen(false)
    }

    return (
        <div className={s.wrap} ref={ref}>
            <button
                className={s.trigger}
                onClick={() => setOpen(v => !v)}
                aria-haspopup="menu"
                aria-expanded={open}
            >
                <span className={s.avatar}>{initials}</span>
                <span className={s.name}>{displayName}</span>
                <span className={`${s.chevron} ${open ? s.chevronOpen : ''}`}>▼</span>
            </button>

            {open && (
                <div className={s.dropdown} role="menu">
                    {/* инфо пользователя */}
                    <div className={s.userInfo}>
                        <div className={s.userEmail}>{user.email}</div>
                        <div className={s.userBadges}>
                            {user.emailVerified && (
                                <span className={`${s.badge} ${s.badgeVerified}`}>{l.emailOk}</span>
                            )}
                            {user.telegramLinked ? (
                                <span className={`${s.badge} ${s.badgeTg}`}>{l.tgOk}</span>
                            ) : (
                                <span className={`${s.badge} ${s.badgeNoTg}`}>{l.tgNo}</span>
                            )}
                        </div>
                    </div>

                    {/* кнопка кабинета */}
                    <div className={s.menu}>
                        <button className={s.menuItem} onClick={handleDashboard} role="menuitem">
                            <span className={s.menuIcon}>⬡</span>
                            {l.dashboard}
                        </button>
                    </div>

                    <div className={s.sep} />

                    {/* блок telegram */}
                    {!user.telegramLinked && (
                        <div className={s.tgBlock}>
                            <span className={s.tgTitle}>{l.tgSection}</span>

                            {!tgLink && !tgLoading && (
                                <button className={s.menuItem} onClick={handleLinkTg} role="menuitem">
                                    <span className={s.menuIcon}>✈️</span>
                                    {l.linkTg}
                                </button>
                            )}

                            {tgLoading && (
                                <span className={s.tgLoading}>{l.tgLoading}</span>
                            )}

                            {tgLink && (
                                <>
                                    <p style={{ fontSize: 12, color: 'var(--c-ink-3)', lineHeight: 1.4 }}>
                                        {l.tgHint}:
                                    </p>
                                    <button className={s.tgLink} onClick={handleCopyLink}>
                                        <span>✈️</span>
                                        <span>{copied ? l.copied : l.copy}</span>
                                    </button>
                                    <a
                                        href={tgLink}
                                        target="_blank"
                                        rel="noopener noreferrer"
                                        className={s.tgLink}
                                        style={{ marginTop: 4 }}
                                    >
                                        <span>🔗</span>
                                        <span>Открыть ссылку</span>
                                    </a>
                                </>
                            )}
                        </div>
                    )}

                    {user.telegramLinked && (
                        <div className={s.tgBlock}>
                            <span className={s.tgTitle}>{l.tgSection}</span>
                            <span style={{ fontSize: 13.5, color: 'var(--c-green)', fontWeight: 600 }}>
                                {l.tgLinked}
                            </span>
                        </div>
                    )}

                    <div className={s.sep} />

                    {/* выход */}
                    <div className={s.menu}>
                        <button
                            className={`${s.menuItem} ${s.logoutItem}`}
                            onClick={handleLogout}
                            role="menuitem"
                        >
                            <span className={s.menuIcon}>↩</span>
                            {l.logout}
                        </button>
                    </div>
                </div>
            )}
        </div>
    )
}