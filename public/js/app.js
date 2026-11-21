/**
 * App controller + UI rendering.
 *
 * Boot flow:
 *  - If a previous mode is stored, start directly (mock demo or real server).
 *  - Otherwise show the start screen: "Try the demo" or "Connect your server"
 *    (server URL + username + password). Login is validated against the user's
 *    Confluence through the Node proxy.
 *
 * Then: space/page browsing, offline reading (IndexedDB), local editing with an
 * offline queue, bookmarks, the wiki agent panel, and sync status. The service
 * worker provides offline app-shell loading; this module provides data offline.
 */
import * as db from './db.js';
import { api, isOnline, isSimulatedOffline, setSimulatedOffline, setRealMode } from './api.js';
import { syncNow, wireAutoSync, onSyncStatus, getSyncMeta, isSyncing } from './sync.js';
import { askAgent } from './agent.js';
import * as auth from './auth.js';
import { loadLocalVersion, setUpdateHandler, pollUpdate, applyUpdate } from './update.js';

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));

const state = {
  spaces: [],
  activeSpace: null,
  activePage: null,
  bookmarks: new Set()
};

/* ---------------- boot ---------------- */

async function boot() {
  await db.openDB();
  state.bookmarks = new Set(await db.getBookmarks());
  registerSW();
  bindUI();
  initAutoUpdate();

  const mode = auth.getMode();
  const creds = auth.getCredentials();
  if (mode === 'real' && creds) {
    setRealMode(true);
    startApp();
  } else if (mode === 'mock') {
    setRealMode(false);
    startApp();
  } else {
    showStart();
  }
}

/* ---------------- auto-update from GitHub ---------------- */

function initAutoUpdate() {
  loadLocalVersion().then(() => pollUpdate());
  setUpdateHandler(showUpdateBanner);
  setInterval(pollUpdate, 30 * 60 * 1000);
  window.addEventListener('online', () => { if (isOnline()) pollUpdate(); });
}

function showUpdateBanner(remote) {
  lastRemote = remote;
  const banner = $('#update-banner');
  banner.dataset.apk = remote.apkUrl ? '1' : '0';
  $('#update-text').textContent =
    `New version ${remote.version} available` + (remote.notes ? ' — ' + remote.notes.split('\n')[0] : '');
  $('#btn-update').textContent = remote.apkUrl ? 'Update' : 'Reload';
  banner.classList.add('show');
}
let lastRemote = null;
function hideUpdateBanner() { $('#update-banner').classList.remove('show'); }

async function startApp() {
  $('#start-screen').classList.add('hidden');
  wireAutoSync();
  bindSpaceButtons(); // safe even if lists empty
  await renderSpaces();
  updateConnectionUI();
  refreshSyncMeta();
  onSyncStatus(renderSyncStatus);
  window.addEventListener('online', updateConnectionUI);
  window.addEventListener('offline', updateConnectionUI);
}

/* ---------------- start / login ---------------- */

function showStart() {
  $('#start-screen').classList.remove('hidden');
}
function hideStart() {
  $('#start-screen').classList.add('hidden');
}

async function handleDemo() {
  auth.setMode('mock');
  auth.clearCredentials();
  setRealMode(false);
  hideStart();
  startApp();
}

async function handleConnect(e) {
  e.preventDefault();
  const serverUrl = $('#login-server').value.trim();
  const username = $('#login-user').value.trim();
  const password = $('#login-pass').value;
  const apiPath = $('#login-apipath').value.trim() || '/wiki/api/v2';
  const direct = $('#login-direct').checked;
  const errEl = $('#login-error');
  errEl.textContent = '';

  if (!serverUrl || !username || !password) {
    errEl.textContent = 'Fill in server URL, username and password.';
    return;
  }

  auth.setCredentials({ serverUrl, username, password, apiPath, direct });
  auth.setMode('real');
  setRealMode(true);

  errEl.textContent = 'Connecting…';
  try {
    await api.currentUser();
    hideStart();
    startApp();
  } catch (err) {
    auth.clearCredentials();
    auth.clearMode();
    setRealMode(false);
    let msg = 'Could not connect: ';
    if (err.status) {
      msg += `HTTP ${err.status}`;
      if (err.status === 401 || err.status === 403) msg += ' — wrong username/password, or the server rejects Basic auth (e.g. SSO).';
    } else if (err.message && /fetch failed|network|Failed to fetch/i.test(err.message)) {
      msg += 'cannot reach the server. ' +
        (direct
          ? 'Direct mode needs the server to allow CORS for this origin.'
          : 'The app server (proxy) cannot reach ' + serverUrl + '. Deploy the app where it has network access to your server, or enable "Connect directly" if your server allows CORS.');
    } else {
      msg += err.message;
    }
    errEl.textContent = msg;
  }
}

