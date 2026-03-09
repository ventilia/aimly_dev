import PageLayout from './PageLayout'
import type { Lang } from '../i18n/translations'
import { t } from '../i18n/translations'
import s from './Page.module.css'

interface Props { lang: Lang; setLang: (l: Lang) => void }

export default function Privacy({ lang, setLang }: Props) {
    const tr = (key: string) => t[lang][key] ?? key

    return (
        <PageLayout lang={lang} setLang={setLang}>
            <h1 className={s.pageTitle}>{tr('privacy.title')}</h1>
            <p className={s.updated}>{tr('privacy.updated')}</p>

            <div className={s.section}>
                <ul className={s.list}>
                    <li>{tr('privacy.intro.li1')}</li>
                    <li>{tr('privacy.intro.li2')}</li>
                    <li>{tr('privacy.intro.li3')}</li>
                    <li>{tr('privacy.intro.li4')}</li>
                </ul>
            </div>

            <div className={s.section}>
                <h2 className={s.sectionTitle}>{tr('privacy.data.title')}</h2>
                <ul className={s.list}>
                    <li>{tr('privacy.data.li1')}</li>
                    <li>{tr('privacy.data.li2')}</li>
                    <li>{tr('privacy.data.li3')}</li>
                    <li>{tr('privacy.data.li4')}</li>
                </ul>
            </div>

            <div className={s.section}>
                <h2 className={s.sectionTitle}>{tr('privacy.usage.title')}</h2>
                <ul className={s.list}>
                    <li>{tr('privacy.usage.li1')}</li>
                    <li>{tr('privacy.usage.li2')}</li>
                    <li>{tr('privacy.usage.li3')}</li>
                </ul>
            </div>

            <div className={s.section}>
                <h2 className={s.sectionTitle}>{tr('privacy.storage.title')}</h2>
                <p className={s.text}>{tr('privacy.storage.text')}</p>
            </div>

            <div className={s.section}>
                <h2 className={s.sectionTitle}>{tr('privacy.contacts.title')}</h2>
                <p className={s.text}>
                    {tr('privacy.contacts.text')}
                    <a href="https://t.me/aimly_support" className={s.link} target="_blank" rel="noopener noreferrer">
                        @aimly_support
                    </a>
                </p>
            </div>
        </PageLayout>
    )
}