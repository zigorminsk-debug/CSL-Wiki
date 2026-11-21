/**
 * Credentials store — the user's wiki server URL, username and password.
 *
 * Stored in localStorage so the session persists across app restarts (the user
 * asked for login by username/password to their own server). On a shared device
 * this is readable by anyone with access; for stronger protection move to a
 * backend token exchange. The password is only ever sent to the proxy as a
 * Basic auth header for the user's own server.
 */
const KEY = 'csl-credentials';
const MODE_KEY = 'csl-mode'; // 'real' | 'mock'

export function getCredentials() {
  try { return JSON.parse(localStorage.getItem(KEY)); } catch { return null; }
}
export function setCredentials(c) {
  localStorage.setItem(KEY, JSON.stringify({
    serverUrl: c.serverUrl,
    username: c.username,
    password: c.password,
    apiPath: c.apiPath || '/wiki/api/v2',
    direct: !!c.direct
  }));
}
export function clearCredentials() { localStorage.removeItem(KEY); }

export function authHeader() {
  const c = getCredentials();
  if (!c || !c.username) return null;
  return 'Basic ' + btoa(`${c.username}:${c.password}`);
}

export function getMode() { return localStorage.getItem(MODE_KEY); }
export function setMode(m) { localStorage.setItem(MODE_KEY, m); }
export function clearMode() { localStorage.removeItem(MODE_KEY); }
