// базовый url — запросы идут на тот же origin, CORS нет. В проде задать наш url
const BASE: string = import.meta.env.VITE_API_URL || ''

export interface AuthResponse {
    id:                     number
    token:                  string
    userId:                 number
    email:                  string
    firstName:              string | null
    emailVerified:          boolean
    telegramLinked:         boolean
    // FIX: добавлены поля которые возвращает бэкенд
    telegramUsername?:      string | null
    role:                   string
    balance:                number
    subscriptionStatus:     string | null
    subscriptionPlan:       string | null
    subscriptionExpiresAt?: string | null
    createdAt?:             string | null
}

export interface RegisterResponse {
    message: string
    userId:  number | null
    token:   string | null
}

export interface TelegramLinkResponse {
    linkToken:   string
    botUsername: string
}

export interface LoginResponse {
    pendingVerification: boolean
    email:               string
    tempToken:           null
    auth:                AuthResponse | null
}

export interface UserNotificationDto {
    id:             number
    notificationId: number
    title:          string
    body:           string
    read:           boolean
    createdAt:      string
}

export interface NotificationDto {
    id:          number
    title:       string
    body:        string
    target:      string
    scheduledAt: string
    sent:        boolean
    createdAt:   string
}

export interface AdminUserDto {
    id:                 number
    email:              string
    firstName:          string | null
    telegramId:         number | null
    telegramUsername:   string | null
    emailVerified:      boolean
    isActive:           boolean
    role:               string
    balance:            number
    subscriptionStatus: string | null
    subscriptionPlan:   string | null
    leadsCount:         number
    createdAt:          string | null
}

export interface PurchaseResponse {
    plan:       string
    expiresAt:  string
    newBalance: number
}

interface SpringErrorBody {
    message: string
    errors?: Record<string, string>
}

async function request<T>(
    path:    string,
    options: RequestInit = {},
): Promise<T> {
    const headers: Record<string, string> = {
        'Content-Type': 'application/json',
        ...(options.headers as Record<string, string> ?? {}),
    }

    let res: Response
    try {
        res = await fetch(`${BASE}${path}`, {
            ...options,
            headers,
            credentials: 'include',
        })
    } catch {
        throw new Error('Не удаётся подключиться к серверу. Проверьте, запущен ли бекенд.')
    }

    if (!res.ok) {
        let body: SpringErrorBody = { message: `Ошибка ${res.status}` }
        try { body = await res.json() } catch { /* тело не JSON */ }

        if (body.errors && Object.keys(body.errors).length > 0) {
            throw new Error(Object.values(body.errors).join('. '))
        }
        throw new Error(body.message)
    }


    if (res.status === 204) return undefined as T

    return res.json() as Promise<T>
}

export const authApi = {

    register(data: {
        email:           string
        password:        string
        confirmPassword: string
        firstName?:      string
    }): Promise<RegisterResponse> {
        return request('/api/v1/auth/register', {
            method: 'POST',
            body:   JSON.stringify(data),
        })
    },

    verifyEmail(code: string): Promise<AuthResponse> {
        return request('/api/v1/auth/verify-email', {
            method: 'POST',
            body:   JSON.stringify({ code }),
        })
    },

    resendCode(): Promise<{ message: string }> {
        return request('/api/v1/auth/resend-code', { method: 'POST' })
    },

    login(email: string, password: string): Promise<LoginResponse> {
        return request('/api/v1/auth/login', {
            method: 'POST',
            body:   JSON.stringify({ email, password }),
        })
    },

    logout(): Promise<void> {
        return request('/api/v1/auth/logout', { method: 'POST' })
    },

    me(): Promise<AuthResponse> {
        return request('/api/v1/auth/me', {})
    },

    getTelegramLink(): Promise<TelegramLinkResponse> {
        return request('/api/v1/auth/telegram/link', { method: 'POST' })
    },

    unlinkTelegram(): Promise<{ message: string }> {
        return request('/api/v1/auth/telegram/unlink', { method: 'POST' })
    },

    loginWithGoogle(idToken: string): Promise<AuthResponse> {
        return request('/api/v1/auth/oauth2/google', {
            method: 'POST',
            body:   JSON.stringify({ id_token: idToken }),
        })
    },
}

export const notificationsApi = {
    getAll(): Promise<UserNotificationDto[]> {
        return request('/api/v1/notifications')
    },
    getUnreadCount(): Promise<{ unread: number }> {
        return request('/api/v1/notifications/unread-count')
    },
    markAllRead(): Promise<void> {
        return request('/api/v1/notifications/read-all', { method: 'POST' })
    },
    markOneRead(id: number): Promise<void> {
        return request(`/api/v1/notifications/${id}/read`, { method: 'POST' })
    },
    createNotification(data: {
        title:       string
        body:        string
        target:      string
        scheduledAt?: string
    }): Promise<NotificationDto> {
        return request('/api/v1/notifications/admin', {
            method: 'POST',
            body:   JSON.stringify(data),
        })
    },
    getAllAdmin(): Promise<NotificationDto[]> {
        return request('/api/v1/notifications/admin')
    },
}

export const adminApi = {
    getUsers(): Promise<AdminUserDto[]> {
        return request('/api/v1/admin/users')
    },
    getUser(id: number): Promise<AdminUserDto> {
        return request(`/api/v1/admin/users/${id}`)
    },
    setRole(id: number, role: string): Promise<AdminUserDto> {
        return request(`/api/v1/admin/users/${id}/role`, {
            method: 'POST',
            body:   JSON.stringify({ role }),
        })
    },
}

export const balanceApi = {
    get(): Promise<{ balance: number }> {
        return request('/api/v1/user/balance')
    },
}

export const subscriptionApi = {
    purchase(plan: string): Promise<PurchaseResponse> {
        return request('/api/v1/subscriptions/purchase', {
            method: 'POST',
            body:   JSON.stringify({ plan }),
        })
    },
}