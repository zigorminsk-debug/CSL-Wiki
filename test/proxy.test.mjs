import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { proxy } from '../server/proxy.js';

test('proxy restricts origins and refuses HTML and credential redirects', async () => {
  let mode = 'json';
  let requests = 0;
  const upstream = http.createServer((req, res) => {
    requests++;
    assert.equal(req.headers.authorization, 'Basic test');
    if (mode === 'redirect') {
      res.writeHead(302, { Location: '/login' });
      return res.end();
    }
    res.setHeader('Content-Type', mode === 'html' ? 'text/html' : 'application/json');
    res.end(mode === 'html' ? '<!DOCTYPE html>' : '{"username":"user"}');
  }).listen(0, '127.0.0.1');
  await once(upstream, 'listening');
  const base = `http://127.0.0.1:${upstream.address().port}`;
  process.env.CSL_ALLOWED_ORIGINS = base;
  const gateway = http.createServer((req, res) => proxy(req, res, new URL(req.url, 'http://gateway'))).listen(0, '127.0.0.1');
  await once(gateway, 'listening');
  const target = `http://127.0.0.1:${gateway.address().port}/api/csl/rest/api/user/current`;
  const call = (origin = base) => fetch(target, { headers: { 'X-CSL-Base': origin, Authorization: 'Basic test' } });
  try {
    let res = await call();
    assert.equal(res.status, 200);
    assert.equal((await res.json()).username, 'user');
    res = await call('https://evil.example');
    assert.equal(res.status, 403);
    mode = 'html';
    res = await call();
    assert.equal(res.status, 502);
    assert.match((await res.json()).message, /HTML/);
    mode = 'redirect';
    res = await call();
    assert.equal(res.status, 502);
    assert.match((await res.json()).message, /redirected/);
    assert.equal(requests, 3, 'redirect target never receives credentials');
  } finally {
    gateway.closeAllConnections(); upstream.closeAllConnections();
    await Promise.all([new Promise((r) => gateway.close(r)), new Promise((r) => upstream.close(r))]);
    delete process.env.CSL_ALLOWED_ORIGINS;
  }
});
