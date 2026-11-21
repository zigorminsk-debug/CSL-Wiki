# Building a real Android app (APK / Play Store)

CSL-Wiki is built as a **Progressive Web App (PWA)**. On Android it can be
installed straight from Chrome ("Add to Home screen") and behaves like a native
app — full screen, offline, with automatic sync. No app store required.

If you want a **compiled `.apk`** (e.g. to distribute privately or publish to
the Play Store), wrap the PWA with one of the two standard tools below.

---

## Option A — Bubblewrap (Trusted Web Activity, recommended)

Produces a small native shell that loads your hosted PWA. Smallest APK, easiest
Play Store submission.

1. Host the `public/` folder on any static host (GitHub Pages, Netlify, S3…).
   The site must be served over **HTTPS** and expose `manifest.webmanifest`.
2. Install the Bubblewrap CLI:
   ```bash
   npm install -g @bubblewrap/cli
   ```
3. Generate the Android project from the manifest in this repo:
   ```bash
   bubblewrap init --manifest https://your-hosted-pwa.example/manifest.webmanifest \
     --directory android
   # or, to use the provided config:
   bubblewrap init --config config/twa-manifest.json --directory android
   ```
   Edit `config/twa-manifest.json` first and replace
   `your-hosted-pwa.example` with your real domain.
4. Build the APK / App Bundle:
   ```bash
   cd android && ./gradlew assembleRelease   # or bundleRelease
   ```
5. Sign with your upload key (see `signingKey` in the manifest config).

---

## Option B — Capacitor (bundled assets, fully offline APK)

Bundles the PWA into the APK itself, so the app needs **no backend at all** to
open and read cached content (it still syncs when it reaches your Confluence).

1. Install Capacitor:
   ```bash
   npm install -D @capacitor/cli @capacitor/core @capacitor/android
   ```
2. The `config/capacitor.config.ts` already points `webDir` at `public`.
   Add the Android platform:
   ```bash
   npx cap add android
   ```
3. Copy the web assets into the native project and open Android Studio:
   ```bash
   npx cap sync android
   npx cap open android
   ```
4. In Android Studio: **Build → Generate Signed Bundle / APK**.

### Notes for Capacitor
- The WebView's IndexedDB persists across app updates, so a user's offline cache
  and queued edits survive upgrades.
- To talk to a real Confluence tenant from the app, set `USE_MOCK=false` in
  `public/js/config.js` and point `CONFLUENCE_BASE_URL` at your tenant. Because
  browsers block cross-origin requests to Confluence, run a tiny proxy (extend
  `server/server.js`) or use Capacitor's `server.url` to a hosted proxy.

---

## Connecting to a real Confluence (username + password)

You do **not** edit config files to connect — the app has a **login screen**:
enter your server URL (default `https://w.csl.by`), username and password. The
app then talks to your server through the bundled Node server acting as a
**CORS-safe proxy** (`/api/csl/...`), forwarding your Basic-auth credentials to
your server. This is required because a browser/WebView cannot call
`w.csl.by` directly (CORS).

How it works:

1. The login form stores `{ serverUrl, username, password, apiPath }` (localStorage).
2. Every API call goes to `PROXY_BASE + apiPath + endpoint` (same origin).
3. The Node server (`server/server.js`, `handleProxy`) forwards to
   `<serverUrl>/<apiPath>/<endpoint>` carrying your `Authorization: Basic …`
   header and an `X-CSL-Base` header.
4. The adapter speaks the Confluence **v2 REST API**
   (`/wiki/api/v2/spaces`, `/wiki/api/v2/pages`, …). For older self-hosted
   instances that only expose v1, change the **API path** field on the login
   screen to `/rest/api`.

The API path prefix is configurable per server (login screen → "API path"),
default `/wiki/api/v2`.

> If your instance uses SSO (Crowd/OIDC) instead of Basic auth, the REST API may
> reject Basic credentials. In that case point the app at a reverse proxy that
> injects a session, or use a personal access token where supported.

### For the compiled APK (Capacitor)

A bundled APK loads its assets from `file://`, so a **relative** `PROXY_BASE`
(`/api/csl`) will not resolve. You must host the Node server somewhere reachable
and set the absolute URL before building:

```js
// public/js/config.js
PROXY_BASE: 'https://csl-proxy.example.com/api/csl'
```

Then `npx cap sync android && ./gradlew assembleRelease` as described above. The
APK's WebView will call your hosted proxy, which forwards to `w.csl.by`.

> Security: the password is stored in the device's localStorage and sent only to
> your own server via the proxy. On a shared device, prefer a short session or a
> backend that exchanges credentials for a token.
