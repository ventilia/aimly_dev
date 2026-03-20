export const CHAT_SEARCH_QUERY_KEY = 'aimly_chat_search_query_for_kw'

export function saveChatSearchQueryForKeywords(query: string) {
    try { sessionStorage.setItem(CHAT_SEARCH_QUERY_KEY, query) } catch { /* ignore */ }
}