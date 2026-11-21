/**
 * IndexedDB wrapper — the local offline store.
 *
 * Stores:
 *  - pages:    cached wiki pages (id, title, body, spaceId, version, labels,
 *              updatedAt, localModified, dirty, conflict)
 *  - queue:    offline mutations to push when back online
 *  - bookmarks:user bookmarks (page id -> true)
 *  - meta:     small key/value (e.g. lastPull timestamp)
 */

import { getMode, getCredentials } from './auth.js';

function databaseName() {
  const c = getCredentials();
  return getMode() === 'real' && c
    ? 'csl-wiki-account:' + JSON.stringify([c.serverUrl, c.username, c.apiPath])
    : 'csl-wiki-demo';
}
const DB_VERSION = 1;

const databases = new Map();

export function openDB() {
  const name = databaseName();
  if (databases.has(name)) return databases.get(name);
  const promise = new Promise((resolve, reject) => {
    const req = indexedDB.open(name, DB_VERSION);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains('pages')) {
        const s = db.createObjectStore('pages', { keyPath: 'id' });
        s.createIndex('spaceId', 'spaceId', { unique: false });
        s.createIndex('updatedAt', 'updatedAt', { unique: false });
      }
      if (!db.objectStoreNames.contains('queue')) {
        db.createObjectStore('queue', { keyPath: 'id', autoIncrement: true });
      }
      if (!db.objectStoreNames.contains('bookmarks')) {
        db.createObjectStore('bookmarks', { keyPath: 'id' });
      }
      if (!db.objectStoreNames.contains('meta')) {
        db.createObjectStore('meta', { keyPath: 'key' });
      }
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
  databases.set(name, promise);
  promise.catch(() => databases.delete(name));
  return promise;
}

function tx(store, mode) {
  const name = Array.isArray(store) ? store[0] : store;
  return openDB().then((db) => db.transaction(store, mode).objectStore(name));
}

function reqAsync(request) {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

/* --- pages --- */

export async function putPage(page) {
  const store = await tx(['pages'], 'readwrite');
  await reqAsync(store.put(page));
}

export async function putPages(pages) {
  const db = await openDB();
  return new Promise((resolve, reject) => {
    const t = db.transaction('pages', 'readwrite');
    const store = t.objectStore('pages');
    pages.forEach((p) => store.put(p));
    t.oncomplete = () => resolve();
    t.onerror = () => reject(t.error);
  });
}

export async function getPage(id) {
  const store = await tx(['pages'], 'readonly');
  return reqAsync(store.get(id));
}

export async function getAllPages() {
  const store = await tx(['pages'], 'readonly');
  return reqAsync(store.getAll());
}

export async function getPagesBySpace(spaceId) {
  const store = await tx(['pages'], 'readonly');
  const idx = store.index('spaceId');
  return reqAsync(idx.getAll(spaceId));
}

export async function deletePageLocal(id) {
  const store = await tx(['pages'], 'readwrite');
  return reqAsync(store.delete(id));
}

/* --- queue (offline mutations) --- */

export async function enqueue(action) {
  const store = await tx(['queue'], 'readwrite');
  const item = { ...action, ts: Date.now() };
  const id = await reqAsync(store.add(item));
  return id;
}

export async function getQueue() {
  const store = await tx(['queue'], 'readonly');
  const all = await reqAsync(store.getAll());
  return all.sort((a, b) => a.ts - b.ts);
}

export async function removeQueued(id) {
  const store = await tx(['queue'], 'readwrite');
  return reqAsync(store.delete(id));
}

export async function clearQueue() {
  const store = await tx(['queue'], 'readwrite');
  return reqAsync(store.clear());
}

/* --- bookmarks --- */

export async function toggleBookmark(id) {
  const db = await openDB();
  return new Promise((resolve) => {
    const t = db.transaction('bookmarks', 'readwrite');
    const store = t.objectStore('bookmarks');
    const get = store.get(id);
    get.onsuccess = () => {
      if (get.result) {
        store.delete(id);
        t.oncomplete = () => resolve(false);
      } else {
        store.put({ id, ts: Date.now() });
        t.oncomplete = () => resolve(true);
      }
    };
  });
}

export async function getBookmarks() {
  const store = await tx(['bookmarks'], 'readonly');
  const all = await reqAsync(store.getAll());
  return all.map((b) => b.id);
}

/* --- meta --- */

export async function setMeta(key, value) {
  const store = await tx(['meta'], 'readwrite');
  await reqAsync(store.put({ key, value }));
}

export async function getMeta(key) {
  const store = await tx(['meta'], 'readonly');
  const r = await reqAsync(store.get(key));
  return r ? r.value : undefined;
}
