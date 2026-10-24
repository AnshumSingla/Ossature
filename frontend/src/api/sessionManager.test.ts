import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { createSessionManager, type SessionInfo } from './sessionManager.ts';

const session = (token: string, expiresInSeconds = 900): SessionInfo => ({
  token,
  expiresInSeconds,
  userId: 'u1',
  email: 'a@x.com',
  displayName: 'A',
  isOwner: false,
});

interface Call {
  url: string;
  init: RequestInit;
}

/** A manager with a scripted fetch and manually-fired timers. */
function setup(responses: Array<() => Response | Promise<Response>>) {
  const calls: Call[] = [];
  const timers: Array<{ fn: () => void; ms: number; cleared: boolean }> = [];
  let i = 0;
  const manager = createSessionManager({
    baseUrl: 'http://api',
    fetchFn: (async (input: string, init: RequestInit) => {
      calls.push({ url: String(input), init });
      const next = responses[Math.min(i++, responses.length - 1)];
      return next();
    }) as unknown as typeof fetch,
    setTimer: (fn, ms) => {
      const t = { fn, ms, cleared: false };
      timers.push(t);
      return t;
    },
    clearTimer: (h) => {
      (h as { cleared: boolean }).cleared = true;
    },
  });
  const live = () => timers.filter((t) => !t.cleared);
  return { manager, calls, timers, live };
}

const ok = (body: unknown) => () => new Response(JSON.stringify(body), { status: 200 });
const status = (code: number) => () => new Response(null, { status: code });

test('the access token is held in memory and a refresh is planned at 80% of its life', () => {
  const { manager, live } = setup([]);
  manager.adopt(session('t1', 900));
  assert.equal(manager.getToken(), 't1');
  assert.equal(live().length, 1);
  assert.equal(live()[0].ms, 720_000);
});

test('refreshing sends the cookie and the client header, adopts the new token and re-plans', async () => {
  const { manager, calls, live } = setup([ok(session('t2'))]);
  const seen: Array<SessionInfo | null> = [];
  manager.onChange((s) => seen.push(s));

  const outcome = await manager.refresh();

  assert.equal(outcome.kind, 'ok');
  assert.equal(manager.getToken(), 't2');
  assert.equal(calls[0].url, 'http://api/api/auth/refresh');
  assert.equal(calls[0].init.method, 'POST');
  assert.equal(calls[0].init.credentials, 'include');
  assert.deepEqual(calls[0].init.headers, { 'X-Ossature-Client': 'web' });
  assert.equal(live().length, 1);
  assert.equal(seen.length, 1);
  assert.equal(seen[0]?.token, 't2');
});

test('simultaneous refreshes share one request', async () => {
  const { manager, calls } = setup([ok(session('t2'))]);
  const [a, b, c] = await Promise.all([manager.refresh(), manager.refresh(), manager.refresh()]);
  assert.equal(calls.length, 1);
  assert.deepEqual([a.kind, b.kind, c.kind], ['ok', 'ok', 'ok']);
});

test('a refused refresh ends the session: token forgotten, timer cancelled, listeners told', async () => {
  const { manager, live } = setup([status(401)]);
  manager.adopt(session('t1'));
  const seen: Array<SessionInfo | null> = [];
  manager.onChange((s) => seen.push(s));

  const outcome = await manager.refresh();

  assert.equal(outcome.kind, 'ended');
  assert.equal(manager.getToken(), null);
  assert.equal(live().length, 0);
  assert.deepEqual(seen, [null]);
});

test('a network failure keeps the session and retries soon, with backoff', async () => {
  const { manager, live } = setup([
    () => {
      throw new TypeError('offline');
    },
  ]);
  manager.adopt(session('t1'));

  assert.equal((await manager.refresh()).kind, 'unreachable');
  assert.equal(manager.getToken(), 't1', 'the access token is kept - the login may still be valid');
  assert.equal(live().length, 1);
  assert.equal(live()[0].ms, 5_000);

  assert.equal((await manager.refresh()).kind, 'unreachable');
  assert.equal(live()[0].ms, 15_000);
});

