/**
 * CSL-Wiki — lightweight mock Confluence server + static file host.
 *
 * Serves the PWA (public/) and a small REST API that mimics the parts of the
 * Confluence Cloud v2 API the app needs (spaces, pages, sync). It exists so the
 * offline / sync behaviour can be demonstrated without a real Confluence tenant.
 *
 * Connect to a real server from the login screen. PWA requests use the
 * restricted proxy in proxy.js; APK requests use native Capacitor HTTP.
 */

import http from 'node:http';
import { proxy as handleProxy } from './proxy.js';
import { readFile, stat } from 'node:fs/promises';
import { existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { extname, join, normalize, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = join(__dirname, '..');
const PUBLIC_DIR = join(ROOT, 'public');
const PORT = process.env.PORT ? Number(process.env.PORT) : 3000;

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2'
};

/* ------------------------------------------------------------------ */
/* In-memory "Confluence" data store                                  */
/* ------------------------------------------------------------------ */

const now = () => new Date().toISOString();

function seed() {
  const spaces = [
    { id: 'SP-1', key: 'CSL', name: 'CSL Engineering', description: 'Engineering docs for the CSL platform.' },
    { id: 'SP-2', key: 'GUIDE', name: 'User Guides', description: 'End-user documentation and onboarding.' },
    { id: 'SP-3', key: 'OPS', name: 'Operations', description: 'Runbooks, on-call and incident process.' }
  ];

  const pages = [
    {
      id: 'P-1', spaceId: 'SP-1', title: 'Architecture Overview',
      body: 'The CSL platform is built from a set of independent services talking over an internal event bus. The API gateway routes external traffic, the sync service keeps mobile clients consistent, and the search index is rebuilt nightly. Each service owns its own database and exposes a typed REST API.',
      labels: ['architecture', 'overview']
    },
    {
      id: 'P-2', spaceId: 'SP-1', title: 'Offline Sync Design',
      body: 'Mobile clients cache pages in a local store and queue mutations while offline. When connectivity returns the client pushes its queue and pulls pages changed since the last successful pull. Conflicts are detected by comparing server version numbers; the newer version wins and conflicts are surfaced to the user for review.',
      labels: ['offline', 'sync', 'design']
    },
    {
      id: 'P-3', spaceId: 'SP-1', title: 'Release Process',
      body: 'Releases are cut from the main branch every two weeks. CI builds the artifacts, runs integration tests against a staging tenant, and promotes to production behind a feature flag. Rollbacks are one-click and revert the flag plus the last database migration.',
      labels: ['release', 'ci', 'process']
    },
    {
      id: 'P-4', spaceId: 'SP-2', title: 'Getting Started',
      body: 'Welcome to CSL. Create an account, join your team space, and open the mobile app to read pages offline. Use the agent panel to ask questions about any space; it searches cached content and links the source pages.',
      labels: ['onboarding', 'start']
    },
    {
      id: 'P-5', spaceId: 'SP-2', title: 'Mobile App Guide',
      body: 'Install the app from your home screen on Android (Add to Home screen). The app works offline: pages you have opened are available without a connection. Edits and bookmarks are queued and synchronised automatically when you are back online.',
      labels: ['mobile', 'offline', 'guide']
    },
    {
      id: 'P-6', spaceId: 'SP-3', title: 'Incident Runbook',
      body: 'On an incident: declare it in the status page, page the on-call engineer, and open a war room. Capture timelines in the incident page and run post-mortems within 5 business days. The sync service outage runbook lives in the Operations space.',
      labels: ['incident', 'runbook', 'ops']
    }
  ];

  const ts = now();
  return {
    spaces,
    pages: pages.map((p) => ({
      ...p,
      version: 1,
      author: 'system',
      createdAt: ts,
      updatedAt: ts
    }))
  };
}

const db = seed();

/* ------------------------------------------------------------------ */
/* Helpers                                                            */
/* ------------------------------------------------------------------ */

function sendJson(res, status, data) {
  const body = JSON.stringify(data);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
    'Cache-Control': 'no-store'
  });
  res.end(body);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let data = '';
    req.on('data', (c) => (data += c));
    req.on('end', () => {
      if (!data) return resolve({});
      try { resolve(JSON.parse(data)); } catch (e) { reject(e); }
    });
    req.on('error', reject);
  });
}

function publicPage(p) {
  // Shape close enough to Confluence v2 for the client adapter.
  return {
    id: p.id,
    spaceId: p.spaceId,
    title: p.title,
    body: p.body,
    labels: p.labels || [],
    version: p.version,
    author: p.author,
    createdAt: p.createdAt,
    updatedAt: p.updatedAt
  };
}

/* ------------------------------------------------------------------ */
/* API routes                                                         */
/* ------------------------------------------------------------------ */

