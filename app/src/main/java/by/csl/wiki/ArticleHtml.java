package by.csl.wiki;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ArticleHtml {
    static String storage(ConfluenceApi.Page page) {
        String body = page.body;
        // Confluence's code/noformat macros use CDATA rather than HTML code tags.
        Matcher code = Pattern.compile("(?s)<ac:plain-text-body>\\s*<!\\[CDATA\\[(.*?)\\]\\]>\\s*</ac:plain-text-body>").matcher(body);
        StringBuffer out = new StringBuffer();
        while (code.find()) code.appendReplacement(out, Matcher.quoteReplacement("<pre><code>" + SitePolicy.escapeHtml(code.group(1)) + "</code></pre>"));
        code.appendTail(out);
        Matcher images = Pattern.compile("(?s)<ac:image\\b[^>]*>(.*?)</ac:image>").matcher(out.toString());
        out = new StringBuffer();
        while (images.find()) {
            String image = images.group(1);
            Matcher file = Pattern.compile("ri:filename=[\"']([^\"']+)[\"']").matcher(image);
            String replacement = "<p class=\"meta\">[Изображение доступно в оригинале статьи]</p>";
            if (file.find() && !image.contains("<ri:page") && page.id.matches("[0-9]+") && SitePolicy.isTrusted(page.url)) {
                String name = file.group(1).replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
                int cut = page.url.indexOf("/pages/viewpage.action");
                if (cut >= 0) {
                    String src = page.url.substring(0, cut) + "/download/attachments/" + page.id + "/" + SitePolicy.encode(name).replace("+", "%20");
                    replacement = "<img loading=\"lazy\" src=\"" + SitePolicy.escapeHtml(src) + "\" alt=\"" + SitePolicy.escapeHtml(name) + "\">";
                }
            }
            images.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        images.appendTail(out);
        return out.toString();
    }
    private ArticleHtml() {}
}
