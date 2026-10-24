// Domain-term correction for on-device speech (A4.11).
//
// A small speech model hears "Redis" as "readies" and "Kafka" as "calf ka". Left alone, the interviewer then
// reasons about a word that was never said. This runs on the finished transcript, entirely on the device, and
// does three conservative things:
//   1. a table of known mishearings of system-design terms ("my sequel" -> MySQL);
//   2. spelled-out acronyms ("s q l" -> SQL, "h t t p" -> HTTP);
//   3. words that SOUND like a label currently on the whiteboard become that label ("readies" -> Redis when
//      there is a Redis box) - the strongest signal there is for what the candidate meant.
// Ordinary speech is left alone: a word is only replaced when it is an exact phonetic match for a known term
// and not already spelled right, and nothing is replaced on a similarity score alone.

/** [canonical term, phrases the recogniser commonly produces instead]. Matched case-insensitively on word boundaries. */
const ALIASES: Array<[string, string[]]> = [
  ['Redis', ['readies', 'reddis', 'readis', 'redus']],
  ['Kafka', ['calf ka', 'cough ka', 'cafka', 'kafca', 'kawfka']],
  ['PostgreSQL', ['post gress', 'post grass', 'postgress', 'post gres', 'postgres sequel', 'postgres ql']],
  ['MySQL', ['my sequel', 'my ess queue ell']],
  ['NoSQL', ['no sequel']],
  ['SQL', ['ess queue ell']],
  ['Kubernetes', ['cooper netties', 'cooper nettys', 'kuber netties', 'kubernetties', 'kubernates', 'cube er netties']],
  ['nginx', ['engine x', 'engine ex']],
  ['DynamoDB', ['dynamo db', 'dynamo d b']],
  ['Memcached', ['mem cached', 'mem cache d', 'memcache d']],
  ['RabbitMQ', ['rabbit m q', 'rabbit mq', 'rabbit em queue']],
  ['gRPC', ['gee rpc', 'g rpc']],
  ['GraphQL', ['graph q l', 'graph ql']],
  ['OAuth', ['o auth', 'oh auth']],
  ['WebSocket', ['web socket']],
  ['WebSockets', ['web sockets']],
  ['Elasticsearch', ['elastic search']],
  ['ZooKeeper', ['zoo keeper']],
  ['HAProxy', ['h a proxy', 'ha proxy']],
  ['idempotent', ['item potent', 'idem potent', 'i dempotent']],
  ['sharding', ['charding']],
  ['S3', ['s three']],
  ['EC2', ['e c two', 'ec two']],
  ['Cassandra', ['cassandra']],
];

/** Which letters to read as one acronym when spelled out: "s q l" -> "SQL". */
const SPELLED = /\b(?:[A-Za-z]\s){2,5}[A-Za-z]\b/g;
const SPELLED_STOP = new Set(['a i', 'i a']);
/** Spelled-out acronyms whose usual written form is not simply upper case. */
const ACRONYM_CASE: Record<string, string> = { GRPC: 'gRPC', OAUTH: 'OAuth', NOSQL: 'NoSQL', MYSQL: 'MySQL', GRAPHQL: 'GraphQL' };