test('a server error is not treated as the login being over', async () => {
  const { manager } = setup([status(503)]);
  manager.adopt(session('t1'));
  assert.equal((await manager.refresh()).kind, 'unreachable');
  assert.equal(manager.getToken(), 't1');
});

test('authFetch adds the bearer token', async () => {
  const { manager, calls } = setup([status(200)]);
  manager.adopt(session('t1'));
  await manager.authFetch('/api/sessions', { headers: { 'Content-Type': 'application/json' } });
  assert.deepEqual(calls[0].init.headers, { 'Content-Type': 'application/json', Authorization: 'Bearer t1' });
});

test('on a 401 authFetch refreshes once and retries with the new token', async () => {
  const { manager, calls } = setup([status(401), ok(session('t2')), status(200)]);
  manager.adopt(session('expired'));

  const res = await manager.authFetch('/api/sessions');

  assert.equal(res.status, 200);
  assert.deepEqual(
    calls.map((c) => c.url),
    ['http://api/api/sessions', 'http://api/api/auth/refresh', 'http://api/api/sessions'],
  );
  assert.equal((calls[2].init.headers as Record<string, string>).Authorization, 'Bearer t2');
});

test('a page reload (no token yet) still works: the first 401 triggers the refresh', async () => {
  const { manager, calls } = setup([status(401), ok(session('fresh')), status(200)]);
  const res = await manager.authFetch('/api/questions');
  assert.equal(res.status, 200);
  assert.equal((calls[0].init.headers as Record<string, string>).Authorization, undefined);
  assert.equal((calls[2].init.headers as Record<string, string>).Authorization, 'Bearer fresh');
});

test('if the refresh fails too, the caller sees the 401 and the session is over', async () => {
  const { manager, calls } = setup([status(401), status(401)]);
  manager.adopt(session('expired'));
  const res = await manager.authFetch('/api/sessions');
  assert.equal(res.status, 401);
  assert.equal(calls.length, 2, 'no retry loop');
  assert.equal(manager.getToken(), null);
});

test('a 401 from the auth endpoints themselves (wrong password) never triggers a refresh', async () => {
  const { manager, calls } = setup([status(401)]);
  const res = await manager.authFetch('/api/auth/login', { method: 'POST' });
  assert.equal(res.status, 401);
  assert.equal(calls.length, 1);
});

test('logout tells the server, sends the cookie, and forgets the token', async () => {
  const { manager, calls, live } = setup([status(204)]);
  manager.adopt(session('t1'));
  await manager.logout();
  assert.equal(calls[0].url, 'http://api/api/auth/logout');
  assert.equal(calls[0].init.credentials, 'include');
  assert.deepEqual(calls[0].init.headers, { 'X-Ossature-Client': 'web' });
  assert.equal(manager.getToken(), null);
  assert.equal(live().length, 0);
});

test('logout still signs out locally when the server cannot be reached', async () => {
  const { manager } = setup([
    () => {
      throw new TypeError('offline');
    },
  ]);
  manager.adopt(session('t1'));
  await manager.logout();
  assert.equal(manager.getToken(), null);
});

test('the scheduled refresh fires and renews the token', async () => {
  const { manager, timers } = setup([ok(session('t2'))]);
  manager.adopt(session('t1'));
  timers[0].fn();
  await manager.refresh(); // joins the refresh the timer started
  assert.equal(manager.getToken(), 't2');
});

// Guard: the whole point of H10 is that no credential is persisted for a script to read.
test('the app never writes the access token to browser storage', () => {
  for (const file of ['./auth.tsx', './session.ts', './sessionManager.ts', './rest.ts']) {
    const src = readFileSync(new URL(file, import.meta.url), 'utf8')
      .split('\n')
      .filter((l) => !l.trim().startsWith('//') && !l.trim().startsWith('*'))
      .join('\n');
    assert.doesNotMatch(src, /JWT_KEY|ossature\.jwt/, `${file} must not persist a token`);
    assert.doesNotMatch(src, /(local|session)Storage\.setItem\([^)]*token/i, `${file} must not store a token`);
  }
});
