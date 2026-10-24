import { Link } from 'react-router-dom';
import './landing.css';

const STEPS = [
  {
    n: '01',
    title: 'Pick a prompt',
    body: 'Choose a system design question from the bank - or submit your own for the community.',
  },
  {
    n: '02',
    title: 'Talk and draw',
    body: 'Sketch your architecture on a shared whiteboard while explaining it out loud, like the real thing.',
  },
  {
    n: '03',
    title: 'Get the debrief',
    body: 'Finish with a verdict: what you nailed, what you missed, and what was left unresolved.',
  },
];

const FEATURES = [
  {
    title: 'Voice in, voice out',
    body: 'Your speech is transcribed and the interviewer’s replies are spoken back, both on your device. No typing mid-thought.',
    icon: (
      <>
        <rect x="9" y="3" width="6" height="11" rx="3" />
        <path d="M5 11a7 7 0 0 0 14 0M12 18v3" />
      </>
    ),
  },
  {
    title: 'A whiteboard that listens',
    body: 'The interviewer sees what you draw and asks about it - the missing cache, the single point of failure.',
    icon: (
      <>
        <rect x="3" y="4" width="18" height="13" rx="2" />
        <path d="M8 21h8M12 17v4" />
      </>
    ),
  },
  {
    title: 'Honest debriefs',
    body: 'Strengths, gaps and open questions, plus a quote from your own session to anchor each point.',
    icon: (
      <>
        <path d="M7 3h8l4 4v14H7z" />
        <path d="M15 3v4h4M10 12h6M10 16h6" />
      </>
    ),
  },
  {
    title: 'Replay every session',
    body: 'Every interview is saved. Scrub back through the conversation and the board to see where it turned.',
    icon: (
      <>
        <circle cx="12" cy="12" r="9" />
        <path d="m10 8.5 5 3.5-5 3.5z" />
      </>
    ),
  },
  {
    title: 'A community question bank',
    body: 'Submit questions you were asked. Reviewed submissions join the bank for everyone to practise on.',
    icon: (
      <>
        <circle cx="9" cy="8" r="3" />
        <path d="M3 20a6 6 0 0 1 12 0M16 5.5a3 3 0 0 1 0 5.5M18 20a5 5 0 0 0-2-4" />
      </>
    ),
  },
];

function Mark() {
  return (
    <span className="landing-mark" aria-hidden="true">
      W
    </span>
  );
}

// A static, decorative stand-in for the interview room: a tiny architecture sketch next to
// the conversation, so the hero shows the product rather than describing it.
function RoomPreview() {
  return (
    <div className="landing-preview" aria-hidden="true">
      <div className="landing-preview-bar">
        <span />
        <span />
        <span />
        <em>Design a URL shortener</em>
      </div>
      <div className="landing-preview-body">
        <svg className="landing-preview-board" viewBox="0 0 300 200" fill="none">
          <defs>
            <marker id="lp-arrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto">
              <path d="M0 1 9 5 0 9z" fill="rgba(255,255,255,0.7)" />
            </marker>
          </defs>
          <rect x="10" y="78" width="66" height="40" rx="10" className="lp-node" />
          <text x="43" y="103" textAnchor="middle">Client</text>
          <rect x="116" y="78" width="66" height="40" rx="10" className="lp-node lp-node-hot" />
          <text x="149" y="103" textAnchor="middle">API</text>
          <rect x="224" y="24" width="66" height="40" rx="10" className="lp-node" />
          <text x="257" y="49" textAnchor="middle">Cache</text>
          <rect x="224" y="132" width="66" height="40" rx="10" className="lp-node" />
          <text x="257" y="157" textAnchor="middle">DB</text>
          <path d="M76 98h38" markerEnd="url(#lp-arrow)" className="lp-edge" />
          <path d="M182 90 224 50" markerEnd="url(#lp-arrow)" className="lp-edge" />
          <path d="M182 106 224 148" markerEnd="url(#lp-arrow)" className="lp-edge" />
        </svg>
        <div className="landing-preview-chat">
          <p className="lp-bubble lp-bubble-ai">
            <b>Interviewer</b>
            What happens to reads when the cache is cold?
          </p>
          <p className="lp-bubble lp-bubble-me">
            <b>You</b>
            Fall through to the DB, then repopulate on the way back&hellip;
          </p>
          <div className="lp-wave">
            <i /> <i /> <i /> <i /> <i /> <i /> <i />
            <span>Listening</span>
          </div>
        </div>
      </div>
    </div>
  );
}

export function LandingPage() {
  return (
    <div className="landing">
      <header className="landing-nav">
        <Link to="/" className="landing-brand">
          <Mark />
          <span>Whiteboard Interviewer</span>
        </Link>
        <nav className="landing-links" aria-label="Sections">
          <a href="#how">How it works</a>
          <a href="#features">Features</a>
        </nav>
        <div className="landing-nav-cta">
          <Link to="/login" className="landing-btn landing-btn-ghost">
            Log in
          </Link>
          <Link to="/signup" className="landing-btn landing-btn-solid">
            Sign up
          </Link>
        </div>
      </header>

      <main>
        <section className="landing-hero">
          <p className="landing-eyebrow">Voice-driven system design practice</p>
          <h1>
            Think out loud.
            <br />
            <em>Draw it as you go.</em>
          </h1>
          <p className="landing-lede">
            Practise system design the way interviews actually happen: talking through a design while sketching it
            on a whiteboard, with an interviewer who answers back by voice.
          </p>
          <div className="landing-hero-cta">
            <Link to="/signup" className="landing-btn landing-btn-solid landing-btn-lg">
              Start a mock interview
            </Link>
            <Link to="/login" className="landing-btn landing-btn-ghost landing-btn-lg">
              I have an account
            </Link>
          </div>
          <RoomPreview />
        </section>

        <section id="how" className="landing-section">
          <p className="landing-eyebrow">How it works</p>
          <h2>From prompt to verdict in one sitting</h2>
          <ol className="landing-steps">
            {STEPS.map((s) => (
              <li key={s.n} className="landing-card">
                <span className="landing-step-n">{s.n}</span>
                <h3>{s.title}</h3>
                <p>{s.body}</p>
              </li>
            ))}
          </ol>
        </section>

        <section id="features" className="landing-section">
          <p className="landing-eyebrow">Features</p>
          <h2>Everything a real loop has, minus the pressure of a stranger</h2>
          <ul className="landing-features">
            {FEATURES.map((f) => (
              <li key={f.title} className="landing-card">
                <span className="landing-icon" aria-hidden="true">
                  <svg
                    width="20"
                    height="20"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="1.8"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  >
                    {f.icon}
                  </svg>
                </span>
                <h3>{f.title}</h3>
                <p>{f.body}</p>
              </li>
            ))}
          </ul>
        </section>

        <section className="landing-section landing-final">
          <div className="landing-card landing-final-card">
            <h2>Your next interview starts with a blank board.</h2>
            <p>Create an account and take your first mock interview in a couple of minutes.</p>
            <Link to="/signup" className="landing-btn landing-btn-solid landing-btn-lg">
              Create an account
            </Link>
          </div>
        </section>
      </main>

      <footer className="landing-footer">
        <span>Whiteboard Interviewer</span>
        <span>
          <Link to="/login">Log in</Link> · <Link to="/signup">Sign up</Link>
        </span>
      </footer>
    </div>
  );
}
