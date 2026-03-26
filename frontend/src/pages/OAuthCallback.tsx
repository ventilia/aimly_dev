import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { authApi } from '../api/auth'
import { useAuthContext } from '../context/AuthContext'

/**
 * Страница обработки редиректа от Google OAuth 2.0 (implicit flow).
 * Google перенаправляет сюда с хэшем вида:
 *   /oauth/callback#id_token=xxx&token_type=Bearer&expires_in=3600
 *
 * Реф-код: если пользователь до входа через Google перешёл по реф-ссылке
 * (?ref=XXXXXX), AuthModal сохраняет код в sessionStorage['aimly_ref_code'].
 * Мы его здесь читаем и передаём в loginWithGoogle().
 * Код очищается только после успешной авторизации.
 */

// Должен совпадать с REF_STORAGE_KEY в AuthModal.tsx
const REF_STORAGE_KEY = 'aimly_ref_code'

export default function OAuthCallback() {
    const { saveSession } = useAuthContext()
    const navigate = useNavigate()
    const [error, setError] = useState<string | null>(null)

    useEffect(() => {
        const hash = window.location.hash.substring(1) // убираем #
        const params = new URLSearchParams(hash)
        const idToken = params.get('id_token')

        if (!idToken) {
            setError('Токен Google не найден. Попробуйте войти снова.')
            setTimeout(() => navigate('/'), 3000)
            return
        }

        // Читаем реф-код, сохранённый до начала OAuth-флоу в AuthModal
        const pendingRefCode = sessionStorage.getItem(REF_STORAGE_KEY) ?? undefined

        authApi.loginWithGoogle(idToken, pendingRefCode)
            .then(auth => {
                // Очищаем реф-код только после успешной авторизации
                if (pendingRefCode) sessionStorage.removeItem(REF_STORAGE_KEY)
                saveSession(auth)
                // Редирект туда, откуда пришёл (если сохранили) или на дашборд
                const returnTo = sessionStorage.getItem('oauth_return_to') || '/dashboard'
                sessionStorage.removeItem('oauth_return_to')
                navigate(returnTo, { replace: true })
            })
            .catch(e => {
                setError(e instanceof Error ? e.message : 'Ошибка входа через Google')
                setTimeout(() => navigate('/'), 4000)
            })
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [])

    return (
        <div style={{
            minHeight: '100vh',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            flexDirection: 'column',
            gap: 16,
            background: 'var(--c-bg)',
            color: 'var(--c-ink)',
            fontFamily: 'var(--font-body)',
        }}>
            {error ? (
                <>
                    <div style={{ fontSize: 32 }}>⚠️</div>
                    <p style={{ fontSize: 15, color: '#ef4444', textAlign: 'center', maxWidth: 360, margin: 0 }}>
                        {error}
                    </p>
                    <p style={{ fontSize: 13, color: 'var(--c-ink-3)', margin: 0 }}>
                        Перенаправляем на главную...
                    </p>
                </>
            ) : (
                <>
                    <div style={{
                        width: 40, height: 40, borderRadius: '50%',
                        border: '3px solid var(--c-accent)',
                        borderTopColor: 'transparent',
                        animation: 'spin 0.8s linear infinite',
                    }} />
                    <p style={{ fontSize: 15, color: 'var(--c-ink-2)', margin: 0 }}>
                        Входим через Google...
                    </p>
                </>
            )}
            <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>
        </div>
    )
}