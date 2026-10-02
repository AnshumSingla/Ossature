import type { ServerMessage } from '../../types/protocol';

type Debrief = Extract<ServerMessage, { type: 'debrief.ready' }>;

function List({ title, items }: { title: string; items: string[] }) {
  return (
    <section>
      <h4>{title}</h4>
      {items.length === 0 ? (
        <p className="muted">—</p>
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
      <h2>Debrief</h2>
      <List title="Strengths" items={debrief.strengths} />
      <List title="Gaps" items={debrief.gaps} />
      <List title="Unresolved" items={debrief.unresolved} />
      <section>
        <h4>Comparison to reference solution</h4>
        <p>{debrief.referenceComparison || '—'}</p>
      </section>
    </div>
  );
}