function handleLogout() {
  auth.clearCredentials();
  auth.clearMode();
  setRealMode(false);
  $('#sidebar').classList.remove('open');
  showStart();
}

/* ---------------- spaces / pages ---------------- */

async function renderSpaces() {
  const el = $('#spaces');
  el.innerHTML = '<li class="muted">Loading spaces…</li>';
  try {
    state.spaces = await api.listSpaces();
  } catch (e) {
    state.spaces = [];
  }
  // Fall back to spaces discovered only in the local cache (offline / bundled).
  const cached = await db.getAllPages();
  if (!state.spaces.length) {
    const map = new Map();
    cached.forEach((p) => {
      if (p.spaceId && !map.has(p.spaceId)) {
        map.set(p.spaceId, {
          id: p.spaceId,
          key: String(p.spaceId).slice(0, 4).toUpperCase(),
          name: p.spaceId,
          description: '',
          pageCount: 0
        });
      }
    });
    state.spaces = [...map.values()];
  }

  if (!state.spaces.length) {
    el.innerHTML = '<li class="muted">No spaces yet. Connect to Confluence or add a page.</li>';
  } else {
    el.innerHTML = '';
    for (const s of state.spaces) {
      const li = document.createElement('li');
      li.innerHTML = `<button class="space-btn" data-id="${s.id}"><span class="space-key">${escapeHtml(s.key)}</span> ${escapeHtml(s.name)}</button>`;
      el.appendChild(li);
    }
  }
  bindSpaceButtons();
  if (state.spaces[0]) selectSpace(state.spaces[0].id);
}

async function selectSpace(id) {
  state.activeSpace = id;
  $$('.space-btn').forEach((b) => b.classList.toggle('active', b.dataset.id === id));
  await renderPageList(id);
}

async function renderPageList(spaceId) {
  const el = $('#page-list');
  el.innerHTML = '<li class="muted">Loading…</li>';
  let pages = [];
  try {
    pages = await api.listPages(spaceId);
  } catch (e) { pages = []; }

  const cached = (await db.getPagesBySpace(spaceId)).map((p) => ({
    id: p.id, title: p.title, version: p.version, cached: true, dirty: p.dirty
  }));
  const merged = mergeById([...pages, ...cached]);
  state._pageList = merged;

  if (!merged.length) { el.innerHTML = '<li class="muted">No pages.</li>'; return; }
  el.innerHTML = '';
  for (const p of merged) {
    const li = document.createElement('li');
    const flag = p.dirty ? ' <span class="dot dirty" title="Not yet synced"></span>'
      : (p.cached && !p._fromServer ? ' <span class="dot cached" title="Cached offline"></span>' : '');
    li.innerHTML = `<button class="page-btn" data-id="${p.id}">${escapeHtml(p.title)}${flag}</button>`;
    el.appendChild(li);
  }
  bindPageButtons();
}

function mergeById(list) {
  const map = new Map();
  for (const p of list) {
    const prev = map.get(p.id);
    if (!prev) map.set(p.id, { ...p, _fromServer: !p.cached });
    else map.set(p.id, { ...prev, ...p, _fromServer: prev._fromServer || !p.cached });
  }
  return [...map.values()];
}

async function openPage(id) {
  $('#reader').classList.remove('hidden');
  $('#page-content').innerHTML = '<p class="muted">Loading…</p>';
  let page = await db.getPage(id);
  if (!page || !page.body) {
    try {
      const remote = await api.getPage(id);
      page = { ...remote, localModified: false, dirty: false, conflict: false };
      await db.putPage(page);
    } catch (e) {
      if (!page) { $('#page-content').innerHTML = '<p class="muted">Offline and not cached.</p>'; return; }
    }
  }
  state.activePage = page;
  renderPage(page);
}

