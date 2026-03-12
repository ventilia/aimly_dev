import { useState, useEffect } from 'react'
import { keywordsApi, businessContextApi, type Keyword } from '../api/leads.ts'
import { useAuthContext } from '../context/AuthContext'
import s from './Keywordspage.module.css'

const MAX_KEYWORDS = 50

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

function SparkleIcon() {
    return (
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none"
             stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M12 2l2.4 7.4H22l-6.2 4.5 2.4 7.4L12 17 5.8 21.3l2.4-7.4L2 9.4h7.6z"/>
        </svg>
    )
}

function CloseIcon() {
    return (
        <svg width="10" height="10" viewBox="0 0 24 24" fill="none"
             stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
            <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
        </svg>
    )
}


const BASE: string = import.meta.env.VITE_API_URL || ''

async function generateKeywordsFromContext(businessContext: string): Promise<string[]> {
    const res = await fetch(`${BASE}/api/v1/keywords/generate`, {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ businessContext }),
    })

    if (!res.ok) {
        const body = await res.json().catch(() => ({ message: `Ошибка ${res.status}` })) as { message?: string; error?: string }
        throw new Error(body.error ?? body.message ?? `Ошибка ${res.status}`)
    }

    const data = await res.json() as { keywords: string[] }
    return data.keywords
}

