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

  if (error) return <p style={{ color: '#b00' }}>Could not load questions: {error}</p>;

  return (
    <ul style={{ listStyle: 'none', padding: 0, maxWidth: 640 }}>
      {questions.map((q) => (
        <li key={q.id} style={{ border: '1px solid #ddd', borderRadius: 8, padding: 12, marginBottom: 10 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12 }}>
            <div>
              <strong>{q.title}</strong>
              <p style={{ margin: '4px 0', opacity: 0.8 }}>{q.prompt}</p>
              <small style={{ opacity: 0.6 }}>
                {q.source}
                {q.hasVettedReference ? ' · vetted reference' : ''}
              </small>
            </div>
            <button type="button" onClick={() => onPick(q)} style={{ alignSelf: 'center' }}>
              Start
            </button>
          </div>
        </li>
      ))}
      {questions.length === 0 && <li>No questions yet — seed the bank (backend migration V2).</li>}
    </ul>
  );
}
