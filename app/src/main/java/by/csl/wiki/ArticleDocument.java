package by.csl.wiki;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Converts Confluence storage into data for Android widgets, never a browser document. */
public final class ArticleDocument {
    public static final class Block {
        public String kind, html = "", text = "", url = "";
        public int level;
        public final List<List<String>> rows = new ArrayList<>();
        Block(String kind) { this.kind = kind; }
    }
    public static List<Block> parse(ConfluenceApi.Page page) {
        Element body = Jsoup.parseBodyFragment(ArticleHtml.storage(page)).body();
        body.select("script,style,iframe,object,embed,form,input,button,meta,link,base,ac|parameter").remove();
        for (Element reference : body.select("ri|page")) reference.text(reference.attr("ri:content-title"));
        List<Block> blocks = new ArrayList<>();
        for (Node node : body.childNodes()) visit(node, blocks, base(page));
        if (blocks.size() >= 400) { Block b = new Block("paragraph"); b.html = "Статья сокращена из-за большого объёма. Полная версия доступна по кнопке «Оригинал»."; blocks.add(b); }
        return blocks;
    }
    private static void visit(Node node, List<Block> out, String base) {
        if (out.size() >= 400) return;
        if (node instanceof TextNode) {
            String text = ((TextNode) node).text().trim();
            if (!text.isEmpty()) { Block block = new Block("paragraph"); block.html = SitePolicy.escapeHtml(text); out.add(block); }
            return;
        }
        if (!(node instanceof Element)) return;
        Element element = (Element) node;
        String tag = element.normalName();
        if (tag.equals("table")) {
            Block block = new Block("table");
            for (Element row : element.select("tr")) {
                if (block.rows.size() >= 100) break;
                List<String> cells = new ArrayList<>();
                for (Element cell : row.children()) { if (cells.size() < 12 && (cell.tagName().equals("td") || cell.tagName().equals("th"))) cells.add(cell.html()); }
                if (!cells.isEmpty()) block.rows.add(cells);
            }
            out.add(block); return;
        }
        if (tag.equals("img")) {
            Block block = new Block("image"); block.text = element.attr("alt");
            try { block.url = URI.create(base + "/").resolve(element.attr("src")).toString(); } catch (Exception ignored) { /* blocked below */ }
            if (!SitePolicy.isTrusted(block.url)) block.url = "";
            out.add(block); return;
        }
        if (tag.equals("pre")) { Block b = new Block("code"); b.text = element.wholeText(); out.add(b); return; }
        if (tag.matches("h[1-6]")) { Block b = new Block("heading"); b.level = Integer.parseInt(tag.substring(1)); b.html = element.html(); out.add(b); return; }
        if (tag.equals("li")) {
            Element inline = element.clone(); inline.select("ul,ol").remove();
            Block b = new Block("bullet"); b.html = inline.html(); out.add(b);
            for (Element child : element.children()) if (child.tagName().equals("ul") || child.tagName().equals("ol")) visit(child, out, base);
            return;
        }
        if (element.selectFirst("table,img,pre,ul,ol,h1,h2,h3,h4,h5,h6,p,div,ac|structured-macro") == null) {
            if (!element.text().trim().isEmpty()) { Block b = new Block("paragraph"); b.html = element.html(); out.add(b); }
            return;
        }
        // Elements containing only inline children stay a single selectable paragraph.
        boolean hasBlockChild = false;
        for (Element child : element.children()) if (child.isBlock() || child.tagName().equals("img") || child.tagName().startsWith("ac:")) hasBlockChild = true;
        if (!hasBlockChild && !element.text().trim().isEmpty()) { Block b = new Block("paragraph"); b.html = element.html(); out.add(b); return; }
        for (Node child : element.childNodes()) visit(child, out, base);
    }
    static String base(ConfluenceApi.Page page) {
        int index = page.url.indexOf("/pages/viewpage.action");
        return index >= 0 && SitePolicy.isTrusted(page.url) ? page.url.substring(0, index) : SitePolicy.DEFAULT_BASE;
    }
    private ArticleDocument() {}
}
