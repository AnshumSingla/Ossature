import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, type QuestionView, type SessionView } from '../api/rest';

function formatDate(iso: string): string {
  return new Date(iso).toLocaleString();
}

export function HistoryPage() {
  const [sessions, setSessions] = useState<SessionView[] | null>(null);
  const [questions, setQuestions] = useState<Record<string, QuestionView>>({});
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    Promise.all([api.listSessions(), api.listQuestions()])
      .then(([s, q]) => {
        setSessions([...s].sort((a, b) => b.startedAt.localeCompare(a.startedAt)));
        setQuestions(Object.fromEntries(q.map((item) => [item.id, item])));
      })
      .catch(() => setError('Could not load your session history.'));
  }, []);

  return (
    <main className="page">
      <p>
        <Link to="/">&larr; Back</Link>
      </p>
      <h1>Session history</h1>
      {error && <p className="error-text">{error}</p>}
      {!sessions && !error && <p className="muted">Loading…</p>}
      {sessions && sessions.length === 0 && <p className="muted">No sessions yet.</p>}
      {sessions && sessions.length > 0 && (
        <ul className="question-list">
          {sessions.map((s) => (
            <li key={s.id} className="question-card">
              <div>
                <h3>{questions[s.questionId]?.title ?? 'Untitled question'}</h3>
                <p>
                  Started {formatDate(s.startedAt)} · {s.state}
                  {s.endedAt ? ` · ended ${formatDate(s.endedAt)}` : ''}
                </p>
              </div>
              {s.state === 'ENDED' ? (
                <Link to={`/session/${s.id}/replay`}>
                  <button type="button">View replay</button>
                </Link>
              ) : (
                <Link to={`/session/${s.id}`}>
                  <button type="button">Continue</button>
                </Link>
              )}
            </li>
          ))}
        </ul>
      )}
    </main>
  );
}
