import { useEffect, useState } from 'react';
import { useLocation, useParams } from 'react-router-dom';
import { api } from '../api/rest';
import { InterviewRoom } from '../features/interview/InterviewRoom';

export function SessionPage() {
  const { id } = useParams<{ id: string }>();
  const location = useLocation() as { state?: { prompt?: string } };
  const [prompt, setPrompt] = useState(location.state?.prompt ?? '');

  useEffect(() => {
    if (prompt || !id) return;
    // Reloaded straight into a session: recover the prompt via the question.
    api
      .getSession(id)
      .then((s) => api.getQuestion(s.questionId))
      .then((q) => setPrompt(q.prompt))
      .catch(() => setPrompt('(prompt unavailable)'));
  }, [id, prompt]);

  if (!id) return <p>Missing session id.</p>;
  return <InterviewRoom sessionId={id} prompt={prompt} />;
}
