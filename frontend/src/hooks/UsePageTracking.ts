/**
 * usePageTracking.ts — хук автоматического логирования переходов по страницам.
 * Подключается один раз в App.tsx внутри <BrowserRouter>.
 */

import { useEffect, useRef } from 'react'
import { useLocation }       from 'react-router-dom'
import { logEvent }           from '../analytics/userLogger'

/** Человекочитаемые названия маршрутов */
const PAGE_LABELS: Record<string, string> = {
    '/':                    'Главная',
    '/about':               'О нас',
    '/blog':                'Блог',
    '/contacts':            'Контакты',
    '/privacy':             'Политика конфиденциальности',
    '/terms':               'Условия использования',
    '/refund':              'Возврат',
    '/checkout':            'Оформление подписки',
    '/oauth/callback':      'OAuth callback',
    '/dashboard':           'Дашборд — Обзор',
    '/dashboard/leads':     'Дашборд — Лиды',
    '/dashboard/chats':     'Дашборд — Чаты',
    '/dashboard/keywords':  'Дашборд — Ключевые слова',
    '/dashboard/profile':   'Дашборд — Профиль',
    '/admin':               'Админ-панель',
}

function getPageLabel(pathname: string): string {
    return PAGE_LABELS[pathname] ?? pathname
}

export function usePageTracking(): void {
    const location      = useLocation()
    const prevPathRef   = useRef<string | null>(null)

    useEffect(() => {
        const path = location.pathname

        // не логируем повторный вход на ту же страницу
        if (path === prevPathRef.current) return

        // PAGE_LEAVE для предыдущей страницы
        if (prevPathRef.current !== null) {
            logEvent('PAGE_LEAVE', {
                label: getPageLabel(prevPathRef.current),
                page:  prevPathRef.current,
            })
        }

        prevPathRef.current = path

        logEvent('PAGE_VIEW', {
            label: getPageLabel(path),
            page:  path,
        })
    }, [location.pathname])
}