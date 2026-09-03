import type { ServerMessage } from '../../types/protocol';

type Debrief = Extract<ServerMessage, { type: 'debrief.ready' }>;

function List({ title, items }: { title: string; items: string[] }) {
  return (
    <section style={{ marginBottom: 16 }}>
      <h4 style={{ margin: '0 0 4px' }}>{title}</h4>
      {items.length === 0 ? (
        <p style={{ opacity: 0.6 }}>—</p>
      ) : (
        <ul style={{ margin: 0, paddingLeft: 20 }}>
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
    <div style={{ padding: 16, maxWidth: 720 }}>
      <h2>Debrief</h2>
      <List title="Strengths" items={debrief.strengths} />
      <List title="Gaps" items={debrief.gaps} />
      <List title="Unresolved" items={debrief.unresolved} />
      <section>
        <h4 style={{ margin: '0 0 4px' }}>Comparison to reference solution</h4>
        <p>{debrief.referenceComparison || '—'}</p>
      </section>
    </div>
  );
}
