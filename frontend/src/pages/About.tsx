import PageLayout from './PageLayout'
import type { Lang } from '../i18n/translations'
import { t } from '../i18n/translations'
import s from './Page.module.css'

interface Props { lang: Lang; setLang: (l: Lang) => void }

export default function About({ lang, setLang }: Props) {
    const tr = (key: string) => t[lang][key] ?? key

    return (
        <PageLayout lang={lang} setLang={setLang}>
            <h1 className={s.pageTitle}>{tr('about.title')}</h1>

            <div className={s.highlight}>
                <p className={s.sectionTitle}>{tr('about.highlight.title')}</p>
                <p className={s.text}>{tr('about.highlight.text')}</p>
            </div>

            <div className={s.section}>
                <h2 className={s.sectionTitle}>{tr('about.origin.title')}</h2>
                <p className={s.text}>{tr('about.origin.text1')}</p>
                <p className={s.text}>{tr('about.origin.text2')}</p>
            </div>

            <div className={s.section}>
                <h2 className={s.sectionTitle}>{tr('about.principles.title')}</h2>
                <ul className={s.list}>
                    <li>{tr('about.principles.li1')}</li>
                    <li>{tr('about.principles.li2')}</li>
                    <li>{tr('about.principles.li3')}</li>
                    <li>{tr('about.principles.li4')}</li>
                </ul>
            </div>

            <div className={s.section}>
                <h2 className={s.sectionTitle}>{tr('about.contacts.title')}</h2>
                <p className={s.text}>
                    {tr('about.contacts.text')}
                    <a href="https://t.me/aimly_support" className={s.link} target="_blank" rel="noopener noreferrer">
                        @aimly_support
                    </a>
                </p>
            </div>
        </PageLayout>
    )
}