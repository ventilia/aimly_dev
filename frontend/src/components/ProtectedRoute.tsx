import { Navigate, Outlet } from 'react-router-dom'
import { useAuthContext } from '../context/AuthContext'

export default function ProtectedRoute() {
    const { user, loading } = useAuthContext()

    if (loading) {
        return (
            <div style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                minHeight: '100vh',
                background: 'var(--c-bg)',
            }}>
                <div style={{
                    display: 'flex',
                    flexDirection: 'column',
                    alignItems: 'center',
                    gap: 12,
                }}>
                    <img
                        src="/AIMLY.png"
                        alt="AIMLY"
                        style={{ width: 40, height: 40, borderRadius: '50%', opacity: 0.6 }}
                    />
                    <div style={{
                        width: 24,
                        height: 24,
                        border: '2px solid var(--c-border)',
                        borderTopColor: 'var(--c-accent)',
                        borderRadius: '50%',
                        animation: 'spin 0.8s linear infinite',
                    }} />
                    <style>{`@keyframes spin { to { transform: rotate(360deg) } }`}</style>
                </div>
            </div>
        )
    }

    if (!user) {
        return <Navigate to="/" replace />
    }

    return <Outlet />
}