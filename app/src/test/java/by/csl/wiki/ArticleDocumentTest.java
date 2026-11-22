package by.csl.wiki;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class ArticleDocumentTest {
    @Test public void splitsHeadingParagraphListCodeAndTableIntoNativeBlocks() {
        ConfluenceApi.Page p = new ConfluenceApi.Page();
        p.body = "<h2>VPN</h2><p>Откройте <strong>настройки</strong>.</p><ul><li>Первый</li><li>Второй</li></ul><pre>a &lt; b</pre><table><tbody><tr><th>Адрес</th><th>Порт</th></tr><tr><td>vpn</td><td>443</td></tr></tbody></table>";
        List<ArticleDocument.Block> blocks = ArticleDocument.parse(p);
        assertEquals("heading", blocks.get(0).kind); assertEquals("paragraph", blocks.get(1).kind);
        assertTrue(blocks.get(1).html.contains("<strong>"));
        assertEquals("bullet", blocks.get(2).kind); assertEquals("code", blocks.get(4).kind);
        assertEquals("a < b", blocks.get(4).text); assertEquals(2, blocks.get(5).rows.size());
        assertEquals("443", blocks.get(5).rows.get(1).get(1));
    }
    @Test public void stripsActiveContentAndDoesNotUseExternalImageUrls() {
        ConfluenceApi.Page p = new ConfluenceApi.Page(); p.body = "<script>steal()</script><iframe src='https://evil.example'></iframe><img src='https://evil.example/pixel'><p>Безопасный текст</p>";
        List<ArticleDocument.Block> blocks = ArticleDocument.parse(p);
        assertEquals("image", blocks.get(0).kind); assertEquals("", blocks.get(0).url);
        assertEquals("Безопасный текст", blocks.get(1).html);
        assertEquals(2, blocks.size());
    }
    @Test public void convertsConfluenceAttachmentToNativeImageBlock() {
        ConfluenceApi.Page p = new ConfluenceApi.Page(); p.id = "42"; p.url = "https://w.csl.by/pages/viewpage.action?pageId=42";
        p.body = "<ac:image><ri:attachment ri:filename='VPN.png'/></ac:image>";
        List<ArticleDocument.Block> blocks = ArticleDocument.parse(p);
        assertEquals("image", blocks.get(0).kind);
        assertEquals("https://w.csl.by/download/attachments/42/VPN.png", blocks.get(0).url);
    }
}
