/**
 * API client.
 *
 * Talks to either the bundled mock server (USE_MOCK) or a real Confluence Cloud
 * v2 REST API. A "simulate offline" switch (stored in localStorage) lets the UI
 * demonstrate offline behaviour without actually killing the network.
 */
import { CONFIG } from './config.js';

const OFFLINE_KEY = 'csl-simulate-offline';

export function isSimulatedOffline() {
  return localStorage.getItem(OFFLINE_KEY) === '1';
}
export function setSimulatedOffline(v) {
  localStorage.setItem(OFFLINE_KEY, v ? '1' : '0');
}
export function isOnline() {
  return navigator.onLine && !isSimulatedOffline();
}

function headers() {
  const h = { 'Content-Type': 'application/json' };
  if (!CONFIG.USE_MOCK && CONFIG.CONFLUENCE_API_TOKEN) {
    // Confluence Cloud uses Basic auth with email:token (base64).
    const creds = btoa(`${CONFIG.CONFLUENCE_EMAIL}:${CONFIG.CONFLUENCE_API_TOKEN}`);
    h.Authorization = `Basic ${creds}`;
  }
  return h;
}

async function request(method, path, body) {
  if (!isOnline()) {
    const err = new Error('offline');
    err.offline = true;
    throw err;
  }
  let url;
  if (CONFIG.USE_MOCK) {
    url = path; // same-origin mock
  } else {
    url = CONFIG.CONFLUENCE_BASE_URL.replace(/\/$/, '') + path;
  }
  const opts = { method, headers: headers() };
  if (body) opts.body = JSON.stringify(body);

  // For real Confluence v2 endpoints add body-format.
  if (!CONFIG.USE_MOCK && method === 'GET' && path.includes('/pages/')) {
    url += (url.includes('?') ? '&' : '?') + 'body-format=storage';
  }

  const res = await fetch(url, opts);
  if (!res.ok) {
    const err = new Error(`HTTP ${res.status}`);
    err.status = res.status;
    throw err;
  }
  return res.json();
}

/* ---- Mock adapter (same shape as real adapter) ---- */

async function mockListSpaces() {
  return request('GET', '/api/spaces');
}
async function mockListPages(spaceId) {
  const data = await request('GET', `/api/spaces/${spaceId}/pages`);
  return data.results;
}
async function mockGetPage(id) {
  return request('GET', `/api/pages/${id}`);
}
async function mockUpdatePage(id, patch) {
  return request('PUT', `/api/pages/${id}`, patch);
}
async function mockCreatePage(page) {
  return request('POST', '/api/pages', page);
}
async function mockDeletePage(id) {
  return request('DELETE', `/api/pages/${id}`);
}
async function mockSyncSince(since) {
  const data = await request('GET', `/api/sync?since=${encodeURIComponent(since || '')}`);
  return data.results;
}

/* ---- Real Confluence adapter (v2) ---- */

async function realListSpaces() {
  const data = await request('GET', '/api/v2/spaces?limit=250');
  return data.results.map((s) => ({
    id: s.id, key: s.key, name: s.name,
    description: s.description?.plain?.value || ''
  }));
}
async function realListPages(spaceId) {
  const data = await request('GET', `/api/v2/spaces/${spaceId}/pages?limit=250`);
  return data.results.map((p) => ({ id: p.id, spaceId, title: p.title, version: p.version?.number || 1 }));
}
async function realGetPage(id) {
  const p = await request('GET', `/api/v2/pages/${id}`);
  return {
    id: p.id, spaceId: p.spaceId, title: p.title,
    body: p.body?.storage?.value || '', version: p.version?.number || 1,
    labels: (p.labels?.results || []).map((l) => l.name),
    updatedAt: p.version?.createdAt || p.createdAt
  };
}
async function realUpdatePage(id, patch) {
  // Need current version number to increment.
  const cur = await request('GET', `/api/v2/pages/${id}`);
  const ver = cur.version.number + 1;
  const body = {
    id, spaceId: cur.spaceId, status: 'current', title: patch.title ?? cur.title,
    body: { storage: { value: patch.body ?? '', representation: 'storage' } },
    version: { number: ver, message: 'Edited from CSL-Wiki mobile' }
  };
  const p = await request('PUT', `/api/v2/pages/${id}`, body);
  return { id: p.id, title: p.title, version: p.version.number };
}
async function realCreatePage(page) {
  const body = {
    spaceId: page.spaceId, status: 'current', title: page.title,
    body: { storage: { value: page.body || '', representation: 'storage' } }
  };
  const p = await request('POST', '/api/v2/pages', body);
  return { id: p.id, title: p.title, version: p.version.number };
}
async function realDeletePage(id) {
  return request('DELETE', `/api/v2/pages/${id}`);
}
async function realSyncSince(since) {
  // v2 does not have a simple "changed since" endpoint; use CQL search.
  const cql = since ? `lastmodified >= "${since}"` : '';
  const url = cql
    ? `/api/v2/pages?body-format=storage&limit=250&cql=${encodeURIComponent(cql)}`
    : `/api/v2/pages?body-format=storage&limit=250`;
  const data = await request('GET', url);
  return data.results.map((p) => ({
    id: p.id, spaceId: p.spaceId, title: p.title,
    body: p.body?.storage?.value || '',
    version: p.version?.number || 1,
    updatedAt: p.version?.createdAt || p.createdAt
  }));
}

/* ---- Public facade ---- */

const A = CONFIG.USE_MOCK
  ? { listSpaces: mockListSpaces, listPages: mockListPages, getPage: mockGetPage, updatePage: mockUpdatePage, createPage: mockCreatePage, deletePage: mockDeletePage, syncSince: mockSyncSince }
  : { listSpaces: realListSpaces, listPages: realListPages, getPage: realGetPage, updatePage: realUpdatePage, createPage: realCreatePage, deletePage: realDeletePage, syncSince: realSyncSince };

export const api = {
  listSpaces: (...a) => A.listSpaces(...a),
  listPages: (...a) => A.listPages(...a),
  getPage: (...a) => A.getPage(...a),
  updatePage: (...a) => A.updatePage(...a),
  createPage: (...a) => A.createPage(...a),
  deletePage: (...a) => A.deletePage(...a),
  syncSince: (...a) => A.syncSince(...a)
};
