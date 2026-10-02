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
          <p key={i} className={`chat-line ${e.sender === 'ai' ? 'ai' : 'candidate'}`}>
            <span className="speaker">
              {e.sender === 'ai' ? `Interviewer${e.kind ? ` (${e.kind})` : ''}` : 'You'}:
            </span>{' '}
            {e.text}
          </p>
        ))}
        {partial && <p className="chat-line partial">You: {partial}…</p>}
        {thinkingNode && <p className="chat-line thinking">Interviewer is thinking ({thinkingNode})…</p>}
      </div>
    </div>
  );
}
