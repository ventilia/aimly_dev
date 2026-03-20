import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import type { Lang } from '../i18n/translations'
import { authApi } from '../api/auth'
import { leadsApi, chatsApi, keywordsApi } from '../api/leads'
import { useAuthContext } from '../context/AuthContext'
import s from './DashboardOverview.module.css'

const txt = {
    ru: {
        title:          'Обзор',
        welcome:        'Добро пожаловать в AIMLY',
        tgAlertTitle:   'Подключить агента для получения лидов',
        tgAlertTitleTrial:   'Запустите бота и получите 5 дней бесплатно',
        tgAlertBtn:     'Подключить через бота',
        tgAlertBtnAlt:  'Скопировать ссылку',
        howTitle:       'Как подключить Telegram',
        step1Title:     'Нажмите «Подключить через бота»',
        step1Desc:      'Откроется Telegram с нашим ботом — нажмите Start.',
        step2Title:     'Нажмите Start',
        step2Desc:      'Бот автоматически привяжет ваш аккаунт AIMLY.',
        step3Title:     'Готово',
        step3Desc:      'Ваш Telegram привязан. Выберите чаты и настройте ключевые слова.',
        setupTitle:     'Настройка системы',
        setupSub:       'Завершите настройку для начала работы',
        setupStep1:     'Подключить Telegram',
        setupStep2:     'Выбрать чаты',
        setupStep3:     'Установить ключевые слова',
        stepsLeft:      'Осталось шагов',
        stepsComplete:  'Завершите настройку для запуска',
        tgLinked:       'Telegram подключён',
        genLinkLoading: 'Генерируем ссылку...',
        linkHint:       'Откройте ссылку и нажмите Start в боте:',
        openLink:       'Открыть бота',
        copyLink:       'Скопировать',
        copied:         'Скопировано',
        linkError:      'Не удалось сгенерировать ссылку',
        statLeads:      'Всего лидов',
        statNew:        'Новых лидов',
        statChats:      'Активных чатов',
        statKeywords:   'Ключевых слов',
        lastLead:       'Последний лид',
        lastLeadEmpty:  'Лидов пока нет',
        lastLeadSub:    'Добавьте чаты и ключевые слова — первые лиды придут сюда',
        openMsg:        'Открыть сообщение',
        linkUnavailable:'Ссылка недоступна',
        linkNote:       'Если ссылка не открывается — проверьте настройки приватности чата',
        viewAll:        'Все лиды →',
    },
    en: {
        title:          'Overview',
        welcome:        'Welcome to AIMLY',
        tgAlertTitle:   'Connect agent to receive leads',
        tgAlertTitleTrial:   'Launch bot and get 5 days free trial',
        tgAlertBtn:     'Connect via bot',
        tgAlertBtnAlt:  'Copy link',
        howTitle:       'How to connect Telegram',
        step1Title:     'Click "Connect via bot"',
        step1Desc:      'Telegram will open with our bot — click Start.',
        step2Title:     'Click Start',
        step2Desc:      'The bot will automatically link your AIMLY account.',
        step3Title:     'Done',
        step3Desc:      'Telegram is linked. Select chats and set keywords.',
        setupTitle:     'System setup',
        setupSub:       'Complete setup to get started',
        setupStep1:     'Connect Telegram',
        setupStep2:     'Select chats',
        setupStep3:     'Set keywords',
        stepsLeft:      'Steps left',
        stepsComplete:  'Complete setup to launch',
        tgLinked:       'Telegram connected',
        genLinkLoading: 'Generating link...',
        linkHint:       'Open the link and click Start in the bot:',
        openLink:       'Open bot',
        copyLink:       'Copy',
        copied:         'Copied',
        linkError:      'Failed to generate link',
        statLeads:      'Total leads',
        statNew:        'New leads',
        statChats:      'Active chats',
        statKeywords:   'Keywords',
        lastLead:       'Latest lead',
        lastLeadEmpty:  'No leads yet',
        lastLeadSub:    'Add chats and keywords — your first leads will appear here',
        openMsg:        'Open message',
        linkUnavailable:'Link unavailable',
        linkNote:       'If the link does not open — check the chat privacy settings',
        viewAll:        'All leads →',
    },
} as const

