import { useNavigate } from 'react-router-dom';
import { api, type QuestionView } from '../api/rest';
import { QuestionPicker } from '../features/questionBank/QuestionPicker';

// Placeholder identity until auth lands (PRD 6.2 auth is a later concern).
const DEMO_USER_ID = '00000000-0000-0000-0000-000000000001';

export function HomePage() {
  const navigate = useNavigate();

  const start = async (q: QuestionView) => {
    const session = await api.createSession(DEMO_USER_ID, q.id);
    navigate(`/session/${session.id}`, { state: { prompt: q.prompt } });
  };

  return (
    <main style={{ padding: 24 }}>
      <h1>Whiteboard Interviewer</h1>
      <p style={{ maxWidth: 640 }}>
        Pick a system design prompt. You&apos;ll talk through your design out loud while drawing it;
        the interviewer responds by voice. Everything spoken is transcribed on-device.
      </p>
      <h2>Question bank</h2>
      <QuestionPicker onPick={start} />
    </main>
  );
}
