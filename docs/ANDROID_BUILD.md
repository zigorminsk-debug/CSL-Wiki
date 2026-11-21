# Android build

## Local build

Requirements: Node 22 recommended, JDK 17, Android SDK platform/build tools 34.
The native Android project is already present; do not run `cap add android` again.
The active Capacitor config is the repository-root `capacitor.config.json`.
`config/capacitor.config.ts` is only an example with matching settings.

```sh
npm ci
npm test
npx cap sync android
cd android
# On Unix, if needed: chmod +x gradlew
./gradlew assembleDebug
```

Output: `android/app/build/outputs/apk/debug/app-debug.apk`.
Debug builds use application ID `com.csl.wiki.debug` and display name
**CSL-Wiki Test**, so they can be installed alongside the release app without
uninstalling it. They have a separate offline cache; sign in again to test.

Native HTTP is enabled in the root Capacitor config, so real-server login goes
directly to Confluence, without the Node proxy and without browser CORS.
The phone must trust the server TLS certificate. Do not disable TLS verification.

A local debug APK has a different signing identity from a previously installed
release APK. Do not uninstall the old app without backing up unsent offline
changes: uninstalling deletes app-local storage.

## Signed release

Keep signing keys **outside Git**. Set these Gradle project properties via a
local, untracked Gradle user properties file or environment variables:

- `ORG_GRADLE_PROJECT_RELEASE_STORE_FILE` — absolute keystore path
- `ORG_GRADLE_PROJECT_RELEASE_STORE_PASSWORD`
- `ORG_GRADLE_PROJECT_RELEASE_KEY_ALIAS`
- `ORG_GRADLE_PROJECT_RELEASE_KEY_PASSWORD`
- Optional `ORG_GRADLE_PROJECT_RELEASE_STORE_TYPE` (default `pkcs12`)

Then run `./gradlew assembleRelease` in `android/`. Without a signing config a
local release is unsigned and cannot be installed as-is; use debug instead.

## GitHub Actions

The workflow installs locked dependencies, runs the tests, syncs Capacitor,
bakes in the build version, and builds an APK. On ordinary branch pushes it
falls back to a debug APK if no signing secrets exist. Tags require release
signing secrets and publish only signed release APKs.

Configure repository Actions secrets through GitHub settings (never in chat):

- `ANDROID_KEYSTORE_BASE64` — Base64-encoded PKCS12 keystore
- `ANDROID_STORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The key is decoded to runner temporary storage and removed after the build.
Download the `csl-wiki-apk` artifact from the workflow run.

Use the same **trusted** signing identity and a higher versionCode for in-place
updates. The old source branch contained a committed key and signing passwords;
they were not imported into this branch. Treat that identity as exposed to
anyone with access to the old history. Moving it into secrets does not undo
exposure. Plan key rotation/distribution appropriately; a new signing key usually
cannot update a sideloaded APK signed with the old key.

## Hosted PWA / TWA alternative

Host the Node service behind HTTPS. A TWA wraps that hosted site rather than
bundling it, and therefore uses the PWA's same-origin proxy. Configure
`CSL_ALLOWED_ORIGINS` for the real Confluence origins, and restrict access to the
service. A static host by itself does not run `server/server.js`.

## Update notifications

The app checks this repository's latest GitHub Release. PWA reloads to pick up
new assets; an APK download still requires normal Android user confirmation.
This is not a silent native installer. Correct signing and a higher versionCode
are required to preserve an existing app's data during an update.
