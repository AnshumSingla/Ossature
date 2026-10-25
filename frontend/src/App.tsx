import { BrowserRouter, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { useEffect } from 'react';
import { useAuth } from './api/auth';
import { preloadVoiceModel } from './features/voice/shared';
import { ThemeToggle } from './components/ThemeToggle';
import { HomePage } from './pages/HomePage';
import { LandingPage } from './pages/LandingPage';
import { LoginPage } from './pages/LoginPage';
import { SessionPage } from './pages/SessionPage';
import { SignupPage } from './pages/SignupPage';
import { HistoryPage } from './pages/HistoryPage';
import { SessionReplayPage } from './pages/SessionReplayPage';
import { SubmitQuestionPage } from './pages/SubmitQuestionPage';
import { ReviewQuestionsPage } from './pages/ReviewQuestionsPage';
import { AccountPage } from './pages/AccountPage';

function RequireAuth({ children }: { children: React.ReactElement }) {
  const { user, restoring } = useAuth();
  if (restoring) return null; // a remembered login is being confirmed with the server: don't flash the login page
  return user ? children : <Navigate to="/login" replace />;
}

// The glass/mountain auth pages have their own self-contained look - the toggle only
// makes sense once you're in the themed app proper.
function GlobalThemeToggle() {
  const { pathname } = useLocation();
  const { user } = useAuth();
  if (pathname === '/login' || pathname === '/signup') return null;
  if (pathname === '/' && !user) return null; // logged-out visitors get the landing page
  return <ThemeToggle />;
}

// "/" is the public landing page until you sign in, then it's the question bank.
function RootRoute() {
  const { user, restoring } = useAuth();
  if (restoring) return null;
  return user ? <HomePage /> : <LandingPage />;
}

export default function App() {
  // Start fetching the on-device voice model the moment the site opens (landing page,
  // login, anywhere), so it is ready by the time the visitor starts an interview.
  useEffect(() => {
    preloadVoiceModel();
  }, []);

  return (
    <BrowserRouter>
      <GlobalThemeToggle />
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/signup" element={<SignupPage />} />
        <Route path="/" element={<RootRoute />} />
        <Route
          path="/session/:id"
          element={
            <RequireAuth>
              <SessionPage />
            </RequireAuth>
          }
        />
        <Route
          path="/session/:id/replay"
          element={
            <RequireAuth>
              <SessionReplayPage />
            </RequireAuth>
          }
        />
        <Route
          path="/history"
          element={
            <RequireAuth>
              <HistoryPage />
            </RequireAuth>
          }
        />
        <Route
          path="/account"
          element={
            <RequireAuth>
              <AccountPage />
            </RequireAuth>
          }
        />
        <Route
          path="/submit-question"
          element={
            <RequireAuth>
              <SubmitQuestionPage />
            </RequireAuth>
          }
        />
        <Route
          path="/review"
          element={
            <RequireAuth>
              <ReviewQuestionsPage />
            </RequireAuth>
          }
        />
      </Routes>
    </BrowserRouter>
  );
}
