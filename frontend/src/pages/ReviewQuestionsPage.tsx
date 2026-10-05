import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, type QuestionView } from '../api/rest';
import { useAuth } from '../api/auth';

export function ReviewQuestionsPage() {
  const { user } = useAuth();
  const [pending, setPending] = useState<QuestionView[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  const load = () => {
    api
      .listPendingQuestions()
      .then(setPending)
      .catch(() => setError('Could not load pending submissions.'));
  };

  useEffect(load, []);

  if (!user?.isOwner) {
    return (
      <main className="page">
        <p className="error-text">Owner access only.</p>
        <Link to="/">Back</Link>
      </main>
    );
  }

  const decide = async (id: string, action: 'approve' | 'reject') => {
    setBusyId(id);
    try {
      if (action === 'approve') await api.approveQuestion(id);
      else await api.rejectQuestion(id);
      setPending((p) => p.filter((q) => q.id !== id));
    } catch {
      setError('That action failed - try again.');
    } finally {
      setBusyId(null);
    }
  };

  return (
    <main className="page">
      <p>
        <Link to="/">&larr; Back</Link>
      </p>
      <h1>Review submissions</h1>
      {error && <p className="error-text">{error}</p>}
      {pending.length === 0 ? (
        <p className="muted">Nothing waiting on review.</p>
      ) : (
        <ul className="question-list">
          {pending.map((q) => (
            <li key={q.id} className="question-card" style={{ alignItems: 'flex-start' }}>
              <div>
                <h3>{q.title}</h3>
                <p>{q.prompt}</p>
              </div>
              <div style={{ display: 'flex', gap: 8, flexShrink: 0 }}>
                <button type="button" disabled={busyId === q.id} onClick={() => decide(q.id, 'approve')}>
                  Approve
                </button>
                <button
                  type="button"
                  className="btn-danger"
                  disabled={busyId === q.id}
                  onClick={() => decide(q.id, 'reject')}
                >
                  Reject
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}
    </main>
  );
}
