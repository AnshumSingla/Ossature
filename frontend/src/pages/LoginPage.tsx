import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { api, errorMessage } from '../api/rest';
import { useAuth } from '../api/auth';
import { validateLogin } from '../api/credentials';
import { PasswordField } from '../components/PasswordField';

export function LoginPage() {
  const navigate = useNavigate();
  const { login } = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [remember, setRemember] = useState(true);
  const [error, setError] = useState<string[]>([]);
  const [forgotNote, setForgotNote] = useState(false);
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const problems = validateLogin(email, password);
    setError(problems);
    if (problems.length) return;
    setBusy(true);
    try {
      const res = await api.login(email, password, remember);
      login(res, remember);
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
        <h1>Login</h1>
        <form onSubmit={submit} noValidate>
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
            placeholder="Password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
          <div className="glass-row">
            <label>
              <input
                type="checkbox"
                checked={remember}
                onChange={(e) => setRemember(e.target.checked)}
              />
              Remember me
            </label>
            <button type="button" className="link" onClick={() => setForgotNote(true)}>
              Forgot Password?
            </button>
          </div>
          {forgotNote && (
            <p className="glass-note">Password reset isn&apos;t available yet — sign up for a new account instead.</p>
          )}
          {error.map((m) => (
            <p key={m} className="glass-note" role="alert">
              {m}
            </p>
          ))}
          <button type="submit" className="glass-btn" disabled={busy}>
            {busy ? 'Logging in…' : 'Login'}
          </button>
        </form>
        <p className="glass-footer">
          Don&apos;t have an account? <Link to="/signup">Register</Link>
        </p>
      </div>
    </div>
  );
}
