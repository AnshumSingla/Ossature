import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import {
  createVerifiedCache,
  IntegrityError,
  manifestPathFor,
  webCryptoSha256,
  type CacheLike,
  type ModelManifest,
} from './integrity.ts';

const enc = (s: string) => new TextEncoder().encode(s).buffer as ArrayBuffer;

async function manifestOf(files: Record<string, string>): Promise<ModelManifest> {
  const out: Record<string, string> = {};
  for (const [path, content] of Object.entries(files)) out[path] = await webCryptoSha256(enc(content));
  return { modelId: 'org/model', revision: 'abc', files: out };
}

class MemoryCache implements CacheLike {
  store = new Map<string, Response>();
  async match(key: string) {
    return this.store.get(key)?.clone();
  }
  async put(key: string, response: Response) {
    this.store.set(key, response.clone());
  }
  async delete(key: string) {
    return this.store.delete(key);
  }
}

const URL_CONFIG = 'https://host.example/org/model/config.json';
const URL_ONNX = 'https://host.example/org/model/resolve/abc/onnx/encoder.onnx';

test('a file whose hash matches the manifest is stored and served', async () => {
  const manifest = await manifestOf({ 'config.json': '{"ok":true}', 'onnx/encoder.onnx': 'weights' });
  const cache = new MemoryCache();
  const verified = createVerifiedCache(manifest, cache);

  await verified.put(URL_CONFIG, new Response('{"ok":true}'));
  await verified.put(URL_ONNX, new Response('weights'));

  assert.equal(await (await verified.match(URL_CONFIG))!.text(), '{"ok":true}');
  assert.equal(await (await verified.match(URL_ONNX))!.text(), 'weights');
});

test('a tampered download is refused before it is stored', async () => {
  const manifest = await manifestOf({ 'onnx/encoder.onnx': 'weights' });
  const cache = new MemoryCache();
  const verified = createVerifiedCache(manifest, cache);

  await assert.rejects(verified.put(URL_ONNX, new Response('weights-with-a-backdoor')), IntegrityError);
  assert.equal(cache.store.size, 0, 'nothing was stored');
  assert.equal(await verified.match(URL_ONNX), undefined);
});

test('every failed download is recorded, because the library only warns and carries on', async () => {
  const manifest = await manifestOf({ 'config.json': 'good', 'onnx/encoder.onnx': 'weights' });
  const verified = createVerifiedCache(manifest, new MemoryCache());
  assert.deepEqual(verified.violations, []);
  await verified.put(URL_CONFIG, new Response('good'));
  assert.deepEqual(verified.violations, [], 'a good file records nothing');
  await assert.rejects(verified.put(URL_ONNX, new Response('evil')));
  await assert.rejects(verified.put('https://host.example/org/model/unlisted.bin', new Response('x')));
  assert.equal(verified.violations.length, 2);
  assert.match(verified.violations[0], /onnx\/encoder\.onnx/);
});

test('a file that is not in the manifest at all is refused', async () => {
  const manifest = await manifestOf({ 'config.json': 'x' });
  const verified = createVerifiedCache(manifest, new MemoryCache());
  await assert.rejects(verified.put('https://host.example/org/model/extra-payload.js', new Response('alert(1)')), /not in the manifest/);
});

test('a poisoned cache entry is dropped and treated as a miss (so it gets re-downloaded and re-verified)', async () => {
  const manifest = await manifestOf({ 'onnx/encoder.onnx': 'weights' });
  const cache = new MemoryCache();
  cache.store.set(URL_ONNX, new Response('someone edited this'));
  const verified = createVerifiedCache(manifest, cache);

  assert.equal(await verified.match(URL_ONNX), undefined);
  assert.equal(cache.store.has(URL_ONNX), false, 'the bad entry was deleted');
});

test('a miss stays a miss', async () => {
  const verified = createVerifiedCache(await manifestOf({ 'config.json': 'x' }), new MemoryCache());
  assert.equal(await verified.match(URL_CONFIG), undefined);
});

test('a file cannot pass as another file with the same name in a different folder', async () => {
  const manifest = await manifestOf({ 'encoder.onnx': 'a', 'onnx/encoder.onnx': 'b' });
  assert.equal(manifestPathFor(URL_ONNX, manifest), 'onnx/encoder.onnx');
  const verified = createVerifiedCache(manifest, new MemoryCache());
  // content of the top-level file offered under the onnx/ URL
  await assert.rejects(verified.put(URL_ONNX, new Response('a')), IntegrityError);
});

test('the committed manifest covers the pinned model and every file has a well-formed hash', () => {
  const manifest = JSON.parse(readFileSync(new URL('../../../../public/stt/model-manifest.json', import.meta.url), 'utf8')) as ModelManifest;
  assert.equal(manifest.modelId, 'onnx-community/whisper-tiny.en');
  assert.match(manifest.revision, /^[0-9a-f]{40}$/);
  for (const required of ['config.json', 'tokenizer.json', 'onnx/encoder_model_quantized.onnx', 'onnx/decoder_model_merged_quantized.onnx']) {
    assert.ok(manifest.files[required], `${required} missing from the manifest`);
  }
  for (const [path, hash] of Object.entries(manifest.files)) assert.match(hash, /^[0-9a-f]{64}$/, path);
});
