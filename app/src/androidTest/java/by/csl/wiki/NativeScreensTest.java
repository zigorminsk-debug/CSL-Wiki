package by.csl.wiki;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class NativeScreensTest {
    @Test public void loginIsNativeAndEmptyCredentialsNeverHitNetwork() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                findText(activity.getWindow().getDecorView(), "Войти в Confluence").performClick();
                assertTrue(activity.findViewById(R.id.login_username) instanceof EditText);
                assertTrue(activity.findViewById(R.id.login_password) instanceof EditText);
                assertFalse(((EditText) activity.findViewById(R.id.login_password)).isSaveEnabled());
                activity.findViewById(R.id.login_submit).performClick();
                assertEquals("Введите логин и пароль.", ((TextView) activity.findViewById(R.id.login_error)).getText().toString());
                assertNoBrowser(activity.getWindow().getDecorView());
                assertNull(findText(activity.getWindow().getDecorView(), "Проверить вход"));
            });
            capture(context, "native-login");
        }
    }
    @Test public void offlineArticleUsesNativeTextAndTablesInsteadOfBrowser() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit();
        OfflineStore store = new OfflineStore(context); store.clear();
        ConfluenceApi.Page page = new ConfluenceApi.Page(); page.id = "42"; page.title = "Тестовая статья: настройка VPN"; page.space = "Проверка интерфейса";
        page.url = "https://w.csl.by/pages/viewpage.action?pageId=42";
        page.body = "<h2>Подключение к сети</h2><p>Откройте настройки и добавьте рабочее подключение.</p><table><tr><th>Параметр</th><th>Значение</th></tr><tr><td>Протокол</td><td>WireGuard</td></tr></table><pre>wg show</pre>";
        store.put(page);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                try {
                    java.lang.reflect.Method open = MainActivity.class.getDeclaredMethod("openPage", String.class, boolean.class); open.setAccessible(true); open.invoke(activity, "42", true);
                } catch (Exception e) { throw new AssertionError(e); }
                View root = activity.getWindow().getDecorView();
                assertNotNull(findText(root, "Подключение к сети")); assertNotNull(findText(root, "WireGuard")); assertNotNull(findText(root, "wg show"));
                assertNoBrowser(root);
            });
            capture(context, "native-article");
        } finally { store.clear(); store.close(); }
    }
    private static View findText(View view, String needle) {
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(needle)) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { View found = findText(((ViewGroup) view).getChildAt(i), needle); if (found != null) return found; }
        return null;
    }
    private static void assertNoBrowser(View view) {
        assertFalse("Embedded browser found", view instanceof android.webkit.WebView);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) assertNoBrowser(((ViewGroup) view).getChildAt(i));
    }
    private static void capture(Context context, String name) throws Exception {
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.waitForIdleSync(); android.os.SystemClock.sleep(500);
        android.graphics.Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot(); assertNotNull(bitmap);
        java.io.File target = new java.io.File(context.getExternalFilesDir(null), name + ".png");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(target)) { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)); }
        bitmap.recycle();
        try (android.os.ParcelFileDescriptor file = instrumentation.getUiAutomation().executeShellCommand("cp " + target.getAbsolutePath() + " /data/local/tmp/csl-" + name + ".png");
             java.io.InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(file)) { byte[] data = new byte[1024]; while (in.read(data) != -1) { /* wait for copy */ } }
    }
}
