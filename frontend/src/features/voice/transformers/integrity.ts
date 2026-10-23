// Integrity check for the on-device speech model (A4.4).
//
// The model is ~40 MB of files that get executed as a neural network in the user's browser. A swapped or tampered
// file (a compromised CDN or file host, a poisoned cache) could make the interviewer hear things that were never
// said. So every file must match a SHA-256 in model-manifest.json, which ships with the app, is generated from the
// pinned model revision (scripts/make-model-manifest.mjs) and is checked:
//   - when a file is downloaded, BEFORE it is stored or used;
//   - when a file is read back from the browser cache (a poisoned cache entry is deleted and re-downloaded);
//   - and any file that is not in the manifest at all is refused.
// Plugs into transformers.js as `env.customCache`, which only needs `match` and `put`.

export interface ModelManifest {
  modelId: string;
  revision: string;
  files: Record<string, string>; // path inside the model -> lowercase hex sha256
}

/** The slice of the Web Cache API that transformers.js (and this wrapper) use. */
export interface CacheLike {
  match(key: string): Promise<Response | undefined>;
  put(key: string, response: Response): Promise<void>;
  delete(key: string): Promise<boolean>;
}

export class IntegrityError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'IntegrityError';
  }
}

export type Sha256Hex = (data: ArrayBuffer) => Promise<string>;

export const webCryptoSha256: Sha256Hex = async (data) => {
  const digest = await crypto.subtle.digest('SHA-256', data);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
};

/** The manifest path a request URL refers to ("…/whisper-tiny.en/resolve/<rev>/onnx/x.onnx" -> "onnx/x.onnx"), or null. */
export function manifestPathFor(url: string, manifest: ModelManifest): string | null {
  let pathname: string;
  try {
    pathname = decodeURIComponent(new URL(url, 'https://placeholder.invalid').pathname);
  } catch {
    return null;
  }
  // The longest manifest path that the URL ends with, so "onnx/a.onnx" is not mistaken for "a.onnx".
  let best: string | null = null;
  for (const path of Object.keys(manifest.files)) {
    if (pathname.endsWith('/' + path) && (best === null || path.length > best.length)) best = path;
  }
  return best;
}

export interface VerifiedCache extends CacheLike {
  /**
   * Every file that failed verification when it was downloaded. transformers.js catches an error thrown from `put`
   * and only logs a warning - it would still USE the file - so the caller must check this list after loading and
   * refuse the model if it is not empty.
   */
  readonly violations: string[];
}

export function createVerifiedCache(manifest: ModelManifest, underlying: CacheLike, sha256: Sha256Hex = webCryptoSha256): VerifiedCache {
  const violations: string[] = [];

  async function check(url: string, body: ArrayBuffer): Promise<void> {
    const path = manifestPathFor(url, manifest);
    if (path === null) {
      throw new IntegrityError(`Refusing a model file that is not in the manifest: ${url}`);
    }
    const actual = await sha256(body);
    if (actual !== manifest.files[path]) {
      throw new IntegrityError(`Model file ${path} does not match its expected checksum (got ${actual.slice(0, 12)}…)`);
    }
  }

  return {
    violations,

    async match(key) {
      const cached = await underlying.match(key);
      if (!cached) return undefined;
      try {
        await check(key, await cached.clone().arrayBuffer());
        return cached;
      } catch {
        // A stored file that no longer verifies is dropped, so the normal path downloads (and verifies) it again.
        await underlying.delete(key);
        return undefined;
      }
    },

    async put(key, response) {
      const body = await response.clone().arrayBuffer();
      try {
        await check(key, body);
      } catch (e) {
        violations.push(e instanceof Error ? e.message : String(e));
        throw e; // never stored (the caller may still hold the bytes - hence `violations`)
      }
      await underlying.put(key, new Response(body, { status: 200, headers: response.headers }));
    },

    delete: (key) => underlying.delete(key),
  };
}