function renderPage(page) {
  const bookmarked = state.bookmarks.has(page.id);
  $('#page-title').textContent = page.title;
  $('#page-meta').textContent =
    `v${page.version} · ${page.labels?.join(', ') || 'no labels'}` +
    (page.dirty ? ' · ⏳ pending sync' : '') + (page.conflict ? ' · ⚠ conflict' : '');
  $('#page-content').innerHTML = `<div class="prose">${escapeHtml(page.body).replace(/\n/g, '<br>')}</div>`;
  $('#btn-bookmark').textContent = bookmarked ? '★ Bookmarked' : '☆ Bookmark';
  $('#btn-bookmark').classList.toggle('active', bookmarked);
  $('#btn-sync-page').disabled = !page.dirty;
}

/* ---------------- editing ---------------- */

async function editPage() {
  const page = state.activePage;
  if (!page) return;
  const title = prompt('Page title', page.title);
  if (title === null) return;
  const body = prompt('Page body', page.body);
  if (body === null) return;

  page.title = title;
  page.body = body;
  page.dirty = true;
  page.localModified = true;
  page.updatedAt = new Date().toISOString();
  await db.putPage(page);
  await db.enqueue({ op: 'update', pageId: page.id, title, body, labels: page.labels });
  renderPage(page);
  await renderPageList(page.spaceId);
  toast('Saved locally — will sync when online');
  if (isOnline()) syncNow();
}

async function createPage() {
  if (!state.activeSpace && !state.spaces[0]) { toast('Open a space first'); return; }
  const spaceId = state.activeSpace || state.spaces[0].id;
  const title = prompt('New page title');
  if (!title) return;
  const body = prompt('New page body', '') ?? '';
  const id = 'local-' + Date.now().toString(36);
  const page = {
    id, spaceId, title, body, labels: [], version: 1,
    updatedAt: new Date().toISOString(), localModified: true, dirty: true, conflict: false, localOnly: true
  };
  await db.putPage(page);
  await db.enqueue({ op: 'create', pageId: id, spaceId, title, body, labels: [] });
  await renderPageList(spaceId);
  toast('Created locally — will sync when online');
  if (isOnline()) syncNow();
}

async function toggleBookmark() {
  const page = state.activePage;
  if (!page) return;
  const on = await db.toggleBookmark(page.id);
  if (on) state.bookmarks.add(page.id); else state.bookmarks.delete(page.id);
  renderPage(page);
  renderBookmarks();
}

async function renderBookmarks() {
  const el = $('#bookmarks');
  const ids = [...state.bookmarks];
  if (!ids.length) { el.innerHTML = '<li class="muted">No bookmarks yet.</li>'; return; }
  const pages = (await Promise.all(ids.map((id) => db.getPage(id)))).filter(Boolean);
  el.innerHTML = '';
  for (const p of pages) {
    const li = document.createElement('li');
    li.innerHTML = `<button class="page-btn" data-id="${p.id}">${escapeHtml(p.title)}</button>`;
    el.appendChild(li);
  }
  bindPageButtons();
}

/* ---------------- agent ---------------- */

async function runAgent() {
  const q = $('#agent-input').value.trim();
  if (!q) return;
  addChat('user', q);
  $('#agent-input').value = '';
  addChat('agent', 'Thinking…', true);
  try {
    const { answer, citations } = await askAgent(q);
    updateLastChat(answer, citations);
  } catch (e) {
    updateLastChat('Sorry, something went wrong: ' + e.message);
  }
}

