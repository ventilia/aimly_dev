import type { Lang } from '../i18n/translations'
import s from './DashboardStub.module.css'

const txt = {
    ru: {

        leads:    { title: 'Лиды',            sub: 'Здесь будет лента входящих лидов из Telegram-чатов.',     icon: '✦', soon: 'Скоро' },
        chats:    { title: 'Чаты',            sub: 'Выберите Telegram-чаты для мониторинга.',                   icon: '◈', soon: 'Скоро' },
        keywords: { title: 'Ключевые слова',  sub: 'Настройте триггеры и ключевые фразы для поиска лидов.',    icon: '◎', soon: 'Скоро' },
        profile:  { title: 'Профиль',         sub: 'Настройки аккаунта, подписки и безопасности.',             icon: '◉', soon: 'Скоро' },
    },
    en: {
        leads:    { title: 'Leads',           sub: 'Your incoming leads from Telegram chats will be here.',    icon: '✦', soon: 'Coming soon' },
        chats:    { title: 'Chats',           sub: 'Select Telegram chats to monitor.',                         icon: '◈', soon: 'Coming soon' },
        keywords: { title: 'Keywords',        sub: 'Set up triggers and key phrases to find leads.',           icon: '◎', soon: 'Coming soon' },
        profile:  { title: 'Profile',         sub: 'Account settings, subscription and security.',             icon: '◉', soon: 'Coming soon' },
    },
} as const


type StubPage = keyof typeof txt['ru']

interface Props { lang: Lang; page: StubPage }

export default function DashboardStub({ lang, page }: Props) {
    const l = txt[lang][page]

    return (
        <div className={s.page}>
            <div className={s.card}>
                <div className={s.icon}>{l.icon}</div>
                <div className={s.badge}>{l.soon}</div>
                <h1 className={s.title}>{l.title}</h1>
                <p className={s.sub}>{l.sub}</p>
                <div className={s.shimmerRow}>
                    <div className={s.shimmer} />
                    <div className={`${s.shimmer} ${s.shimmerShort}`} />
                    <div className={`${s.shimmer} ${s.shimmerMed}`} />
                </div>
            </div>
        </div>
    )
}