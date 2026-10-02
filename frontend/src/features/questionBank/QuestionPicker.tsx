import { useEffect, useState } from 'react';
import { api, type QuestionView } from '../../api/rest';

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

  return (
    <ul className="question-list">
      {questions.map((q) => (
        <li key={q.id} className="question-card">
          <div>
            <h3>{q.title}</h3>
            <p>{q.prompt}</p>
            <small>
              {q.source}
              {q.hasVettedReference ? ' · vetted reference' : ''}
            </small>
          </div>
          <button type="button" onClick={() => onPick(q)}>
            Start
          </button>
        </li>
      ))}
      {questions.length === 0 && <li className="muted">No questions yet — seed the bank (backend migration V2).</li>}
    </ul>
  );
}
