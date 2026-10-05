import { Link, useNavigate } from 'react-router-dom';
import { api, type QuestionView } from '../api/rest';
import { useAuth } from '../api/auth';
import { QuestionPicker } from '../features/questionBank/QuestionPicker';

export function HomePage() {
  const navigate = useNavigate();
  const { user, logout } = useAuth();

  const start = async (q: QuestionView) => {
    const session = await api.createSession(q.id);
    navigate(`/session/${session.id}`, { state: { prompt: q.prompt } });
  };

  return (
    <main className="page">
      <div className="app-header">
        <h1>Whiteboard Interviewer</h1>
        <div className="user">
          <Link to="/history">History</Link>
          <Link to="/submit-question">Submit a question</Link>
          {user?.isOwner && <Link to="/review">Review submissions</Link>}
          <span>{user?.displayName}</span>
          <button onClick={logout}>Log out</button>
        </div>
      </div>
      <p className="muted">
        Pick a system design prompt. You&apos;ll talk through your design out loud while drawing it;
        the interviewer responds by voice. Everything spoken is transcribed on-device.
      </p>
      <h2>Question bank</h2>
      <QuestionPicker onPick={start} />
    </main>
  );
}
