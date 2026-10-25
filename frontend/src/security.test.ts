import assert from 'node:assert/strict';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { test } from 'node:test';
import { securityHeaders } from '../security-headers.mjs';

// C4 / C4b of the security test plan, as regression tests that run on every build.

function sourceFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) return sourceFiles(p);
    return /\.(ts|tsx)$/.test(name) && !name.endsWith('.test.ts') ? [p] : [];
  });
}

const SRC = new URL('.', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');

test('no code path can turn text into markup or code (stored-XSS sinks)', () => {
  const banned: Array<[RegExp, string]> = [
    [/dangerouslySetInnerHTML/, 'dangerouslySetInnerHTML'],
    [/\.innerHTML\s*=/, 'innerHTML assignment'],
    [/\.outerHTML\s*=/, 'outerHTML assignment'],
    [/insertAdjacentHTML/, 'insertAdjacentHTML'],
    [/document\.write/, 'document.write'],
    [/\beval\s*\(/, 'eval()'],
    [/new\s+Function\s*\(/, 'new Function()'],
    [/setTimeout\s*\(\s*['"`]/, 'setTimeout with a string'],
  ];
  const offences: string[] = [];
  for (const file of sourceFiles(SRC)) {
    const text = readFileSync(file, 'utf8');
    for (const [re, label] of banned) {
      if (re.test(text)) offences.push(`${file}: ${label}`);
    }
  }
  assert.deepEqual(offences, []);
});

const html = '<html><script>window.theme = 1;</script></html>';
const headers = securityHeaders({
  html,
  apiBase: 'https://api.example.com',
  wsUrl: 'wss://api.example.com/ws/interview',
  modelHost: 'https://models.example.com',
}) as Record<string, string>;
const csp = headers['Content-Security-Policy'];
const directive = (name: string) => csp.split(';').map((d) => d.trim()).find((d) => d.startsWith(name + ' ')) ?? '';

test('the CSP never allows inline or eval-ed scripts', () => {
  const script = directive('script-src');
  assert.ok(script.includes("'wasm-unsafe-eval'"), 'needed for the on-device speech model');
  assert.ok(!script.includes("'unsafe-inline'"));
  assert.ok(!script.includes("'unsafe-eval'"));
  assert.ok(!/\s\*(\s|$)|https:(\s|$)/.test(script), 'no wildcard / scheme-wide script sources');
  assert.match(script, /'sha256-[A-Za-z0-9+/=]+'/);
});

test('the CSP locks down plugins, framing, base URL and form targets', () => {
  assert.equal(directive('default-src'), "default-src 'self'");
  assert.equal(directive('object-src'), "object-src 'none'");
  assert.equal(directive('frame-ancestors'), "frame-ancestors 'none'");
  assert.equal(directive('base-uri'), "base-uri 'self'");
  assert.equal(directive('form-action'), "form-action 'self'");
});

test('with a self-hosted model, connections go only to our own origins - no audio or text to third parties', () => {
  const connect = directive('connect-src').split(/\s+/).slice(1);
  assert.deepEqual(connect.sort(), ["'self'", 'https://api.example.com', 'https://models.example.com', 'wss://api.example.com'].sort());
  assert.ok(!directive('connect-src').includes('huggingface'));
  assert.ok(!connect.some((c) => c === '*' || c === 'https:' || c === 'http:' || c === 'ws:' || c === 'wss:'));
});

test('the standard hardening headers are all present', () => {
  assert.equal(headers['X-Content-Type-Options'], 'nosniff');
  assert.equal(headers['X-Frame-Options'], 'DENY');
  assert.match(headers['Strict-Transport-Security'], /max-age=\d{7,}/);
  assert.match(headers['Referrer-Policy'], /strict-origin/);
  assert.match(headers['Permissions-Policy'], /microphone=\(self\)/);
  assert.match(headers['Permissions-Policy'], /camera=\(\)/);
});
