package by.csl.wiki;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import static org.junit.Assert.*;

/** No real-server access or credentials. Verifies that the native Activity actually launches. */
@RunWith(AndroidJUnit4.class)
public class WelcomeSmokeTest {
    @Test public void welcomeRendersAndSurvivesActivityRecreation() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            assertWelcome(scenario);
            scenario.recreate();
            assertWelcome(scenario);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            android.os.SystemClock.sleep(500); // screenshot only; assertions above are synchronous
            Bitmap image = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            assertNotNull(image);
            try (FileOutputStream out = new FileOutputStream(new File(context.getExternalFilesDir(null), "welcome.png"))) {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, out));
            }
            image.recycle();
            // Gradle may uninstall the tested app after the run, deleting externalFilesDir.
            // Copy the non-sensitive welcome screenshot to shell-owned temporary storage first.
            try (android.os.ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .executeShellCommand("cp /sdcard/Android/data/by.csl.wiki/files/welcome.png /data/local/tmp/csl-welcome.png && echo COPIED");
                 java.io.InputStream result = new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[1024]; int count;
                while ((count = result.read(buffer)) != -1) bytes.write(buffer, 0, count);
                assertTrue(bytes.toString("UTF-8").contains("COPIED"));
            }
        }
    }
    private static void assertWelcome(ActivityScenario<MainActivity> scenario) {
        scenario.onActivity(activity -> {
            View root = activity.getWindow().getDecorView();
            assertTrue(contains(root, "Знания команды."));
            assertTrue(contains(root, "Войти в Confluence"));
            assertTrue(contains(root, "Параметры подключения"));
        });
    }
    private static boolean contains(View view, String text) {
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(text)) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (contains(group.getChildAt(i), text)) return true;
        }
        return false;
    }
}
