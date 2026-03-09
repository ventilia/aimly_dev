import { useState, useEffect } from 'react'
import { keywordsApi, businessContextApi, type Keyword } from '../api/leads.ts'
import { useAuthContext } from '../context/AuthContext'
import s from './Keywordspage.module.css'

const SUGGESTIONS = [
    'ищу дизайнера', 'нужен разработчик', 'ищу smm', 'нужен копирайтер',
    'требуется верстальщик', 'ищем маркетолога', 'нужна реклама', 'ищу таргетолога',
    'ищу менеджера', 'нужен монтажёр',
]

function SearchIcon() {
    return (
        <svg width="13" height="13" viewBox="0 0 24 24" fill="none"
             stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/>
        </svg>
    )
}

function InfoIcon() {
    return (
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none"
             stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <circle cx="12" cy="12" r="10"/>
            <line x1="12" y1="8" x2="12" y2="12"/>
            <line x1="12" y1="16" x2="12.01" y2="16"/>
        </svg>
    )
}

function AlertIcon() {
    return (
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none"
             stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/>
            <line x1="12" y1="9" x2="12" y2="13"/>
            <line x1="12" y1="17" x2="12.01" y2="17"/>
        </svg>
    )
}

function LockIcon() {
    return (
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none"
             stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <rect x="3" y="11" width="18" height="11" rx="2" ry="2"/>
            <path d="M7 11V7a5 5 0 0 1 10 0v4"/>
        </svg>
    )
}

function SparkleIcon() {
    return (
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none"
             stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M12 2l2.4 7.4H22l-6.2 4.5 2.4 7.4L12 17 5.8 21.3l2.4-7.4L2 9.4h7.6z"/>
        </svg>
    )
}

