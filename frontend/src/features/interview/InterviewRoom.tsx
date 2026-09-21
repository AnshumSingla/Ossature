import { useEffect } from 'react';
import { useInterviewSocket } from '../../hooks/useInterviewSocket';
import { speak, useSpeechRecognition } from '../../hooks/useSpeech';
import { useWhiteboardStore } from '../whiteboard/whiteboardStore';
import { Whiteboard } from '../whiteboard/Whiteboard';
import { DebriefPanel } from '../debrief/DebriefPanel';
import { TranscriptPanel } from './TranscriptPanel';

function micErrorMessage(code: string): string {
  switch (code) {
    case 'not-allowed':
    case 'service-not-allowed':
      return 'Microphone permission was denied. Allow it in the browser\'s site settings.';
    case 'audio-capture':
      return 'No microphone found, or it\'s in use by another app.';
    case 'network':
      return (
        "Couldn't reach the browser's speech recognition service. This relies on the " +
        "browser's own backend (Google's, for Chrome) - Brave and other privacy-hardened " +
        'browsers strip that wiring out, so speech recognition never works there no matter ' +
        'the network. Try Chrome (or Edge) instead.'
      );
    default:
      return code;
  }
}

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
          {stt.error && stt.error !== 'no-speech' && (
            <p style={{ color: '#b00', fontSize: 13, margin: '6px 0 0' }}>
              Mic error: {micErrorMessage(stt.error)}
            </p>
          )}
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
