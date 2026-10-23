// One speech engine for the whole app, created on demand and kept alive across page
// navigations. This is what makes "start downloading the model as soon as the visitor lands"
// useful: the model is loaded into memory on the landing page, so by the time they open an
// interview it is already ready, and the interview room just attaches to the same engine.
//
// The engine itself stays a plain class with a single listener; this module fans its events
// out to any number of React subscribers and remembers the model's load state for late joiners.

import {
  createLocalEngine,
  isLocalSttSupported,
  type EngineEvent,
  type EngineListener,
  type LocalSpeechEngine,
} from './engine';

export type ModelState = 'idle' | 'loading' | 'ready' | 'failed';

export interface ModelStatus {
  state: ModelState;
  /** 0..1 while loading. */
  progress: number;
}

const listeners = new Set<EngineListener>();
let status: ModelStatus = { state: 'idle', progress: 0 };
let statusListeners = new Set<() => void>();
let enginePromise: Promise<LocalSpeechEngine> | null = null;

function setStatus(next: ModelStatus): void {
  status = next;
  statusListeners.forEach((fn) => fn());
}

function broadcast(event: EngineEvent): void {
  if (event.type === 'loading') {
    // Only publish whole-percent changes; the worker reports much more often than that.
    if (status.state !== 'loading' || Math.round(event.progress * 100) !== Math.round(status.progress * 100)) {
      setStatus({ state: 'loading', progress: event.progress });
    }
  } else if (event.type === 'ready') {
    setStatus({ state: 'ready', progress: 1 });
  } else if (event.type === 'error' && event.code === 'model-load-failed') {
    setStatus({ state: 'failed', progress: 0 });
  }
  listeners.forEach((fn) => fn(event));
}

/** Subscribe to engine events (mic state, transcripts, errors). Returns the unsubscribe. */
export function subscribeVoiceEvents(fn: EngineListener): () => void {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

/** For useSyncExternalStore: subscribe to model load-state changes. */
export function subscribeModelStatus(fn: () => void): () => void {
  statusListeners.add(fn);
  return () => {
    statusListeners = new Set([...statusListeners].filter((f) => f !== fn));
  };
}

export function getModelStatus(): ModelStatus {
  return status;
}

/** The shared engine, created on first use. */
export function getVoiceEngine(): Promise<LocalSpeechEngine> {
  enginePromise ??= createLocalEngine(broadcast).catch((err: unknown) => {
    enginePromise = null; // allow a retry
    throw err;
  });
  return enginePromise;
}

function shouldPreload(): boolean {
  if (import.meta.env.VITE_STT_PRELOAD === 'false') return false;
  if (!isLocalSttSupported()) return false;
  // Be considerate of visitors who have asked us not to spend their data. They can still use
  // voice: the model then downloads on demand when they press "Start mic".
  const conn = (navigator as unknown as { connection?: { saveData?: boolean; effectiveType?: string } }).connection;
  if (conn?.saveData) return false;
  if (conn?.effectiveType === 'slow-2g' || conn?.effectiveType === '2g') return false;
  return true;
}

let preloadStarted = false;

/**
 * Begin downloading and initialising the model in the background. Call once when the app
 * starts. It waits for the browser to be idle so it never competes with the first render, does
 * not touch the microphone (no permission prompt), and failures are silent - the user can
 * still start voice later, which retries.
 */
export function preloadVoiceModel(): void {
  if (preloadStarted || !shouldPreload()) return;
  preloadStarted = true;

  const run = () => {
    getVoiceEngine()
      .then((engine) => engine.load())
      .catch(() => {
        // Show it as failed rather than leaving the indicator frozen at "Preparing voice… 98%" (this includes a
        // model file that failed its integrity check).
        setStatus({ state: 'failed', progress: 0 });
        preloadStarted = false; // let a later call (e.g. pressing Start) try again
      });
  };
  if ('requestIdleCallback' in window) window.requestIdleCallback(run, { timeout: 4000 });
  else setTimeout(run, 1500);
}
