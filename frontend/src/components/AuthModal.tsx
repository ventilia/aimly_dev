import { useState, useEffect, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import type { Lang } from '../i18n/translations'
import { authApi } from '../api/auth'
import { useAuthContext } from '../context/AuthContext'
import s from './AuthModal.module.css'


function EyeIcon() {
    return (
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none"
             stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8S1 12 1 12z" />
            <circle cx="12" cy="12" r="3" />
        </svg>
    )
}

function EyeOffIcon() {
    return (
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none"
             stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94" />
            <path d="M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19" />
            <line x1="1" y1="1" x2="23" y2="23" />
        </svg>
    )
}

function CloseIcon() {
    return (
        <svg width="14" height="14" viewBox="0 0 14 14" fill="none"
             stroke="currentColor" strokeWidth="2" strokeLinecap="round">
            <line x1="1" y1="1" x2="13" y2="13" />
            <line x1="13" y1="1" x2="1" y2="13" />
        </svg>
    )
}


function GoogleIcon() {
    return (
        <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true">
            <path fill="#4285F4" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z"/>
            <path fill="#34A853" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"/>
            <path fill="#FBBC05" d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l3.66-2.84z"/>
            <path fill="#EA4335" d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z"/>
        </svg>
    )
}


const txt = {
    ru: {
        login:              'Войти',
        register:           'Регистрация',
        email:              'Email',
        password:           'Пароль',
        confirmPassword:    'Повторите пароль',
        firstName:          'Имя (необязательно)',
        loginBtn:           'Войти',
        registerBtn:        'Создать аккаунт',
        loading:            'Загрузка…',
        codeTitle:          'Код из письма',
        codeHint:           'Отправили 6-значный код на',
        verifyBtn:          'Подтвердить',
        resend:             'Отправить снова',
        resendIn:           'Повторно через',
        sec:                'сек',
        successTitle:       'Добро пожаловать!',
        successText:        'Email подтверждён. Переходим в личный кабинет…',
        successBtn:         'Перейти в кабинет',
        close:              'Закрыть',
        passwordHint:       'Минимум 8 символов: строчные + заглавные + цифра',
        showPass:           'Показать пароль',
        hidePass:           'Скрыть пароль',
        passwordsNoMatch:   'Пароли не совпадают',
        orDivider:          'или',
        googleBtn:          'Войти через Google',
        unverifiedHint:     'Мы отправили новый код на ваш email. Подтвердите почту.',
    },
    en: {
        login:              'Sign in',
        register:           'Sign up',
        email:              'Email',
        password:           'Password',
        confirmPassword:    'Confirm password',
        firstName:          'First name (optional)',
        loginBtn:           'Sign in',
        registerBtn:        'Create account',
        loading:            'Loading…',
        codeTitle:          'Email verification',
        codeHint:           'We sent a 6-digit code to',
        verifyBtn:          'Verify',
        resend:             'Send again',
        resendIn:           'Resend in',
        sec:                's',
        successTitle:       'Welcome!',
        successText:        'Email confirmed. Redirecting to your dashboard…',
        successBtn:         'Go to dashboard',
        close:              'Close',
        passwordHint:       'Min 8 chars: lowercase + uppercase + digit',
        showPass:           'Show password',
        hidePass:           'Hide password',
        passwordsNoMatch:   'Passwords do not match',
        orDivider:          'or',
        googleBtn:          'Continue with Google',
        unverifiedHint:     'We sent a new code to your email. Please verify.',
    },
} as const

type Step    = 'form' | 'code' | 'success'
type TabMode = 'login' | 'register'
interface Props {
    lang:        Lang
    onClose:     () => void
    initialTab?: TabMode
}

interface PFProps {
    label:        string
    value:        string
    onChange:     (v: string) => void
    onEnter?:     () => void
    hasError?:    boolean
    errorText?:   string
    hint?:        string
    autoComplete: string
    showLabel:    string
    hideLabel:    string
}

function PasswordField({ label, value, onChange, onEnter, hasError, errorText, hint, autoComplete, showLabel, hideLabel }: PFProps) {
    const [show, setShow] = useState(false)
    return (
        <div className={s.field}>
            <label className={s.label}>{label}</label>
            <div className={s.passwordWrap}>
                <input
                    className={`${s.input} ${hasError ? s.inputError : ''}`}
                    type={show ? 'text' : 'password'}
                    placeholder="••••••••"
                    value={value}
                    onChange={e => onChange(e.target.value)}
                    onKeyDown={e => { if (e.key === 'Enter' && onEnter) onEnter() }}
                    autoComplete={autoComplete}
                />
                <button
                    type="button"
                    className={s.showPassBtn}
                    onClick={() => setShow(v => !v)}
                    aria-label={show ? hideLabel : showLabel}
                    tabIndex={-1}
                >
                    {show ? <EyeOffIcon /> : <EyeIcon />}
                </button>
            </div>
            {hint      && !hasError && <span className={s.fieldHint}>{hint}</span>}
            {errorText &&  hasError && <span className={s.fieldError}>{errorText}</span>}
        </div>
    )
}


export default function AuthModal({ lang, onClose, initialTab = 'login' }: Props) {
    const l = txt[lang]
    const { saveSession } = useAuthContext()
    const navigate = useNavigate()

    const [tab,  setTab]  = useState<TabMode>(initialTab)
    const [step, setStep] = useState<Step>('form')

    const [email,           setEmail]           = useState('')
    const [password,        setPassword]        = useState('')
    const [confirmPassword, setConfirmPassword] = useState('')
    const [firstName,       setFirstName]       = useState('')
    const [emailUsed,       setEmailUsed]       = useState('')

    const [code,        setCode]        = useState('')
    const [resendTimer, setResendTimer] = useState(0)
    const timerRef = useRef<ReturnType<typeof setInterval> | null>(null)

    const [loading,   setLoading]   = useState(false)
    const [error,     setError]     = useState<string | null>(null)
    const [codeError, setCodeError] = useState<string | null>(null)

    const codeInputRef = useRef<HTMLInputElement>(null)

    // Обновляем tab если изменился initialTab (при открытии с другим режимом)
    useEffect(() => {
        setTab(initialTab)
    }, [initialTab])

    useEffect(() => {
        const fn = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
        document.addEventListener('keydown', fn)
        return () => document.removeEventListener('keydown', fn)
    }, [onClose])

    useEffect(() => {
        document.body.style.overflow = 'hidden'
        return () => { document.body.style.overflow = '' }
    }, [])

    useEffect(() => () => { if (timerRef.current) clearInterval(timerRef.current) }, [])

    useEffect(() => {
        if (step === 'code') setTimeout(() => codeInputRef.current?.focus(), 120)
    }, [step])

    // Автоматический редирект через 2 секунды после успешной верификации
    useEffect(() => {
        if (step === 'success') {
            const timer = setTimeout(() => {
                onClose()
                navigate('/dashboard')
            }, 2000)
            return () => clearTimeout(timer)
        }
    }, [step, navigate, onClose])

    const startResendTimer = (seconds = 60) => {
        setResendTimer(seconds)
        if (timerRef.current) clearInterval(timerRef.current)
        timerRef.current = setInterval(() => {
            setResendTimer(prev => {
                if (prev <= 1) { clearInterval(timerRef.current!); return 0 }
                return prev - 1
            })
        }, 1000)
    }

    const switchTab = (next: TabMode) => {
        setTab(next)
        setError(null)
        setPassword('')
        setConfirmPassword('')
    }

    const goToDashboard = () => {
        onClose()
        navigate('/dashboard')
    }

    const handleLogin = async () => {
        setError(null)
        if (!email.trim() || !password) { setError('Заполните все поля'); return }
        setLoading(true)
        try {
            const res = await authApi.login(email.trim(), password)

            if (res.pendingVerification) {
                setEmailUsed(res.email)
                setStep('code')
                startResendTimer(60)
                return
            }

            if (res.auth) {
                saveSession(res.auth)
                goToDashboard()
            }
        } catch (e: unknown) {
            setError(e instanceof Error ? e.message : 'Ошибка входа')
        } finally {
            setLoading(false)
        }
    }

    const handleRegister = async () => {
        setError(null)
        if (!email.trim() || !password || !confirmPassword) {
            setError('Заполните все обязательные поля'); return
        }
        if (password !== confirmPassword) {
            setError(l.passwordsNoMatch); return
        }
        if (password.length < 8) {
            setError(l.passwordHint); return
        }
        setLoading(true)
        try {
            const res = await authApi.register({
                email:          email.trim(),
                password,
                confirmPassword,
                firstName:      firstName.trim() || undefined,
            })
            if (res.token !== null || res.userId !== null) {
                setEmailUsed(email.trim())
                setStep('code')
                startResendTimer(60)
            } else {
                setError(res.message)
            }
        } catch (e: unknown) {
            setError(e instanceof Error ? e.message : 'Ошибка регистрации')
        } finally {
            setLoading(false)
        }
    }

    const handleVerify = async () => {
        setCodeError(null)
        if (code.length !== 6) { setCodeError('Введите 6-значный код'); return }
        setLoading(true)
        try {
            const res = await authApi.verifyEmail(code)
            saveSession(res)
            setStep('success')
        } catch (e: unknown) {
            setCodeError(e instanceof Error ? e.message : 'Неверный код')
        } finally {
            setLoading(false)
        }
    }

    const handleResend = async () => {
        if (resendTimer > 0) return
        setCodeError(null)
        setLoading(true)
        try {
            await authApi.resendCode()
            setCode('')
            startResendTimer(60)
        } catch (e: unknown) {
            setCodeError(e instanceof Error ? e.message : 'Не удалось отправить код')
        } finally {
            setLoading(false)
        }
    }

    const handleGoogle = () => {
        const google = (window as unknown as { google?: { accounts: { id: { initialize: (cfg: object) => void; prompt: () => void } } } }).google
        if (!google) {
            setError('Google Sign-In не загружен. Перезагрузите страницу.')
            return
        }

        google.accounts.id.initialize({
            client_id: import.meta.env.VITE_GOOGLE_CLIENT_ID,
            callback:  async (response: { credential: string }) => {
                setLoading(true)
                setError(null)
                try {
                    const auth = await authApi.loginWithGoogle(response.credential)
                    saveSession(auth)
                    goToDashboard()
                } catch (e: unknown) {
                    setError(e instanceof Error ? e.message : 'Ошибка входа через Google')
                } finally {
                    setLoading(false)
                }
            },
        })

        google.accounts.id.prompt()
    }

    if (step === 'success') {
        return (
            <div className={s.overlay}>
                <div className={s.modal} role="dialog" aria-modal="true">
                    <div className={s.body}>
                        <div className={s.successStep}>
                            <div className={s.successIcon}>✓</div>
                            <h2 className={s.successTitle}>{l.successTitle}</h2>
                            <p className={s.successText}>{l.successText}</p>
                            <button className={`btn-primary ${s.successBtn}`} onClick={goToDashboard}>
                                {l.successBtn}
                            </button>
                        </div>
                    </div>
                </div>
            </div>
        )
    }

    if (step === 'code') {
        return (
            <div className={s.overlay} onMouseDown={e => { if (e.target === e.currentTarget) onClose() }}>
                <div className={s.modal} role="dialog" aria-modal="true">
                    <div className={s.header}>
                        <span className={s.title}>{l.codeTitle}</span>
                        <button className={s.closeBtn} onClick={onClose} aria-label={l.close}><CloseIcon /></button>
                    </div>
                    <div className={s.body}>
                        <div className={s.codeStep}>
                            <p className={s.codeHint}>
                                {l.codeHint}{' '}
                                <span className={s.codeHintEmail}>{emailUsed}</span>
                            </p>

                            <div className={s.codeInputWrap}>
                                <input
                                    ref={codeInputRef}
                                    className={`${s.codeInput} ${codeError ? s.codeInputError : ''}`}
                                    type="text"
                                    inputMode="numeric"
                                    pattern="\d{6}"
                                    maxLength={6}
                                    placeholder="——————"
                                    value={code}
                                    onChange={e => {
                                        setCode(e.target.value.replace(/\D/g, '').slice(0, 6))
                                        setCodeError(null)
                                    }}
                                    onKeyDown={e => { if (e.key === 'Enter') handleVerify() }}
                                    autoComplete="one-time-code"
                                />
                            </div>

                            {codeError && <p className={s.errorBox}>{codeError}</p>}

                            <button
                                className={`btn-primary ${s.submitBtn}`}
                                onClick={handleVerify}
                                disabled={loading || code.length !== 6}
                            >
                                {loading ? l.loading : l.verifyBtn}
                            </button>

                            <div className={s.resendRow}>
                                {resendTimer > 0 ? (
                                    <span className={s.timer}>{l.resendIn} {resendTimer} {l.sec}</span>
                                ) : (
                                    <button className={s.resendBtn} onClick={handleResend} disabled={loading}>
                                        {l.resend}
                                    </button>
                                )}
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        )
    }


    const confirmMismatch = !!confirmPassword && confirmPassword !== password

    return (
        <div className={s.overlay} onMouseDown={e => { if (e.target === e.currentTarget) onClose() }}>
            <div className={s.modal} role="dialog" aria-modal="true">
                <div className={s.header}>
                    <span className={s.title}>{tab === 'login' ? l.login : l.register}</span>
                    <button className={s.closeBtn} onClick={onClose} aria-label={l.close}><CloseIcon /></button>
                </div>

                <div className={s.body}>
                    {/* Google кнопка */}
                    <button className={s.googleBtn} onClick={handleGoogle} disabled={loading} type="button">
                        <GoogleIcon />
                        {l.googleBtn}
                    </button>

                    <div className={s.orDivider}>
                        <span>{l.orDivider}</span>
                    </div>

                    <div className={s.tabs} role="tablist">
                        <button
                            role="tab"
                            aria-selected={tab === 'login'}
                            className={`${s.tab} ${tab === 'login' ? s.tabActive : ''}`}
                            onClick={() => switchTab('login')}
                        >
                            {l.login}
                        </button>
                        <button
                            role="tab"
                            aria-selected={tab === 'register'}
                            className={`${s.tab} ${tab === 'register' ? s.tabActive : ''}`}
                            onClick={() => switchTab('register')}
                        >
                            {l.register}
                        </button>
                    </div>

                    <div className={s.form}>
                        {tab === 'register' && (
                            <div className={s.field}>
                                <label className={s.label}>{l.firstName}</label>
                                <input
                                    className={s.input}
                                    type="text"
                                    placeholder="Иван"
                                    value={firstName}
                                    onChange={e => setFirstName(e.target.value)}
                                    autoComplete="given-name"
                                />
                            </div>
                        )}

                        <div className={s.field}>
                            <label className={s.label}>{l.email}</label>
                            <input
                                className={s.input}
                                type="email"
                                placeholder="ivan@example.com"
                                value={email}
                                onChange={e => { setEmail(e.target.value); setError(null) }}
                                onKeyDown={e => { if (e.key === 'Enter' && tab === 'login') handleLogin() }}
                                autoComplete="email"
                            />
                        </div>

                        <PasswordField
                            label={l.password}
                            value={password}
                            onChange={v => { setPassword(v); setError(null) }}
                            onEnter={tab === 'login' ? handleLogin : undefined}
                            autoComplete={tab === 'login' ? 'current-password' : 'new-password'}
                            hint={tab === 'register' ? l.passwordHint : undefined}
                            showLabel={l.showPass}
                            hideLabel={l.hidePass}
                        />

                        {tab === 'register' && (
                            <PasswordField
                                label={l.confirmPassword}
                                value={confirmPassword}
                                onChange={v => { setConfirmPassword(v); setError(null) }}
                                autoComplete="new-password"
                                hasError={confirmMismatch}
                                errorText={l.passwordsNoMatch}
                                showLabel={l.showPass}
                                hideLabel={l.hidePass}
                            />
                        )}

                        {error && <div className={s.errorBox}>{error}</div>}

                        <button
                            className={`btn-primary ${s.submitBtn}`}
                            onClick={tab === 'login' ? handleLogin : handleRegister}
                            disabled={loading}
                        >
                            {loading ? l.loading : tab === 'login' ? l.loginBtn : l.registerBtn}
                        </button>
                    </div>
                </div>
            </div>
        </div>
    )
}