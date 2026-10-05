import { BrowserRouter, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { useAuth } from './api/auth';
import { ThemeToggle } from './components/ThemeToggle';
import { HomePage } from './pages/HomePage';
import { LoginPage } from './pages/LoginPage';
import { SessionPage } from './pages/SessionPage';
import { SignupPage } from './pages/SignupPage';
import { HistoryPage } from './pages/HistoryPage';
import { SessionReplayPage } from './pages/SessionReplayPage';
import { SubmitQuestionPage } from './pages/SubmitQuestionPage';
import { ReviewQuestionsPage } from './pages/ReviewQuestionsPage';

function RequireAuth({ children }: { children: React.ReactElement }) {
  const { user } = useAuth();
  return user ? children : <Navigate to="/login" replace />;
}

// The glass/mountain auth pages have their own self-contained look - the toggle only
// makes sense once you're in the themed app proper.
function GlobalThemeToggle() {
  const { pathname } = useLocation();
  if (pathname === '/login' || pathname === '/signup') return null;
  return <ThemeToggle />;
}

export default function App() {
  return (
    <BrowserRouter>
      <GlobalThemeToggle />
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/signup" element={<SignupPage />} />
        <Route
          path="/"
          element={
            <RequireAuth>
              <HomePage />
            </RequireAuth>
          }
        />
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
