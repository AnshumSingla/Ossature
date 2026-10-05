import type { ServerMessage } from '../../types/protocol';

type Debrief = Extract<ServerMessage, { type: 'debrief.ready' }>;

type Tone = 'strengths' | 'gaps' | 'unresolved';

function ToneIcon({ tone }: { tone: Tone }) {
  if (tone === 'strengths') {
    return (
      <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
        <path d="m5 13 4 4L19 7" />
      </svg>
    );
  }
  if (tone === 'gaps') {
    return (
      <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
        <path d="M12 9v4M12 17h.01M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z" />
      </svg>
    );
  }
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
      <circle cx="12" cy="12" r="9" />
      <path d="M12 8v4M12 16h.01" />
    </svg>
  );
}

function Verdict({ tone, title, items }: { tone: Tone; title: string; items: string[] }) {
  return (
    <section className={`debrief-section tone-${tone}`}>
      <div className="debrief-section-head">
        <span className="debrief-icon">
          <ToneIcon tone={tone} />
        </span>
        <h4>{title}</h4>
        <span className="debrief-count">{items.length}</span>
      </div>
      {items.length === 0 ? (
        <p className="muted">Nothing noted here.</p>
      ) : (
        <ul>
          {items.map((it, i) => (
            <li key={i}>{it}</li>
          ))}
        </ul>
      )}
    </section>
  );
}

export function DebriefPanel({ debrief }: { debrief: Debrief }) {
  return (
    <div className="debrief">
      <span className="debrief-eyebrow">Interview complete</span>
      <h2>Debrief</h2>
      <Verdict tone="strengths" title="Strengths" items={debrief.strengths} />
      <Verdict tone="gaps" title="Gaps" items={debrief.gaps} />
      <Verdict tone="unresolved" title="Unresolved" items={debrief.unresolved} />
      {debrief.referenceComparison && (
        <section className="debrief-quote">
          <h4>Compared to the reference solution</h4>
          <p>{debrief.referenceComparison}</p>
        </section>
      )}
    </div>
  );
}
