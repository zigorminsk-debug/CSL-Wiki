/**
 * Client configuration.
 *
 * Mode is chosen at runtime from the login screen and stored in localStorage
 * (see auth.js): 'mock' uses the bundled demo server; 'real' connects to the
 * user's own Confluence via the Node proxy (CORS-safe).
 *
 * For a real server the app sends requests to PROXY_BASE (same-origin) which the
 * Node server forwards to the user's server URL with their Basic auth. For the
 * bundled APK, set PROXY_BASE to the absolute URL of a deployed Node server
 * (the proxy must be hosted somewhere the app can reach).
 */
export const CONFIG = {
  // Demo mode toggle (used when the user picks "Demo" on the start screen).
  USE_MOCK: true,

  // Real-server mode: talk to the user's Confluence through the Node proxy.
  USE_PROXY: true,
  PROXY_BASE: '/api/csl',          // same-origin proxy path (PWA served by Node)
  // For a bundled Capacitor APK, set this to e.g. 'https://csl-proxy.example/api/csl'

  // Defaults pre-filled on the login screen.
  DEFAULT_SERVER: 'https://w.csl.by',
  DEFAULT_API_PATH: '/wiki/api/v2', // Confluence v2 REST API path (use /rest/api for older)

  // Optional AI endpoint for the agent (OpenAI-compatible chat completions).
  AGENT_API_URL: '',
  AGENT_API_KEY: '',
  AGENT_MODEL: 'gpt-4o-mini',

  // Auto-update: which GitHub repo's Releases to watch.
  UPDATE_REPO: 'zigorminsk-debug/CSL-Wiki',

  CACHE_VERSION: 'csl-wiki-v1'
};
