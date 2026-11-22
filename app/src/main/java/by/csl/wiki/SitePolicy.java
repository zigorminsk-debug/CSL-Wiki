package by.csl.wiki;

import java.net.URI;
import java.net.URLEncoder;

/** One trusted HTTPS host. No arbitrary credential proxy or Basic auth. */
public final class SitePolicy {
    public static final String DEFAULT_BASE = "https://w.csl.by";
    public static String normalizeBase(String input) {
        try {
            URI uri = URI.create(input.trim());
            if (!trusted(uri) || uri.getRawQuery() != null || uri.getRawFragment() != null) throw new IllegalArgumentException();
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            if (!path.matches("(/[A-Za-z0-9_-]+)*/?")) throw new IllegalArgumentException();
            return DEFAULT_BASE + path.replaceAll("/+$", "");
        } catch (Exception e) {
            throw new IllegalArgumentException("Нужен HTTPS-адрес w.csl.by, при необходимости с путём, например /confluence.");
        }
    }
    private static boolean trusted(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) && "w.csl.by".equalsIgnoreCase(uri.getHost())
                && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getRawUserInfo() == null;
    }
    public static boolean isTrusted(String url) {
        try { return trusted(URI.create(url)); } catch (Exception e) { return false; }
    }
    public static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8"); }
        catch (java.io.UnsupportedEncodingException e) { throw new AssertionError(e); }
    }
    public static String searchCql(String query) {
        String q = query.trim();
        if (q.isEmpty() || q.length() > 200) throw new IllegalArgumentException("Введите запрос от 1 до 200 символов.");
        String escaped = q.replace("\\", "\\\\").replace("\"", "\\\"").replaceAll("[\\r\\n\\t]", " ");
        return "type = page AND (title ~ \"" + escaped + "\" OR text ~ \"" + escaped + "\")";
    }
    public static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
    private SitePolicy() {}
}
