import { useState } from 'react'
import { BrowserRouter, Routes, Route } from 'react-router-dom'
import type { Lang } from './i18n/translations'
import { AuthProvider } from './context/AuthContext.tsx'
import { useScrollFade } from './hooks/useScrollFade'
import Header from './components/Header'
import Hero from './components/Hero'
import {
    Problems, Features, HowItWorks, Audience, Compare,
    Pricing, Reviews, Faq, CtaFinal, Footer,
} from './components/Sections'
import About    from './pages/About'
import Blog     from './pages/Blog'
import Contacts from './pages/Contacts'
import Privacy  from './pages/Privacy'
import Terms    from './pages/Terms'
import Refund   from './pages/Refund'
import Checkout from './pages/Checkout'
import OAuthCallback from './pages/OAuthCallback'
import AdminPanel from './pages/AdminPanel'
import DashboardLayout   from './dashboard/DashboardLayout'
import DashboardOverview from './dashboard/DashboardOverview'
import LeadsPage         from './dashboard/LeadsPage.tsx'
import ChatsPage         from './dashboard/ChatsPage'
import KeywordsPage      from './dashboard/KeywordsPage.tsx'
import ImportPage        from './dashboard/ImportPage'
import ProfilePage       from './dashboard/ProfilePage'
import ProtectedRoute    from './components/ProtectedRoute.tsx'
import AdminRoute        from './components/AdminRoute.tsx'

function Home({ lang, setLang }: { lang: Lang; setLang: (l: Lang) => void }) {
    useScrollFade()
    return (
        <>
            <Header lang={lang} onLang={setLang} />
            <main>
                <Hero lang={lang} />
                <Problems lang={lang} />
                <Features lang={lang} />
                <HowItWorks lang={lang} />
                <Audience lang={lang} />
                <Compare lang={lang} />
                <Pricing lang={lang} />
                <Reviews lang={lang} />
                <Faq lang={lang} />
                <CtaFinal lang={lang} />
            </main>
            <Footer lang={lang} />
        </>
    )
}

export default function App() {
    const [lang, setLang] = useState<Lang>('ru')
    const pp = { lang, setLang }

    return (
        <AuthProvider>
            <BrowserRouter>
                <Routes>
                    <Route path="/"              element={<Home lang={lang} setLang={setLang} />} />
                    <Route path="/about"         element={<About    {...pp} />} />
                    <Route path="/blog"          element={<Blog     {...pp} />} />
                    <Route path="/contacts"      element={<Contacts {...pp} />} />
                    <Route path="/privacy"       element={<Privacy  {...pp} />} />
                    <Route path="/terms"         element={<Terms    {...pp} />} />
                    <Route path="/refund"        element={<Refund   {...pp} />} />
                    <Route path="/checkout"      element={<Checkout {...pp} />} />
                    {}
                    <Route path="/oauth/callback" element={<OAuthCallback />} />

                    <Route element={<ProtectedRoute />}>
                        <Route path="/dashboard" element={<DashboardLayout lang={lang} onLang={setLang} />}>
                            <Route index           element={<DashboardOverview lang={lang} />} />
                            <Route path="leads"    element={<LeadsPage />} />
                            <Route path="chats"    element={<ChatsPage />} />
                            <Route path="keywords" element={<KeywordsPage />} />
                            <Route path="import"   element={<ImportPage />} />
                            <Route path="profile"  element={<ProfilePage lang={lang} />} />
                        </Route>
                    </Route>

                    <Route element={<AdminRoute />}>
                        <Route path="/admin" element={<AdminPanel {...pp} />} />
                    </Route>
                </Routes>
            </BrowserRouter>
        </AuthProvider>
    )
}