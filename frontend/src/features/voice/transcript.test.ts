import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { cleanTranscript } from './transcript.ts';

describe('cleanTranscript', () => {
  it('keeps normal speech, trimming and collapsing whitespace', () => {
    assert.equal(cleanTranscript('  We shard   by user id. '), 'We shard by user id.');
  });

  it('drops Whisper non-speech markers', () => {
    assert.equal(cleanTranscript('[BLANK_AUDIO]'), '');
    assert.equal(cleanTranscript(' (silence) '), '');
    assert.equal(cleanTranscript('[MUSIC]'), '');
    assert.equal(cleanTranscript('♪ ♪'), '');
  });

  it('removes a marker but keeps the speech around it', () => {
    assert.equal(cleanTranscript('We use Redis [BLANK_AUDIO] for caching.'), 'We use Redis for caching.');
    assert.equal(cleanTranscript('(coughs) so the API gateway routes it'), 'so the API gateway routes it');
  });

  it('drops output with no letters or digits', () => {
    assert.equal(cleanTranscript('...'), '');
    assert.equal(cleanTranscript('-'), '');
    assert.equal(cleanTranscript(''), '');
  });

  it('keeps numbers and non-Latin text', () => {
    assert.equal(cleanTranscript('62'), '62');
    assert.equal(cleanTranscript('bonjour le monde'), 'bonjour le monde');
  });
});
