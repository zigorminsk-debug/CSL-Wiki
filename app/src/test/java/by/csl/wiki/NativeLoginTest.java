package by.csl.wiki;

import org.junit.Test;
import static org.junit.Assert.*;

public class NativeLoginTest {
    private static final String FORM = "<form method='post' action='dologin.action'><input type='hidden' name='atl_token' value='csrf&amp;value'><input name='os_username'><input name='os_password' type='password'><input type='hidden' name='os_cookie' value='true'></form>";
    @Test public void parsesStandardConfluenceFormAndPreservesCsrf() throws Exception {
        NativeLogin.Form form = NativeLogin.parseForm("https://w.csl.by", "https://w.csl.by/login.action", FORM);
        assertEquals("https://w.csl.by/dologin.action", form.action);
        assertEquals("csrf&value", form.fields.get("atl_token"));
        assertFalse(form.fields.containsKey("os_cookie"));
        assertFalse(form.fields.containsKey("os_password"));
        assertEquals("/index.action", form.fields.get("os_destination"));
    }
    @Test public void handlesContextPath() throws Exception {
        NativeLogin.Form form = NativeLogin.parseForm("https://w.csl.by/confluence", "https://w.csl.by/confluence/login.action", FORM);
        assertEquals("https://w.csl.by/confluence/dologin.action", form.action);
        assertEquals("/confluence/index.action", form.fields.get("os_destination"));
    }
    @Test public void encodesUnicodeAndSpecialCharactersWithoutChangingPassword() throws Exception {
        NativeLogin.Form form = NativeLogin.parseForm("https://w.csl.by", "https://w.csl.by/login.action", FORM);
        java.util.Map<String,String> fields = NativeLogin.credentials(form, " тест ", " +&=пароль ");
        assertEquals("тест", fields.get("os_username")); assertEquals(" +&=пароль ", fields.get("os_password"));
        String body = NativeLogin.encode(fields);
        assertTrue(body.contains("os_password=+%2B%26%3D%D0"));
        assertTrue(body.contains("atl_token=csrf%26value"));
    }
    @Test public void neverPostsCredentialsOutsideLoginEndpoint() throws Exception {
        for (String action : new String[]{"https://evil.example/dologin.action", "http://w.csl.by/dologin.action", "/pages/capture.action", "//w.csl.by.evil.test/dologin.action"}) {
            try { NativeLogin.parseForm("https://w.csl.by", "https://w.csl.by/login.action", FORM.replace("action='dologin.action'", "action='"+action+"'")); fail(action); }
            catch (ConfluenceApi.Failure e) { assertEquals(422, e.status); }
        }
    }
    @Test public void captchaIsReportedBeforeSendingPassword() throws Exception {
        try { NativeLogin.parseForm("https://w.csl.by", "https://w.csl.by/login.action", FORM.replace("</form>", "<input name='os_captcha'></form>")); fail(); }
        catch (ConfluenceApi.Failure e) { assertEquals(428, e.status); }
    }
    @Test public void ssoAndMissingFormsDoNotBecomeSuccess() throws Exception {
        try { NativeLogin.parseForm("https://w.csl.by", "https://w.csl.by/login.action", "<html>SSO only</html>"); fail(); }
        catch (ConfluenceApi.Failure e) { assertEquals(422, e.status); }
    }
    @Test public void onlyAllowsTrustedGetRedirectsAndNoCredentialReplay() throws Exception {
        assertEquals("https://w.csl.by/index.action", NativeLogin.trustedRedirect("https://w.csl.by/dologin.action", "/index.action"));
        try { NativeLogin.trustedRedirect("https://w.csl.by/dologin.action", "https://sso.example/login"); fail(); }
        catch (ConfluenceApi.Failure e) { assertEquals(422, e.status); }
    }
}
