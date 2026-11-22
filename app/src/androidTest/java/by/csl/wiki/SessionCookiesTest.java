package by.csl.wiki;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SessionCookiesTest {
    @Test public void persistsEncryptedSessionAndClearsItWithoutSavingPassword() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SessionCookies cookies = new SessionCookies(context); cookies.clear();
        cookies.set("https://w.csl.by/login.action", "JSESSIONID=secret-test-session; Path=/; Secure; HttpOnly");
        assertTrue(cookies.get("https://w.csl.by/rest/api/user/current").contains("secret-test-session"));
        String disk = context.getSharedPreferences("native-session", Context.MODE_PRIVATE).getAll().toString();
        assertFalse(disk.contains("secret-test-session")); assertFalse(disk.contains("JSESSIONID"));
        SessionCookies restored = new SessionCookies(context);
        assertTrue(restored.get("https://w.csl.by/rest/api/user/current").contains("secret-test-session"));
        assertNull(restored.get("https://evil.example/"));
        restored.clear();
        assertEquals("", new SessionCookies(context).get("https://w.csl.by/rest/api/user/current"));
    }
}
