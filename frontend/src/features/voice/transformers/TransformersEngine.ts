// Local speech-to-text engine: any speech-recognition model that transformers.js can run (today
// Whisper tiny.en; Moonshine and others work too - see features/voice/engine.ts for the choice),
// running entirely in the browser (WebAssembly, in a Web Worker). No audio leaves the device, and
// it works in Brave, Firefox and Safari - unlike the Web Speech API, which depends on a vendor's
// cloud service.
//
// Pipeline:  mic -> AudioWorklet -> resample to 16 kHz -> UtteranceSegmenter (VAD)
//            -> worker (the model) -> 'final' text per spoken phrase.

import {
  isLocalSttSupported,
  type EngineConfig,
  type EngineErrorCode,
  type EngineListener,
  type LocalSpeechEngine,
} from '../engine';
import { Resampler } from '../resample';
import { SAMPLE_RATE, UtteranceSegmenter } from '../segmenter';
import { cleanTranscript } from '../transcript';
import type { WorkerRequest, WorkerResponse } from './worker';

export class TransformersEngine implements LocalSpeechEngine {
  private readonly emit: EngineListener;
  private readonly config: EngineConfig;

  private worker: Worker | null = null;
  private loadPromise: Promise<void> | null = null;
  private ready = false;

  private stream: MediaStream | null = null;
  private ctx: AudioContext | null = null;
  private resampler: Resampler | null = null;
  private resamplerRate = SAMPLE_RATE;
  private readonly segmenter = new UtteranceSegmenter();

  private active = false;
  private nextId = 1;
  private inFlight = 0;
  private disposed = false;

  constructor(listener: EngineListener, config: EngineConfig) {
    this.emit = (e) => {
      if (!this.disposed) listener(e);
    };
    this.config = config;
  }

  // ---- model ----

  load(): Promise<void> {
    if (this.ready) return Promise.resolve();
    this.loadPromise ??= new Promise<void>((resolve, reject) => {
      const worker = this.ensureWorker();
      this.emit({ type: 'loading', progress: 0 });
      const onMessage = (e: MessageEvent<WorkerResponse>) => {
        const msg = e.data;
        if (msg.type === 'progress') this.emit({ type: 'loading', progress: msg.progress });
        else if (msg.type === 'ready') {
          worker.removeEventListener('message', onMessage);
          this.ready = true;
          this.emit({ type: 'ready' });
          resolve();
        } else if (msg.type === 'error' && msg.id === undefined) {
          worker.removeEventListener('message', onMessage);
          this.loadPromise = null; // allow a retry
          reject(new Error(msg.message));
        }
      };
      worker.addEventListener('message', onMessage);
      this.post({
        type: 'load',
        modelId: this.config.modelId,
        revision: this.config.revision,
        modelHost: this.config.modelHost,
        expectedBytes: this.config.expectedBytes,
      });
    });
    return this.loadPromise;
  }

  private ensureWorker(): Worker {
    if (!this.worker) {
      this.worker = new Worker(new URL('./worker.ts', import.meta.url), { type: 'module' });
      this.worker.addEventListener('message', (e: MessageEvent<WorkerResponse>) => this.onWorkerMessage(e.data));
    }
    return this.worker;
  }

  private post(msg: WorkerRequest, transfer: Transferable[] = []): void {
    this.ensureWorker().postMessage(msg, transfer);
  }

  // ---- microphone ----

  async start(): Promise<void> {
    if (this.active) return;
    if (!isLocalSttSupported()) return this.fail('unsupported');

    // Mic first: a permission denial should fail immediately, before any model download.
    let stream: MediaStream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({
        audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
      });
    } catch (err) {
      const name = err instanceof DOMException ? err.name : '';
      return this.fail(name === 'NotAllowedError' || name === 'SecurityError' ? 'not-allowed' : 'audio-capture');
    }

    try {
      await this.load();
    } catch (err) {
      stream.getTracks().forEach((t) => t.stop());
      return this.fail('model-load-failed', err instanceof Error ? err.message : String(err));
    }
    if (this.disposed) {
      stream.getTracks().forEach((t) => t.stop());
      return;
    }

    try {
      // Native sample rate (44.1/48 kHz); we resample ourselves - see resample.ts for why.
      const ctx = new AudioContext();
      await ctx.audioWorklet.addModule('/stt/capture-worklet.js');
      const source = ctx.createMediaStreamSource(stream);
      const node = new AudioWorkletNode(ctx, 'capture-processor', { numberOfInputs: 1, numberOfOutputs: 1 });
      node.port.onmessage = (e: MessageEvent<Float32Array>) => this.pushFrames(e.data, ctx.sampleRate);
      // Routed through a muted gain node to the destination: some browsers only run a worklet
      // that is connected downstream. Gain 0 means no echo of the user's own voice.
      const mute = ctx.createGain();
      mute.gain.value = 0;
      source.connect(node).connect(mute).connect(ctx.destination);

      this.stream = stream;
      this.ctx = ctx;
      this.active = true;
      this.emit({ type: 'listening' });
    } catch (err) {
      stream.getTracks().forEach((t) => t.stop());
      this.fail('audio-capture', err instanceof Error ? err.message : String(err));
    }
  }

  stop(): void {
    if (!this.active && !this.stream) return;
    this.active = false;
    // Transcribe whatever the speaker was in the middle of saying.
    this.handleSegments(this.segmenter.flush());
    this.stream?.getTracks().forEach((t) => t.stop());
    this.stream = null;
    void this.ctx?.close();
    this.ctx = null;
    this.emit({ type: 'stopped' });
  }

  dispose(): void {
    this.stop();
    this.disposed = true;
    this.worker?.terminate();
    this.worker = null;
  }

  // ---- audio in ----

  /**
   * Feed audio at any sample rate. The microphone path calls this; tests and tools can too,
   * which lets the whole segment -> transcribe path be exercised without a microphone.
   */
  pushFrames(samples: Float32Array, sampleRate = SAMPLE_RATE): void {
    if (this.resampler === null || this.resamplerRate !== sampleRate) {
      this.resampler = new Resampler(sampleRate, SAMPLE_RATE);
      this.resamplerRate = sampleRate;
    }
    this.handleSegments(this.segmenter.push(this.resampler.push(samples)));
  }

  private handleSegments(events: ReturnType<UtteranceSegmenter['push']>): void {
    for (const ev of events) {
      if (ev.type === 'speech-start') {
        this.emit({ type: 'speech' });
      } else {
        this.inFlight += 1;
        this.emit({ type: 'transcribing' });
        this.post({ type: 'transcribe', id: this.nextId++, audio: ev.audio }, [ev.audio.buffer]);
      }
    }
  }

  // ---- results ----

  private onWorkerMessage(msg: WorkerResponse): void {
    if (msg.type === 'result') {
      this.inFlight = Math.max(0, this.inFlight - 1);
      const text = cleanTranscript(msg.text);
      if (text) this.emit({ type: 'final', text });
      if (this.active && this.inFlight === 0) this.emit({ type: 'listening' });
    } else if (msg.type === 'error' && msg.id !== undefined) {
      // One utterance failed; keep listening rather than killing the session.
      this.inFlight = Math.max(0, this.inFlight - 1);
      // eslint-disable-next-line no-console
      console.warn('[stt] transcription failed:', msg.message);
      if (this.active && this.inFlight === 0) this.emit({ type: 'listening' });
    }
  }

  private fail(code: EngineErrorCode, message?: string): void {
    this.emit({ type: 'error', code, message });
  }
}
