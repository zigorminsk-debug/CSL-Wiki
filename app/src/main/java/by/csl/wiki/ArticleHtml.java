package by.csl.wiki;

public final class ArticleHtml {
    public static String render(ConfluenceApi.Page page) {
        return "<!doctype html><html lang=\"ru\"><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src https://w.csl.by data:; style-src 'unsafe-inline'; form-action 'none'; base-uri 'none'\">"
            + "<style>:root{color-scheme:dark}body{margin:0;padding:24px 22px 64px;background:#0b1220;color:#dbe4f0;font:17px/1.75 sans-serif;overflow-wrap:anywhere}"
            + "h1{font-size:30px;line-height:1.25;color:#f5f8fc;margin:12px 0 24px}h2,h3,h4{color:#f5f8fc;line-height:1.4;margin-top:32px}"
            + "a{color:#48dcc3}img{max-width:100%;height:auto;border-radius:8px}pre{overflow:auto;background:#142236;padding:16px;border-radius:10px}"
            + "code{font-family:monospace;font-size:14px}table{display:block;max-width:100%;overflow:auto;border-collapse:collapse}td,th{border:1px solid #2a3b50;padding:10px;min-width:80px}"
            + "blockquote{border-left:3px solid #48dcc3;margin:20px 0;padding-left:16px;color:#9cacbf}.meta{font-size:12px;color:#8da0b8;letter-spacing:1px}"
            + "hr{border:0;border-top:1px solid #26384c}ac\\:structured-macro{display:block;background:#142236;padding:12px;border-radius:10px;margin:12px 0}</style></head><body>"
            + "<div class=\"meta\">" + SitePolicy.escapeHtml(page.space) + " · ВЕРСИЯ " + page.version + "</div>"
            + "<h1>" + SitePolicy.escapeHtml(page.title) + "</h1>" + page.body + "</body></html>";
    }
    private ArticleHtml() {}
}
