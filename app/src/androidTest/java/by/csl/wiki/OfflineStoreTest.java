package by.csl.wiki;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Exercises real Android SQLite persistence, not a fake browser database. */
@RunWith(AndroidJUnit4.class)
public class OfflineStoreTest {
    @Test public void savedArticleSurvivesReopenAndCanBeSearchedAndCleared() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        OfflineStore first = new OfflineStore(context);
        first.clear();
        ConfluenceApi.Page page = new ConfluenceApi.Page();
        page.id = "123"; page.title = "Настройка VPN"; page.space = "IT";
        page.body = "<p>Корпоративная сеть</p>";
        page.url = "https://w.csl.by/pages/viewpage.action?pageId=123";
        first.put(page); first.setSaved(page.id, true); first.close();
        OfflineStore reopened = new OfflineStore(context);
        try {
            assertEquals(page.body, reopened.get("123").body);
            assertTrue(reopened.get("123").saved);
            assertEquals(1, reopened.savedCount());
            assertEquals(1, reopened.list(false, "настройка").size());
            page.version = 2; reopened.put(page);
            assertTrue(reopened.get("123").saved);
            reopened.setSaved("123", false);
            assertEquals(0, reopened.savedCount());
            assertNotNull(reopened.get("123"));
            reopened.clear();
            assertNull(reopened.get("123"));
            assertTrue(reopened.list(false, "").isEmpty());
        } finally { reopened.clear(); reopened.close(); }
    }
}
