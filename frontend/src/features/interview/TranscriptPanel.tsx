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
    <div className="transcript">
      <h3>Conversation</h3>
      <div className="transcript-scroll">
        {chat.map((e, i) => (
          <div key={i} className={`bubble-row ${e.sender}`}>
            <div className="bubble">
              {e.sender === 'ai' && e.kind && <span className="bubble-kind">{e.kind}</span>}
              {e.text}
            </div>
          </div>
        ))}
        {partial && (
          <div className="bubble-row candidate">
            <div className="bubble partial">{partial}…</div>
          </div>
        )}
        {thinkingNode && <p className="chat-line thinking">Interviewer is thinking ({thinkingNode})…</p>}
      </div>
    </div>
  );
}
