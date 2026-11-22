package by.csl.wiki;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.security.KeyStore;
import java.util.Collections;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Native cookie jar. The persisted session is encrypted with an Android Keystore key. */
public final class SessionCookies implements ConfluenceApi.Cookies {
    private static final String ALIAS = "csl-native-session-v1";
    private final CookieManager manager = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    private final SharedPreferences prefs;
    private final java.util.Map<HttpCookie, Long> expiry = new java.util.IdentityHashMap<>();
    public SessionCookies(Context context) {
        prefs = context.getSharedPreferences("native-session", Context.MODE_PRIVATE);
        try {
            String encrypted = prefs.getString("cookies", "");
            if (encrypted.isEmpty()) return;
            byte[] iv = Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            JSONArray rows = new JSONArray(new String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), java.nio.charset.StandardCharsets.UTF_8));
            long now = System.currentTimeMillis();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                long expires = row.optLong("expires", 0);
                if (expires > 0 && expires <= now) continue;
                HttpCookie cookie = new HttpCookie(row.getString("name"), row.getString("value"));
                cookie.setDomain(row.getString("domain")); cookie.setPath(row.getString("path"));
                cookie.setSecure(true); cookie.setHttpOnly(row.optBoolean("httpOnly", true)); cookie.setVersion(0);
                if (expires > 0) cookie.setMaxAge(Math.max(1, (expires - now) / 1000));
                manager.getCookieStore().add(URI.create(SitePolicy.DEFAULT_BASE), cookie);
                expiry.put(cookie, expires);
            }
        } catch (Exception e) { clear(); }
    }
    @Override public synchronized String get(String url) {
        if (!SitePolicy.isTrusted(url)) return null;
        try { return String.join("; ", manager.get(URI.create(url), Collections.emptyMap()).getOrDefault("Cookie", Collections.emptyList())); }
        catch (Exception e) { return null; }
    }
    @Override public synchronized void set(String url, String value) {
        if (!SitePolicy.isTrusted(url)) return;
        try { manager.put(URI.create(url), Collections.singletonMap("Set-Cookie", Collections.singletonList(value))); persist(); }
        catch (Exception ignored) { /* never print cookie material */ }
    }
    public synchronized void replace(Iterable<HttpCookie> cookies) throws Exception {
        manager.getCookieStore().removeAll(); expiry.clear();
        for (HttpCookie cookie : cookies) {
            if (!cookie.hasExpired()) manager.getCookieStore().add(URI.create(SitePolicy.DEFAULT_BASE), cookie);
        }
        persist();
    }
    public synchronized void clear() { manager.getCookieStore().removeAll(); expiry.clear(); prefs.edit().clear().commit(); }
    private void persist() throws Exception {
        JSONArray rows = new JSONArray(); long now = System.currentTimeMillis();
        for (HttpCookie cookie : manager.getCookieStore().getCookies()) {
            if (cookie.hasExpired()) continue;
            rows.put(new JSONObject().put("name", cookie.getName()).put("value", cookie.getValue())
                .put("domain", cookie.getDomain() == null ? "w.csl.by" : cookie.getDomain())
                .put("path", cookie.getPath() == null ? "/" : cookie.getPath()).put("httpOnly", cookie.isHttpOnly())
                .put("expires", cookie.getMaxAge() < 0 ? 0 : expiry.computeIfAbsent(cookie, ignored -> now + cookie.getMaxAge() * 1000)));
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        String encrypted = Base64.encodeToString(cipher.doFinal(rows.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)), Base64.NO_WRAP);
        if (!prefs.edit().putString("cookies", encrypted).putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)).commit()) {
            throw new java.io.IOException("Не удалось сохранить сессию на устройстве.");
        }
    }
    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
}