export default function KeywordsPage() {
    const { user } = useAuthContext()
    const [keywords, setKeywords]           = useState<Keyword[]>([])
    const [loading, setLoading]             = useState(true)
    const [input, setInput]                 = useState('')
    const [adding, setAdding]               = useState(false)
    const [error, setError]                 = useState('')
    const [removing, setRemoving]           = useState<number | null>(null)


    const [bizContext, setBizContext]       = useState('')
    const [bizContextSaved, setBizContextSaved] = useState('')
    const [bizSaving, setBizSaving]         = useState(false)
    const [bizError, setBizError]           = useState('')
    const [bizSuccess, setBizSuccess]       = useState(false)
    const [bizLoading, setBizLoading]       = useState(true)

    // Тариф: MINIMUM = без AI; START/BUSINESS = с AI
    const plan = user?.subscriptionPlan ?? null
    const hasAiFeatures = plan === 'START' || plan === 'BUSINESS'

    useEffect(() => {
        keywordsApi
            .list()
            .then(setKeywords)
            .catch((e: unknown) => setError(e instanceof Error ? e.message : 'Ошибка загрузки'))
            .finally(() => setLoading(false))

        // Загружаем бизнес-контекст только если есть AI-тариф
        if (hasAiFeatures) {
            businessContextApi
                .get()
                .then(r => {
                    const v = r.businessContext ?? ''
                    setBizContext(v)
                    setBizContextSaved(v)
                })
                .catch(() => {})
                .finally(() => setBizLoading(false))
        } else {
            setBizLoading(false)
        }
    }, [hasAiFeatures])

    const add = async (kw?: string) => {
        const word = (kw ?? input).trim()
        if (!word) return
        setAdding(true)
        setError('')
        try {
            const added = await keywordsApi.add(word)
            setKeywords(prev => prev.find(k => k.id === added.id) ? prev : [...prev, added])
            if (!kw) setInput('')
        } catch (e: unknown) {
            setError(e instanceof Error ? e.message : 'Не удалось добавить')
        } finally {
            setAdding(false)
        }
    }

    const remove = async (id: number) => {
        setRemoving(id)
        try {
            await keywordsApi.remove(id)
            setKeywords(prev => prev.filter(k => k.id !== id))
        } catch (e: unknown) {
            setError(e instanceof Error ? e.message : 'Ошибка удаления')
        } finally {
            setRemoving(null)
        }
    }

    const saveBizContext = async () => {
        setBizSaving(true)
        setBizError('')
        setBizSuccess(false)
        try {
            const res = await businessContextApi.save(bizContext)
            const v = res.businessContext ?? ''
            setBizContext(v)
            setBizContextSaved(v)
            setBizSuccess(true)
            setTimeout(() => setBizSuccess(false), 2500)
        } catch (e: unknown) {
            setBizError(e instanceof Error ? e.message : 'Ошибка сохранения')
        } finally {
            setBizSaving(false)
        }
    }

    const unused = SUGGESTIONS.filter(sg => !keywords.find(k => k.keyword.toLowerCase() === sg.toLowerCase()))
    const bizContextChanged = bizContext !== bizContextSaved

    return (
        <div className={s.page}>
            <div className={s.pageHead}>
                <div>
                    <h1 className={s.title}>Ключевые слова и персонализация</h1>
                    <p className={s.sub}>
                        Бот ищет совпадения в каждом сообщении и присылает лид при нахождении
                    </p>
                </div>
                {keywords.length > 0 && (
                    <div className={s.counter}>
                        <span className={s.counterNum}>{keywords.length}</span>
                        <span className={s.counterLabel}>слов</span>
                    </div>
                )}
            </div>

            {/* ─── Блок персонализации ─── */}
            <div className={s.section} style={{ marginBottom: 24 }}>
                <div className={s.sectionHead}>
                    <span className={s.sectionTitle} style={{ display: 'flex', alignItems: 'center', gap: 7 }}>
                        <SparkleIcon />
                        Персонализация для AI
                    </span>
                    {!hasAiFeatures && (
                        <span style={{
                            display: 'inline-flex', alignItems: 'center', gap: 4,
                            fontSize: 11, fontWeight: 600,
                            background: 'rgba(92,57,223,.12)', color: 'var(--c-accent)',
                            padding: '2px 8px', borderRadius: 100,
                        }}>
                            <LockIcon />
                            Тариф СТАРТ
                        </span>
                    )}
                </div>

                {hasAiFeatures ? (
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                        <p style={{ fontSize: 13, color: 'var(--c-ink-2)', margin: 0, lineHeight: 1.5 }}>
                            Опишите ваш бизнес, услуги и целевую аудиторию. AI будет учитывать это при фильтрации лидов — отбирая только тех, кто ищет именно то, что вы предлагаете.
                        </p>
                        <textarea
                            value={bizContext}
                            onChange={e => setBizContext(e.target.value)}
                            placeholder="Например: Я frontend-разработчик, специализируюсь на React и Next.js. Ищу клиентов, которым нужна разработка или доработка веб-приложений. Работаю с бюджетами от 50 000 ₽..."
                            disabled={bizSaving}
                            maxLength={2000}
                            rows={5}
                            style={{
                                width: '100%',
                                background: 'var(--c-surface)',
                                border: '1.5px solid var(--c-border)',
                                borderRadius: 10,
                                padding: '12px 14px',
                                fontSize: 13,
                                color: 'var(--c-ink)',
                                fontFamily: 'var(--font-body)',
                                lineHeight: 1.55,
                                resize: 'vertical',
                                outline: 'none',
                                boxSizing: 'border-box',
                                transition: 'border-color .15s',
                            }}
                            onFocus={e => (e.target.style.borderColor = 'var(--c-accent)')}
                            onBlur={e => (e.target.style.borderColor = 'var(--c-border)')}
                        />
                        <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
                            <button
                                onClick={saveBizContext}
                                disabled={bizSaving || !bizContextChanged}
                                style={{
                                    padding: '9px 20px', borderRadius: 9,
                                    background: bizContextChanged ? 'var(--c-accent)' : 'var(--c-surface)',
                                    border: `1.5px solid ${bizContextChanged ? 'var(--c-accent)' : 'var(--c-border)'}`,
                                    color: bizContextChanged ? '#fff' : 'var(--c-ink-3)',
                                    fontSize: 13, fontWeight: 600, cursor: bizContextChanged ? 'pointer' : 'default',
                                    fontFamily: 'var(--font-body)', transition: 'all .15s',
                                }}
                            >
                                {bizSaving ? 'Сохраняем...' : bizSuccess ? '✓ Сохранено' : 'Сохранить'}
                            </button>
                            <span style={{ fontSize: 12, color: 'var(--c-ink-3)' }}>
                                {bizContext.length} / 2000
                            </span>
                        </div>
                        {bizError && (
                            <div className={s.error}>
                                <span className={s.errorIcon}><AlertIcon /></span>
                                <span>{bizError}</span>
                            </div>
                        )}
                    </div>
                ) : (
                    /* Заглушка для тарифа MINIMUM */
                    <div style={{
                        background: 'var(--c-surface)',
                        border: '1.5px dashed var(--c-border)',
                        borderRadius: 12,
                        padding: '20px 20px',
                        display: 'flex',
                        flexDirection: 'column',
                        gap: 10,
                    }}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                            <span style={{ color: 'var(--c-accent)' }}><LockIcon /></span>
                            <span style={{ fontSize: 14, fontWeight: 600, color: 'var(--c-ink)' }}>
                                Персонализация AI недоступна на тарифе MINIMUM
                            </span>
                        </div>
                        <p style={{ fontSize: 13, color: 'var(--c-ink-2)', margin: 0, lineHeight: 1.5 }}>
                            Тариф <b>СТАРТ</b> включает персонализацию для AI: опишите ваш бизнес один раз, и система будет автоматически отбирать лиды, подходящие именно вам — отсеивая нерелевантные запросы.
                        </p>
                        <a
                            href="/checkout"
                            style={{
                                display: 'inline-flex', alignItems: 'center', gap: 6,
                                padding: '9px 18px', borderRadius: 9,
                                background: 'var(--c-accent)', color: '#fff',
                                fontSize: 13, fontWeight: 600, textDecoration: 'none',
                                width: 'fit-content', transition: 'opacity .15s',
                            }}
                            onMouseEnter={e => (e.currentTarget as HTMLAnchorElement).style.opacity = '0.85'}
                            onMouseLeave={e => (e.currentTarget as HTMLAnchorElement).style.opacity = '1'}
                        >
                            Улучшить тариф →
                        </a>
                    </div>
                )}
            </div>

            {/* ─── Добавление ключевых слов ─── */}
            <div className={s.addBlock}>
                <div className={s.addRow}>
                    <div className={s.inputWrap}>
                        <span className={s.inputIcon}><SearchIcon /></span>
                        <input
                            className={s.input}
                            value={input}
                            onChange={e => setInput(e.target.value)}
                            onKeyDown={e => e.key === 'Enter' && add()}
                            placeholder="Введите слово или фразу..."
                            disabled={adding}
                            autoComplete="off"
                        />
                    </div>
                    <button
                        className={s.addBtn}
                        onClick={() => add()}
                        disabled={adding || !input.trim()}
                    >
                        {adding ? <span className={s.spinner} /> : <>+ Добавить</>}
                    </button>
                </div>

                {unused.length > 0 && (
                    <div className={s.suggestions}>
                        <span className={s.sugLabel}>Популярные:</span>
                        <div className={s.sugChips}>
                            {unused.slice(0, 6).map(sg => (
                                <button key={sg} className={s.sugChip} onClick={() => add(sg)} disabled={adding}>
                                    + {sg}
                                </button>
                            ))}
                        </div>
                    </div>
                )}
            </div>

            {error && (
                <div className={s.error}>
                    <span className={s.errorIcon}><AlertIcon /></span>
                    <span>{error}</span>
                    <button className={s.errorClose} onClick={() => setError('')}>
                        <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
                            <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
                        </svg>
                    </button>
                </div>
            )}

            {loading ? (
                <div className={s.skels}>
                    {[...Array(4)].map((_, i) => <div key={i} className={s.skel} />)}
                </div>
            ) : keywords.length === 0 ? (
                <div className={s.empty}>
                    <div className={s.emptyIcon}><SearchIcon /></div>
                    <p className={s.emptyTitle}>Нет ключевых слов</p>
                    <span className={s.emptySub}>Добавьте слово выше или выберите из подсказок</span>
                </div>
            ) : (
                <div className={s.section}>
                    <div className={s.sectionHead}>
                        <span className={s.sectionTitle}>Активные слова</span>
                        <span className={s.badge}>{keywords.length}</span>
                    </div>
                    <div className={s.chips}>
                        {keywords.map(kw => (
                            <div key={kw.id} className={s.chip}>
                                <span className={s.chipDot} />
                                <span className={s.chipText}>{kw.keyword}</span>
                                <button
                                    className={s.chipDel}
                                    onClick={() => remove(kw.id)}
                                    disabled={removing === kw.id}
                                    title="Удалить"
                                >
                                    {removing === kw.id ? <span className={s.spinnerXs} /> : (
                                        <svg width="9" height="9" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
                                            <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
                                        </svg>
                                    )}
                                </button>
                            </div>
                        ))}
                    </div>
                </div>
            )}

            <div className={s.hint}>
                <span className={s.hintIcon}><InfoIcon /></span>
                <span>
                    {hasAiFeatures
                        ? 'Поиск нечувствителен к регистру. AI автоматически расширяет каждое ключевое слово вариантами и синонимами.'
                        : 'Поиск нечувствителен к регистру. Фразы из нескольких слов работают лучше — они дают меньше ложных совпадений.'}
                </span>
            </div>
        </div>
    )
}