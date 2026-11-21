/** Runtime mode is selected on the login screen, not by build-time flags. */
export const CONFIG = {
  // PWA only: the Node server proxies requests to administrator-approved origins.
  // The bundled Android app uses native Capacitor HTTP directly instead.
  PROXY_BASE: '/api/csl',
  DEFAULT_SERVER: 'https://w.csl.by',
  DEFAULT_API_PATH: '/rest/api', // Server/Data Center; Cloud uses /wiki/api/v2

  // Optional OpenAI-compatible endpoint. Never embed a shared production secret
  // in a distributed APK/PWA; use an authenticated backend for that purpose.
  AGENT_API_URL: '',
  AGENT_API_KEY: '',
  AGENT_MODEL: 'gpt-4o-mini',
  UPDATE_REPO: 'zigorminsk-debug/CSL-Wiki'
};
