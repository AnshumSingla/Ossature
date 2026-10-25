// Writes public/stt/model-manifest.json: the SHA-256 of every file of the pinned speech-model revision.
//
//   node scripts/make-model-manifest.mjs [modelId] [revision]
//
// The browser refuses any model file whose hash is not in this manifest (src/features/voice/transformers/integrity.ts),
// whether it came from Hugging Face or from our own host, so a tampered or substituted file cannot be loaded. Run it
// again whenever VITE_STT_MODEL_ID / VITE_STT_MODEL_REVISION change, and commit the result.
//
// Large (LFS) files: the hash is the one Hugging Face publishes for that exact revision, AND it is re-checked by
// downloading the file once. Small files are downloaded and hashed here.

import { createHash } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const modelId = process.argv[2] ?? 'onnx-community/whisper-tiny.en';
const revision = process.argv[3] ?? '2575352d61be1bf7225cf8f8b268a4678025fc58';
const only = /^(?:[^/]+\.(?:json|txt)|onnx\/[^/]*quantized[^/]*\.onnx)$/; // what the q8 pipeline can ask for

const base = 'https://huggingface.co';
const tree = await (await fetch(`${base}/api/models/${modelId}/tree/${revision}?recursive=true`)).json();
if (!Array.isArray(tree)) throw new Error(`Unexpected listing for ${modelId}@${revision}: ${JSON.stringify(tree).slice(0, 200)}`);

const files = {};
for (const entry of tree.filter((e) => e.type === 'file' && only.test(e.path)).sort((a, b) => a.path.localeCompare(b.path))) {
  const res = await fetch(`${base}/${modelId}/resolve/${revision}/${entry.path}`);
  if (!res.ok) throw new Error(`${entry.path}: HTTP ${res.status}`);
  const hash = createHash('sha256').update(Buffer.from(await res.arrayBuffer())).digest('hex');
  const published = entry.lfs?.oid;
  if (published && published !== hash) throw new Error(`${entry.path}: downloaded hash ${hash} != published ${published}`);
  files[entry.path] = hash;
  console.log(`${hash}  ${entry.path}${published ? '  (matches the published LFS hash)' : ''}`);
}

const out = join(dirname(fileURLToPath(import.meta.url)), '..', 'public', 'stt', 'model-manifest.json');
mkdirSync(dirname(out), { recursive: true });
writeFileSync(out, JSON.stringify({ modelId, revision, files }, null, 2) + '\n');
console.log(`\nwrote ${out} (${Object.keys(files).length} files)`);
