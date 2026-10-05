import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { useAuth } from './api/auth';
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

export default function App() {
  return (
    <BrowserRouter>
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
