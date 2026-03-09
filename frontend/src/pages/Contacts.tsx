import PageLayout from './PageLayout'
import type { Lang } from '../i18n/translations'
import { t } from '../i18n/translations'
import s from './Page.module.css'

interface Props { lang: Lang; setLang: (l: Lang) => void }

export default function Contacts({ lang, setLang }: Props) {
    const tr = (key: string) => t[lang][key] ?? key

    return (
        <PageLayout lang={lang} setLang={setLang}>
            <h1 className={s.pageTitle}>{tr('contacts.title')}</h1>

            <div className={s.section}>
                <p className={s.text}>{tr('contacts.text')}</p>
            </div>

            <div className={s.section}>
                <a
                    href="https://t.me/aimly_support"
                    className={s.contactCard}
                    target="_blank"
                    rel="noopener noreferrer"
                >
                    <span className={s.contactIcon}>✈️</span>
                    <div>
                        <div className={s.contactLabel}>{tr('contacts.support.label')}</div>
                        <div className={s.contactValue}>{tr('contacts.support.value')}</div>
                    </div>
                </a>
            </div>

            <div className={s.section}>
                <h2 className={s.sectionTitle}>{tr('contacts.common.title')}</h2>
                <ul className={s.list}>
                    <li>{tr('contacts.common.li1')}</li>
                    <li>{tr('contacts.common.li2')}</li>
                    <li>{tr('contacts.common.li3')}</li>
                    <li>{tr('contacts.common.li4')}</li>
                </ul>
            </div>
        </PageLayout>
    )
}