// The contract every local speech-to-text engine implements. The UI only ever talks to this,
// so adding another engine later (Whisper, Vosk...) means writing one adapter class and
// registering it in `createLocalEngine` below - no change to the interview room.

export type EngineErrorCode =
  | 'not-allowed' // microphone permission denied
  | 'audio-capture' // no microphone / device busy
  | 'unsupported' // browser lacks AudioWorklet / WebAssembly / getUserMedia
  | 'model-load-failed'; // model could not be downloaded or initialised

export type EngineEvent =
  /** Model download/initialisation progress, 0..1. */
  | { type: 'loading'; progress: number }
  | { type: 'ready' }
  | { type: 'listening' }
  /** The user started talking (VAD). */
  | { type: 'speech' }
  /** An utterance ended and is being transcribed. */
  | { type: 'transcribing' }
  | { type: 'final'; text: string }
  | { type: 'stopped' }
  | { type: 'error'; code: EngineErrorCode; message?: string };

export interface LocalSpeechEngine {
  /** Download/initialise the model. Safe to call repeatedly. */
  load(): Promise<void>;
  /** Ask for the mic (first, so a denial fails fast and before any download), load, listen. */
  start(): Promise<void>;
  stop(): void;
  dispose(): void;
}

export type EngineListener = (event: EngineEvent) => void;

export interface EngineConfig {
  /** Which adapter to build. Only the transformers.js one exists today. */
  engine: 'transformers';
  modelId: string;
  /** Pinned model revision, so the files we run can't silently change under us. */
  revision: string;
  /** Where model files come from. Empty = the Hugging Face Hub; set to self-host. */
  modelHost: string;
  /**
   * Approximate total download size in bytes. Files are discovered one by one, so measuring
   * progress against what is known so far would jump backwards; this keeps the bar honest.
   */
  expectedBytes: number;
}

export function readEngineConfig(): EngineConfig {
  const env = import.meta.env;
  return {
    engine: 'transformers',
    // Whisper tiny.en (q8, ~41 MB): best accuracy for its size in the 2026-10-12 bake-off
    // (docs/architecture.md). Moonshine ('onnx-community/moonshine-tiny-ONNX', ~28 MB, ~4x
    // faster, less accurate on jargon) also works: set VITE_STT_MODEL_ID + _REVISION.
    modelId: env.VITE_STT_MODEL_ID ?? 'onnx-community/whisper-tiny.en',
    // Pinned commit of that model repo, so the files can't change under us.
    revision: env.VITE_STT_MODEL_REVISION ?? '2575352d61be1bf7225cf8f8b268a4678025fc58',
    modelHost: env.VITE_STT_MODEL_HOST ?? '',
    expectedBytes: Number(env.VITE_STT_MODEL_BYTES ?? 41_000_000),
  };
}

/** True when this browser can run the local engine at all. */
export function isLocalSttSupported(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof WebAssembly === 'object' &&
    typeof Worker !== 'undefined' &&
    typeof AudioContext !== 'undefined' &&
    typeof AudioWorkletNode !== 'undefined' &&
    !!navigator.mediaDevices?.getUserMedia
  );
}

/** The single place that decides which adapter to build. */
export async function createLocalEngine(listener: EngineListener, config = readEngineConfig()): Promise<LocalSpeechEngine> {
  switch (config.engine) {
    case 'transformers': {
      // Loaded lazily so the transformers.js bundle is only fetched when the mic is used.
      const { TransformersEngine } = await import('./transformers/TransformersEngine');
      return new TransformersEngine(listener, config);
    }
  }
}