function addChat(role, text, pending = false) {
  const el = document.createElement('div');
  el.className = `chat-msg ${role}`;
  el.dataset.pending = pending ? '1' : '0';
  el.innerHTML = `<div class="bubble">${escapeHtml(text)}</div>`;
  $('#chat').appendChild(el);
  $('#chat').scrollTop = $('#chat').scrollHeight;
  return el;
}
function updateLastChat(text, citations = []) {
  const msgs = $$('#chat .chat-msg.agent');
  const last = msgs[msgs.length - 1];
  if (!last) return;
  last.dataset.pending = '0';
  const cit = citations.length
    ? `<div class="cites">Sources: ${citations.map((c) => `<button class="cite" data-id="${c.pageId}">${escapeHtml(c.pageTitle)}</button>`).join(' · ')}</div>`
    : '';
  $('.bubble', last).innerHTML = escapeHtml(text).replace(/\n/g, '<br>') + cit;
  $$('.cite', last).forEach((b) => b.addEventListener('click', () => openPage(b.dataset.id)));
  $('#chat').scrollTop = $('#chat').scrollHeight;
}

/* ---------------- sync / connection UI ---------------- */

function updateConnectionUI() {
  const online = isOnline();
  const badge = $('#conn-badge');
  badge.textContent = online ? '● Online' : '● Offline';
  badge.className = 'badge ' + (online ? 'online' : 'offline');
  $('#btn-sync').disabled = !online;
  $('#sim-offline').checked = isSimulatedOffline();
}

function renderSyncStatus(s) {
  const el = $('#sync-status');
  if (!s) return;
  const map = {
    syncing: '⟳ Syncing…',
    done: `✓ Synced (pulled ${s.pulled ?? 0}, pushed ${s.pushed ?? 0})`,
    error: s.error === 'offline' ? '⏸ Offline — changes queued' : `⚠ Sync error: ${s.error}`,
    idle: ''
  };
  el.textContent = map[s.state] || '';
  el.className = 'sync-status ' + s.state;
  refreshSyncMeta();
}

async function refreshSyncMeta() {
  const { lastPull, queued } = await getSyncMeta();
  $('#sync-meta').textContent = queued
    ? `${queued} change(s) waiting to sync`
    : (lastPull ? `Last sync ${new Date(lastPull).toLocaleString()}` : 'Not synced yet');
}

/* ---------------- misc ---------------- */

function bindUI() {
  $('#btn-sync').addEventListener('click', () => syncNow());
  $('#btn-edit').addEventListener('click', editPage);
  $('#btn-new').addEventListener('click', createPage);
  $('#btn-bookmark').addEventListener('click', toggleBookmark);
  $('#btn-sync-page').addEventListener('click', () => syncNow());
  $('#btn-agent').addEventListener('click', runAgent);
  $('#agent-input').addEventListener('keydown', (e) => { if (e.key === 'Enter') runAgent(); });
  $('#btn-close-reader').addEventListener('click', () => $('#reader').classList.add('hidden'));
  $('#sim-offline').addEventListener('change', (e) => {
    setSimulatedOffline(e.target.checked);
    updateConnectionUI();
    if (!e.target.checked && isOnline()) syncNow();
  });
  $('#panel-toggle').addEventListener('click', () => $('#sidebar').classList.toggle('open'));

  $('#btn-demo').addEventListener('click', handleDemo);
  $('#login-form').addEventListener('submit', handleConnect);
  $('#btn-logout').addEventListener('click', handleLogout);

  $('#btn-update').addEventListener('click', () => { if (lastRemote) applyUpdate(lastRemote); });
  $('#btn-update-later').addEventListener('click', hideUpdateBanner);

  renderBookmarks();
}

function bindSpaceButtons() {
  $$('.space-btn').forEach((b) => b.addEventListener('click', () => selectSpace(b.dataset.id)));
}
function bindPageButtons() {
  $$('.page-btn').forEach((b) => b.addEventListener('click', () => {
    openPage(b.dataset.id);
    if (window.innerWidth < 820) $('#sidebar').classList.remove('open');
  }));
}

let _toastTimer;
function toast(msg) {
  const t = $('#toast');
  t.textContent = msg;
  t.classList.add('show');
  clearTimeout(_toastTimer);
  _toastTimer = setTimeout(() => t.classList.remove('show'), 2600);
}

function escapeHtml(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function registerSW() {
  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('./sw.js').catch((e) => console.warn('SW register failed', e));
  }
}

boot().catch((e) => console.error(e));
