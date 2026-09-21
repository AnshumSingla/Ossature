// Local, on-device STT + TTS via the Web Speech API (PRD 6.5 / FR2, FR2a).
// No audio ever leaves the browser and no paid API is touched.

import { useCallback, useEffect, useRef, useState } from 'react';

/* --- Minimal ambient types (Web Speech API is not in lib.dom for all TS versions) --- */
interface SpeechRecognitionResultLike {
  0: { transcript: string };
  isFinal: boolean;
}
interface SpeechRecognitionEventLike {
  resultIndex: number;
  results: { length: number; [i: number]: SpeechRecognitionResultLike };
}
interface SpeechRecognitionErrorLike {
  error: string; // 'no-speech' | 'audio-capture' | 'not-allowed' | 'network' | ...
  message?: string;
}
interface SpeechRecognitionLike {
  continuous: boolean;
  interimResults: boolean;
  lang: string;
  start(): void;
  stop(): void;
  onresult: ((e: SpeechRecognitionEventLike) => void) | null;
  onend: (() => void) | null;
  onerror: ((e: SpeechRecognitionErrorLike) => void) | null;
}
type SpeechRecognitionCtor = new () => SpeechRecognitionLike;

function getRecognitionCtor(): SpeechRecognitionCtor | null {
  const w = window as unknown as {
    SpeechRecognition?: SpeechRecognitionCtor;
    webkitSpeechRecognition?: SpeechRecognitionCtor;
  };
  return w.SpeechRecognition ?? w.webkitSpeechRecognition ?? null;
}

export interface UseSpeechRecognition {
  supported: boolean;
  listening: boolean;
  partial: string;
  /** Last recognition error, e.g. 'no-speech' | 'not-allowed' | 'network'. Null once clear. */
  error: string | null;
  start: () => void;
  stop: () => void;
  /** Fires once per finalized utterance. */
  onFinal: (cb: (text: string) => void) => void;
}

export function useSpeechRecognition(lang = 'en-US'): UseSpeechRecognition {
  const ctor = getRecognitionCtor();
  const recognitionRef = useRef<SpeechRecognitionLike | null>(null);
  const finalCb = useRef<(text: string) => void>(() => {});
  // Tracks whether the *user* wants to be listening, independent of whether the browser's
  // recognizer has (silently) stopped itself - Chrome's implementation ends a session after
  // a few seconds of silence even with `continuous: true`, so we restart it automatically
  // rather than surface that as "the mic turned off".
  const wantListeningRef = useRef(false);
  const [listening, setListening] = useState(false);
  const [partial, setPartial] = useState('');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!ctor) return;
    const rec = new ctor();
    rec.continuous = true;
    rec.interimResults = true;
    rec.lang = lang;

    rec.onresult = (e) => {
      setError(null);
      let interim = '';
      for (let i = e.resultIndex; i < e.results.length; i += 1) {
        const r = e.results[i];
        if (r.isFinal) finalCb.current(r[0].transcript.trim());
        else interim += r[0].transcript;
      }
      setPartial(interim);
    };
    rec.onerror = (e) => {
      // eslint-disable-next-line no-console
      console.warn('[speech] recognition error:', e.error, e.message);
      setError(e.error);
      // 'no-speech' fires routinely on silence and isn't fatal; anything else (permission,
      // device, network) means retrying immediately would just spin, so stop trying.
      if (e.error !== 'no-speech') {
        wantListeningRef.current = false;
        setListening(false);
      }
    };
    rec.onend = () => {
      if (wantListeningRef.current) {
        // Browser ended the session on its own (silence timeout) - restart transparently
        // so "continuous" dictation actually feels continuous to the candidate.
        try {
          rec.start();
          return;
        } catch {
          // start() throws if called while already starting; the next onend will retry.
        }
      }
      setListening(false);
    };
    recognitionRef.current = rec;

    return () => {
      wantListeningRef.current = false;
      rec.stop();
    };
  }, [ctor, lang]);

  const start = useCallback(() => {
    if (!recognitionRef.current) return;
    setError(null);
    wantListeningRef.current = true;
    recognitionRef.current.start();
    setListening(true);
  }, []);

  const stop = useCallback(() => {
    wantListeningRef.current = false;
    recognitionRef.current?.stop();
    setListening(false);
  }, []);

  const onFinal = useCallback((cb: (text: string) => void) => {
    finalCb.current = cb;
  }, []);

  return { supported: ctor !== null, listening, partial, error, start, stop, onFinal };
}

export function speak(text: string, lang = 'en-US'): void {
  if (!('speechSynthesis' in window)) return;
  const u = new SpeechSynthesisUtterance(text);
  u.lang = lang;
  window.speechSynthesis.cancel();
  window.speechSynthesis.speak(u);
}
