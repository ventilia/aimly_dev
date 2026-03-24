import { useState, useEffect, useCallback, useRef } from 'react'
import { authApi, type AuthResponse } from '../api/auth'
import { setLoggerUserContext, clearLoggerUserContext, logEvent } from '../analytics/userLogger'



export interface AuthState {
    user:    AuthResponse | null
    loading: boolean
}


type SessionEvent =
    | { type: 'LOGIN';  user: AuthResponse }
    | { type: 'LOGOUT' }


const CHANNEL_NAME = 'aimly:session'

export function useAuth() {
    const [state, setState] = useState<AuthState>({
        user:    null,
        loading: true,
    })


    const channelRef = useRef<BroadcastChannel | null>(null)

    useEffect(() => {

        if (!('BroadcastChannel' in window)) return

        const ch = new BroadcastChannel(CHANNEL_NAME)
        channelRef.current = ch

        ch.onmessage = (e: MessageEvent<SessionEvent>) => {
            const event = e.data

            if (event.type === 'LOGIN') {
                setState({ user: event.user, loading: false })
                setLoggerUserContext({ userId: event.user.id, userEmail: event.user.email })
            }

            if (event.type === 'LOGOUT') {
                setState({ user: null, loading: false })
                clearLoggerUserContext()
            }
        }

        return () => {
            ch.close()
            channelRef.current = null
        }
    }, [])


    useEffect(() => {
        authApi.me()
            .then(user => {
                setState({ user, loading: false })
                setLoggerUserContext({ userId: user.id, userEmail: user.email })
            })
            .catch(() => {
                setState({ user: null, loading: false })
                clearLoggerUserContext()
            })
    }, [])


    const saveSession = useCallback((response: AuthResponse) => {
        setState({ user: response, loading: false })
        setLoggerUserContext({ userId: response.id, userEmail: response.email })
        channelRef.current?.postMessage({ type: 'LOGIN', user: response } satisfies SessionEvent)
    }, [])


    const logout = useCallback(async () => {
        logEvent('LOGOUT', { label: 'Выход из аккаунта' })
        try {
            await authApi.logout()
        } catch {
            // ignore
        }
        setState({ user: null, loading: false })
        clearLoggerUserContext()
        channelRef.current?.postMessage({ type: 'LOGOUT' } satisfies SessionEvent)
    }, [])


    const refreshUser = useCallback(async () => {
        try {
            const user = await authApi.me()
            setState(prev => ({ ...prev, user }))
            setLoggerUserContext({ userId: user.id, userEmail: user.email })
        } catch {
            // ignore
        }
    }, [])


    return { ...state, token: null as null, saveSession, logout, refreshUser }
}

export type UseAuthReturn = ReturnType<typeof useAuth>