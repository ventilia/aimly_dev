import PageLayout from './PageLayout'
import type { Lang } from '../i18n/translations'
import { t } from '../i18n/translations'
import s from './Page.module.css'

interface Props { lang: Lang; setLang: (l: Lang) => void }

export default function Refund({ lang, setLang }: Props) {
    const tr = (key: string) => t[lang][key] ?? key

    return (
        <PageLayout lang={lang} setLang={setLang}>
            <h1 className={s.pageTitle}>{tr('refund.title')}</h1>
            <p className={s.updated}>{tr('refund.updated')}</p>

            <div className={s.section}>
                <h2 className={s.sectionTitle}>{tr('refund.period.title')}</h2>
                <p className={s.text}>{tr('refund.period.text')}</p>
            </div>

            <div className={s.section}>
                <h2 className={s.sectionTitle}>{tr('refund.errors.title')}</h2>
                <p className={s.text}>{tr('refund.errors.text')}</p>
                <ul className={s.list}>
                    <li>{tr('refund.errors.li1')}</li>
                    <li>{tr('refund.errors.li2')}</li>
                    <li>{tr('refund.errors.li3')}</li>
                </ul>
            </div>

            <div className={s.section}>
                <h2 className={s.sectionTitle}>{tr('refund.appeal.title')}</h2>
                <p className={s.text}>
                    {tr('refund.appeal.text')}
                    <a href="https://t.me/aimly_support" className={s.link} target="_blank" rel="noopener noreferrer">
                        @aimly_support
                    </a>
                    {lang === 'ru' && ' — опишите ситуацию и приложите скриншот платежа. Разберёмся в течение одного рабочего дня.'}
                    {lang === 'en' && ' — describe the situation and attach a payment screenshot. We\'ll resolve it within one business day.'}
                </p>
            </div>
        </PageLayout>
    )
}