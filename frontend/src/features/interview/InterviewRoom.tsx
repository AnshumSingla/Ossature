import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useInterviewSocket } from '../../hooks/useInterviewSocket';
import { speak } from '../../hooks/useSpeech';
import { useLocalSpeechRecognition } from '../../hooks/useLocalSpeech';
import { useWhiteboardStore } from '../whiteboard/whiteboardStore';
import { correctJargon } from '../voice/jargon';
import { Whiteboard } from '../whiteboard/Whiteboard';
import { DebriefPanel } from '../debrief/DebriefPanel';
import { TranscriptPanel } from './TranscriptPanel';

function micErrorMessage(code: string): string {
  switch (code) {
    case 'not-allowed':
      return "Microphone permission was denied. Allow it in the browser's site settings.";
    case 'audio-capture':
      return "No microphone found, or it's in use by another app.";
    case 'model-load-failed':
      return (
        "Couldn't download the voice model (one time, then it's cached). Check your connection " +
        'and press Start mic to try again, or type your answer instead.'
      );
    case 'unsupported':
      return "This browser can't run on-device voice input. Type your answer instead.";
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
    sendFinal,
    sendSnapshot,
    submitDiagram,
    endSession,
  } = useInterviewSocket(sessionId);

  const stt = useLocalSpeechRecognition();
  const [typing, setTyping] = useState(false);
  const [typed, setTyped] = useState('');
  const { toDiagram, setLocked, locked, reset } = useWhiteboardStore();

  // The whiteboard store is a module-level singleton shared across every page in this SPA -
  // without this, re-entering a session (or opening a different one) after visiting another
  // session would still show the previous board's shapes.
  useEffect(() => {
    reset();
  }, [sessionId, reset]);

  // Wire finalized utterances -> server (FR2).
  useEffect(() => {
    stt.onFinal((text) => {
      if (!text) return;
      // On-device fix-ups for system-design terms the recogniser mishears ("readies" -> Redis), helped by what is
      // actually drawn on the board right now.
      const board = useWhiteboardStore.getState();
      const labels = [
        ...board.nodes.map((n) => String((n.data as { label?: unknown } | undefined)?.label ?? '')),
        ...board.edges.map((e) => String(e.label ?? '')),
      ];
      sendFinal(correctJargon(text, { labels }));
    });
  }, [stt, sendFinal]);

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

  const sendTyped = () => {
    const text = typed.trim();
    if (!text) return;
    sendFinal(text);
    setTyped('');
  };

  const onSubmit = () => {
    submitDiagram(toDiagram());
    setLocked(true);
  };

  return (
    <div className="interview-layout">
      <div style={{ minWidth: 0 }}>
        <Whiteboard />
      </div>
      <div className="interview-sidebar">
        <div className="interview-toolbar">
          <div className="session-meta">
            <Link to="/">&larr; Home</Link> · Session {sessionId.slice(0, 8)} · socket {status}
          </div>
          <p className="prompt">{prompt}</p>
          {debrief ? (
            <div className="actions">
              <Link to="/">
                <button type="button">Back to home</button>
              </Link>
              <Link to="/history">
                <button type="button">Session history</button>
              </Link>
            </div>
          ) : (
            <>
              <div className="actions">
                {stt.supported ? (
                  <button
                    type="button"
                    onClick={stt.listening ? stt.stop : stt.start}
                    disabled={stt.loading}
                  >
                    {stt.loading
                      ? `Preparing voice… ${Math.round(stt.progress * 100)}%`
                      : stt.listening
                        ? 'Stop mic'
                        : 'Start mic'}
                  </button>
                ) : (
                  <span className="error-text">Voice input unavailable in this browser</span>
                )}
                <button type="button" onClick={onSubmit} disabled={locked}>
                  Submit diagram
                </button>
                <button type="button" onClick={endSession}>
                  End session
                </button>
              </div>
              {stt.loading && (
                <p className="voice-status">
                  Getting the on-device voice model ready (one-time download, cached afterwards).
                  You can already type your answers below.
                </p>
              )}
              {stt.listening && !stt.loading && (
                <p className="voice-status" aria-live="polite">
                  {stt.activity === 'hearing'
                    ? 'Hearing you…'
                    : stt.activity === 'transcribing'
                      ? 'Transcribing…'
                      : 'Listening - speak, then pause.'}
                </p>
              )}
              {stt.error && stt.error !== 'no-speech' && (
                <p className="error-text" style={{ fontSize: 13, margin: '6px 0 0' }}>
                  Mic error: {micErrorMessage(stt.error)}
                </p>
              )}
              <div className="voice-options">
                <span>Voice runs on this device - audio never leaves your browser.</span>
                <button type="button" className="link-button" onClick={() => setTyping((t) => !t)}>
                  {typing || !stt.supported ? 'Hide typing box' : 'Type instead'}
                </button>
              </div>
              {(typing || !stt.supported) && (
                <form
                  className="type-box"
                  onSubmit={(e) => {
                    e.preventDefault();
                    sendTyped();
                  }}
                >
                  <input
                    value={typed}
                    onChange={(e) => setTyped(e.target.value)}
                    placeholder="Type what you would say…"
                    maxLength={1000}
                    aria-label="Type your answer"
                  />
                  <button type="submit" disabled={!typed.trim()}>
                    Send
                  </button>
                </form>
              )}
            </>
          )}
        </div>
        <div style={{ flex: 1, minHeight: 0 }}>
          {debrief ? (
            <DebriefPanel debrief={debrief} />
          ) : (
            <TranscriptPanel chat={chat} partial="" thinkingNode={thinkingNode} />
          )}
        </div>
      </div>
    </div>
  );
}
