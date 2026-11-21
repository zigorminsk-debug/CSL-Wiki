/**
 * API client.
 *
 * Three modes, selected at runtime:
 *  - mock : bundled demo server (same origin /api/...)
 *  - real : the user's own Confluence, reached via the Node proxy at
 *           PROXY_BASE (same origin), which forwards to the user's server URL
 *           carrying their Basic auth header. Avoids CORS.
 *
 * A "simulate offline" switch (localStorage) lets the UI demonstrate offline
 * behaviour without killing the network.
 */
import { CONFIG } from './config.js';
import { getCredentials } from './auth.js';

const OFFLINE_KEY = 'csl-simulate-offline';
let realMode = localStorage.getItem('csl-mode') === 'real';

export function setRealMode(v) { realMode = !!v; }
export function isRealMode() { return realMode; }

export function isSimulatedOffline() { return localStorage.getItem(OFFLINE_KEY) === '1'; }
export function setSimulatedOffline(v) { localStorage.setItem(OFFLINE_KEY, v ? '1' : '0'); }
export function isOnline() { return navigator.onLine && !isSimulatedOffline(); }

function proxyHeaders() {
  const h = { 'Content-Type': 'application/json' };
  const c = getCredentials();
  if (c && c.username) h.Authorization = 'Basic ' + btoa(`${c.username}:${c.password}`);
  if (c && c.serverUrl) h['X-CSL-Base'] = c.serverUrl.replace(/\/$/, '');
  return h;
}
function directHeaders() {
  const h = { 'Content-Type': 'application/json' };
  if (CONFIG.CONFLUENCE_API_TOKEN) {
    h.Authorization = 'Basic ' + btoa(`${CONFIG.CONFLUENCE_EMAIL}:${CONFIG.CONFLUENCE_API_TOKEN}`);
  }
  return h;
}
function apiPathPrefix() {
  const c = getCredentials();
  return (c && c.apiPath) || CONFIG.DEFAULT_API_PATH;
}

function buildUrl(apiPath) {
  if (!realMode) return '/api' + apiPath;            // mock
  if (CONFIG.USE_PROXY) return CONFIG.PROXY_BASE + apiPath;
  return CONFIG.CONFLUENCE_BASE_URL.replace(/\/$/, '') + apiPath; // direct
}
function buildHeaders() {
  return realMode && CONFIG.USE_PROXY ? proxyHeaders() : (!realMode ? { 'Content-Type': 'application/json' } : directHeaders());
}

async function request(method, apiPath, body) {
  if (!isOnline()) { const e = new Error('offline'); e.offline = true; throw e; }
  const opts = { method, headers: buildHeaders() };
  if (body) opts.body = JSON.stringify(body);
  const res = await fetch(buildUrl(apiPath), opts);
  if (!res.ok) { const e = new Error(`HTTP ${res.status}`); e.status = res.status; throw e; }
  return res.json();
}

/* ---- Mock adapter (demo server) ---- */
const mock = {
  listSpaces: () => request('GET', '/spaces'),
  listPages: (id) => request('GET', `/spaces/${id}/pages`).then((d) => d.results),
  getPage: (id) => request('GET', `/pages/${id}`),
  updatePage: (id, patch) => request('PUT', `/pages/${id}`, patch),
  createPage: (p) => request('POST', '/pages', p),
  deletePage: (id) => request('DELETE', `/pages/${id}`),
  syncSince: (since) => request('GET', `/sync?since=${encodeURIComponent(since || '')}`).then((d) => d.results),
  currentUser: () => request('GET', '/spaces')
};

/* ---- Real Confluence v2 adapter (via proxy) ---- */
const P = () => apiPathPrefix();
const v2 = {
  listSpaces: () => request('GET', `${P()}/spaces?limit=250`).then((d) => (d.results || []).map((s) => ({
    id: s.id, key: s.key, name: s.name, description: s.description?.plain?.value || ''
  }))),
  listPages: (id) => request('GET', `${P()}/spaces/${id}/pages?limit=250`).then((d) => (d.results || []).map((p) => ({
    id: p.id, spaceId: p.spaceId, title: p.title, version: p.version?.number || 1
  }))),
  getPage: (id) => request('GET', `${P()}/pages/${id}?body-format=storage`).then((p) => ({
    id: p.id, spaceId: p.spaceId, title: p.title,
    body: p.body?.storage?.value || '', version: p.version?.number || 1,
    labels: (p.labels?.results || []).map((l) => l.name),
    updatedAt: p.version?.createdAt || p.createdAt
  })),
  updatePage: async (id, patch) => {
    const cur = await request('GET', `${P()}/pages/${id}`);
    const ver = (cur.version?.number || 1) + 1;
    const res = await request('PUT', `${P()}/pages/${id}`, {
      id, spaceId: cur.spaceId, status: 'current',
      title: patch.title ?? cur.title,
      body: { storage: { value: patch.body ?? '', representation: 'storage' } },
      version: { number: ver, message: 'Edited from CSL-Wiki' }
    });
    return { id: res.id, title: res.title, version: res.version?.number || ver };
  },
  createPage: (p) => request('POST', `${P()}/pages`, {
    spaceId: p.spaceId, status: 'current', title: p.title,
    body: { storage: { value: p.body || '', representation: 'storage' } }
  }).then((res) => ({ id: res.id, title: res.title, version: res.version?.number || 1 })),
  deletePage: (id) => request('DELETE', `${P()}/pages/${id}`),
  syncSince: async (since) => {
    let url = `${P()}/pages?limit=250`;
    if (since) url += `&cql=${encodeURIComponent(`lastmodified >= "${since}"`)}`;
    const d = await request('GET', url);
    return (d.results || []).map((p) => ({
      id: p.id, spaceId: p.spaceId, title: p.title,
      body: p.body?.storage?.value || '', version: p.version?.number || 1,
      updatedAt: p.version?.createdAt || p.createdAt
    }));
  },
  currentUser: () => request('GET', `${P()}/user/current`)
};

const A = () => (realMode ? v2 : mock);

export const api = {
  listSpaces: (...a) => A().listSpaces(...a),
  listPages: (...a) => A().listPages(...a),
  getPage: (...a) => A().getPage(...a),
  updatePage: (...a) => A().updatePage(...a),
  createPage: (...a) => A().createPage(...a),
  deletePage: (...a) => A().deletePage(...a),
  syncSince: (...a) => A().syncSince(...a),
  currentUser: (...a) => A().currentUser(...a)
};

/** Validate credentials against the user's server (used by the login screen). */
export async function testConnection() {
  return api.currentUser();
}
