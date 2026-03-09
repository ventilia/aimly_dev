import PageLayout from './PageLayout'
import type { Lang } from '../i18n/translations'
import { t } from '../i18n/translations'
import s from './Page.module.css'

interface Props { lang: Lang; setLang: (l: Lang) => void }

export default function Terms({ lang, setLang }: Props) {
    const tr = (key: string) => t[lang][key] ?? key

    return (
        <PageLayout lang={lang} setLang={setLang}>
            <h1 className={s.pageTitle}>{tr('terms.title')}</h1>
            <p className={s.updated}>{tr('terms.updated')}</p>

            <div className={s.section}>
                <p className={s.text}>{tr('terms.intro.text')}</p>
            </div>

            <div className={s.section}>
                <h2 className={s.sectionTitle}>{tr('terms.what.title')}</h2>
                <p className={s.text}>{tr('terms.what.text')}</p>
            </div>

            <div className={s.section}>
                <h2 className={s.sectionTitle}>{tr('terms.rules.title')}</h2>
                <ul className={s.list}>
                    <li>{tr('terms.rules.li1')}</li>
                    <li>{tr('terms.rules.li2')}</li>
                    <li>{tr('terms.rules.li3')}</li>
                    <li>{tr('terms.rules.li4')}</li>
                </ul>
            </div>

            <div className={s.section}>
                <h2 className={s.sectionTitle}>{tr('terms.availability.title')}</h2>
                <p className={s.text}>{tr('terms.availability.text')}</p>
            </div>

            <div className={s.section}>
                <h2 className={s.sectionTitle}>{tr('terms.changes.title')}</h2>
                <p className={s.text}>{tr('terms.changes.text')}</p>
            </div>

            <div className={s.section}>
                <h2 className={s.sectionTitle}>{tr('terms.contacts.title')}</h2>
                <p className={s.text}>
                    {tr('terms.contacts.text')}
                    <a href="https://t.me/aimly_support" className={s.link} target="_blank" rel="noopener noreferrer">
                        @aimly_support
                    </a>
                </p>
            </div>
        </PageLayout>
    )
}