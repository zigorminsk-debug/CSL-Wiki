package by.csl.wiki;

import org.json.JSONObject;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class ConfluenceApiTest {
    @Test public void identifiesKnownUser() throws Exception {
        assertEquals("Иван", ConfluenceApi.authenticatedUser(new JSONObject("{\"type\":\"known\",\"username\":\"ivan\",\"displayName\":\"Иван\"}")));
        assertEquals("Пользователь", ConfluenceApi.authenticatedUser(new JSONObject("{\"accountId\":\"42\"}")));
    }
    @Test public void anonymousAndEmptyResponsesNeverAuthenticate() throws Exception {
        for (String value : new String[]{"{}", "{\"type\":\"anonymous\"}", "{\"type\":\"anonymous\",\"username\":\"guest\"}"}) {
            try { ConfluenceApi.authenticatedUser(new JSONObject(value)); fail("Anonymous accepted"); }
            catch (ConfluenceApi.Failure error) { assertEquals(401, error.status); }
        }
    }
    @Test public void htmlHasActionableErrorInsteadOfJsonCrash() throws Exception {
        for (String html : new String[]{"<!DOCTYPE html><title>Login</title>", "  <html>SSO</html>"}) {
            try { ConfluenceApi.parseJsonResponse(html, "text/html"); fail("HTML accepted"); }
            catch (ConfluenceApi.Failure error) { assertEquals(422, error.status); assertTrue(error.getMessage().contains("HTML")); }
        }
    }
    @Test public void badJsonIsNotReportedAsBadPassword() {
        try { ConfluenceApi.parseJsonResponse("not json", "application/json"); fail(); }
        catch (ConfluenceApi.Failure error) { assertTrue(error.getMessage().contains("не подтверждает ошибку пароля")); }
    }
    @Test public void decodesUtf8() throws Exception {
        String body = "{\"title\":\"Инструкция\"}";
        String decoded = ConfluenceApi.readLimited(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        assertEquals("Инструкция", ConfluenceApi.parseJsonResponse(decoded, "application/json").getString("title"));
    }
    @Test(expected = IOException.class) public void responseSizeIsBounded() throws Exception {
        ConfluenceApi.readLimited(new ByteArrayInputStream(new byte[12 * 1024 * 1024 + 1]));
    }
    @Test public void parsesRealServerStorageAndIgnoresUntrustedLinks() throws Exception {
        ConfluenceApi client = new ConfluenceApi("https://w.csl.by/confluence", url -> null);
        JSONObject data = new JSONObject("{\"id\":\"123\",\"title\":\"VPN\",\"space\":{\"name\":\"IT\"},\"version\":{\"number\":3},\"body\":{\"storage\":{\"value\":\"<p>Hello</p>\"}},\"_links\":{\"base\":\"https://evil.example\",\"webui\":\"/steal\"}}");
        ConfluenceApi.Page p = client.parsePage(data, true);
        assertEquals("123", p.id); assertEquals("IT", p.space); assertEquals(3, p.version);
        assertEquals("<p>Hello</p>", p.body);
        assertEquals("https://w.csl.by/confluence/pages/viewpage.action?pageId=123", p.url);
    }
    @Test public void absentBodyIsNotCachedAsAnEmptyArticle() throws Exception {
        ConfluenceApi client = new ConfluenceApi(SitePolicy.DEFAULT_BASE, url -> null);
        try { client.parsePage(new JSONObject("{\"id\":\"123\"}"), true); fail(); }
        catch (ConfluenceApi.Failure error) { assertEquals(422, error.status); }
    }
    @Test public void rendersConfluenceCodeAndAttachmentImagesSafely() {
        ConfluenceApi.Page p = new ConfluenceApi.Page(); p.id = "123";
        p.url = "https://w.csl.by/confluence/pages/viewpage.action?pageId=123";
        p.body = "<ac:plain-text-body><![CDATA[if (a < b) { alert(1); }]]></ac:plain-text-body>"
                + "<ac:image><ri:attachment ri:filename=\"vpn diagram.png\" /></ac:image>";
        String result = ArticleHtml.storage(p);
        assertTrue(result.contains("<pre><code>if (a &lt; b)"));
        assertTrue(result.contains("https://w.csl.by/confluence/download/attachments/123/vpn%20diagram.png"));
        assertFalse(result.contains("<![CDATA["));
    }
    @Test public void nativeDocumentRemovesScriptsAndKeepsParagraphs() {
        ConfluenceApi.Page p = new ConfluenceApi.Page(); p.body = "<script>alert(1)</script><p>Text</p>";
        java.util.List<ArticleDocument.Block> blocks = ArticleDocument.parse(p);
        assertEquals(1, blocks.size()); assertEquals("Text", blocks.get(0).html);
    }
}
