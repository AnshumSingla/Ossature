import assert from 'node:assert/strict';
import { test } from 'node:test';
import { correctJargon, phoneticKey } from './jargon.ts';

test('known mishearings of system-design terms are fixed with no whiteboard at all', () => {
  const cases: Array<[string, string]> = [
    ['we cache it in readies', 'we cache it in Redis'],
    ['events go through calf ka', 'events go through Kafka'],
    ['store it in my sequel or post gress', 'store it in MySQL or PostgreSQL'],
    ['a no sequel store like dynamo db', 'a NoSQL store like DynamoDB'],
    ['deployed on cooper netties behind engine x', 'deployed on Kubernetes behind nginx'],
    ['use web sockets and g r p c', 'use WebSockets and gRPC'],
    ['the operation must be item potent', 'the operation must be idempotent'],
    ['put it in s three', 'put it in S3'],
  ];
  for (const [heard, expected] of cases) assert.equal(correctJargon(heard), expected, heard);
});

test('spelled-out acronyms become acronyms', () => {
  assert.equal(correctJargon('we use s q l and a c d n'), 'we use SQL and a CDN');
  assert.equal(correctJargon('over h t t p s'), 'over HTTPS');
});

test('a spoken word that sounds like a label on the board becomes that label', () => {
  const ctx = { labels: ['Redis', 'API Gateway', 'PostgreSQL'] };
  assert.equal(correctJargon('then we ask reddis first', ctx), 'then we ask Redis first');
  assert.equal(correctJargon('the readis cluster', ctx), 'the Redis cluster');
});

test('the same word is left alone when nothing on the board matches it', () => {
  assert.equal(correctJargon('the ready state is fine', { labels: ['Kafka'] }), 'the ready state is fine');
  assert.equal(correctJargon('we are ready now'), 'we are ready now');
});

test('similar-looking ordinary words are not rewritten', () => {
  const ctx = { labels: ['Redis', 'Client', 'Cache', 'Datastore'] };
  const sentence = 'the reader reads data and the red client is ready';
  assert.equal(correctJargon(sentence, ctx), sentence);
});

test('two words that sound like one board label are joined', () => {
  assert.equal(correctJargon('write to the data base', { labels: ['Database'] }), 'write to the Database');
});

test('punctuation, spacing and casing of everything else survive', () => {
  const ctx = { labels: ['Redis'] };
  assert.equal(correctJargon('First,  use readies; then   scale.', ctx), 'First,  use Redis; then   scale.');
  assert.equal(correctJargon('', ctx), '');
  assert.equal(correctJargon('Hello', ctx), 'Hello');
});

test('correcting twice changes nothing more (idempotent)', () => {
  const ctx = { labels: ['Redis', 'Kafka'] };
  const once = correctJargon('readies and calf ka behind engine x, s q l', ctx);
  assert.equal(correctJargon(once, ctx), once);
});

test('generic default labels do not cause rewrites', () => {
  // the whiteboard starts with boxes called Component / Datastore / Client
  const ctx = { labels: ['Component', 'Datastore', 'Client', ''] };
  const s = 'a component talks to the client and the server answers';
  assert.equal(correctJargon(s, ctx), s);
});

test('phoneticKey groups spoken variants together and keeps different words apart', () => {
  assert.equal(phoneticKey('readies'), phoneticKey('Redis'));
  assert.equal(phoneticKey('reddis'), phoneticKey('Redis'));
  assert.notEqual(phoneticKey('ready'), phoneticKey('Redis'));
  assert.notEqual(phoneticKey('Kafka'), phoneticKey('Redis'));
});
