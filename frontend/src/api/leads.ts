const BASE: string = import.meta.env.VITE_API_URL || ''

async function req<T>(path: string, options: RequestInit = {}): Promise<T> {
    const res = await fetch(`${BASE}${path}`, {
        ...options,
        credentials: 'include',
        headers: { 'Content-Type': 'application/json', ...(options.headers ?? {}) },
    })
    if (!res.ok) {
        const b = await res.json().catch(() => ({ message: `Ошибка ${res.status}` })) as { message?: string; error?: string }
        throw new Error(b.error ?? b.message ?? `Ошибка ${res.status}`)
    }
    if (res.status === 204) return undefined as T
    return res.json() as Promise<T>
}

export interface Lead {
    id:              number
    chatTitle:       string
    chatLink:        string
    authorName:      string
    authorUsername:  string
    messageText:     string
    messageLink:     string
    matchedKeyword:  string
    status:          'NEW' | 'VIEWED' | 'REPLIED' | 'IGNORED'
    foundAt:         string
    aiValid:         boolean | null
    aiReason:        string | null
    contextMessages: string[]
    source:          'LIVE' | 'MANUAL_EXPORT'
    messageDate:     string
    // Оценка пользователя. null = ещё не оценен.
    rating:          'GOOD' | 'BAD' | null
}

export interface LeadPage {
    content:       Lead[]
    totalElements: number
    totalPages:    number
    page:          number
    size:          number
    newCount:      number
}

export interface ChatSubscription {
    id:        number
    chatLink:  string
    chatTitle: string
    chatTgId:  number
    isActive:  boolean
    createdAt: string
}

export interface Keyword {
    id:       number
    keyword:  string
    isActive: boolean
}

export interface SubscriptionInfo {
    userId:    number
    email:     string
    firstName: string | null
    status:    string | null
    plan:      string | null
    expiresAt: string | null
    balance:   number
}

export interface ImportResult {
    chatTitle:     string
    totalMessages: number
    matchedLeads:  number
    skippedLeads:  number
    format:        string
}

// ─── Feedback API ─────────────────────────────────────────────────────────────

export interface LeadFeedbackResponse {
    leadId:     number
    rating:     string
    // true — очередь пуста (все ожидающие лиды доставлены либо их не было)
    queueEmpty: boolean
}

export interface FeedbackStatusResponse {
    queueSize:     number
    hasQueue:      boolean
    // ID первого неоцененного уведомленного лида. null — все лиды оценены.
    pendingLeadId: number | null
}

export const feedbackApi = {
    /**
     * Отправить или изменить оценку лида.
     * Повторный вызов с другим рейтингом меняет предыдущую оценку (upsert).
     */
    submit(leadId: number, rating: 'GOOD' | 'BAD'): Promise<LeadFeedbackResponse> {
        return req(`/api/v1/leads/${leadId}/feedback`, {
            method: 'POST',
            body:   JSON.stringify({ rating }),
        })
    },

    /**
     * Получить состояние очереди оценок:
     *   - queueSize     — сколько лидов ожидает доставки в TG
     *   - pendingLeadId — ID лида, который нужно оценить прямо сейчас
     */
    getStatus(): Promise<FeedbackStatusResponse> {
        return req('/api/v1/leads/feedback-status')
    },
}

// ─── Leads API ────────────────────────────────────────────────────────────────

export const leadsApi = {
    list(params: { status?: string; page?: number; size?: number } = {}): Promise<LeadPage> {
        const q = new URLSearchParams()
        if (params.status)        q.set('status', params.status)
        if (params.page != null)  q.set('page', String(params.page))
        if (params.size  != null) q.set('size',  String(params.size))
        return req(`/api/v1/leads?${q}`)
    },
    updateStatus(id: number, status: string): Promise<Lead> {
        return req(`/api/v1/leads/${id}/status`, {
            method: 'PATCH',
            body:   JSON.stringify({ status }),
        })
    },
    markAllRead(): Promise<void> {
        return req('/api/v1/leads/read-all', { method: 'POST' })
    },
}

export const importApi = {
    uploadExport(file: File): Promise<ImportResult> {
        const formData = new FormData()
        formData.append('file', file)
        return fetch(`${BASE}/api/v1/leads/import-export`, {
            method:      'POST',
            credentials: 'include',
            body:        formData,
        }).then(async res => {
            if (!res.ok) {
                const b = await res.json().catch(() => ({ error: `Ошибка ${res.status}` })) as { error?: string }
                throw new Error(b.error ?? `Ошибка ${res.status}`)
            }
            return res.json() as Promise<ImportResult>
        })
    },
}

export const chatsApi = {
    list(): Promise<ChatSubscription[]> {
        return req('/api/v1/chats')
    },
    add(chatLink: string): Promise<ChatSubscription> {
        return req('/api/v1/chats', {
            method: 'POST',
            body:   JSON.stringify({ chatLink }),
        })
    },
    remove(id: number): Promise<void> {
        return req(`/api/v1/chats/${id}`, { method: 'DELETE' })
    },
}

export const keywordsApi = {
    list(): Promise<Keyword[]> {
        return req('/api/v1/keywords')
    },
    add(keyword: string): Promise<Keyword> {
        return req('/api/v1/keywords', {
            method: 'POST',
            body:   JSON.stringify({ keyword }),
        })
    },
    remove(id: number): Promise<void> {
        return req(`/api/v1/keywords/${id}`, { method: 'DELETE' })
    },
}

export const businessContextApi = {
    get(): Promise<{ businessContext: string | null }> {
        return req('/api/v1/business-context')
    },
    save(businessContext: string): Promise<{ businessContext: string | null }> {
        return req('/api/v1/business-context', {
            method: 'POST',
            body:   JSON.stringify({ businessContext }),
        })
    },
}

export const adminSubsApi = {
    list(): Promise<SubscriptionInfo[]> {
        return req('/api/v1/admin/subscriptions')
    },
    grant(userId: number, plan: string, durationDays = 30): Promise<SubscriptionInfo> {
        return req('/api/v1/admin/subscriptions/grant', {
            method: 'POST',
            body:   JSON.stringify({ userId, plan, durationDays }),
        })
    },
    changePlan(userId: number, plan: string): Promise<SubscriptionInfo> {
        return req(`/api/v1/admin/subscriptions/${userId}/plan`, {
            method: 'PATCH',
            body:   JSON.stringify({ plan, status: 'ACTIVE' }),
        })
    },
    setExpiry(userId: number, plan: string, durationDays: number): Promise<SubscriptionInfo> {
        return req('/api/v1/admin/subscriptions/grant', {
            method: 'POST',
            body:   JSON.stringify({ userId, plan, durationDays }),
        })
    },
    revoke(userId: number): Promise<SubscriptionInfo> {
        return req(`/api/v1/admin/subscriptions/${userId}/revoke`, { method: 'POST' })
    },
    adjustBalance(userId: number, amount: number): Promise<SubscriptionInfo> {
        return req('/api/v1/admin/subscriptions/balance', {
            method: 'POST',
            body:   JSON.stringify({ userId, amount }),
        })
    },
}


export interface AdminLeadDto {
    id:             number
    userId:         number
    userEmail:      string
    chatTitle:      string
    chatLink:       string
    authorName:     string
    authorUsername: string
    messageText:    string
    messageLink:    string
    matchedKeyword: string
    status:         string
    aiValid:        boolean | null
    aiReason:       string | null
    foundAt:        string
    source:         'LIVE' | 'MANUAL_EXPORT'
    messageDate:    string
}