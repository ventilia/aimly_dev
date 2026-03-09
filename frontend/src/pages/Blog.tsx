import PageLayout from './PageLayout'
import type { Lang } from '../i18n/translations'
import { t } from '../i18n/translations'
import s from './Page.module.css'

interface Props { lang: Lang; setLang: (l: Lang) => void }

export default function Blog({ lang, setLang }: Props) {
    const tr = (key: string) => t[lang][key] ?? key


    const posts = [
        { titleKey: 'blog.post1.title', textKey: 'blog.post1.text', slug: '#' },
        { titleKey: 'blog.post2.title', textKey: 'blog.post2.text', slug: '#' },
        { titleKey: 'blog.post3.title', textKey: 'blog.post3.text', slug: '#' },
    ]

    return (
        <PageLayout lang={lang} setLang={setLang}>
            <h1 className={s.pageTitle}>{tr('blog.title')}</h1>

            {posts.map(post => (
                <a key={post.titleKey} href={post.slug} className={s.blogCard}>
                    <div className={s.blogCardTitle}>{tr(post.titleKey)}</div>
                    <div className={s.blogCardText}>{tr(post.textKey)}</div>
                </a>
            ))}
        </PageLayout>
    )
}