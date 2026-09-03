import { useEffect } from 'react';
import { useInterviewSocket } from '../../hooks/useInterviewSocket';
import { speak, useSpeechRecognition } from '../../hooks/useSpeech';
import { useWhiteboardStore } from '../whiteboard/whiteboardStore';
import { Whiteboard } from '../whiteboard/Whiteboard';
import { DebriefPanel } from '../debrief/DebriefPanel';
import { TranscriptPanel } from './TranscriptPanel';

export function InterviewRoom({ sessionId, prompt }: { sessionId: string; prompt: string }) {
  const {
    status,
    chat,
    thinkingNode,
    debrief,
    sendPartial,
    sendFinal,
    sendSnapshot,
    submitDiagram,
    endSession,
  } = useInterviewSocket(sessionId);

  const stt = useSpeechRecognition();
  const { toDiagram, setLocked, locked } = useWhiteboardStore();

  // Wire finalized utterances -> server (FR2).
  useEffect(() => {
    stt.onFinal((text) => {
      if (text) sendFinal(text);
    });
  }, [stt, sendFinal]);

  // Stream interim transcript (PRD 6.4 transcript.partial).
  useEffect(() => {
    if (stt.partial) sendPartial(stt.partial);
  }, [stt.partial, sendPartial]);

  // Voice the interviewer's turns locally (FR2a).
  useEffect(() => {
    const last = chat[chat.length - 1];
    if (last?.sender === 'ai') speak(last.text);
  }, [chat]);

  // Persistence-only diagram snapshots on a ~90s cadence (PRD 6.2).
  useEffect(() => {
    const id = window.setInterval(() => sendSnapshot(toDiagram()), 90_000);
    return () => window.clearInterval(id);
  }, [sendSnapshot, toDiagram]);

  const onSubmit = () => {
    submitDiagram(toDiagram());
    setLocked(true);
  };

  return (
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 380px', height: '100vh' }}>
      <div style={{ borderRight: '1px solid #ddd', minWidth: 0 }}>
        <Whiteboard />
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', minHeight: 0 }}>
        <div style={{ padding: 12, borderBottom: '1px solid #ddd' }}>
          <div style={{ fontSize: 12, opacity: 0.6 }}>
            Session {sessionId.slice(0, 8)} · socket {status}
          </div>
          <p style={{ margin: '6px 0', fontWeight: 600 }}>{prompt}</p>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            {stt.supported ? (
              <button type="button" onClick={stt.listening ? stt.stop : stt.start}>
                {stt.listening ? 'Stop mic' : 'Start mic'}
              </button>
            ) : (
              <span style={{ color: '#b00' }}>Speech recognition unavailable in this browser</span>
            )}
            <button type="button" onClick={onSubmit} disabled={locked}>
              Submit diagram
            </button>
            <button type="button" onClick={endSession}>
              End session
            </button>
          </div>
        </div>
        <div style={{ flex: 1, minHeight: 0 }}>
          {debrief ? (
            <DebriefPanel debrief={debrief} />
          ) : (
            <TranscriptPanel chat={chat} partial={stt.partial} thinkingNode={thinkingNode} />
          )}
        </div>
      </div>
    </div>
  );
}