const IconWarning = () => (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/>
        <line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/>
    </svg>
)

const IconCheck = () => (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
        <polyline points="20 6 9 17 4 12"/>
    </svg>
)

const IconGear = () => (
    <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <circle cx="12" cy="12" r="3"/>
        <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/>
    </svg>
)

const IconInfo = () => (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/>
    </svg>
)

const IconExternal = () => (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/>
        <polyline points="15 3 21 3 21 9"/><line x1="10" y1="14" x2="21" y2="3"/>
    </svg>
)

interface Props { lang: Lang }

export default function DashboardOverview({ lang }: Props) {
    const l = txt[lang]
    const { user, refreshUser } = useAuthContext()
    const navigate = useNavigate()

    const [tgLink,    setTgLink]    = useState<string | null>(null)
    const [tgLoading, setTgLoading] = useState(false)
    const [tgError,   setTgError]   = useState<string | null>(null)
    const [copied,    setCopied]    = useState(false)

    const [totalLeads,    setTotalLeads]    = useState<number | null>(null)
    const [newLeads,      setNewLeads]      = useState<number | null>(null)
    const [chatsCount,    setChatsCount]    = useState<number | null>(null)
    const [keywordsCount, setKeywordsCount] = useState<number | null>(null)

    const [lastLead, setLastLead] = useState<import('../api/leads').Lead | null>(null)

    useEffect(() => {
        leadsApi.list({ size: 20 })
            .then(p => {
                setTotalLeads(p.totalElements)
                setNewLeads(p.newCount)
                const firstActive = p.content.find(l => l.status !== 'IGNORED') ?? null
                setLastLead(firstActive)
            })
            .catch(() => {})

        chatsApi.list()
            .then(list => setChatsCount(list.filter(c => c.isActive).length))
            .catch(() => {})

        keywordsApi.list()
            .then(list => setKeywordsCount(list.filter(k => k.isActive).length))
            .catch(() => {})
    }, [])

    if (!user) return null

    const tgLinked  = user.telegramLinked
    const steps     = [tgLinked, (chatsCount ?? 0) > 0, (keywordsCount ?? 0) > 0]
    const stepsLeft = steps.filter(v => !v).length

    const stepRoutes = ['/dashboard/profile', '/dashboard/chats', '/dashboard/keywords']

    const handleConnectBot = async () => {
        setTgLoading(true)
        setTgError(null)
        try {
            const res  = await authApi.getTelegramLink()
            const link = `https://t.me/${res.botUsername}?start=${res.linkToken}`
            setTgLink(link)
            window.open(link, '_blank', 'noopener,noreferrer')
            const check = setInterval(async () => { await refreshUser() }, 3000)
            setTimeout(() => clearInterval(check), 60_000)
        } catch {
            setTgError(l.linkError)
        } finally {
            setTgLoading(false)
        }
    }

    const handleCopy = async () => {
        if (!tgLink) return
        try {
            await navigator.clipboard.writeText(tgLink)
            setCopied(true)
            setTimeout(() => setCopied(false), 2000)
            setTimeout(() => refreshUser(), 5000)
        } catch {
            window.open(tgLink, '_blank')
        }
    }

    const fmt = (v: number | null) => v === null ? '—' : String(v)

    return (
        <div className={s.page}>
            <div className={s.pageHead}>
                <h1 className={s.pageTitle}>{l.title}</h1>
                <p className={s.pageSub}>{l.welcome}</p>
            </div>

            {/* ─── Статистика ─── */}
            <div className={s.statsRow}>
                <div className={s.statCard}>
                    <div className={s.statVal}>{fmt(totalLeads)}</div>
                    <div className={s.statLabel}>{l.statLeads}</div>
                </div>
                <div className={s.statCard}>
                    <div className={`${s.statVal} ${(newLeads ?? 0) > 0 ? s.statValAccent : ''}`}>{fmt(newLeads)}</div>
                    <div className={s.statLabel}>{l.statNew}</div>
                </div>
                <div className={s.statCard}>
                    <div className={s.statVal}>{fmt(chatsCount)}</div>
                    <div className={s.statLabel}>{l.statChats}</div>
                </div>
                <div className={s.statCard}>
                    <div className={s.statVal}>{fmt(keywordsCount)}</div>
                    <div className={s.statLabel}>{l.statKeywords}</div>
                </div>
            </div>

            {/* ─── Последний лид ─── */}
            <div style={{ background: 'var(--c-surface)', border: '1.5px solid var(--c-border)', borderRadius: 16, padding: '22px 24px', display: 'flex', flexDirection: 'column', gap: 14 }}>
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
                    <span style={{ fontSize: 13, fontWeight: 700, color: 'var(--c-ink-2)', textTransform: 'uppercase', letterSpacing: '.5px' }}>{l.lastLead}</span>
                    <a href="/dashboard/leads" style={{ fontSize: 13, color: 'var(--c-accent)', fontWeight: 600, textDecoration: 'none' }}>{l.viewAll}</a>
                </div>

                {lastLead ? (
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                        <div style={{ display: 'flex', alignItems: 'flex-start', gap: 14 }}>
                            <div style={{ width: 44, height: 44, borderRadius: '50%', flexShrink: 0, background: 'var(--c-accent-soft)', color: 'var(--c-accent)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontWeight: 800, fontSize: 18, fontFamily: 'var(--font-head)' }}>
                                {(lastLead.authorName || lastLead.authorUsername || lastLead.chatTitle || '?').replace('@', '').charAt(0).toUpperCase()}
                            </div>
                            <div style={{ flex: 1, minWidth: 0 }}>
                                <div style={{ display: 'flex', alignItems: 'center', gap: 7, flexWrap: 'wrap', marginBottom: 2 }}>
                                    {lastLead.authorName?.trim() && <span style={{ fontSize: 15, fontWeight: 700, color: 'var(--c-ink)' }}>{lastLead.authorName}</span>}
                                    {lastLead.authorUsername?.trim() && <span style={{ fontSize: 13, color: 'var(--c-accent)', fontWeight: 600 }}>@{lastLead.authorUsername}</span>}
                                    {!lastLead.authorName?.trim() && !lastLead.authorUsername?.trim() && <span style={{ fontSize: 15, fontWeight: 600, color: 'var(--c-ink-3)' }}>Аноним</span>}
                                    {lastLead.status === 'NEW' && <span style={{ fontSize: 10, fontWeight: 700, padding: '2px 8px', borderRadius: 100, background: 'rgba(239,68,68,.12)', color: '#dc2626', letterSpacing: '.3px' }}>NEW</span>}
                                </div>
                                <div style={{ fontSize: 12, color: 'var(--c-ink-3)' }}>
                                    {(lastLead.chatTitle || lastLead.chatLink) && <span style={{ marginRight: 10 }}>{lastLead.chatTitle || lastLead.chatLink}</span>}
                                    <span>{new Date(lastLead.foundAt).toLocaleString(lang === 'ru' ? 'ru-RU' : 'en-US', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })}</span>
                                </div>
                                {lastLead.matchedKeyword && <span style={{ display: 'inline-block', marginTop: 4, fontSize: 11, fontWeight: 600, background: 'var(--c-accent-soft)', color: 'var(--c-accent)', padding: '2px 9px', borderRadius: 100 }}>{lastLead.matchedKeyword}</span>}
                            </div>
                        </div>

                        <div style={{ fontSize: 14, color: 'var(--c-ink)', lineHeight: 1.6, background: 'var(--c-bg)', borderRadius: 10, padding: '12px 14px', border: '1px solid var(--c-border)', wordBreak: 'break-word', maxHeight: 160, overflowY: 'auto', whiteSpace: 'pre-wrap', WebkitOverflowScrolling: 'touch' } as React.CSSProperties}>
                            {lastLead.messageText}
                        </div>

                        {lastLead.aiValid !== null && lastLead.aiValid !== undefined && (
                            <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8, padding: '8px 12px', borderRadius: 9, background: lastLead.aiValid ? 'rgba(16,185,129,.08)' : 'rgba(239,68,68,.08)', border: `1px solid ${lastLead.aiValid ? 'rgba(16,185,129,.2)' : 'rgba(239,68,68,.2)'}` }}>
                                <span style={{ fontSize: 12, fontWeight: 700, flexShrink: 0, color: lastLead.aiValid ? '#10b981' : '#ef4444' }}>AI {lastLead.aiValid ? '✓' : '✗'}</span>
                                {lastLead.aiReason && <span style={{ fontSize: 12, color: 'var(--c-ink-2)', lineHeight: 1.45 }}>{lastLead.aiReason}</span>}
                            </div>
                        )}

                        <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
                            {lastLead.messageLink ? (
                                <a href={lastLead.messageLink} target="_blank" rel="noopener noreferrer" title={l.linkNote}
                                   style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '9px 18px', borderRadius: 9, background: 'var(--c-accent)', color: '#fff', fontSize: 13, fontWeight: 600, textDecoration: 'none', transition: 'opacity .15s' }}
                                   onMouseEnter={e => (e.currentTarget as HTMLAnchorElement).style.opacity = '0.85'}
                                   onMouseLeave={e => (e.currentTarget as HTMLAnchorElement).style.opacity = '1'}>
                                    {l.openMsg}<IconExternal />
                                </a>
                            ) : (
                                <span title={l.linkNote} style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '9px 18px', borderRadius: 9, border: '1.5px solid var(--c-border)', color: 'var(--c-ink-3)', fontSize: 13, fontWeight: 600, cursor: 'default' }}>
                                    {l.linkUnavailable}
                                </span>
                            )}
                            <a href="/dashboard/leads"
                               style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '9px 18px', borderRadius: 9, border: '1.5px solid var(--c-border)', color: 'var(--c-ink-2)', fontSize: 13, fontWeight: 600, textDecoration: 'none', transition: 'all .15s' }}
                               onMouseEnter={e => { (e.currentTarget as HTMLAnchorElement).style.borderColor = 'var(--c-accent)'; (e.currentTarget as HTMLAnchorElement).style.color = 'var(--c-accent)' }}
                               onMouseLeave={e => { (e.currentTarget as HTMLAnchorElement).style.borderColor = 'var(--c-border)'; (e.currentTarget as HTMLAnchorElement).style.color = 'var(--c-ink-2)' }}>
                                {l.viewAll}
                            </a>
                        </div>
                    </div>
                ) : (
                    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8, padding: '24px 0', textAlign: 'center' }}>
                        <div style={{ width: 48, height: 48, borderRadius: '50%', background: 'var(--c-bg)', border: '1.5px solid var(--c-border)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 22, marginBottom: 4 }}>
                            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M22 12h-4l-3 9L9 3l-3 9H2"/></svg>
                        </div>
                        <span style={{ fontSize: 15, fontWeight: 600, color: 'var(--c-ink)' }}>{l.lastLeadEmpty}</span>
                        <span style={{ fontSize: 13, color: 'var(--c-ink-3)', maxWidth: 320 }}>{l.lastLeadSub}</span>
                    </div>
                )}
            </div>

            {/* ─── Telegram ─── */}
            {!tgLinked ? (
                <div className={s.tgAlert}>
                    <div className={s.tgAlertLeft}>
                        <div className={s.tgAlertIcon}><IconWarning /></div>
                        <div className={s.tgAlertTitle}>
                            {!user.trialUsed ? l.tgAlertTitleTrial : l.tgAlertTitle}
                        </div>
                    </div>
                    <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                        <button className={s.tgAlertBtn} onClick={handleConnectBot} disabled={tgLoading}>
                            {tgLoading ? l.genLinkLoading : l.tgAlertBtn}
                        </button>
                        {tgLink && (
                            <button onClick={handleCopy} style={{ padding: '11px 18px', borderRadius: 10, border: '1.5px solid #fca5a5', background: '#fff', color: '#991b1b', fontSize: 14, fontWeight: 600, cursor: 'pointer', fontFamily: 'var(--font-body)' }}>
                                {copied ? l.copied : l.tgAlertBtnAlt}
                            </button>
                        )}
                    </div>
                </div>
            ) : (
                <div className={s.tgSuccess}>
                    <span className={s.tgSuccessIcon}><IconCheck /></span>
                    <span className={s.tgSuccessText}>{l.tgLinked}</span>
                </div>
            )}

            {tgError && <div style={{ color: '#dc2626', fontSize: 13, padding: '4px 0' }}>{tgError}</div>}

            {!tgLinked && tgLink && (
                <div className={s.instrCard}>
                    <h3 className={s.instrTitle}>{l.howTitle}</h3>
                    <div className={s.instrSteps}>
                        {[
                            { title: l.step1Title, desc: l.step1Desc },
                            { title: l.step2Title, desc: l.step2Desc },
                            { title: l.step3Title, desc: l.step3Desc },
                        ].map((step, i) => (
                            <div key={i} className={s.instrStep}>
                                <div className={s.instrNum}>{i + 1}</div>
                                <div>
                                    <div className={s.instrStepTitle}>{step.title}</div>
                                    <div className={s.instrStepDesc}>{step.desc}</div>
                                </div>
                            </div>
                        ))}
                    </div>
                    <div className={s.linkBlock}>
                        <p className={s.linkHint}>{l.linkHint}</p>
                        <div className={s.linkRow}>
                            <div className={s.linkValue}>{tgLink}</div>
                            <button className={s.copyBtn} onClick={handleCopy}>{copied ? l.copied : l.copyLink}</button>
                            <a href={tgLink} target="_blank" rel="noopener noreferrer" className={s.openBtn}>{l.openLink}</a>
                        </div>
                    </div>
                </div>
            )}

            {/* ─── Шаги настройки ─── */}
            {stepsLeft > 0 && (
                <div className={s.setupRow}>
                    <div className={s.setupCard}>
                        <div className={s.setupCardHead}>
                            <div className={s.setupCardIcon}><IconGear /></div>
                            <div>
                                <div className={s.setupCardTitle}>{l.setupTitle}</div>
                                <div className={s.setupCardSub}>{l.setupSub}</div>
                            </div>
                        </div>
                        <div className={s.setupSteps}>
                            {[l.setupStep1, l.setupStep2, l.setupStep3].map((step, i) => (
                                <div
                                    key={i}
                                    className={`${s.setupStep} ${steps[i] ? s.setupStepDone : ''}`}
                                    onClick={() => !steps[i] && navigate(stepRoutes[i])}
                                    style={{ cursor: steps[i] ? 'default' : 'pointer', transition: 'opacity .15s' }}
                                    onMouseEnter={e => { if (!steps[i]) (e.currentTarget as HTMLDivElement).style.opacity = '0.75' }}
                                    onMouseLeave={e => { (e.currentTarget as HTMLDivElement).style.opacity = '1' }}
                                >
                                    <div className={s.setupStepNum}>
                                        {steps[i] ? <IconCheck /> : i + 1}
                                    </div>
                                    <span>{step}</span>
                                    {!steps[i] && (
                                        <span style={{ marginLeft: 'auto', fontSize: 11, color: 'var(--c-accent)', fontWeight: 600, flexShrink: 0 }}>
                                            {lang === 'ru' ? 'Перейти →' : 'Go →'}
                                        </span>
                                    )}
                                </div>
                            ))}
                        </div>
                    </div>

                    <div className={s.progressCard}>
                        <div className={s.progressNum}>{stepsLeft}</div>
                        <div className={s.progressLabel}>{l.stepsLeft}</div>
                        <div className={s.progressSub}>{l.stepsComplete}</div>
                    </div>
                </div>
            )}

            {stepsLeft === 0 && !lastLead && (
                <div style={{ display: 'flex', alignItems: 'center', gap: 10, background: 'var(--c-surface)', border: '1px solid var(--c-border)', borderRadius: 12, padding: '14px 18px', color: 'var(--c-green)', fontSize: 13.5, fontWeight: 600 }}>
                    <span style={{ width: 28, height: 28, borderRadius: '50%', background: 'var(--c-green-soft)', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
                        <IconInfo />
                    </span>
                    {lang === 'ru' ? 'Система настроена и работает. Лиды поступают в реальном времени.' : 'System is set up and running. Leads are coming in real time.'}
                </div>
            )}
        </div>
    )
}