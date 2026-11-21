/** Restricted same-origin proxy. Do not expose an unrestricted credential tunnel. */
export async function proxy(req, res, url) {
  const json = (status, data) => {
    res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify(data));
  };
  let base;
  try {
    base = new URL(req.headers['x-csl-base'] || process.env.CSL_BASE || 'https://w.csl.by');
    if (!['https:', 'http:'].includes(base.protocol) || base.username || base.password || base.search || base.hash) throw new Error();
  } catch { return json(400, { message: 'Invalid Confluence base URL.' }); }
  const allowed = (process.env.CSL_ALLOWED_ORIGINS || process.env.CSL_BASE || 'https://w.csl.by')
    .split(',').map((s) => new URL(s.trim()).origin);
  if (!allowed.includes(base.origin)) return json(403, { message: 'This Confluence origin is not allowed by the proxy administrator (CSL_ALLOWED_ORIGINS).' });
  const path = url.pathname.replace(/^\/api\/csl/, '');
  if (!/^\/(?:[\w-]+\/)*(?:rest\/api|api\/v2)\//.test(path) || /%2e|%2f|%5c/i.test(path)) {
    return json(400, { message: 'Only Confluence REST API paths can be proxied.' });
  }
  if (!['GET', 'POST', 'PUT', 'DELETE', 'HEAD'].includes(req.method)) return json(405, { message: 'Method not allowed.' });
  let body;
  if (!['GET', 'HEAD'].includes(req.method)) {
    const chunks = [];
    let size = 0;
    for await (const chunk of req) {
      size += chunk.length;
      if (size > 2 * 1024 * 1024) return json(413, { message: 'Request body too large.' });
      chunks.push(chunk);
    }
    body = Buffer.concat(chunks);
  }
  const headers = { Accept: 'application/json', 'Content-Type': 'application/json' };
  if (req.headers.authorization) headers.Authorization = req.headers.authorization;
  try {
    const upstream = await fetch(base.href.replace(/\/$/, '') + path + url.search, {
      method: req.method, headers, body, redirect: 'manual', signal: AbortSignal.timeout(30000)
    });
    if (upstream.status >= 300 && upstream.status < 400) {
      await upstream.body?.cancel();
      return json(502, { message: 'Confluence redirected the API request. Check the base URL/context path and SSO configuration. Redirects are not followed with credentials.' });
    }
    const text = await upstream.text();
    if ([401, 403].includes(upstream.status)) {
      return json(upstream.status, { message: 'Confluence rejected authentication or permission to use this API.' });
    }
    if (upstream.status !== 204 && !/json/i.test(upstream.headers.get('content-type') || '')) {
      return json(502, { message: 'Confluence returned HTML/non-JSON instead of its REST API. Check the API path and SSO configuration.' });
    }
    res.writeHead(upstream.status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    res.end(text);
  } catch {
    json(502, { message: 'The proxy cannot reach Confluence. Check DNS, network/VPN and the server TLS certificate.' });
  }
}
