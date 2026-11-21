/**
 * Functional test for the offline + sync engine, run under Node with an
 * IndexedDB shim. Exercises: cache-on-read, queue-while-offline, push on
 * reconnect, pull of server changes, and the create flow.
 *
 * Starts its own mock server on an ephemeral port.
 */
import 'fake-indexeddb/auto';
import { once } from 'node:events';
process.env.PORT = '0';
const { server } = await import('../server/server.js');
if (!server.listening) await once(server, 'listening');
const origin = `http://127.0.0.1:${server.address().port}`;

// --- browser-global shims ------------------------------------------------
const store = {};
globalThis.localStorage = {
  getItem: (k) => (k in store ? store[k] : null),
  setItem: (k, v) => { store[k] = String(v); },
  removeItem: (k) => { delete store[k]; }
};
Object.defineProperty(globalThis, 'navigator', {
  value: { onLine: true, userAgent: 'node-test' },
  configurable: true, writable: true
});
globalThis.window = { addEventListener() {}, innerWidth: 1000 };

const realFetch = globalThis.fetch;
globalThis.fetch = (url, opts) =>
  realFetch(typeof url === 'string' && url.startsWith('/') ? origin + url : url, opts);

// --- imports -------------------------------------------------------------
const db = await import('../public/js/db.js');
const api = await import('../public/js/api.js');
const sync = await import('../public/js/sync.js');

let failures = 0;
function assert(cond, msg) {
  console.log((cond ? 'PASS' : 'FAIL') + ' — ' + msg);
  if (!cond) failures++;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  await db.openDB();

  // 1. browse + cache a page
  const spaces = await api.api.listSpaces();
  assert(spaces.length === 3, 'lists 3 spaces from server');

  const pages = await api.api.listPages('SP-1');
  assert(pages.length === 3, 'lists pages for SP-1');

  const remote = await api.api.getPage('P-1');
  await db.putPage({ ...remote, localModified: false, dirty: false, conflict: false });
  assert((await db.getPage('P-1')).title === 'Architecture Overview', 'page cached locally');

  // 2. go offline, make an edit (queued)
  globalThis.navigator.onLine = false;
  const p1 = await db.getPage('P-1');
  p1.body = 'OFFLINE EDIT';
  p1.dirty = true;
  p1.localModified = true;
  await db.putPage(p1);
  await db.enqueue({ op: 'update', pageId: 'P-1', title: p1.title, body: p1.body, labels: p1.labels });

  let meta = await sync.getSyncMeta();
  assert(meta.queued === 1, 'one change queued while offline');

  // 3. sync while offline must NOT push
  const r1 = await sync.syncNow();
  assert(r1.state === 'error' && r1.error === 'offline', 'sync refused while offline');
  meta = await sync.getSyncMeta();
  assert(meta.queued === 1, 'queue preserved while offline');

  // 4. back online -> automatic push + pull
  globalThis.navigator.onLine = true;
  const r2 = await sync.syncNow();
  assert(r2.state === 'done' && r2.pushed === 1, 'pushed queued edit on reconnect');
  meta = await sync.getSyncMeta();
  assert(meta.queued === 0, 'queue drained after sync');

  const after = await db.getPage('P-1');
  assert(after.dirty === false, 'local page marked clean after push');
  assert(after.version === 2, 'version advanced to 2 after server update');

  // 5. pull: server change is fetched on next sync
  await realFetch(origin + '/api/pages/P-2', {
    method: 'PUT', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ body: 'SERVER CHANGED THIS' })
  });
  const r3 = await sync.syncNow();
  assert(r3.state === 'done' && r3.pulled >= 1, 'pulled server changes');
  const p2 = await db.getPage('P-2');
  assert(p2.body === 'SERVER CHANGED THIS', 'server edit reflected locally');

  // 6. create flow while offline -> placeholder replaced by server page
  globalThis.navigator.onLine = false;
  const localId = 'local-test';
  await db.putPage({ id: localId, spaceId: 'SP-2', title: 'Brand New', body: 'x', labels: [], version: 1, dirty: true, localModified: true });
  await db.enqueue({ op: 'create', pageId: localId, spaceId: 'SP-2', title: 'Brand New', body: 'x', labels: [] });
  globalThis.navigator.onLine = true;
  await sync.syncNow();
  const stillThere = await db.getPage(localId);
  assert(!stillThere, 'local-only placeholder removed after create sync');
  const all = await db.getAllPages();
  assert(all.some((p) => p.title === 'Brand New' && !String(p.id).startsWith('local-')), 'server-created page present locally');

  // Failed pulls must not advance the cursor or report a successful sync.
  const lastPull = await db.getMeta('lastPull');
  const originalSync = api.api.syncSince;
  api.api.syncSince = async () => { throw new Error('HTTP 401'); };
  const failed = await sync.syncNow();
  assert(failed.state === 'error', 'pull failure reported as error');
  assert((await db.getMeta('lastPull')) === lastPull, 'failed pull preserves sync cursor');
  api.api.syncSince = originalSync;

  // A newer server version must not overwrite a pending local edit.
  const dirty = await db.getPage('P-1');
  dirty.body = 'UNSENT'; dirty.dirty = true;
  await db.putPage(dirty);
  await db.enqueue({ op: 'update', pageId: dirty.id, title: dirty.title, body: dirty.body });
  api.api.syncSince = async () => [{ ...dirty, version: dirty.version + 1, body: 'SERVER' }];
  const conflict = await sync.syncNow();
  assert(conflict.state === 'error', 'conflicting edits are not silently pushed');
  assert((await db.getPage('P-1')).body === 'UNSENT', 'pending local body survives newer server version');
  assert((await db.getQueue()).length === 1, 'conflicting edit remains queued');
  api.api.syncSince = originalSync;

  // Accounts and demo must never share offline pages/queued mutations.
  const auth = await import('../public/js/auth.js');
  auth.setCredentials({ serverUrl: 'https://wiki.example', username: 'alice', password: 'x' });
  auth.setMode('real');
  assert((await db.getAllPages()).length === 0, 'real account cannot read demo cache');
  assert((await db.getQueue()).length === 0, 'real account cannot push demo edits');
  await db.putPage({ id: 'private', title: 'Private page' });
  auth.setCredentials({ serverUrl: 'https://wiki.example', username: 'bob', password: 'y' });
  assert(!(await db.getPage('private')), 'different users have isolated caches');
  auth.clearMode(); auth.clearCredentials();

  console.log('\n' + (failures === 0 ? 'ALL TESTS PASSED' : failures + ' TEST(S) FAILED'));
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => { console.error('TEST ERROR', e); process.exit(1); });
