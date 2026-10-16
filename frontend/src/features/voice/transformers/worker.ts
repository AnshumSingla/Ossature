// Runs the speech model off the main thread, so a 0.5 s transcription never freezes the
// whiteboard. Talks to TransformersEngine through the small message protocol below.

import { env, pipeline } from '@huggingface/transformers';

export type WorkerRequest =
  | { type: 'load'; modelId: string; revision: string; modelHost: string; expectedBytes: number }
  | { type: 'transcribe'; id: number; audio: Float32Array };

export type WorkerResponse =
  | { type: 'progress'; progress: number }
  | { type: 'ready' }
  | { type: 'result'; id: number; text: string; ms: number }
  | { type: 'error'; id?: number; message: string };

type Asr = (audio: Float32Array) => Promise<{ text: string } | { text: string }[]>;

let asr: Asr | null = null;
let loading: Promise<void> | null = null;
let queue: Promise<void> = Promise.resolve();

const send = (msg: WorkerResponse) => (self as unknown as Worker).postMessage(msg);

async function load(modelId: string, revision: string, modelHost: string, expectedBytes: number): Promise<void> {
  env.allowLocalModels = false;
  // The ONNX runtime's WebAssembly files are served from our own origin (see the
  // `serveOnnxRuntime` plugin in vite.config.ts), not a third-party CDN.
  env.backends.onnx.wasm!.wasmPaths = '/ort/';
  // One thread: measured faster for a model this small, and it avoids needing
  // cross-origin-isolation headers (COOP/COEP) for SharedArrayBuffer.
  env.backends.onnx.wasm!.numThreads = 1;
  if (modelHost) {
    // Self-hosted model files: <modelHost>/<modelId>/... (revision folder not used).
    env.remoteHost = modelHost.endsWith('/') ? modelHost : `${modelHost}/`;
    env.remotePathTemplate = '{model}/';
  }

  // Per-file byte counts -> one overall 0..1 figure. Files are discovered one at a time (the
  // small config files finish before the big model files even start), so progress is measured
  // against the larger of what we know and the expected total, and never goes backwards.
  const files = new Map<string, { loaded: number; total: number }>();
  let best = 0;
  const report = () => {
    let loaded = 0;
    let total = 0;
    for (const f of files.values()) {
      loaded += f.loaded;
      total += f.total;
    }
    if (total === 0) return;
    // Hold at 99% until the model is actually initialised ('ready' is what completes it).
    best = Math.max(best, Math.min(0.99, loaded / Math.max(total, expectedBytes)));
    send({ type: 'progress', progress: best });
  };

  const pipe = await pipeline('automatic-speech-recognition', modelId, {
    dtype: 'q8',
    device: 'wasm',
    revision,
    progress_callback: (p: { status: string; file?: string; loaded?: number; total?: number }) => {
      if (p.status === 'progress' && p.file && p.total) {
        files.set(p.file, { loaded: p.loaded ?? 0, total: p.total });
        report();
      }
    },
  });
  asr = pipe as unknown as Asr;
}

self.onmessage = (e: MessageEvent<WorkerRequest>) => {
  const msg = e.data;

  if (msg.type === 'load') {
    loading ??= load(msg.modelId, msg.revision, msg.modelHost, msg.expectedBytes)
      .then(() => send({ type: 'ready' }))
      .catch((err: unknown) => {
        loading = null; // allow a retry
        send({ type: 'error', message: err instanceof Error ? err.message : String(err) });
      });
    return;
  }

  // Utterances are transcribed strictly in order; a new one waits for the previous.
  queue = queue.then(async () => {
    if (!asr) {
      send({ type: 'error', id: msg.id, message: 'model not loaded' });
      return;
    }
    const t0 = performance.now();
    try {
      const out = await asr(msg.audio);
      const text = (Array.isArray(out) ? out[0]?.text : out.text) ?? '';
      send({ type: 'result', id: msg.id, text, ms: Math.round(performance.now() - t0) });
    } catch (err) {
      send({ type: 'error', id: msg.id, message: err instanceof Error ? err.message : String(err) });
    }
  });
};
