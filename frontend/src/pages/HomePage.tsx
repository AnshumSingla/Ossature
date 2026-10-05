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
    <main className="page-wide">
      <div className="app-header">
        <div className="app-brand">
          <span className="app-brand-mark" aria-hidden="true">
            W
          </span>
          <h1>Whiteboard Interviewer</h1>
        </div>
        <nav className="app-nav">
          <Link to="/history">History</Link>
          <Link to="/submit-question">Submit a question</Link>
          {user?.isOwner && <Link to="/review">Review submissions</Link>}
        </nav>
        <div className="user">
          <span>{user?.displayName}</span>
          <button onClick={logout}>Log out</button>
        </div>
      </div>
      <p className="lede">
        Pick a prompt, then talk through your design out loud while you draw it — the
        interviewer answers back by voice, same as the real thing.
      </p>
      <h2>Question bank</h2>
      <QuestionPicker onPick={start} />
    </main>
  );
}
