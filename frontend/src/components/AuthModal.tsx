import {useState, useEffect, useRef, useMemo} from 'react'
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

function BackIcon() {
    return (
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none"
             stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <polyline points="15 18 9 12 15 6" />
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


function buildGoogleOAuthUrl(): string {
    const clientId   = import.meta.env.VITE_GOOGLE_CLIENT_ID as string
    const origin     = window.location.origin
    const redirectUri = `${origin}/oauth/callback`
    const nonce      = Math.random().toString(36).substring(2) + Math.random().toString(36).substring(2)
    const params     = new URLSearchParams({
        client_id:     clientId,
        redirect_uri:  redirectUri,
        response_type: 'id_token',
        scope:         'openid email profile',
        nonce,
    })
    return `https://accounts.google.com/o/oauth2/v2/auth?${params.toString()}`
}

// ── Хелперы для реферального кода ────────────────────────────────────────────
const REF_STORAGE_KEY = 'aimly_ref_code'

/** Читаем ?ref= из URL и сохраняем в sessionStorage, чтобы не потерять при навигации */
function captureRefCode(): string | undefined {
    const params = new URLSearchParams(window.location.search)
    const fromUrl = params.get('ref') || undefined
    if (fromUrl) {
        sessionStorage.setItem(REF_STORAGE_KEY, fromUrl)
        return fromUrl
    }
    return sessionStorage.getItem(REF_STORAGE_KEY) || undefined
}


const txt = {
    ru: {
        login:            'Войти',
        register:         'Регистрация',
        email:            'Email',
        password:         'Пароль',
        confirmPassword:  'Повторите пароль',
        firstName:        'Имя (необязательно)',
        loginBtn:         'Войти',
        registerBtn:      'Создать аккаунт',
        loading:          'Загрузка…',
        codeTitle:        'Код из письма',
        codeHint:         'Отправили 6-значный код на',
        spamNote:         'Не пришло? Проверьте папку «Спам» или «Рассылки»',
        verifyBtn:        'Подтвердить',
        resend:           'Отправить снова',
        resendIn:         'Повторно через',
        sec:              'сек',
        successTitle:     'Добро пожаловать!',
        successText:      'Email подтверждён. Переходим…',
        successBtn:       'Продолжить',
        close:            'Закрыть',
        back:             'Назад',
        passwordHint:     'Минимум 8 символов: строчные + заглавные + цифра',
        showPass:         'Показать пароль',
        hidePass:         'Скрыть пароль',
        passwordsNoMatch: 'Пароли не совпадают',
        orDivider:        'или',
        googleBtn:        'Войти через Google',
        unverifiedHint:   'Мы отправили новый код на ваш email. Подтвердите почту.',
        forgotPassword:   'Забыли пароль?',
        // Шаг 1: запрос сброса
        forgotTitle:      'Восстановление пароля',
        forgotHint:       'Введите email от вашего аккаунта, и мы отправим код для сброса пароля.',
        forgotBtn:        'Отправить код',
        forgotSuccess:    'Код отправлен. Проверьте почту',
        forgotTgHint:     'Также отправили код в Telegram, если он привязан к аккаунту.',
        // Шаг 2: ввод кода сброса
        resetCodeTitle:   'Введите код',
        resetCodeHint:    'Отправили код сброса пароля на',
        resetCodeNote:    'Также проверьте Telegram, если он привязан к аккаунту.',
        // Шаг 3: новый пароль
        newPasswordTitle: 'Новый пароль',
        newPassword:      'Новый пароль',
        confirmNewPass:   'Подтвердите новый пароль',
        setPasswordBtn:   'Сохранить пароль',
        // Успех
        resetSuccessTitle: 'Пароль изменён!',
        resetSuccessText:  'Вы вошли в аккаунт. Переходим…',
    },
    en: {
        login:            'Sign in',
        register:         'Sign up',
        email:            'Email',
        password:         'Password',
        confirmPassword:  'Confirm password',
        firstName:        'First name (optional)',
        loginBtn:         'Sign in',
        registerBtn:      'Create account',
        loading:          'Loading…',
        codeTitle:        'Email verification',
        codeHint:         'We sent a 6-digit code to',
        spamNote:         'Not received? Check your Spam or Promotions folder',
        verifyBtn:        'Verify',
        resend:           'Send again',
        resendIn:         'Resend in',
        sec:              's',
        successTitle:     'Welcome!',
        successText:      'Email confirmed. Continuing…',
        successBtn:       'Continue',
        close:            'Close',
        back:             'Back',
        passwordHint:     'Min 8 chars: lowercase + uppercase + digit',
        showPass:         'Show password',
        hidePass:         'Hide password',
        passwordsNoMatch: 'Passwords do not match',
        orDivider:        'or',
        googleBtn:        'Continue with Google',
        unverifiedHint:   'We sent a new code to your email. Please verify.',
        forgotPassword:   'Forgot password?',
        // Step 1: request reset
        forgotTitle:      'Reset password',
        forgotHint:       'Enter your account email and we\'ll send you a reset code.',
        forgotBtn:        'Send code',
        forgotSuccess:    'Code sent. Check your email',
        forgotTgHint:     'Also sent to Telegram if it\'s linked to your account.',
        // Step 2: enter reset code
        resetCodeTitle:   'Enter the code',
        resetCodeHint:    'We sent a reset code to',
        resetCodeNote:    'Also check Telegram if it\'s linked to your account.',
        // Step 3: new password
        newPasswordTitle: 'New password',
        newPassword:      'New password',
        confirmNewPass:   'Confirm new password',
        setPasswordBtn:   'Save password',
        // Success
        resetSuccessTitle: 'Password changed!',
        resetSuccessText:  'You are now signed in. Continuing…',
    },
} as const


type Step    = 'form' | 'code' | 'success' | 'forgot' | 'reset-code' | 'new-password' | 'reset-success'
type TabMode = 'login' | 'register'

interface Props {
    lang:        Lang
    onClose:     () => void

    onSuccess?:  () => void
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



function PasswordField({
                           label, value, onChange, onEnter,
                           hasError, errorText, hint,
                           autoComplete, showLabel, hideLabel,
                       }: PFProps) {
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



export default function AuthModal({
                                      lang,
                                      onClose,
                                      onSuccess,
                                      initialTab = 'login',
                                  }: Props) {
    const l = txt[lang]
    const { saveSession } = useAuthContext()
    const navigate = useNavigate()

    const [tab,  setTab]  = useState<TabMode>(initialTab)
    const [step, setStep] = useState<Step>('form')

    // --- Реферальный код — читаем из URL или sessionStorage (не теряется при навигации) ---
    // eslint-disable-next-line react-hooks/exhaustive-deps
    const referralCode = useMemo(() => captureRefCode(), [])

    // --- Поля формы входа/регистрации ---
    const [email,           setEmail]           = useState('')
    const [password,        setPassword]        = useState('')
    const [confirmPassword, setConfirmPassword] = useState('')
    const [firstName,       setFirstName]       = useState('')
    const [emailUsed,       setEmailUsed]       = useState('')

    // --- Поля верификации email ---
    const [code,        setCode]        = useState('')
    const [resendTimer, setResendTimer] = useState(0)
    const timerRef = useRef<ReturnType<typeof setInterval> | null>(null)

    // --- Поля сброса пароля ---
    const [forgotEmail,      setForgotEmail]      = useState('')
    const [resetCode,        setResetCode]        = useState('')
    const [newPassword,      setNewPassword]      = useState('')
    const [confirmNewPass,   setConfirmNewPass]   = useState('')
    const [resetResendTimer, setResetResendTimer] = useState(0)
    const resetTimerRef = useRef<ReturnType<typeof setInterval> | null>(null)

    // --- Состояние загрузки/ошибок ---
    const [loading,        setLoading]        = useState(false)
    const [error,          setError]          = useState<string | null>(null)
    const [codeError,      setCodeError]      = useState<string | null>(null)
    const [forgotError,    setForgotError]    = useState<string | null>(null)
    const [resetCodeError, setResetCodeError] = useState<string | null>(null)
    const [newPassError,   setNewPassError]   = useState<string | null>(null)

    const codeInputRef      = useRef<HTMLInputElement>(null)
    const resetCodeInputRef = useRef<HTMLInputElement>(null)
    const forgotEmailRef    = useRef<HTMLInputElement>(null)



    useEffect(() => { setTab(initialTab) }, [initialTab])

    useEffect(() => {
        const fn = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
        document.addEventListener('keydown', fn)
        return () => document.removeEventListener('keydown', fn)
    }, [onClose])

    useEffect(() => {
        document.body.style.overflow = 'hidden'
        return () => { document.body.style.overflow = '' }
    }, [])

    useEffect(() => () => {
        if (timerRef.current) clearInterval(timerRef.current)
        if (resetTimerRef.current) clearInterval(resetTimerRef.current)
    }, [])

    useEffect(() => {
        if (step === 'code') setTimeout(() => codeInputRef.current?.focus(), 120)
        if (step === 'reset-code') setTimeout(() => resetCodeInputRef.current?.focus(), 120)
        if (step === 'forgot') setTimeout(() => forgotEmailRef.current?.focus(), 120)
    }, [step])

    useEffect(() => {
        if (step === 'success') {
            const timer = setTimeout(() => {
                // После успешной регистрации — очищаем сохранённый реф-код
                sessionStorage.removeItem(REF_STORAGE_KEY)
                if (onSuccess) {
                    onSuccess()
                } else {
                    onClose()
                    navigate('/dashboard')
                }
            }, 1500)
            return () => clearTimeout(timer)
        }
        if (step === 'reset-success') {
            const timer = setTimeout(() => {
                onClose()
                navigate('/dashboard')
            }, 1500)
            return () => clearTimeout(timer)
        }
    }, [step, navigate, onClose, onSuccess])



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

    const startResetResendTimer = (seconds = 60) => {
        setResetResendTimer(seconds)
        if (resetTimerRef.current) clearInterval(resetTimerRef.current)
        resetTimerRef.current = setInterval(() => {
            setResetResendTimer(prev => {
                if (prev <= 1) { clearInterval(resetTimerRef.current!); return 0 }
                return prev - 1
            })
        }, 1000)
    }

    const switchTab = (next: TabMode) => {
        setTab(next); setError(null); setPassword(''); setConfirmPassword('')
    }

    const goToDashboard = () => {
        if (onSuccess) { onSuccess() } else { onClose(); navigate('/dashboard') }
    }



    const handleLogin = async () => {
        setError(null)
        if (!email.trim() || !password) { setError('Заполните все поля'); return }
        setLoading(true)
        try {
            const res = await authApi.login(email.trim(), password)
            if (res.pendingVerification) {
                setEmailUsed(res.email); setStep('code'); startResendTimer(60); return
            }
            if (res.auth) { saveSession(res.auth); goToDashboard() }
        } catch (e: unknown) {
            setError(e instanceof Error ? e.message : 'Ошибка входа')
        } finally { setLoading(false) }
    }

    const handleRegister = async () => {
        setError(null)
        if (!email.trim() || !password || !confirmPassword) {
            setError('Заполните все обязательные поля'); return
        }
        if (password !== confirmPassword) { setError(l.passwordsNoMatch); return }
        if (password.length < 8) { setError(l.passwordHint); return }
        setLoading(true)
        try {
            const res = await authApi.register({
                email:          email.trim(),
                password,
                confirmPassword,
                firstName:      firstName.trim() || undefined,
                referralCode,   // передаём реф-код из URL или sessionStorage
            })
            if (res.token !== null || res.userId !== null) {
                setEmailUsed(email.trim()); setStep('code'); startResendTimer(60)
            } else {
                setError(res.message)
            }
        } catch (e: unknown) {
            setError(e instanceof Error ? e.message : 'Ошибка регистрации')
        } finally { setLoading(false) }
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
        } finally { setLoading(false) }
    }

    const handleResend = async () => {
        if (resendTimer > 0) return
        setCodeError(null); setLoading(true)
        try {
            await authApi.resendCode(); setCode(''); startResendTimer(60)
        } catch (e: unknown) {
            setCodeError(e instanceof Error ? e.message : 'Не удалось отправить код')
        } finally { setLoading(false) }
    }

    const handleGoogle = () => {
        if (onSuccess) sessionStorage.setItem('oauth_after_checkout', '1')
        sessionStorage.setItem('oauth_return_to', '/dashboard')
        // Сохраняем реф-код чтобы он не потерялся при редиректе через OAuth
        if (referralCode) sessionStorage.setItem(REF_STORAGE_KEY, referralCode)
        window.location.href = buildGoogleOAuthUrl()
    }


    // ─── Обработчики сброса пароля ─────────────────────────────────────────────

    const handleForgotPassword = async () => {
        setForgotError(null)
        const trimmedEmail = forgotEmail.trim()
        if (!trimmedEmail) { setForgotError('Введите email'); return }
        if (!trimmedEmail.includes('@') || !trimmedEmail.includes('.')) {
            setForgotError('Некорректный email'); return
        }
        setLoading(true)
        try {
            await authApi.forgotPassword(trimmedEmail)
            setEmailUsed(trimmedEmail)
            setStep('reset-code')
            startResetResendTimer(60)
        } catch (e: unknown) {
            setForgotError(e instanceof Error ? e.message : 'Ошибка. Попробуйте позже')
        } finally { setLoading(false) }
    }

    const handleVerifyResetCode = async () => {
        setResetCodeError(null)
        if (resetCode.length !== 6) { setResetCodeError('Введите 6-значный код'); return }
        setStep('new-password')
    }

    const handleResendResetCode = async () => {
        if (resetResendTimer > 0) return
        setResetCodeError(null); setLoading(true)
        try {
            await authApi.forgotPassword(emailUsed)
            setResetCode('')
            startResetResendTimer(60)
        } catch (e: unknown) {
            setResetCodeError(e instanceof Error ? e.message : 'Не удалось отправить код')
        } finally { setLoading(false) }
    }

    const handleSetNewPassword = async () => {
        setNewPassError(null)
        if (!newPassword) { setNewPassError('Введите новый пароль'); return }
        if (newPassword.length < 8) { setNewPassError(l.passwordHint); return }
        if (newPassword !== confirmNewPass) { setNewPassError(l.passwordsNoMatch); return }
        setLoading(true)
        try {
            const res = await authApi.resetPassword({
                code:            resetCode,
                newPassword,
                confirmPassword: confirmNewPass,
            })
            saveSession(res)
            setStep('reset-success')
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : 'Ошибка. Попробуйте ещё раз'
            if (msg.toLowerCase().includes('код') || msg.toLowerCase().includes('code')) {
                setStep('reset-code')
                setResetCodeError(msg)
            } else {
                setNewPassError(msg)
            }
        } finally { setLoading(false) }
    }



    // ─── Рендер шагов ─────────────────────────────────────────────────────────────

    if (step === 'reset-success') {
        return (
            <div className={s.overlay}>
                <div className={s.modal} role="dialog" aria-modal="true">
                    <div className={s.body}>
                        <div className={s.successStep}>
                            <div className={s.successIcon}>✓</div>
                            <h2 className={s.successTitle}>{l.resetSuccessTitle}</h2>
                            <p className={s.successText}>{l.resetSuccessText}</p>
                        </div>
                    </div>
                </div>
            </div>
        )
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
                        <button className={s.closeBtn} onClick={onClose} aria-label={l.close}>
                            <CloseIcon />
                        </button>
                    </div>
                    <div className={s.body}>
                        <div className={s.codeStep}>
                            <p className={s.codeHint}>
                                {l.codeHint}{' '}
                                <span className={s.codeHintEmail}>{emailUsed}</span>
                            </p>

                            <p style={{
                                fontSize:   12,
                                color:      'var(--c-ink-3)',
                                textAlign:  'center',
                                margin:     '-4px 0 0',
                                lineHeight: 1.5,
                            }}>
                                {l.spamNote}
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


    // ─── Шаг 1: запрос кода сброса ────────────────────────────────────────────────

    if (step === 'forgot') {
        return (
            <div className={s.overlay} onMouseDown={e => { if (e.target === e.currentTarget) onClose() }}>
                <div className={s.modal} role="dialog" aria-modal="true">
                    <div className={s.header}>
                        <button
                            className={s.backBtn}
                            onClick={() => { setStep('form'); setForgotError(null) }}
                            aria-label={l.back}
                        >
                            <BackIcon />
                        </button>
                        <span className={s.title}>{l.forgotTitle}</span>
                        <button className={s.closeBtn} onClick={onClose} aria-label={l.close}>
                            <CloseIcon />
                        </button>
                    </div>
                    <div className={s.body}>
                        <div className={s.form}>
                            <p className={s.forgotHintText}>{l.forgotHint}</p>

                            <div className={s.field}>
                                <label className={s.label}>{l.email}</label>
                                <input
                                    ref={forgotEmailRef}
                                    className={s.input}
                                    type="email"
                                    placeholder="ivan@example.com"
                                    value={forgotEmail}
                                    onChange={e => { setForgotEmail(e.target.value); setForgotError(null) }}
                                    onKeyDown={e => { if (e.key === 'Enter') handleForgotPassword() }}
                                    autoComplete="email"
                                />
                            </div>

                            {forgotError && <div className={s.errorBox}>{forgotError}</div>}

                            <button
                                className={`btn-primary ${s.submitBtn}`}
                                onClick={handleForgotPassword}
                                disabled={loading}
                            >
                                {loading ? l.loading : l.forgotBtn}
                            </button>
                        </div>
                    </div>
                </div>
            </div>
        )
    }


    // ─── Шаг 2: ввод кода сброса ─────────────────────────────────────────────────

    if (step === 'reset-code') {
        return (
            <div className={s.overlay} onMouseDown={e => { if (e.target === e.currentTarget) onClose() }}>
                <div className={s.modal} role="dialog" aria-modal="true">
                    <div className={s.header}>
                        <button
                            className={s.backBtn}
                            onClick={() => { setStep('forgot'); setResetCodeError(null) }}
                            aria-label={l.back}
                        >
                            <BackIcon />
                        </button>
                        <span className={s.title}>{l.resetCodeTitle}</span>
                        <button className={s.closeBtn} onClick={onClose} aria-label={l.close}>
                            <CloseIcon />
                        </button>
                    </div>
                    <div className={s.body}>
                        <div className={s.codeStep}>
                            <p className={s.codeHint}>
                                {l.resetCodeHint}{' '}
                                <span className={s.codeHintEmail}>{emailUsed}</span>
                            </p>

                            <p style={{
                                fontSize:   12,
                                color:      'var(--c-ink-3)',
                                textAlign:  'center',
                                margin:     '-4px 0 0',
                                lineHeight: 1.5,
                            }}>
                                {l.resetCodeNote}
                            </p>

                            <div className={s.codeInputWrap}>
                                <input
                                    ref={resetCodeInputRef}
                                    className={`${s.codeInput} ${resetCodeError ? s.codeInputError : ''}`}
                                    type="text"
                                    inputMode="numeric"
                                    pattern="\d{6}"
                                    maxLength={6}
                                    placeholder="——————"
                                    value={resetCode}
                                    onChange={e => {
                                        setResetCode(e.target.value.replace(/\D/g, '').slice(0, 6))
                                        setResetCodeError(null)
                                    }}
                                    onKeyDown={e => { if (e.key === 'Enter') handleVerifyResetCode() }}
                                    autoComplete="one-time-code"
                                />
                            </div>

                            {resetCodeError && <p className={s.errorBox}>{resetCodeError}</p>}

                            <button
                                className={`btn-primary ${s.submitBtn}`}
                                onClick={handleVerifyResetCode}
                                disabled={loading || resetCode.length !== 6}
                            >
                                {loading ? l.loading : l.verifyBtn}
                            </button>

                            <div className={s.resendRow}>
                                {resetResendTimer > 0 ? (
                                    <span className={s.timer}>{l.resendIn} {resetResendTimer} {l.sec}</span>
                                ) : (
                                    <button className={s.resendBtn} onClick={handleResendResetCode} disabled={loading}>
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


    // ─── Шаг 3: новый пароль ─────────────────────────────────────────────────────

    if (step === 'new-password') {
        const newPassMismatch = !!confirmNewPass && confirmNewPass !== newPassword
        return (
            <div className={s.overlay} onMouseDown={e => { if (e.target === e.currentTarget) onClose() }}>
                <div className={s.modal} role="dialog" aria-modal="true">
                    <div className={s.header}>
                        <button
                            className={s.backBtn}
                            onClick={() => { setStep('reset-code'); setNewPassError(null) }}
                            aria-label={l.back}
                        >
                            <BackIcon />
                        </button>
                        <span className={s.title}>{l.newPasswordTitle}</span>
                        <button className={s.closeBtn} onClick={onClose} aria-label={l.close}>
                            <CloseIcon />
                        </button>
                    </div>
                    <div className={s.body}>
                        <div className={s.form}>
                            <PasswordField
                                label={l.newPassword}
                                value={newPassword}
                                onChange={v => { setNewPassword(v); setNewPassError(null) }}
                                onEnter={!newPassMismatch ? handleSetNewPassword : undefined}
                                autoComplete="new-password"
                                hint={l.passwordHint}
                                showLabel={l.showPass}
                                hideLabel={l.hidePass}
                            />

                            <PasswordField
                                label={l.confirmNewPass}
                                value={confirmNewPass}
                                onChange={v => { setConfirmNewPass(v); setNewPassError(null) }}
                                onEnter={handleSetNewPassword}
                                autoComplete="new-password"
                                hasError={newPassMismatch}
                                errorText={l.passwordsNoMatch}
                                showLabel={l.showPass}
                                hideLabel={l.hidePass}
                            />

                            {newPassError && !newPassMismatch && (
                                <div className={s.errorBox}>{newPassError}</div>
                            )}

                            <button
                                className={`btn-primary ${s.submitBtn}`}
                                onClick={handleSetNewPassword}
                                disabled={loading || newPassMismatch}
                            >
                                {loading ? l.loading : l.setPasswordBtn}
                            </button>
                        </div>
                    </div>
                </div>
            </div>
        )
    }



    // ─── Основная форма (login / register) ────────────────────────────────────────

    const confirmMismatch = !!confirmPassword && confirmPassword !== password

    return (
        <div className={s.overlay} onMouseDown={e => { if (e.target === e.currentTarget) onClose() }}>
            <div className={s.modal} role="dialog" aria-modal="true">
                <div className={s.header}>
                    <span className={s.title}>{tab === 'login' ? l.login : l.register}</span>
                    <button className={s.closeBtn} onClick={onClose} aria-label={l.close}>
                        <CloseIcon />
                    </button>
                </div>

                <div className={s.body}>
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

                        <div>
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

                            {/* Ссылка "Забыли пароль?" — только на табе входа */}
                            {tab === 'login' && (
                                <div className={s.forgotRow}>
                                    <button
                                        type="button"
                                        className={s.forgotLink}
                                        onClick={() => {
                                            setForgotEmail(email.trim())
                                            setForgotError(null)
                                            setStep('forgot')
                                        }}
                                    >
                                        {l.forgotPassword}
                                    </button>
                                </div>
                            )}
                        </div>

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