/**
 * Auto-update from GitHub Releases.
 *
 * On launch and periodically (and whenever connectivity returns) the app asks
 * GitHub for the latest published Release of this repo. If the release's version
 * is newer than the version baked into the running app (public/version.json),
 * an update banner is shown:
 *   - Installed PWA      -> reload (the new assets are already deployed).
 *   - Bundled APK        -> download the release APK and install over the
 *                          existing app (same signing key => seamless update).
 *
 * The GitHub REST API sends `Access-Control-Allow-Origin: *`, so the browser/
 * WebView can call it directly (no proxy, no token, just rate-limited).
 */
import { CONFIG } from './config.js';

const REPO = CONFIG.UPDATE_REPO || 'zigorminsk-debug/CSL-Wiki';
const API = `https://api.github.com/repos/${REPO}/releases/latest`;
const CHECK_INTERVAL = 30 * 60 * 1000; // 30 minutes

let localVersion = { version: '0.0.0', versionCode: 1 };
let lastCheck = 0;
let onUpdate = null;

function parseVer(v) {
  return String(v || '').replace(/^v/i, '').split('.').map((n) => parseInt(n, 10) || 0);
}
function compare(a, b) {
  const A = parseVer(a), B = parseVer(b);
  for (let i = 0; i < Math.max(A.length, B.length); i++) {
    const x = A[i] || 0, y = B[i] || 0;
    if (x !== y) return x - y;
  }
  return 0;
}

export async function loadLocalVersion() {
  try {
    localVersion = await (await fetch('./version.json')).json();
  } catch { /* keep defaults */ }
  return localVersion;
}

export function setUpdateHandler(fn) { onUpdate = fn; }

export async function checkForUpdate() {
  const now = Date.now();
  if (now - lastCheck < CHECK_INTERVAL) return null;
  lastCheck = now;
  try {
    const res = await fetch(API, { headers: { Accept: 'application/vnd.github+json' } });
    if (!res.ok) return null; // 404 = no releases published yet
    const data = await res.json();
    const tag = data.tag_name || '';
    const apk = (data.assets || []).find((a) => /\.apk$/i.test(a.name));
    const remote = {
      version: tag.replace(/^v/i, ''),
      tag,
      notes: data.body || '',
      publishedAt: data.published_at,
      apkUrl: apk ? apk.browser_download_url : ''
    };
    return compare(remote.version, localVersion.version) > 0 ? remote : null;
  } catch {
    return null;
  }
}

export async function pollUpdate() {
  const remote = await checkForUpdate();
  if (remote && onUpdate) onUpdate(remote);
  return remote;
}

export function applyUpdate(remote) {
  const isApk = !!window.Capacitor;
  if (isApk && remote.apkUrl) {
    // Download + install over the existing app (same signing key).
    window.location.href = remote.apkUrl;
  } else {
    // PWA: new assets are already deployed — just reload.
    window.location.reload();
  }
}
