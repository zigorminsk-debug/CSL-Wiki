package by.csl.wiki;

import org.junit.Test;
import static org.junit.Assert.*;

public class SitePolicyTest {
    @Test public void normalizesTrustedContext() {
        assertEquals("https://w.csl.by", SitePolicy.normalizeBase(" https://w.csl.by/ "));
        assertEquals("https://w.csl.by/confluence", SitePolicy.normalizeBase("https://w.csl.by/confluence/"));
        assertEquals("https://w.csl.by/wiki", SitePolicy.normalizeBase("https://W.CSL.BY:443/wiki"));
    }
    @Test public void rejectsCredentialAndHostConfusion() {
        String[] invalid = {"http://w.csl.by", "https://w.csl.by.evil.example", "https://w.csl.by@evil.example", "https://user:secret@w.csl.by", "https://w.csl.by:8443", "https://w.csl.by?x=1", "https://w.csl.by/#x", "https://w.csl.by/a/../b", "https://w.csl.by/%2f%2fevil", "javascript:alert(1)", "file:///etc/passwd", "https://evil.example"};
        for (String address : invalid) {
            try { SitePolicy.normalizeBase(address); fail("Accepted " + address); } catch (IllegalArgumentException expected) { /* good */ }
        }
    }
    @Test public void navigationOnlyTrustsHttpsHost() {
        assertTrue(SitePolicy.isTrusted("https://w.csl.by/login.action?os_destination=%2F"));
        assertFalse(SitePolicy.isTrusted("intent://w.csl.by"));
        assertFalse(SitePolicy.isTrusted("https://w.csl.by.attacker.test/"));
        assertFalse(SitePolicy.isTrusted("https://w.csl.by@attacker.test/"));
        assertFalse(SitePolicy.isTrusted("data:text/html,test"));
    }
    @Test public void cqlEscapesUserInput() {
        assertEquals("type = page AND (title ~ \"VPN\" OR text ~ \"VPN\")", SitePolicy.searchCql(" VPN "));
        assertTrue(SitePolicy.searchCql("test\" OR type=blogpost").contains("test\\\" OR type=blogpost"));
        assertTrue(SitePolicy.searchCql("path\\name").contains("path\\\\name"));
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsEmptySearch() { SitePolicy.searchCql("  "); }
    @Test(expected = IllegalArgumentException.class) public void rejectsOversizedSearch() { SitePolicy.searchCql(new String(new char[201]).replace('\0', 'a')); }
    @Test public void unicodeQueryAndHtmlAreEncoded() {
        assertTrue(SitePolicy.encode("Настройка VPN").startsWith("%D0"));
        assertEquals("&lt;script&gt;&amp;&quot;&#39;", SitePolicy.escapeHtml("<script>&\"'"));
    }
}
