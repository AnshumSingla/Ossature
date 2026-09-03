import type { ChatEntry } from '../../hooks/useInterviewSocket';

export function TranscriptPanel({
  chat,
  partial,
  thinkingNode,
}: {
  chat: ChatEntry[];
  partial: string;
  thinkingNode: 'council' | 'review' | null;
}) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', overflow: 'hidden' }}>
      <h3 style={{ margin: '8px 12px' }}>Conversation</h3>
      <div style={{ flex: 1, overflowY: 'auto', padding: '0 12px' }}>
        {chat.map((e, i) => (
          <p key={i} style={{ margin: '6px 0' }}>
            <strong style={{ color: e.sender === 'ai' ? '#2563eb' : '#111' }}>
              {e.sender === 'ai' ? `Interviewer${e.kind ? ` (${e.kind})` : ''}` : 'You'}:
            </strong>{' '}
            {e.text}
          </p>
        ))}
        {partial && <p style={{ margin: '6px 0', opacity: 0.5 }}>You: {partial}…</p>}
        {thinkingNode && (
          <p style={{ margin: '6px 0', fontStyle: 'italic', opacity: 0.6 }}>
            Interviewer is thinking ({thinkingNode})…
          </p>
        )}
      </div>
    </div>
  );
}
