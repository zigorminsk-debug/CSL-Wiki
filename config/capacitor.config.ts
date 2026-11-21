import type { CapacitorConfig } from '@capacitor/cli';

/**
 * Capacitor configuration for packaging CSL-Wiki as a native Android app.
 *
 * The PWA in ../public is bundled into the APK, so the app works fully offline
 * on Android. Build steps are in docs/ANDROID_BUILD.md.
 *
 * To target a *hosted* PWA instead of bundled assets, set:
 *   server: { url: 'https://your-hosted-pwa.example', cleartext: false }
 * and comment out `android.allowMixedContent`.
 */
const config: CapacitorConfig = {
  appId: 'com.csl.wiki',
  appName: 'CSL-Wiki',
  webDir: 'public',
  server: {
    androidScheme: 'https',
    // Allow the bundled app to call plain http backends in dev if needed.
    allowMixedContent: true
  },
  android: {
    // Keep WebView storage (IndexedDB) across app updates.
    webContentsDebuggingEnabled: false
  },
  plugins: {}
};

export default config;
