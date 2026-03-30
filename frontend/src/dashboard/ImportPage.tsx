import { useState, useRef, useCallback } from 'react'
import { Link } from 'react-router-dom'
import { importApi, type ImportResult } from '../api/leads.ts'
import s from './Importpage.module.css'

// ─── Icons ───────────────────────────────────────────────────────────────────

const IconUpload = () => (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
        <polyline points="17 8 12 3 7 8"/>
        <line x1="12" y1="3" x2="12" y2="15"/>
    </svg>
)

const IconFile = () => (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
        <polyline points="14 2 14 8 20 8"/>
    </svg>
)

const IconX = () => (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
        <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
    </svg>
)

const IconCheck = () => (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
        <polyline points="20 6 9 17 4 12"/>
    </svg>
)

const IconArrow = () => (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
        <line x1="5" y1="12" x2="19" y2="12"/>
        <polyline points="12 5 19 12 12 19"/>
    </svg>
)

const IconAlert = () => (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" style={{ flexShrink: 0, marginTop: 1 }}>
        <circle cx="12" cy="12" r="10"/>
        <line x1="12" y1="8" x2="12" y2="12"/>
        <line x1="12" y1="16" x2="12.01" y2="16"/>
    </svg>
)

const IconLock = () => (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <rect x="3" y="11" width="18" height="11" rx="2" ry="2"/>
        <path d="M7 11V7a5 5 0 0 1 10 0v4"/>
    </svg>
)

// ─── Утилиты ─────────────────────────────────────────────────────────────────

