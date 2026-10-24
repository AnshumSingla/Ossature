import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { api, errorMessage } from '../api/rest';
import { useAuth } from '../api/auth';
import { validateSignup } from '../api/credentials';
import { PasswordField } from '../components/PasswordField';

export function SignupPage() {
  const navigate = useNavigate();
  const { login } = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [error, setError] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const problems = validateSignup(displayName, email, password);
    setError(problems);
    if (problems.length) return;
    setBusy(true);
    try {
      const res = await api.signup(email, password, displayName);
      login(res);
      navigate('/');
    } catch (e) {
      setError([errorMessage(e)]);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="auth-bg">
      <div className="glass-card">
        <h1>Register</h1>
        <form onSubmit={submit} noValidate>
          <div className="glass-field">
            <input
              type="text"
              placeholder="Display name"
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
              required
            />
            <span className="icon" aria-hidden="true">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <circle cx="12" cy="8" r="4" />
                <path d="M4 20c0-4 4-6 8-6s8 2 8 6" />
              </svg>
            </span>
          </div>
          <div className="glass-field">
            <input
              type="email"
              placeholder="Email ID"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
            />
            <span className="icon" aria-hidden="true">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <rect x="3" y="5" width="18" height="14" rx="2" />
                <path d="m3 7 9 6 9-6" />
              </svg>
            </span>
          </div>
          <PasswordField
            placeholder="Password (min 8, a letter and a digit)"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
          {error.map((m) => (
            <p key={m} className="glass-note" role="alert">
              {m}
            </p>
          ))}
          <button type="submit" className="glass-btn" disabled={busy}>
            {busy ? 'Signing up…' : 'Register'}
          </button>
        </form>
        <p className="glass-footer">
          Already have an account? <Link to="/login">Login</Link>
        </p>
      </div>
    </div>
  );
}
