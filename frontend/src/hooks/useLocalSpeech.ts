// React binding for the shared on-device speech engine (features/voice/shared.ts).
// Speech recognition is on-device only: no audio ever leaves the browser, and there is no
// browser/cloud fallback. If the browser can't run it, the interview room offers typing.

import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { isLocalSttSupported, type EngineEvent } from '../features/voice/engine';
import {
  getModelStatus,
  getVoiceEngine,
  subscribeModelStatus,
  subscribeVoiceEvents,
} from '../features/voice/shared';

export type VoiceActivity = 'idle' | 'hearing' | 'transcribing';

export interface UseLocalSpeech {
  supported: boolean;
  listening: boolean;
  /** Voice model downloading/initialising (started in the background when the app opened). */
  loading: boolean;
  /** True once the model is in memory and "Start mic" will begin instantly. */
  ready: boolean;
  /** Model download progress, 0..1 (only meaningful while `loading`). */
  progress: number;
  activity: VoiceActivity;
  /** Last error code: 'not-allowed' | 'audio-capture' | 'unsupported' | 'model-load-failed'. */
  error: string | null;
  start: () => void;
  stop: () => void;
  /** Fires once per transcribed phrase. */
  onFinal: (cb: (text: string) => void) => void;
}

export function useLocalSpeechRecognition(): UseLocalSpeech {
  const model = useSyncExternalStore(subscribeModelStatus, getModelStatus);
  const finalCb = useRef<(text: string) => void>(() => {});
  const wantRef = useRef(false);
  const startedRef = useRef(false);

  const [listening, setListening] = useState(false);
  const [activity, setActivity] = useState<VoiceActivity>('idle');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const handle = (e: EngineEvent) => {
      switch (e.type) {
        case 'listening':
          setListening(true);
          setActivity('idle');
          break;
        case 'speech':
          setActivity('hearing');
          break;
        case 'transcribing':
          setActivity('transcribing');
          break;
        case 'final':
          setActivity('idle');
          finalCb.current(e.text);
          break;
        case 'stopped':
          setListening(false);
          setActivity('idle');
          break;
        case 'error':
          wantRef.current = false;
          setError(e.code);
          setListening(false);
          setActivity('idle');
          break;
        default:
          break; // 'loading' / 'ready' are reflected through the model status
      }
    };
    const unsubscribe = subscribeVoiceEvents(handle);
    return () => {
      unsubscribe();
      // Leaving the room stops the microphone, but the engine (and the loaded model) stays alive
      // for the next visit.
      if (startedRef.current) {
        wantRef.current = false;
        void getVoiceEngine().then((engine) => engine.stop());
      }
    };
  }, []);

  const start = useCallback(async () => {
    setError(null);
    wantRef.current = true;
    startedRef.current = true;
    const engine = await getVoiceEngine();
    if (!wantRef.current) return; // stopped while the engine was being created
    await engine.start();
  }, []);

  const stop = useCallback(() => {
    wantRef.current = false;
    setListening(false);
    setError(null); // stopping is the user's "start over": clear a previous failure
    void getVoiceEngine().then((engine) => engine.stop());
  }, []);

  const onFinal = useCallback((cb: (text: string) => void) => {
    finalCb.current = cb;
  }, []);

  return {
    supported: isLocalSttSupported(),
    listening,
    loading: model.state === 'loading',
    ready: model.state === 'ready',
    progress: model.progress,
    activity,
    error,
    start: () => void start(),
    stop,
    onFinal,
  };
}
