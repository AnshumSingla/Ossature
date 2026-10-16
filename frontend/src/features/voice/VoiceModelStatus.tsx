import { useSyncExternalStore } from 'react';
import { getModelStatus, getVoiceEngine, subscribeModelStatus } from './shared';
import { isLocalSttSupported } from './engine';

/**
 * A quiet one-line indicator of the on-device voice model, so a visitor who sees the model being
 * prepared in the background knows what it is and when it is done. Renders nothing while idle
 * or when voice isn't supported here.
 */
export function VoiceModelStatus() {
  const status = useSyncExternalStore(subscribeModelStatus, getModelStatus);
  if (!isLocalSttSupported() || status.state === 'idle') return null;

  if (status.state === 'loading') {
    return (
      <p className="voice-model-status" role="status">
        Preparing voice input… {Math.round(status.progress * 100)}% (one-time download, runs on this device)
      </p>
    );
  }
  if (status.state === 'failed') {
    return (
      <p className="voice-model-status" role="status">
        Voice input isn&apos;t ready: the model couldn&apos;t be downloaded.{' '}
        <button type="button" className="link-button" onClick={() => void getVoiceEngine().then((e) => e.load())}>
          Try again
        </button>
      </p>
    );
  }
  return (
    <p className="voice-model-status" role="status">
      Voice input ready - runs on this device.
    </p>
  );
}
