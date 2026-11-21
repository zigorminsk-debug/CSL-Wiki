/** Confluence Server/Data Center REST v1 and Cloud v2, plus the Node demo API. */
import { CONFIG } from './config.js';
import { getCredentials, authHeader, isNative } from './auth.js';

const OFFLINE_KEY = 'csl-simulate-offline';
let realMode = localStorage.getItem('csl-mode') === 'real';
export function setRealMode(v) { realMode = !!v; }
export function isRealMode() { return realMode; }
export function isSimulatedOffline() { return localStorage.getItem(OFFLINE_KEY) === '1'; }
export function setSimulatedOffline(v) { localStorage.setItem(OFFLINE_KEY, v ? '1' : '0'); }
export function isOnline() { return navigator.onLine && !isSimulatedOffline(); }

const P = () => getCredentials()?.apiPath || CONFIG.DEFAULT_API_PATH;
const isV2 = () => /\/api\/v2$/.test(P());
const v1Path = () => P().replace(/\/api\/v2$/, '/rest/api');
const direct = () => isNative() || !!getCredentials()?.direct;
const enc = encodeURIComponent;

async function request(method, path, body) {
  if (!isOnline()) { const e = new Error('offline'); e.offline = true; throw e; }
  const c = getCredentials();
  const headers = { Accept: 'application/json' };
  let url = '/api' + path;
  if (realMode) {
    if (!c) throw new Error('Sign in to your Confluence server first.');
    headers.Authorization = authHeader();
    if (direct()) url = c.serverUrl.replace(/\/$/, '') + path;
    else {
      url = CONFIG.PROXY_BASE + path;
      headers['X-CSL-Base'] = c.serverUrl;
    }
  }
  const opts = { method, headers, signal: AbortSignal.timeout(30000) };
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    opts.body = JSON.stringify(body);
  }
  // CapacitorHttp patches fetch in the APK (see capacitor.config.json).
  const res = await fetch(url, opts);
  const text = await res.text();
  if (!res.ok) {
    const e = new Error(`HTTP ${res.status}`);
    e.status = res.status;
    try { e.message = JSON.parse(text).message || e.message; } catch { /* never display upstream HTML */ }
    throw e;
  }
  if (res.status === 204 || !text.trim()) return null;
  if (/^\s*</.test(text) || /text\/html/i.test(res.headers.get('content-type') || '')) {
    throw new Error('Received HTML instead of Confluence JSON. Check the base URL and API path; the server may be redirecting to an SSO login. A bundled APK must use native HTTP, not a relative Node proxy.');
  }
  try { return JSON.parse(text); }
  catch { throw new Error('The server returned invalid JSON. Check the API path and reverse-proxy configuration.'); }
}

// Use numeric pagination for both APIs. Never follow arbitrary upstream next URLs
// with the user's Authorization header.
async function list(path) {
  const results = [];
  const seen = new Set();
  let next = path;
  while (next) {
    if (seen.has(next)) throw new Error('Confluence returned a pagination loop.');
    seen.add(next);
    const d = await request('GET', next);
    if (!Array.isArray(d?.results)) throw new Error('Unexpected Confluence response: missing results array.');
    results.push(...d.results);
    const link = d._links?.next;
    if (!link) break;
    const current = new URL(next, 'https://pagination.invalid');
    const candidate = new URL(link, current);
    if (candidate.pathname !== current.pathname) throw new Error('Unexpected Confluence pagination path.');
    // Copy only pagination parameters, not the host/path from the server.
    const cursor = candidate.searchParams.get('cursor');
    const start = candidate.searchParams.get('start');
    if (cursor !== null) current.searchParams.set('cursor', cursor);
    else if (start !== null) current.searchParams.set('start', start);
    else throw new Error('Unsupported Confluence pagination response.');
    next = current.pathname + current.search;
  }
  return results;
}

