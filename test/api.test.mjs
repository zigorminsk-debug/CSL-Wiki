import test from 'node:test';
import assert from 'node:assert/strict';

const store = new Map();
globalThis.localStorage = {
  getItem: (k) => store.get(k) ?? null,
  setItem: (k, v) => store.set(k, String(v)),
  removeItem: (k) => store.delete(k)
};
Object.defineProperty(globalThis, 'navigator', { value: { onLine: true }, configurable: true });
globalThis.window = {};
const auth = await import('../public/js/auth.js');
const { api, setRealMode } = await import('../public/js/api.js');
const json = (value, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
function setup(apiPath = '/rest/api', direct = false) {
  store.clear();
  window.Capacitor = undefined;
  auth.setCredentials({ serverUrl: 'https://wiki.example', username: 'user', password: 'secret', apiPath, direct });
  setRealMode(true);
}

test('PWA uses proxy and Server current-user endpoint', async () => {
  setup();
  globalThis.fetch = async (url, opts) => {
    assert.equal(url, '/api/csl/rest/api/user/current');
    assert.equal(opts.headers['X-CSL-Base'], 'https://wiki.example');
    assert.equal(opts.headers.Authorization, 'Basic dXNlcjpzZWNyZXQ=');
    return json({ username: 'user', type: 'known' });
  };
  await api.currentUser();
});

test('APK bypasses missing relative Node proxy automatically', async () => {
  setup();
  window.Capacitor = { isNativePlatform: () => true };
  globalThis.fetch = async (url, opts) => {
    assert.equal(url, 'https://wiki.example/rest/api/user/current');
    assert.equal(opts.headers['X-CSL-Base'], undefined);
    return json({ username: 'user' });
  };
  await api.currentUser();
});

test('Cloud v2 checks user through REST v1', async () => {
  setup('/wiki/api/v2', true);
  globalThis.fetch = async (url) => {
    assert.equal(url, 'https://wiki.example/wiki/rest/api/user/current');
    return json({ accountId: '123' });
  };
  await api.currentUser();
});

test('HTML and invalid JSON give actionable errors, not JSON.parse stack', async () => {
  setup();
  globalThis.fetch = async () => new Response('<!DOCTYPE html><h1>Login</h1>', { headers: { 'Content-Type': 'text/html' } });
  await assert.rejects(api.currentUser(), /HTML instead of Confluence JSON/);
  globalThis.fetch = async () => new Response('not json');
  await assert.rejects(api.currentUser(), /invalid JSON/);
});

test('anonymous, 401 and 403 are not successful login', async () => {
  setup();
  globalThis.fetch = async () => json({ type: 'anonymous' });
  await assert.rejects(api.currentUser(), /did not authenticate/);
  for (const status of [401, 403]) {
    globalThis.fetch = async () => json({ message: 'Denied' }, status);
    await assert.rejects(api.currentUser(), (err) => err.status === status);
  }
});

test('Unicode Basic credentials and HTTPS validation', () => {
  setup();
  auth.setCredentials({ serverUrl: 'https://wiki.example/confluence/', username: 'тест', password: 'пароль' });
  assert.equal(Buffer.from(auth.authHeader().slice(6), 'base64').toString('utf8'), 'тест:пароль');
  assert.equal(auth.getCredentials().serverUrl, 'https://wiki.example/confluence');
  assert.throws(() => auth.setCredentials({ serverUrl: 'http://wiki.example', username: 'x' }), /HTTPS/);
  assert.throws(() => auth.setCredentials({ serverUrl: 'https://wiki.example', username: 'x', apiPath: '//evil.example/rest/api' }), /API path/);
});

test('Server adapter uses space key and v1 content API, paginating safely', async () => {
  setup();
  const urls = [];
  globalThis.fetch = async (url) => {
    urls.push(url);
    if (url.includes('/space?')) return json({ results: [{ id: 42, key: 'DOC', name: 'Docs' }] });
    if (url.includes('start=1')) return json({ results: [{ id: '2', title: 'Second' }] });
    return json({ results: [{ id: '1', title: 'First' }], _links: { next: '/rest/api/content?start=1&limit=1' } });
  };
  assert.equal((await api.listSpaces())[0].id, 'DOC');
  assert.equal((await api.listPages('DOC')).length, 2);
  assert.match(urls[1], /\/content\?type=page&spaceKey=DOC/);
});

test('Server CRUD uses v1 bodies, preserves body on title-only edits and handles 204', async () => {
  setup();
  globalThis.fetch = async (url, opts) => {
    assert.match(url, /\/rest\/api\/content/);
    if (opts.method === 'GET') return json({ id: '7', title: 'Old', space: { key: 'DOC' }, version: { number: 2 }, body: { storage: { value: 'Keep me' } } });
    if (opts.method === 'DELETE') return new Response(null, { status: 204 });
    const body = JSON.parse(opts.body);
    assert.equal(body.type, 'page');
    if (opts.method === 'PUT') {
      assert.equal(body.body.storage.value, 'Keep me');
      assert.equal(body.version.number, 3);
    } else assert.equal(body.space.key, 'DOC');
    return json({ id: '7', title: body.title, version: { number: 3 } });
  };
  assert.equal((await api.updatePage('7', { title: 'New' })).version, 3);
  await api.createPage({ spaceId: 'DOC', title: 'Created', body: 'Text' });
  assert.equal(await api.deletePage('7'), null);
});

test('Cloud sync requests storage bodies with cursor pagination and no unsupported CQL', async () => {
  setup('/wiki/api/v2');
  globalThis.fetch = async (url) => {
    assert.match(url, /body-format=storage/);
    assert.ok(!url.includes('cql='));
    const last = url.includes('cursor=next');
    return json({ results: [{ id: last ? '2' : '1', spaceId: '42', body: { storage: { value: 'Full body' } } }],
      _links: last ? {} : { next: 'https://untrusted.example/wiki/api/v2/pages?cursor=next' } });
  };
  const pages = await api.syncSince('2026-01-01');
  assert.equal(pages.length, 2);
  assert.equal(pages[1].body, 'Full body');
});
