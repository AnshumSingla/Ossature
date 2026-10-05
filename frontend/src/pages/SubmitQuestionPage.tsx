import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/rest';

export function SubmitQuestionPage() {
  const [title, setTitle] = useState('');
  const [prompt, setPrompt] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitted, setSubmitted] = useState(false);
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      await api.submitQuestion(title, prompt);
      setSubmitted(true);
    } catch {
      setError('Could not submit this question. Try again in a moment.');
    } finally {
      setBusy(false);
    }
  };

  if (submitted) {
    return (
      <main className="page">
        <h1>Thanks!</h1>
        <p>
          Your question is in the queue for review. Approved questions join the public
          question bank.
        </p>
        <p>
          <Link to="/">Back to the question bank</Link>
        </p>
      </main>
    );
  }

  return (
    <main className="page">
      <p>
        <Link to="/">&larr; Back</Link>
      </p>
      <h1>Submit a question</h1>
      <p className="muted">
        Propose a system-design prompt for the bank. It&apos;s reviewed before it goes live
        (PRD §9).
      </p>
      <form onSubmit={submit} className="form" style={{ maxWidth: 560 }}>
        <input
          type="text"
          placeholder="Title (e.g. &quot;Design a ride-sharing dispatch system&quot;)"
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          required
        />
        <textarea
          placeholder="Full prompt - what should the candidate design, and what should they cover?"
          value={prompt}
          onChange={(e) => setPrompt(e.target.value)}
          rows={8}
          required
        />
        {error && <p className="error-text">{error}</p>}
        <button type="submit" disabled={busy}>
          {busy ? 'Submitting…' : 'Submit for review'}
        </button>
      </form>
    </main>
  );
}
