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
interface SpeechRecognitionLike {
  continuous: boolean;
  interimResults: boolean;
  lang: string;
  start(): void;
  stop(): void;
  onresult: ((e: SpeechRecognitionEventLike) => void) | null;
  onend: (() => void) | null;
  onerror: ((e: unknown) => void) | null;
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
  start: () => void;
  stop: () => void;
  /** Fires once per finalized utterance. */
  onFinal: (cb: (text: string) => void) => void;
}

export function useSpeechRecognition(lang = 'en-US'): UseSpeechRecognition {
  const ctor = getRecognitionCtor();
  const recognitionRef = useRef<SpeechRecognitionLike | null>(null);
  const finalCb = useRef<(text: string) => void>(() => {});
  const [listening, setListening] = useState(false);
  const [partial, setPartial] = useState('');

  useEffect(() => {
    if (!ctor) return;
    const rec = new ctor();
    rec.continuous = true;
    rec.interimResults = true;
    rec.lang = lang;

    rec.onresult = (e) => {
      let interim = '';
      for (let i = e.resultIndex; i < e.results.length; i += 1) {
        const r = e.results[i];
        if (r.isFinal) finalCb.current(r[0].transcript.trim());
        else interim += r[0].transcript;
      }
      setPartial(interim);
    };
    rec.onend = () => setListening(false);
    rec.onerror = () => setListening(false);
    recognitionRef.current = rec;

    return () => rec.stop();
  }, [ctor, lang]);

  const start = useCallback(() => {
    if (!recognitionRef.current) return;
    recognitionRef.current.start();
    setListening(true);
  }, []);

  const stop = useCallback(() => {
    recognitionRef.current?.stop();
    setListening(false);
  }, []);

  const onFinal = useCallback((cb: (text: string) => void) => {
    finalCb.current = cb;
  }, []);

  return { supported: ctor !== null, listening, partial, start, stop, onFinal };
}

export function speak(text: string, lang = 'en-US'): void {
  if (!('speechSynthesis' in window)) return;
  const u = new SpeechSynthesisUtterance(text);
  u.lang = lang;
  window.speechSynthesis.cancel();
  window.speechSynthesis.speak(u);
}
