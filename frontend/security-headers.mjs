// Single source of truth for the frontend's HTTP security headers (H13).
//
// Used three ways by vite.config.ts: `vite preview` serves them (so they can be tested locally),
// and every build emits them as dist/_headers (Netlify / Cloudflare Pages format) and
// dist/nginx-security-headers.conf, ready to drop into whatever serves the static files.
// They cannot go in the HTML because frame-ancestors and HSTS are ignored in a <meta> tag.

import { createHash } from 'node:crypto';

/** sha256 of the page's single inline <script>, so CSP can allow exactly that script and no other. */
export function inlineScriptHash(html) {
  const inline = [...html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/gi)].map((m) => m[1]);
  if (inline.length !== 1) {
    throw new Error(
      `index.html must contain exactly one inline <script> (the theme bootstrap); found ${inline.length}. ` +
        'Any other inline script would be blocked by the Content-Security-Policy.',
    );
  }
  return `'sha256-${createHash('sha256').update(inline[0]).digest('base64')}'`;
}

function origin(url) {
  try {
    return new URL(url).origin;
  } catch {
    return null;
  }
}

/**
 * @param {{ html: string, apiBase: string, wsUrl: string, modelHost: string }} cfg
 *   apiBase/wsUrl: the backend origins the app talks to (VITE_API_BASE_URL / VITE_WS_URL).
 *   modelHost: VITE_STT_MODEL_HOST - empty means the speech model comes from Hugging Face.
 */
export function securityHeaders({ html, apiBase, wsUrl, modelHost }) {
  const connect = ["'self'"];
  for (const url of [apiBase, wsUrl]) {
    const o = origin(url);
    if (o) connect.push(o);
  }
  if (!modelHost) {
    // Hugging Face serves model files from its own host and redirects large files to CDN hosts.
    connect.push('https://huggingface.co', 'https://*.huggingface.co', 'https://*.hf.co');
  } else if (/^https?:/.test(modelHost)) {
    connect.push(origin(modelHost));
  }

  const csp = [
    "default-src 'self'",
    // 'wasm-unsafe-eval' lets the speech model's WebAssembly compile; it does NOT allow eval().
    `script-src 'self' ${inlineScriptHash(html)} 'wasm-unsafe-eval'`,
    // React sets style="" attributes, so style needs 'unsafe-inline' (styles only, never scripts).
    "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
    "font-src 'self' https://fonts.gstatic.com",
    "img-src 'self' data: blob:",
    `connect-src ${connect.join(' ')}`,
    "worker-src 'self' blob:",
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
    "frame-ancestors 'none'",
  ].join('; ');

  return {
    'Content-Security-Policy': csp,
    'X-Content-Type-Options': 'nosniff',
    'X-Frame-Options': 'DENY',
    'Referrer-Policy': 'strict-origin-when-cross-origin',
    // The interview needs the microphone for this origin only.
    'Permissions-Policy': 'microphone=(self), camera=(), geolocation=(), payment=()',
    'Cross-Origin-Opener-Policy': 'same-origin',
    // Browsers ignore this over plain HTTP, so it is safe to send everywhere.
    'Strict-Transport-Security': 'max-age=31536000; includeSubDomains',
  };
}

export function toNetlifyHeaders(headers) {
  const lines = Object.entries(headers).map(([k, v]) => `  ${k}: ${v}`);
  return `/*\n${lines.join('\n')}\n`;
}

export function toNginxHeaders(headers) {
  const lines = Object.entries(headers).map(([k, v]) => `add_header ${k} "${v.replace(/"/g, '\\"')}" always;`);
  return `# Include inside the server { } block that serves the built frontend.\n${lines.join('\n')}\n`;
}
