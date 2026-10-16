export function inlineScriptHash(html: string): string;

export function securityHeaders(cfg: {
  html: string;
  apiBase: string;
  wsUrl: string;
  modelHost: string;
}): Record<string, string>;

export function toNetlifyHeaders(headers: Record<string, string>): string;
export function toNginxHeaders(headers: Record<string, string>): string;