function formatBytes(bytes: number): string {
    if (bytes < 1024)       return `${bytes} Б`
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} КБ`
    return `${(bytes / 1024 / 1024).toFixed(1)} МБ`
}

// ─── Компонент ───────────────────────────────────────────────────────────────

type Phase = 'idle' | 'loading' | 'done'

export default function ImportPage() {
    const [file,       setFile]       = useState<File | null>(null)
    const [dragOver,   setDragOver]   = useState(false)
    const [phase,      setPhase]      = useState<Phase>('idle')
    const [error,      setError]      = useState('')
    const [result,     setResult]     = useState<ImportResult | null>(null)
    const inputRef = useRef<HTMLInputElement>(null)

    const handleFiles = useCallback((files: FileList | null) => {
        if (!files || files.length === 0) return
        const f = files[0]
        const name = f.name.toLowerCase()
        if (!name.endsWith('.html') && !name.endsWith('.htm') && !name.endsWith('.json')) {
            setError('Поддерживаются только файлы .html и .json (экспорт Telegram Desktop).')
            return
        }
        setError('')
        setResult(null)
        setPhase('idle')
        setFile(f)
    }, [])

    const onDrop = useCallback((e: React.DragEvent) => {
        e.preventDefault()
        setDragOver(false)
        handleFiles(e.dataTransfer.files)
    }, [handleFiles])

    const onDragOver = (e: React.DragEvent) => { e.preventDefault(); setDragOver(true) }
    const onDragLeave = () => setDragOver(false)

    const handleSubmit = async () => {
        if (!file) return
        setPhase('loading')
        setError('')
        try {
            const res = await importApi.uploadExport(file)
            setResult(res)
            setPhase('done')
        } catch (e: unknown) {
            setError(e instanceof Error ? e.message : 'Неизвестная ошибка')
            setPhase('idle')
        }
    }

    const reset = () => {
        setFile(null)
        setResult(null)
        setError('')
        setPhase('idle')
    }

    return (
        <div className={s.page}>

            {/* ── Заголовок ── */}
            <div className={s.header}>
                <h1 className={s.title}>Импорт экспорта чата</h1>
                <p className={s.subtitle}>
                    Загрузите файл экспорта Telegram Desktop — сервис найдёт лиды по вашим ключевым словам.
                </p>
            </div>

            {/* ── Объяснение ── */}
            <div className={s.explainer}>
                <div className={s.explainerIcon}>
                    <IconLock />
                </div>
                <div className={s.explainerBody}>
                    <p className={s.explainerTitle}>Почему не всегда работает автоматический мониторинг?</p>
                    <p className={s.explainerText}>
                        AIMLY мониторит чаты через ваш аккаунт Telegram — в режиме реального времени, как живой участник.
                        Однако есть закрытые сообщества: платные клубы, чаты без публичной ссылки, каналы только по инвайту.
                        Добавить их в мониторинг обычным способом невозможно — ни для вас, ни для нас.
                        Но вы уже <strong>состоите в них</strong> и можете сделать экспорт истории через Telegram Desktop.
                        Загрузите его сюда — мы обработаем всю переписку и найдём лиды по вашим ключевым словам, как если бы мониторинг работал изначально.
                    </p>
                </div>
            </div>

            {/* ── Инструкция ── */}
            <div className={s.steps}>
                <div className={s.step}>
                    <span className={s.stepNum}>1</span>
                    <span className={s.stepText}>
                        Откройте нужный чат в <strong>Telegram Desktop</strong> (Windows, macOS или Linux).
                    </span>
                </div>
                <div className={s.step}>
                    <span className={s.stepNum}>2</span>
                    <span className={s.stepText}>
                        Нажмите <strong>⋮ (три точки)</strong> в правом верхнем углу → <strong>«Экспортировать историю чата»</strong>.
                    </span>
                </div>
                <div className={s.step}>
                    <span className={s.stepNum}>3</span>
                    <span className={s.stepText}>
                        В настройках экспорта выберите формат <strong>HTML</strong> или <strong>JSON</strong>. Machine-readable JSON обрабатывается быстрее.
                    </span>
                </div>
                <div className={s.step}>
                    <span className={s.stepNum}>4</span>
                    <span className={s.stepText}>
                        Дождитесь завершения экспорта и загрузите файл ниже. Максимальный размер — <strong>100 МБ</strong>.
                    </span>
                </div>
            </div>

            {/* ── Зона загрузки ── */}
            {phase !== 'done' && (
                <div className={s.uploadCard}>
                    <p className={s.uploadCardTitle}>Загрузить файл экспорта</p>

                    {!file ? (
                        <div
                            className={`${s.dropzone} ${dragOver ? s.dropzoneActive : ''}`}
                            onDrop={onDrop}
                            onDragOver={onDragOver}
                            onDragLeave={onDragLeave}
                            onClick={() => inputRef.current?.click()}
                            role="button"
                            tabIndex={0}
                            onKeyDown={e => e.key === 'Enter' && inputRef.current?.click()}
                        >
                            <div className={s.dropzoneIcon}>
                                <IconUpload />
                            </div>
                            <span className={s.dropzoneLabel}>
                                Перетащите файл или нажмите, чтобы выбрать
                            </span>
                            <span className={s.dropzoneSub}>.html, .htm, .json · до 100 МБ</span>
                            <input
                                ref={inputRef}
                                type="file"
                                accept=".html,.htm,.json"
                                style={{ display: 'none' }}
                                onChange={e => handleFiles(e.target.files)}
                            />
                        </div>
                    ) : (
                        <div className={s.filePreview}>
                            <div className={s.fileIcon}><IconFile /></div>
                            <div className={s.fileInfo}>
                                <span className={s.fileName}>{file.name}</span>
                                <span className={s.fileSize}>{formatBytes(file.size)}</span>
                            </div>
                            <button className={s.fileClear} onClick={reset} title="Убрать файл">
                                <IconX />
                            </button>
                        </div>
                    )}

                    {error && (
                        <div className={s.error}>
                            <IconAlert />
                            {error}
                        </div>
                    )}

                    {phase === 'loading' ? (
                        <div className={s.progress}>
                            <div className={s.spinner} />
                            <span className={s.progressText}>Обрабатываем файл, ищем лиды…</span>
                        </div>
                    ) : (
                        <button
                            className={s.submitBtn}
                            onClick={handleSubmit}
                            disabled={!file}
                        >
                            <IconUpload />
                            Найти лиды
                        </button>
                    )}
                </div>
            )}

            {/* ── Результат ── */}
            {phase === 'done' && result && (
                <div className={s.result}>
                    <div className={s.resultHeader}>
                        <div className={s.resultBadge}><IconCheck /></div>
                        <div>
                            <p className={s.resultTitle}>Импорт завершён</p>
                            <p className={s.resultChat}>
                                {result.chatTitle} · {result.format.toUpperCase()}
                            </p>
                        </div>
                    </div>

                    <div className={s.resultStats}>
                        <div className={s.statCard}>
                            <span className={s.statValue}>{result.totalMessages.toLocaleString('ru')}</span>
                            <span className={s.statLabel}>Сообщений обработано</span>
                        </div>
                        <div className={s.statCard}>
                            <span className={s.statValue} style={{ color: result.matchedLeads > 0 ? '#10b981' : 'var(--c-ink)' }}>
                                {result.matchedLeads}
                            </span>
                            <span className={s.statLabel}>Лидов найдено</span>
                        </div>
                        <div className={s.statCard}>
                            <span className={s.statValue} style={{ color: result.skippedLeads > 0 ? '#d97706' : 'var(--c-ink)' }}>
                                {result.skippedLeads}
                            </span>
                            <span className={s.statLabel}>Пропущено (дубли)</span>
                        </div>
                    </div>

                    {result.matchedLeads === 0 && (
                        <div className={s.error} style={{ background: 'rgba(245,158,11,.06)', borderColor: 'rgba(245,158,11,.2)', color: '#d97706' }}>
                            <IconAlert />
                            Лидов не найдено. Убедитесь, что ключевые слова настроены и присутствуют в переписке этого чата.
                        </div>
                    )}

                    <div className={s.resultActions}>
                        <Link to="/dashboard/leads" className={s.btnPrimary}>
                            Перейти к лидам
                            <IconArrow />
                        </Link>
                        <button className={s.btnSecondary} onClick={reset}>
                            Загрузить ещё один файл
                        </button>
                    </div>
                </div>
            )}

        </div>
    )
}