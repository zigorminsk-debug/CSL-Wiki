# CSL-Wiki

Mobile Confluence client: PWA + Capacitor Android shell, offline IndexedDB cache,
queued edits, bookmarks and a local keyword-search assistant.

## Run and test

Use Node 18+ (Node 22 recommended).

```sh
npm ci
npm test
npm start
```

The server listens on all interfaces, port 3000 (`PORT` overrides it). Open the
hosted app in a browser. **Try the demo** uses the Node mock API; the bundled APK
has no Node runtime, so demo mode is only available in the hosted version.

## Connect to Confluence

Enter the HTTPS **base URL**, username and password on the login screen.

- **Server / Data Center:** API path `/rest/api` (default).
- **Cloud:** base URL `https://your-site.atlassian.net`, API path `/wiki/api/v2`,
  email as username and an API token as password. Ordinary Cloud account
  passwords do not replace API tokens.
- A context path belongs in the base URL, e.g. `https://wiki.example/confluence`
  with API path `/rest/api`. Do not repeat it in both fields.
- The current-user check uses REST v1 `/user/current`, even for Cloud v2 pages.
- SSO-only deployments may reject Basic auth; this app does not implement an
  interactive SSO login or Bearer personal-access-token authentication.

**Android APK:** uses CapacitorHttp's native fetch transport directly to the
entered server. No Node proxy or browser CORS configuration is needed. The phone
must be able to reach the server (possibly via VPN), and trust its TLS certificate.

**Hosted PWA:** uses the same-origin Node proxy. Configure allowed Confluence
origins on the server (default: only `https://w.csl.by`):

```sh
CSL_ALLOWED_ORIGINS=https://w.csl.by,https://wiki.example npm start
```

Expose this service only to trusted users, ideally behind access control. The
allowlist is an SSRF safeguard, not authentication of callers. Use HTTPS for the
hosted app so credentials cannot be intercepted. Direct browser mode requires
Confluence to explicitly allow CORS. Static hosting alone does not provide the
Node demo API or proxy.

## Offline data and limitations

- Offline databases are separated by server, username and API path; demo data
  cannot be pushed into a real account.
- The old unscoped `csl-wiki` database is deliberately not imported: its owner
  cannot be established safely. Old queued edits are not deleted, but are not
  replayed automatically. Back them up before clearing application data.
- Failed pulls do not advance the sync cursor. Dirty local bodies are preserved
  and newer remote versions are flagged as conflicts rather than overwritten.
  There is not yet a conflict-resolution UI.
- Real-server sync currently fetches all accessible pages with pagination, not
  an incremental change feed. Remote deletion reconciliation and dependent
  edits to a newly created local placeholder need more work.
- Passwords persist in localStorage on the device. This is not encrypted secure
  storage. Do not use on an untrusted/shared device. Android backup is disabled.
- Confluence storage markup is displayed escaped, not rendered as rich HTML.
- Never ship a shared AI API secret in the public JS/APK configuration.

## Android build and audit

See [Android build instructions](docs/ANDROID_BUILD.md) and the
[diagnostic report](docs/AUDIT_LOGIN_RU.md).

The original Android signing key and passwords are **not included** here.
CI uses repository secrets for signed releases; without secrets branch builds
produce an installable debug APK. Debug APKs cannot update an existing
release-signed installation.

`npm audit` currently reports vulnerabilities in the Capacitor 6 CLI development
toolchain (including `tar`). Fixing them safely requires a coordinated Capacitor
upgrade. Do not apply a forced major override: replacing `tar` 6 with 7 breaks
Capacitor 6's template extraction. See the audit report for remaining work.