export default function KeywordsPage() {
    const { user } = useAuthContext()
    const [keywords, setKeywords]           = useState<Keyword[]>([])
    const [loading, setLoading]             = useState(true)
    const [input, setInput]                 = useState('')
    const [adding, setAdding]               = useState(false)
    const [error, setError]                 = useState('')
    const [removing, setRemoving]           = useState<number | null>(null)

    const [bizContext, setBizContext]           = useState('')
    const [bizContextSaved, setBizContextSaved] = useState('')
    const [bizSaving, setBizSaving]             = useState(false)
    const [bizError, setBizError]               = useState('')
    const [bizSuccess, setBizSuccess]           = useState(false)
    const [bizLoading, setBizLoading]           = useState(true)


    const [aiGenerating, setAiGenerating]   = useState(false)
    const [aiSuggestions, setAiSuggestions] = useState<string[]>([])
    const [aiError, setAiError]             = useState('')
    const [addingAi, setAddingAi]           = useState(false)

    const plan   = user?.subscriptionPlan   ?? null
    const status = user?.subscriptionStatus ?? null
    const hasAiFeatures = (
        plan === 'MINIMUM' ||
        plan === 'START'   ||
        status === 'TRIAL'
    )

    const atLimit = keywords.length >= MAX_KEYWORDS

    useEffect(() => {
        keywordsApi
            .list()
            .then(setKeywords)
            .catch((e: unknown) => setError(e instanceof Error ? e.message : 'Ошибка загрузки'))
            .finally(() => setLoading(false))

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
        if (keywords.length >= MAX_KEYWORDS) {
            setError(`Достигнут лимит — максимум ${MAX_KEYWORDS} ключевых слов`)
            return
        }
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
        setAiSuggestions([])
        setAiError('')
        try {
            const res = await businessContextApi.save(bizContext)
            const v = res.businessContext ?? ''
            setBizContext(v)
            setBizContextSaved(v)
            setBizSuccess(true)
            setTimeout(() => setBizSuccess(false), 2500)


            if (v.trim().length > 20) {
                setAiGenerating(true)
                try {
                    const generated = await generateKeywordsFromContext(v)
                    setAiSuggestions(generated)
                } catch (e: unknown) {
                    setAiError(e instanceof Error ? e.message : 'Не удалось сгенерировать ключевые слова')
                } finally {
                    setAiGenerating(false)
                }
            }
        } catch (e: unknown) {
            setBizError(e instanceof Error ? e.message : 'Ошибка сохранения')
        } finally {
            setBizSaving(false)
        }
    }


    const generateKeywords = async () => {
        if (!bizContextSaved.trim() || bizContextSaved.trim().length < 20) {
            setAiError('Сначала заполните и сохраните описание бизнеса')
            return
        }
        setAiGenerating(true)
        setAiError('')
        setAiSuggestions([])
        try {
            const generated = await generateKeywordsFromContext(bizContextSaved)
            setAiSuggestions(generated)
        } catch (e: unknown) {
            setAiError(e instanceof Error ? e.message : 'Не удалось сгенерировать ключевые слова')
        } finally {
            setAiGenerating(false)
        }
    }

    const addAiSuggestion = async (kw: string) => {
        if (keywords.length >= MAX_KEYWORDS) {
            setError(`Достигнут лимит — максимум ${MAX_KEYWORDS} ключевых слов`)
            return
        }
        setError('')

        setAiSuggestions(prev => prev.filter(s => s !== kw))
        try {
            const added = await keywordsApi.add(kw)
            setKeywords(prev => prev.find(k => k.id === added.id) ? prev : [...prev, added])
        } catch (e: unknown) {

            setAiSuggestions(prev => [kw, ...prev])
            setError(e instanceof Error ? e.message : 'Не удалось добавить')
        }
    }


    const dismissAiSuggestion = (kw: string) => {
        setAiSuggestions(prev => prev.filter(s => s !== kw))
    }


    const addAllAiSuggestions = async () => {
        const available = MAX_KEYWORDS - keywords.length
        if (available <= 0) {
            setError(`Достигнут лимит — максимум ${MAX_KEYWORDS} ключевых слов`)
            return
        }

        const toAdd = aiSuggestions.slice(0, available)
        if (toAdd.length === 0) return

        setAddingAi(true)
        setError('')

        const tempItems: Keyword[] = toAdd.map((kw, i) => ({
            id: -(Date.now() + i), // отрицательный временный id
            keyword: kw,
            isActive: true,
            variants: [],
        }))
        setKeywords(prev => [...prev, ...tempItems])
        setAiSuggestions([])

        const results = await Promise.allSettled(
            toAdd.map(kw => keywordsApi.add(kw))
        )

        setKeywords(prev => {
            const withoutTemp = prev.filter(k => k.id >= 0)
            const added = results
                .filter((r): r is PromiseFulfilledResult<Keyword> => r.status === 'fulfilled')
                .map(r => r.value)

            const existingIds = new Set(withoutTemp.map(k => k.id))
            const newItems = added.filter(k => !existingIds.has(k.id))
            return [...withoutTemp, ...newItems]
        })


        const failed = results.filter(r => r.status === 'rejected')
        if (failed.length > 0) {
            setError(`Не удалось добавить ${failed.length} слов — возможно, они уже существуют`)
        }

        setAddingAi(false)
    }

    // Отклоняем все AI-предложения сразу
    const dismissAllAiSuggestions = () => {
        setAiSuggestions([])
    }

    const unused = SUGGESTIONS.filter(sg => !keywords.find(k => k.keyword.toLowerCase() === sg.toLowerCase()))
    const bizContextChanged = bizContext !== bizContextSaved

    return (
        <div className={s.page}>

            {/* ─── Персонализация AI ─── */}
            <div className={s.bizBlock}>
                <div className={s.bizHeader}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                        <span style={{ color: 'var(--c-accent)', display: 'flex' }}><SparkleIcon /></span>
                        <span style={{ fontSize: 15, fontWeight: 700, color: 'var(--c-ink)' }}>
                            Персонализация AI
                        </span>
                    </div>
                    {hasAiFeatures && (
                        <span style={{
                            fontSize: 11, fontWeight: 700, padding: '3px 8px',
                            borderRadius: 6, background: 'var(--c-accent-soft)',
                            color: 'var(--c-accent)', letterSpacing: '0.5px',
                        }}>
                            AI-функция
                        </span>
                    )}
                </div>

                {hasAiFeatures ? (
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                        <p style={{ fontSize: 13, color: 'var(--c-ink-2)', margin: 0, lineHeight: 1.5 }}>
                            Опишите ваш бизнес, услуги и целевую аудиторию. AI будет учитывать это при фильтрации лидов и автоматически сгенерирует ключевые слова для мониторинга и дополнительные варианты к нему.
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

                            {}
                            {bizContextSaved.trim().length > 20 && (
                                <button
                                    onClick={generateKeywords}
                                    disabled={aiGenerating || bizSaving}
                                    style={{
                                        display: 'inline-flex', alignItems: 'center', gap: 6,
                                        padding: '9px 18px', borderRadius: 9,
                                        background: aiGenerating ? 'var(--c-surface)' : 'var(--c-green-soft)',
                                        border: '1.5px solid var(--c-green)',
                                        color: 'var(--c-green)',
                                        fontSize: 13, fontWeight: 600,
                                        cursor: aiGenerating ? 'default' : 'pointer',
                                        fontFamily: 'var(--font-body)', transition: 'all .15s',
                                        opacity: aiGenerating ? 0.7 : 1,
                                    }}
                                >
                                    <SparkleIcon />
                                    {aiGenerating ? 'Генерируем...' : 'Сгенерировать ключевые слова'}
                                </button>
                            )}

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

                        {}
                        {aiGenerating && (
                            <div style={{
                                padding: '16px 18px',
                                background: 'var(--c-accent-soft)',
                                borderRadius: 10,
                                border: '1.5px solid rgba(92,57,223,.15)',
                                display: 'flex', alignItems: 'center', gap: 10,
                                fontSize: 13, color: 'var(--c-accent)',
                            }}>
                                <span style={{ animation: 'spin 1s linear infinite', display: 'inline-block' }}>✦</span>
                                AI анализирует ваш бизнес и подбирает ключевые слова...
                            </div>
                        )}

                        {aiError && (
                            <div className={s.error}>
                                <span className={s.errorIcon}><AlertIcon /></span>
                                <span>Ошибка генерации: {aiError}</span>
                            </div>
                        )}

                        {aiSuggestions.length > 0 && (
                            <div style={{
                                padding: '16px 18px',
                                background: 'var(--c-surface)',
                                border: '1.5px solid var(--c-green)',
                                borderRadius: 12,
                                display: 'flex', flexDirection: 'column', gap: 12,
                            }}>
                                {}
                                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 8 }}>
                                    <span style={{ fontSize: 13, fontWeight: 700, color: 'var(--c-ink)', display: 'flex', alignItems: 'center', gap: 6 }}>
                                        <span style={{ color: 'var(--c-green)' }}><SparkleIcon /></span>
                                        AI сгенерировал {aiSuggestions.length} ключевых слов
                                    </span>
                                    <div style={{ display: 'flex', gap: 8 }}>
                                        <button
                                            onClick={addAllAiSuggestions}
                                            disabled={atLimit || addingAi}
                                            title={atLimit ? `Лимит ${MAX_KEYWORDS} слов достигнут` : undefined}
                                            style={{
                                                padding: '6px 14px', borderRadius: 8,
                                                background: (atLimit || addingAi) ? 'var(--c-border)' : 'var(--c-green)',
                                                color: '#fff',
                                                border: 'none', fontSize: 12, fontWeight: 600,
                                                cursor: (atLimit || addingAi) ? 'default' : 'pointer',
                                                fontFamily: 'var(--font-body)',
                                                opacity: (atLimit || addingAi) ? 0.5 : 1,
                                            }}
                                        >
                                            {addingAi ? 'Добавляем...' : 'Добавить все'}
                                        </button>
                                        <button
                                            onClick={dismissAllAiSuggestions}
                                            disabled={addingAi}
                                            style={{
                                                padding: '6px 14px', borderRadius: 8,
                                                background: 'transparent',
                                                color: 'var(--c-ink-3)',
                                                border: '1.5px solid var(--c-border)',
                                                fontSize: 12, fontWeight: 600,
                                                cursor: 'pointer',
                                                fontFamily: 'var(--font-body)',
                                                transition: 'all .15s',
                                            }}
                                            onMouseEnter={e => {
                                                (e.currentTarget as HTMLButtonElement).style.borderColor = '#ef4444'
                                                ;(e.currentTarget as HTMLButtonElement).style.color = '#ef4444'
                                            }}
                                            onMouseLeave={e => {
                                                (e.currentTarget as HTMLButtonElement).style.borderColor = 'var(--c-border)'
                                                ;(e.currentTarget as HTMLButtonElement).style.color = 'var(--c-ink-3)'
                                            }}
                                        >
                                            Отклонить все
                                        </button>
                                    </div>
                                </div>

                                {}
                                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
                                    {aiSuggestions.map(kw => (
                                        <div
                                            key={kw}
                                            style={{
                                                display: 'inline-flex', alignItems: 'center',
                                                borderRadius: 20,
                                                background: 'var(--c-green-soft)',
                                                border: '1.5px solid var(--c-green)',
                                                overflow: 'hidden',
                                            }}
                                        >
                                            {}
                                            <button
                                                onClick={() => addAiSuggestion(kw)}
                                                disabled={atLimit}
                                                title={atLimit ? `Лимит ${MAX_KEYWORDS} слов достигнут` : 'Добавить'}
                                                style={{
                                                    display: 'inline-flex', alignItems: 'center', gap: 5,
                                                    padding: '6px 10px 6px 12px',
                                                    background: 'transparent', border: 'none',
                                                    color: 'var(--c-green)',
                                                    fontSize: 12, fontWeight: 600,
                                                    cursor: atLimit ? 'default' : 'pointer',
                                                    fontFamily: 'var(--font-body)',
                                                    transition: 'all .15s',
                                                    opacity: atLimit ? 0.5 : 1,
                                                }}
                                                onMouseEnter={e => {
                                                    if (!atLimit) {
                                                        (e.currentTarget as HTMLButtonElement).style.background = 'rgba(16,185,129,.15)'
                                                    }
                                                }}
                                                onMouseLeave={e => {
                                                    (e.currentTarget as HTMLButtonElement).style.background = 'transparent'
                                                }}
                                            >
                                                + {kw}
                                            </button>
                                            {}
                                            <button
                                                onClick={() => dismissAiSuggestion(kw)}
                                                title="Отклонить"
                                                style={{
                                                    display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                                                    width: 24, height: '100%',
                                                    padding: '0 6px 0 2px',
                                                    background: 'transparent', border: 'none',
                                                    color: 'var(--c-green)',
                                                    cursor: 'pointer',
                                                    transition: 'color .15s',
                                                    opacity: 0.6,
                                                }}
                                                onMouseEnter={e => {
                                                    (e.currentTarget as HTMLButtonElement).style.opacity = '1'
                                                    ;(e.currentTarget as HTMLButtonElement).style.color = '#ef4444'
                                                }}
                                                onMouseLeave={e => {
                                                    (e.currentTarget as HTMLButtonElement).style.opacity = '0.6'
                                                    ;(e.currentTarget as HTMLButtonElement).style.color = 'var(--c-green)'
                                                }}
                                            >
                                                <CloseIcon />
                                            </button>
                                        </div>
                                    ))}
                                </div>

                                <p style={{ fontSize: 12, color: 'var(--c-ink-3)', margin: 0 }}>
                                    Нажмите на фразу чтобы добавить, × чтобы отклонить одну, или используйте кнопки выше
                                </p>
                            </div>
                        )}
                    </div>
                ) : (

                    <div style={{
                        background: 'var(--c-surface)',
                        border: '1.5px dashed var(--c-border)',
                        borderRadius: 12,
                        padding: '20px 20px',
                        display: 'flex',
                        flexDirection: 'column',
                        gap: 10,
                    }}>
                        <p style={{ fontSize: 13, color: 'var(--c-ink-2)', margin: 0, lineHeight: 1.5 }}>
                            Персонализация AI и генерация ключевых слов доступны на тарифе <b>МИНИМУМ</b>.
                            Опишите ваш бизнес один раз, и система будет автоматически подбирать релевантные лиды.
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
                            Подключить тариф →
                        </a>
                    </div>
                )}
            </div>

            {}
            <div className={s.addBlock}>
                <div className={s.addRow}>
                    <div className={s.inputWrap}>
                        <span className={s.inputIcon}><SearchIcon /></span>
                        <input
                            className={s.input}
                            value={input}
                            onChange={e => setInput(e.target.value)}
                            onKeyDown={e => e.key === 'Enter' && add()}
                            placeholder={atLimit ? `Достигнут лимит ${MAX_KEYWORDS} слов` : 'Введите слово или фразу...'}
                            disabled={adding || atLimit}
                            autoComplete="off"
                        />
                    </div>
                    <button
                        className={s.addBtn}
                        onClick={() => add()}
                        disabled={adding || !input.trim() || atLimit}
                        title={atLimit ? `Максимум ${MAX_KEYWORDS} ключевых слов` : undefined}
                    >
                        {adding ? <span className={s.spinner} /> : <>+ Добавить</>}
                    </button>
                </div>

                {unused.length > 0 && !atLimit && (
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
                        <span className={s.badge}>{keywords.length} / {MAX_KEYWORDS}</span>
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
                        ? 'Поиск нечувствителен к регистру. AI автоматически расширяет каждое ключевое слово вариантами и синонимами. Максимум 50 слов.'
                        : 'Поиск нечувствителен к регистру. Фразы из нескольких слов работают лучше — они дают меньше ложных совпадений. Максимум 50 слов.'}
                </span>
            </div>
        </div>
    )
}