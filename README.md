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

## License

MIT
