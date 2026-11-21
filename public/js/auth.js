/** Credentials persist on this device. Do not use on an untrusted/shared device. */
const KEY = 'csl-credentials';
const MODE_KEY = 'csl-mode';

export function isNative() {
  return globalThis.Capacitor?.isNativePlatform?.() === true ||
    globalThis.window?.Capacitor?.isNativePlatform?.() === true;
}

export function getCredentials() {
  try { return JSON.parse(localStorage.getItem(KEY)); } catch { return null; }
}

export function setCredentials(c) {
  const url = new URL(c.serverUrl.trim());
  if (url.protocol !== 'https:') throw new Error('Use an HTTPS server URL to protect your password.');
  if (url.username || url.password || url.search || url.hash) {
    throw new Error('Enter the Confluence base URL only, without credentials, query or fragment.');
  }
  const apiPath = (c.apiPath || '/rest/api').trim().replace(/\/+$/, '');
  if (!/^\/(?:[a-zA-Z0-9_-]+\/)*(?:rest\/api|api\/v2)$/.test(apiPath)) {
    throw new Error('API path must end in /rest/api (Server/Data Center) or /api/v2 (Cloud).');
  }
  if (c.username.includes(':')) throw new Error('Username cannot contain a colon.');
  localStorage.setItem(KEY, JSON.stringify({
    serverUrl: url.href.replace(/\/+$/, ''),
    username: c.username,
    password: c.password,
    apiPath,
    direct: !!c.direct
  }));
}
export function clearCredentials() { localStorage.removeItem(KEY); }

export function authHeader() {
  const c = getCredentials();
  if (!c?.username) return null;
  // btoa alone rejects Cyrillic and other Unicode credentials.
  const bytes = new TextEncoder().encode(`${c.username}:${c.password}`);
  return 'Basic ' + btoa(Array.from(bytes, (b) => String.fromCharCode(b)).join(''));
}

export function getMode() { return localStorage.getItem(MODE_KEY); }
export function setMode(m) { localStorage.setItem(MODE_KEY, m); }
export function clearMode() { localStorage.removeItem(MODE_KEY); }