const mock = {
  listSpaces: () => request('GET', '/spaces'),
  listPages: (id) => request('GET', `/spaces/${enc(id)}/pages`).then((d) => d.results),
  getPage: (id) => request('GET', `/pages/${enc(id)}`),
  updatePage: (id, patch) => request('PUT', `/pages/${enc(id)}`, patch),
  createPage: (p) => request('POST', '/pages', p),
  deletePage: (id) => request('DELETE', `/pages/${enc(id)}`),
  syncSince: (since) => request('GET', `/sync?since=${enc(since || '')}`).then((d) => d.results),
  currentUser: () => request('GET', '/spaces')
};

function page(p) {
  return {
    id: String(p.id), spaceId: String(p.spaceId ?? p.space?.key ?? ''), title: p.title,
    body: p.body?.storage?.value || '', version: p.version?.number || 1,
    labels: (p.labels?.results || p.metadata?.labels?.results || []).map((l) => l.name),
    updatedAt: p.version?.createdAt || p.version?.when || p.history?.lastUpdated?.when || p.createdAt
  };
}
const expand = 'body.storage,version,space,metadata.labels';
const storage = (value) => ({ storage: { value: value ?? '', representation: 'storage' } });
const real = {
  listSpaces: async () => (await list(isV2() ? `${P()}/spaces?limit=100` : `${P()}/space?limit=100`)).map((s) => ({
    id: String(isV2() ? s.id : s.key), key: s.key, name: s.name,
    description: s.description?.plain?.value || ''
  })),
  listPages: async (id) => (await list(isV2()
    ? `${P()}/spaces/${enc(id)}/pages?limit=100`
    : `${P()}/content?type=page&spaceKey=${enc(id)}&expand=version&limit=100`)).map((p) => ({
      id: String(p.id), spaceId: String(id), title: p.title, version: p.version?.number || 1
    })),
  getPage: async (id) => page(await request('GET', isV2()
    ? `${P()}/pages/${enc(id)}?body-format=storage`
    : `${P()}/content/${enc(id)}?expand=${expand}`)),
  updatePage: async (id, patch) => {
    const cur = await real.getPage(id);
    if (patch.expectedVersion !== undefined && cur.version !== patch.expectedVersion) {
      const err = new Error('Page changed on the server. Resolve the conflict before syncing.');
      err.status = 409;
      throw err;
    }
    const payload = {
      title: patch.title ?? cur.title, body: storage(patch.body ?? cur.body),
      version: { number: cur.version + 1, message: 'Edited from CSL-Wiki' }
    };
    if (isV2()) Object.assign(payload, { id, spaceId: cur.spaceId, status: 'current' });
    else payload.type = 'page';
    return page(await request('PUT', `${P()}/${isV2() ? 'pages' : 'content'}/${enc(id)}`, payload));
  },
  createPage: async (p) => page(await request('POST', `${P()}/${isV2() ? 'pages' : 'content'}`, {
    ...(isV2() ? { spaceId: p.spaceId, status: 'current' } : { type: 'page', space: { key: p.spaceId } }),
    title: p.title, body: storage(p.body)
  })),
  deletePage: (id) => request('DELETE', `${P()}/${isV2() ? 'pages' : 'content'}/${enc(id)}`),
  syncSince: async () => {
    // Both APIs paginate. Fetch storage bodies as well: v2's page list does not
    // accept CQL and otherwise sync would overwrite cached content with blanks.
    const rows = await list(isV2()
      ? `${P()}/pages?limit=100&body-format=storage`
      : `${P()}/content?type=page&limit=100&expand=${expand}`);
    return rows.map(page);
  },
  currentUser: async () => {
    // current user is a REST v1 endpoint, also when pages use Cloud v2.
    const u = await request('GET', `${v1Path()}/user/current`);
    if (!u || u.type === 'anonymous' || !(u.accountId || u.userKey || u.username)) {
      throw new Error('Confluence did not authenticate this user. Check the login/password or API token and whether Basic authentication is allowed (SSO may require a different method).');
    }
    return u;
  }
};
const adapter = () => realMode ? real : mock;
export const api = Object.fromEntries(Object.keys(mock).map((name) => [name, (...args) => adapter()[name](...args)]));
export async function testConnection() { return api.currentUser(); }
