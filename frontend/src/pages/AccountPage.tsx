import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { api, errorMessage } from '../api/rest';
import { useAuth } from '../api/auth';

// "Your data" (H21): download everything we hold about you, or delete it all.
export function AccountPage() {
  const navigate = useNavigate();
  const { user, logout } = useAuth();
  const [downloading, setDownloading] = useState(false);
  const [downloadError, setDownloadError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [password, setPassword] = useState('');
  const [deleting, setDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  const download = async () => {
    setDownloading(true);
    setDownloadError(null);
    try {
      const data = await api.exportAccount();
      const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = 'ossature-export.json';
      a.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      setDownloadError(errorMessage(e));
    } finally {
      setDownloading(false);
    }
  };

  const remove = async (e: FormEvent) => {
    e.preventDefault();
    if (!password) {
      setDeleteError('Enter your password to confirm.');
      return;
    }
    setDeleting(true);
    setDeleteError(null);
    try {
      await api.deleteAccount(password);
      logout(); // forget the session in this browser too
      navigate('/', { replace: true });
    } catch (err) {
      setDeleteError(errorMessage(err));
      setDeleting(false);
    }
  };

  return (
    <main className="page">
      <p>
        <Link to="/">&larr; Back</Link>
      </p>
      <h1>Your data</h1>
      <p className="muted">
        Signed in as {user?.email}. Your interviews (what you said, your diagrams, the interviewer’s notes and your
        debriefs) are deleted automatically after a retention period — one year after they started, by default.
        What you say is also sent to the AI provider that powers the interviewer while an interview runs.
      </p>

      <section className="account-section">
        <h2>Download your data</h2>
        <p>Everything we hold about you — profile, interviews, transcripts, debriefs, questions you submitted — as one file.</p>
        <button type="button" onClick={download} disabled={downloading}>
          {downloading ? 'Preparing…' : 'Download my data'}
        </button>
        {downloadError && <p className="error-text">{downloadError}</p>}
      </section>

      <section className="account-section danger">
        <h2>Delete your account</h2>
        <p>
          This permanently deletes your account and every interview you have done, and signs you out everywhere. It
          can’t be undone. Questions you submitted that were already approved stay in the question bank, no longer
          linked to you; ones still waiting for review are deleted.
        </p>
        {!confirming ? (
          <button type="button" className="btn-danger" onClick={() => setConfirming(true)}>
            Delete my account…
          </button>
        ) : (
          <form onSubmit={remove} noValidate>
            <label>
              Enter your password to confirm
              <input
                type="password"
                autoComplete="current-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
              />
            </label>
            <div className="card-actions" style={{ marginTop: 12 }}>
              <button type="submit" className="btn-danger" disabled={deleting}>
                {deleting ? 'Deleting…' : 'Permanently delete everything'}
              </button>
              <button
                type="button"
                onClick={() => {
                  setConfirming(false);
                  setPassword('');
                  setDeleteError(null);
                }}
                disabled={deleting}
              >
                Cancel
              </button>
            </div>
            {deleteError && <p className="error-text">{deleteError}</p>}
          </form>
        )}
      </section>
    </main>
  );
}