async function handleApi(req, res, url) {
  const { pathname, searchParams } = url;
  const method = req.method;

  // GET /api/spaces
  if (method === 'GET' && pathname === '/api/spaces') {
    return sendJson(res, 200, db.spaces.map((s) => ({ ...s, pageCount: db.pages.filter((p) => p.spaceId === s.id).length })));
  }

  // GET /api/spaces/:id/pages
  let m = pathname.match(/^\/api\/spaces\/([^/]+)\/pages$/);
  if (method === 'GET' && m) {
    const list = db.pages.filter((p) => p.spaceId === m[1]).map(publicPage);
    return sendJson(res, 200, { results: list, size: list.length });
  }

  // GET /api/sync?since=ISO  -> pages changed since
  if (method === 'GET' && pathname === '/api/sync') {
    const since = searchParams.get('since');
    let list = db.pages;
    if (since) list = list.filter((p) => p.updatedAt > since);
    return sendJson(res, 200, { results: list.map(publicPage), since, now: now() });
  }

  // GET /api/pages/:id
  m = pathname.match(/^\/api\/pages\/([^/]+)$/);
  if (method === 'GET' && m) {
    const p = db.pages.find((x) => x.id === m[1]);
    if (!p) return sendJson(res, 404, { error: 'not_found' });
    return sendJson(res, 200, publicPage(p));
  }

  // PUT /api/pages/:id  (update body -> bump version)
  if (method === 'PUT' && m) {
    const p = db.pages.find((x) => x.id === m[1]);
    if (!p) return sendJson(res, 404, { error: 'not_found' });
    const body = await readBody(req);
    if (typeof body.body === 'string') p.body = body.body;
    if (typeof body.title === 'string') p.title = body.title;
    if (Array.isArray(body.labels)) p.labels = body.labels;
    p.version += 1;
    p.updatedAt = now();
    return sendJson(res, 200, publicPage(p));
  }

  // DELETE /api/pages/:id
  if (method === 'DELETE' && m) {
    const idx = db.pages.findIndex((x) => x.id === m[1]);
    if (idx === -1) return sendJson(res, 404, { error: 'not_found' });
    db.pages.splice(idx, 1);
    return sendJson(res, 200, { ok: true });
  }

  // POST /api/pages  (create)
  if (method === 'POST' && pathname === '/api/pages') {
    const body = await readBody(req);
    const id = 'P-' + (db.pages.length + 1) + '-' + Date.now().toString(36);
    const p = {
      id,
      spaceId: body.spaceId || 'SP-1',
      title: body.title || 'Untitled',
      body: body.body || '',
      labels: body.labels || [],
      version: 1,
      author: 'mobile',
      createdAt: now(),
      updatedAt: now()
    };
    db.pages.push(p);
    return sendJson(res, 201, publicPage(p));
  }

  return sendJson(res, 404, { error: 'unknown_route', path: pathname });
}

/* ------------------------------------------------------------------ */
/* Proxy to a real Confluence server (CORS-safe tunnel)               */
/*                                                                     */
/* The browser/WebView cannot call w.csl.by directly (CORS), so it     */
/* calls /api/csl/<path> on THIS server. We forward the request to     */
/* <X-CSL-Base>/<path> carrying the user's Basic auth header. The      */
/* proxy is same-origin to the app, so the browser is happy.           */
/* ------------------------------------------------------------------ */

/* ------------------------------------------------------------------ */
/* Static hosting                                                     */
/* ------------------------------------------------------------------ */

async function serveStatic(req, res, url) {
  let pathname = decodeURIComponent(url.pathname);
  if (pathname === '/') pathname = '/index.html';

  // Prevent path traversal.
  const safe = normalize(pathname).replace(/^(\.\.[/\\])+/, '');
  let filePath = join(PUBLIC_DIR, safe);
  if (!filePath.startsWith(PUBLIC_DIR)) {
    res.writeHead(403); return res.end('Forbidden');
  }

  try {
    const st = await stat(filePath);
    if (st.isDirectory()) filePath = join(filePath, 'index.html');
    const data = await readFile(filePath);
    const type = MIME[extname(filePath)] || 'application/octet-stream';
    res.writeHead(200, { 'Content-Type': type, 'Cache-Control': 'no-cache' });
    res.end(data);
  } catch (e) {
    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('Not found');
  }
}

/* ------------------------------------------------------------------ */
/* Server                                                             */
/* ------------------------------------------------------------------ */

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, `http://${req.headers.host}`);
    if (url.pathname === '/api/csl' || url.pathname.startsWith('/api/csl/')) return await handleProxy(req, res, url);
    if (url.pathname.startsWith('/api/')) return await handleApi(req, res, url);
    return await serveStatic(req, res, url);
  } catch (err) {
    sendJson(res, 500, { error: 'server_error', message: String(err && err.message || err) });
  }
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`CSL-Wiki server listening on port ${server.address().port}`);
  console.log(`Open it on your Android phone via your computer's LAN IP, or use Chrome DevTools device mode.`);
});

export { server, db };
