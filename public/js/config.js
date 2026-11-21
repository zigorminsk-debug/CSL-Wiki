/**
 * Client configuration.
 *
 * By default the app talks to the bundled mock server (same origin). To use a
 * real Confluence Cloud tenant, set USE_MOCK = false and fill in the base URL
 * and an API token. Confluence's REST API does not send CORS headers for
 * browser apps, so in production you should proxy requests through a backend
 * (this repo's server can be extended to do that) — see docs/ANDROID_BUILD.md.
 */
export const CONFIG = {
  USE_MOCK: true,

  // Real Confluence Cloud example:
  // CONFLUENCE_BASE_URL: 'https://your-tenant.atlassian.net/wiki',
  // CONFLUENCE_API_TOKEN: 'your-api-token',
  // CONFLUENCE_EMAIL: 'you@example.com',
  CONFLUENCE_BASE_URL: '',
  CONFLUENCE_API_TOKEN: '',
  CONFLUENCE_EMAIL: '',

  // Optional AI endpoint for the agent (OpenAI-compatible chat completions).
  // Leave empty to use the built-in, fully offline keyword agent.
  AGENT_API_URL: '',
  AGENT_API_KEY: '',
  AGENT_MODEL: 'gpt-4o-mini',

  // App-shell cache version for the service worker.
  CACHE_VERSION: 'csl-wiki-v1'
};
