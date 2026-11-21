/**
 * Sync engine.
 *
 * Pull:  fetch pages changed on the server since the last successful pull and
 *         merge them into the local store (server version wins unless a page is
 *         locally dirty and newer — then it is flagged as a conflict).
 * Push:   replay the offline queue (edits / creates / deletes) against the
 *         server; remove succeeded items; keep failed ones for the next run.
 *
 * Triggered automatically on connectivity return, on app start, and manually.
 */
import * as db from './db.js';
import { api, isOnline } from './api.js';
import { getMode } from './auth.js';

const LAST_PULL_KEY = 'lastPull';

let _syncing = false;
const _listeners = new Set();

export function onSyncStatus(fn) { _listeners.add(fn); return () => _listeners.delete(fn); }
function emit(status) { _listeners.forEach((fn) => fn(status)); }

export function isSyncing() { return _syncing; }

/**
 * status shape: { state: 'idle'|'syncing'|'done'|'error', pushed, pulled,
 *                 queued, conflicts, error, at }
 */
function status(state, extra = {}) {
  const s = { state, at: Date.now(), ...extra };
  emit(s);
  return s;
}

export async function syncNow() {
  if (_syncing) return status('idle', { skipped: true });
  if (!isOnline()) return status('error', { error: 'offline', queued: await db.getQueue().then((q) => q.length) });

  _syncing = true;
  status('syncing');
  try {
    const pulled = await pull();
    const pushed = await push();
    const conflicts = await db.getAllPages().then((ps) => ps.filter((p) => p.conflict).length);
    const queued = (await db.getQueue()).length;
    _syncing = false;
    return status('done', { pulled, pushed, conflicts, queued });
  } catch (e) {
    _syncing = false;
    return status('error', { error: String(e && e.message || e) });
  }
}

async function pull() {
  const since = (await db.getMeta(LAST_PULL_KEY)) || '';
  const startedAt = new Date().toISOString();
  const remote = await api.syncSince(since);
  if (!remote || !remote.length) {
    await db.setMeta(LAST_PULL_KEY, startedAt);
    return 0;
  }

  const local = await db.getAllPages();
  const localById = new Map(local.map((p) => [p.id, p]));

  let conflicts = 0;
  for (const r of remote) {
    const existing = localById.get(r.id);
    if (existing && existing.dirty) {
      // Local edit is newer / equal — keep local, mark conflict for user.
      existing.conflict = existing.conflict || r.version > existing.version;
      await db.putPage(existing);
      conflicts++;
      continue;
    }
    await db.putPage({
      id: r.id,
      spaceId: r.spaceId,
      title: r.title,
      body: r.body,
      labels: r.labels || [],
      version: r.version,
      updatedAt: r.updatedAt,
      localModified: false,
      dirty: false,
      conflict: false
    });
  }
  await db.setMeta(LAST_PULL_KEY, startedAt);
  return remote.length;
}

async function push() {
  const queue = await db.getQueue();
  let pushed = 0;
  for (const item of queue) {
    try {
      if (item.op === 'update') {
        const pending = await db.getPage(item.pageId);
        if (pending?.conflict) throw new Error('Page changed on the server. Resolve the conflict before syncing.');
        const res = await api.updatePage(item.pageId, { title: item.title, body: item.body, labels: item.labels, expectedVersion: pending?.version });
        // update local copy version + clear dirty
        const local = await db.getPage(item.pageId);
        if (local) {
          local.version = res.version ?? local.version;
          local.dirty = false;
          local.localModified = false;
          local.conflict = false;
          local.updatedAt = new Date().toISOString();
          await db.putPage(local);
        }
      } else if (item.op === 'create') {
        const res = await api.createPage({ spaceId: item.spaceId, title: item.title, body: item.body, labels: item.labels });
        // Remove the local-only placeholder and store the server-assigned page.
        await db.deletePageLocal(item.pageId);
        await db.putPage({
          id: res.id, spaceId: item.spaceId, title: res.title, body: item.body,
          labels: item.labels || [], version: res.version || 1,
          updatedAt: new Date().toISOString(), localModified: false, dirty: false, conflict: false
        });
      } else if (item.op === 'delete') {
        await api.deletePage(item.pageId);
        await db.deletePageLocal(item.pageId);
      }
      await db.removeQueued(item.id);
      pushed++;
    } catch (e) {
      // leave in queue; will retry next sync (e.g. still offline / server error)
      throw e;
    }
  }
  return pushed;
}

/* ---- Auto-trigger wiring ---- */

let wired = false;
export function wireAutoSync() {
  if (wired) return;
  wired = true;
  window.addEventListener('online', () => { if (getMode()) syncNow(); });
  window.addEventListener('offline', () => status('idle', { offline: true }));
  // Initial sync when app starts with connectivity.
  if (getMode() && isOnline()) syncNow();
}

export async function getSyncMeta() {
  const last = await db.getMeta(LAST_PULL_KEY);
  const queued = (await db.getQueue()).length;
  return { lastPull: last || null, queued };
}
