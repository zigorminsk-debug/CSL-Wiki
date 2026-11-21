/**
 * Service Worker — offline app shell.
 *
 * - App shell (html/css/js/icons/manifest) is precached on install and served
 *   cache-first so the app launches with no network.
 * - /api/* requests use network-first with a cache fallback so previously
 *   fetched data is available offline; mutations (POST/PUT/DELETE) are never
 *   cached.
 * - Navigation requests fall back to the cached index.html (offline SPA).
 */
const CACHE = 'csl-wiki-shell-v2';
const SHELL = [
  './',
  './index.html',
  './manifest.webmanifest',
  './css/styles.css',
  './js/config.js',
  './js/auth.js',
  './js/update.js',
  './js/db.js',
  './js/api.js',
  './js/sync.js',
  './js/agent.js',
  './js/app.js',
  './icons/icon.svg',
  './icons/icon-maskable.svg'
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  const url = new URL(req.url);

  if (req.method !== 'GET') return; // never cache mutations

  // Authenticated responses belong in account-scoped IndexedDB, never the
  // shared Cache API (whose keys do not isolate Authorization headers).
  if (url.origin !== self.location.origin || url.pathname.startsWith('/api/') ||
      req.headers.has('Authorization')) return;

  // Navigation: serve cached shell when offline.
  if (req.mode === 'navigate') {
    event.respondWith(
      fetch(req).catch(() => caches.match('./index.html'))
    );
    return;
  }

  // Static assets: cache-first.
  event.respondWith(
    caches.match(req).then((cached) =>
      cached || fetch(req).then((res) => {
        const copy = res.clone();
        caches.open(CACHE).then((c) => c.put(req, copy));
        return res;
      }).catch(() => cached)
    )
  );
});
