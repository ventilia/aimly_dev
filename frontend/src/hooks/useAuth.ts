import { useState, useEffect, useCallback, useRef } from 'react'
import { authApi, type AuthResponse } from '../api/auth'



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
            }

            if (event.type === 'LOGOUT') {

                setState({ user: null, loading: false })
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
            })
            .catch(() => {

                setState({ user: null, loading: false })
            })
    }, [])


    const saveSession = useCallback((response: AuthResponse) => {
        setState({ user: response, loading: false })
        channelRef.current?.postMessage({ type: 'LOGIN', user: response } satisfies SessionEvent)
    }, [])


    const logout = useCallback(async () => {
        try {
            await authApi.logout()
        } catch {

        }
        setState({ user: null, loading: false })
        channelRef.current?.postMessage({ type: 'LOGOUT' } satisfies SessionEvent)
    }, [])


    const refreshUser = useCallback(async () => {
        try {
            const user = await authApi.me()
            setState(prev => ({ ...prev, user }))

        } catch {
            // dkdkd
        }
    }, [])


    return { ...state, token: null as null, saveSession, logout, refreshUser }
}

export type UseAuthReturn = ReturnType<typeof useAuth>