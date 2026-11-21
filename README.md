# CSL-Wiki — Offline Confluence Wiki client for Android

A mobile-first **Confluence Wiki client** you can use comfortably on a phone.
It works **offline** (pages you have opened stay available without a connection)
and **automatically syncs** your data the moment the internet comes back.

Built as a **Progressive Web App (PWA)** so it installs and behaves like a
native Android app, with everything needed to wrap it into a real compiled
`.apk` (Capacitor / Bubblewrap) when you want one.

## Features

- 📱 **Phone-first UI** — bottom-friendly layout, installable to the Android
  home screen, full-screen standalone mode.
- 📴 **Offline reading** — opened pages are cached in the device
  (IndexedDB) and served with no network; the app shell loads offline via a
  Service Worker.
- 🔄 **Auto sync on reconnect** — edits, new pages and bookmarks made offline
  are queued and pushed automatically when connectivity returns; the app also
  pulls pages changed on the server since your last sync.
- 🤖 **Wiki Agent** — ask plain-English questions ("How does offline sync
  work?"). It searches your cached/online pages and answers with citations.
  Works fully offline; optionally uses an OpenAI-compatible model if configured.
- 🔖 **Bookmarks** and **spaces/pages** browsing, with dirty/conflict indicators.

## Quick start (demo)

```bash
npm start          # serves the app + a mock Confluence API on :3000
```

Open <http://localhost:3000>. For the real phone experience, either:

- Visit the URL from your Android phone's Chrome and choose
  **⋮ → Add to Home screen**, or
- Use Chrome DevTools device toolbar (toggle device mode).

Try the offline flow: open a few pages, tick **Simulate offline** in the Sync
panel, edit a page (it is queued), then untick it — the queued change syncs
automatically.

## Project layout

```
CSL-Wiki/
├── public/                # the PWA (what runs on the phone)
│   ├── index.html
│   ├── manifest.webmanifest
│   ├── sw.js              # service worker: offline app shell
│   ├── css/styles.css
│   ├── icons/
│   └── js/
│       ├── config.js     # mock vs real Confluence + optional AI agent
│       ├── db.js         # IndexedDB local store (pages, queue, bookmarks)
│       ├── api.js        # API client (mock + Confluence v2 adapter)
│       ├── sync.js       # pull/push sync engine + auto-trigger
│       ├── agent.js      # wiki agent (offline keyword + optional AI)
│       └── app.js        # UI controller
├── server/
│   └── server.js         # static host + mock Confluence REST API
├── config/
│   ├── capacitor.config.ts   # wrap into a bundled Android APK
│   └── twa-manifest.json     # wrap into a Trusted Web Activity
└── docs/
    └── ANDROID_BUILD.md  # how to produce a real .apk / Play Store build
```

## How offline + sync works

1. **Cache on read** — every page you open is stored in IndexedDB (`db.js`).
2. **Queue on write** — edits / new pages / deletes while offline are recorded
   both locally (so you keep reading/writing) and in a `queue` store (`sync.js`).
3. **Detect reconnect** — `navigator.onLine` plus the `online`/`offline`
   events, plus an in-app **Simulate offline** toggle for testing.
4. **Sync on reconnect** — `syncNow()` pushes the queue (POST/PUT/DELETE) and
   pulls pages changed on the server since `lastPull`, resolving conflicts by
   version number and flagging them for review.

## Connecting to a real Confluence

See **docs/ANDROID_BUILD.md** → "Connecting to a real Confluence Cloud". Edit
`public/js/config.js`, set `USE_MOCK=false` and provide your tenant URL + API
token.

## Building a native APK

Two supported paths, both documented in **docs/ANDROID_BUILD.md**:

- **Bubblewrap (TWA)** — tiny native shell around a hosted PWA. Best for the
  Play Store.
- **Capacitor** — bundles the PWA into the APK, so the app is fully self-
  contained and offline even before first sync.

## Continuous build with GitHub Actions (signed APK)

The repo includes `.github/workflows/build-android.yml`, which on every push
(and on `workflow_dispatch`) builds a **signed release APK** and uploads it as a
workflow artifact. Push a tag `v1.0.0` to also publish a GitHub Release with the
APK attached.

The signing identity is **permanent** so every build — and therefore every
update installed over a previous one — uses the same key:

- The keystore `android/app/release-key.p12` is **committed to the repo** (this
  is what makes updates install over the old app — the signing key never
  changes).
- Its password / alias live in `android/gradle.properties`
  (`RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`).
- `android/app/build.gradle` reads those props in its `signingConfigs.release`
  and applies them to the `release` build type.

> ⚠️ **Security note:** because the keystore is committed, anyone with read
> access to the repo can sign builds with this key. For a public repo, prefer
> moving the three `RELEASE_*` password/alias values into GitHub Actions
> **secrets** and keeping only `release-key.p12` committed (the workflow can be
> switched to read from `${{ secrets.* }}`). For a private repo this is fine.
> Never lose `release-key.p12` — losing it means you can no longer ship updates
> that install over existing installs.

To bump `versionCode` per build (required for updates to apply), the workflow
already rewrites `versionCode` in `android/app/build.gradle` to the run number.

### Get the APK

- **Actions tab → latest run → Artifacts → `csl-wiki-apk`**, or
- push a tag and grab it from the Release.

## License

MIT