function escapeRe(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

const ALIAS_RULES: Array<[RegExp, string]> = ALIASES.flatMap(([term, aliases]) =>
  aliases
    .filter((a) => a.toLowerCase() !== term.toLowerCase())
    .map((alias): [RegExp, string] => [
      // nosemgrep: javascript.lang.security.audit.detect-non-literal-regexp.detect-non-literal-regexp -- built once from our own constant alias table with every word escaped; never from user input
      new RegExp(`\\b${alias.split(/\s+/).map(escapeRe).join('\\s+')}\\b`, 'gi'),
      term,
    ]),
);

/**
 * A rough phonetic key: words that sound alike get the same key ("readies"/"Redis" -> "rds"). Drops vowels after
 * the first letter, folds common spelling variants, collapses repeats. Deliberately crude - it is only ever used
 * to compare a spoken word with a term that is actually on the board.
 */
export function phoneticKey(word: string): string {
  let w = word.toLowerCase().replace(/[^a-z0-9]/g, '');
  if (!w) return '';
  w = w
    .replace(/^kn/, 'n')
    .replace(/^wr/, 'r')
    .replace(/ph/g, 'f')
    .replace(/ck/g, 'k')
    .replace(/sch|sh|ch/g, 'x')
    .replace(/gh/g, '')
    .replace(/c(?=[eiy])/g, 's')
    .replace(/c/g, 'k')
    .replace(/q/g, 'k')
    .replace(/x/g, 'ks')
    .replace(/z/g, 's')
    .replace(/(.)\1+/g, '$1');
  // keep the first letter whatever it is; drop the vowels (and h/w/y) after it
  const head = w[0];
  const tail = w.slice(1).replace(/[aeiouhwy]/g, '');
  return (head + tail).replace(/(.)\1+/g, '$1');
}

/** Number of vowel groups: "reads" has 1, "Redis"/"readies" have 2. Keeps one-syllable words from matching a two-syllable term. */
function syllables(word: string): number {
  return Math.max(1, (word.toLowerCase().match(/[aeiouy]+/g) ?? []).length);
}

/** Phonetic key plus syllable count - what two words must share to count as "the same sound". */
function soundKey(word: string): string {
  return `${phoneticKey(word)}|${syllables(word)}`;
}

/** The words of a board label that could be spoken terms: skip short or generic ones. */
function labelTerms(labels: string[]): Map<string, string> {
  const terms = new Map<string, string>(); // phonetic key -> the label's own spelling
  for (const label of labels) {
    for (const word of label.split(/[^A-Za-z0-9]+/)) {
      if (word.length < 4 && !/\d/.test(word)) continue;
      const key = soundKey(word);
      if (phoneticKey(word).length >= 3 && !terms.has(key)) terms.set(key, word);
    }
  }
  return terms;
}

export interface CorrectionContext {
  /** Text of the labels currently on the whiteboard (boxes and arrows). */
  labels?: string[];
}

export function correctJargon(text: string, ctx: CorrectionContext = {}): string {
  let out = text;

  for (const [re, term] of ALIAS_RULES) out = out.replace(re, term);

  out = out.replace(SPELLED, (m) => {
    let letters = m.split(/\s+/);
    if (SPELLED_STOP.has(letters.join(' ').toLowerCase())) return m;
    // "a c d n": with four or more letters a leading "a" / "I" is far more likely the word than a letter
    let lead = '';
    if (letters.length >= 4 && /^[aAiI]$/.test(letters[0])) {
      lead = letters[0] + ' ';
      letters = letters.slice(1);
    }
    const joined = letters.join('').toUpperCase();
    return lead + (ACRONYM_CASE[joined] ?? joined);
  });

  const terms = labelTerms(ctx.labels ?? []);
  if (terms.size === 0) return out;

  // Words and the gaps between them, so spacing and punctuation survive untouched.
  const parts = out.split(/([A-Za-z0-9]+)/); // odd indexes are words
  for (let i = 1; i < parts.length; i += 2) {
    const word = parts[i];
    if (word.length < 4) continue;

    // two spoken words that sound like one term ("data base" -> Database)
    const gap = parts[i + 1];
    const next = parts[i + 2];
    if (next && /^\s+$/.test(gap ?? '') && next.length >= 2) {
      const joined = terms.get(soundKey(word + next));
      if (joined && word[0].toLowerCase() === joined[0].toLowerCase()) {
        parts[i] = joined;
        parts[i + 1] = '';
        parts[i + 2] = '';
        continue;
      }
    }

    const term = terms.get(soundKey(word));
    if (term && word.toLowerCase() !== term.toLowerCase() && word[0].toLowerCase() === term[0].toLowerCase()
        && Math.abs(word.length - term.length) <= 3) {
      parts[i] = term;
    }
  }
  return parts.join('');
}
