import { useEffect, useState } from 'react';
import { api, type QuestionView } from '../../api/rest';

function badgeClass(source: string): string {
  if (source === 'COMMUNITY') return 'q-badge source-community';
  if (source === 'GENERATED') return 'q-badge source-generated';
  return 'q-badge';
}

export function QuestionPicker({ onPick }: { onPick: (q: QuestionView) => void }) {
  const [questions, setQuestions] = useState<QuestionView[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api
      .listQuestions()
      .then(setQuestions)
      .catch((e: unknown) => setError(String(e)));
  }, []);

  if (error) return <p className="error-text">Could not load questions: {error}</p>;

  if (questions.length === 0) {
    return <p className="muted">No questions yet — seed the bank (backend migration V2).</p>;
  }

  return (
    <ul className="question-grid">
      {questions.map((q) => (
        <li key={q.id} className="q-card">
          <h3>{q.title}</h3>
          <p>{q.prompt}</p>
          <div className="q-card-footer">
            <span className={badgeClass(q.source)}>
              {q.source.toLowerCase()}
              {q.hasVettedReference && <span className="vetted"> · vetted</span>}
            </span>
            <button type="button" onClick={() => onPick(q)}>
              Start
            </button>
          </div>
        </li>
      ))}
    </ul>
  );
}
