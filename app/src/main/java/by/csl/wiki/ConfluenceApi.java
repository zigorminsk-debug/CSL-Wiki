package by.csl.wiki;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ConfluenceApi {
    public interface Cookies { String get(String url); default void set(String url, String value) {} }
    public static final class Failure extends Exception {
        public final int status;
        public Failure(String message, int status) { super(message); this.status = status; }
        public boolean authentication() { return status == 401 || status == 403; }
    }
    public static final class Space {
        public final String key, name;
        Space(String key, String name) { this.key = key; this.name = name; }
    }
    public static final class Page {
        public String id = "", title = "", space = "", body = "", url = "", updated = "";
        public int version = 1;
        public boolean saved;
    }
    public static final class Slice<T> {
        public final List<T> items;
        public final int next;
        Slice(List<T> items, int next) { this.items = items; this.next = next; }
    }
    private final String base;
    private final Cookies cookies;
    private final String userAgent;
    public ConfluenceApi(String base, Cookies cookies) { this(base, cookies, "CSLWiki-Android/0.1"); }
    public ConfluenceApi(String base, Cookies cookies, String userAgent) {
        this.base = SitePolicy.normalizeBase(base); this.cookies = cookies; this.userAgent = userAgent;
    }
    public static final class User {
        public final String name, identity;
        User(String name, String identity) { this.name = name; this.identity = identity; }
    }
    public User currentUser() throws Exception {
        JSONObject user = request("/user/current");
        String name = authenticatedUser(user);
        String identity = user.optString("accountId");
        if (identity.isEmpty()) identity = user.optString("userKey");
        if (identity.isEmpty()) identity = user.optString("username");
        return new User(name, identity);
    }
    static String authenticatedUser(JSONObject user) throws Failure {
        if ("anonymous".equalsIgnoreCase(user.optString("type")) ||
                (user.optString("username").isEmpty() && user.optString("userKey").isEmpty() && user.optString("accountId").isEmpty())) {
            throw new Failure("Сервер не подтвердил вход. Введите логин и пароль в приложении.", 401);
        }
        String name = user.optString("displayName", user.optString("username", "Пользователь"));
        return name.isEmpty() ? "Пользователь" : name;
    }
    public Slice<Space> spaces(int start) throws Exception {
        JSONObject data = request("/space?limit=50&start=" + start);
        JSONArray rows = results(data);
        List<Space> items = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            items.add(new Space(row.getString("key"), row.optString("name", row.getString("key"))));
        }
        return new Slice<>(items, next(data, start, rows.length()));
    }
    public Slice<Page> pages(String key, int start) throws Exception {
        return pageList("/content?type=page&spaceKey=" + SitePolicy.encode(key) + "&expand=space,version&limit=50&start=" + start, start);
    }
    public Slice<Page> search(String query, int start) throws Exception {
        return pageList("/content/search?cql=" + SitePolicy.encode(SitePolicy.searchCql(query))
                + "&expand=space,version&limit=50&start=" + start, start);
    }
    private Slice<Page> pageList(String path, int start) throws Exception {
        JSONObject data = request(path);
        JSONArray rows = results(data);
        List<Page> pages = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) pages.add(parsePage(rows.getJSONObject(i), false));
        return new Slice<>(pages, next(data, start, rows.length()));
    }
    public Page page(String id) throws Exception {
        if (!id.matches("[0-9]+")) throw new Failure("Неверный идентификатор страницы.", 400);
        return parsePage(request("/content/" + id + "?expand=body.storage,version,space"), true);
    }
    Page parsePage(JSONObject row, boolean requireBody) throws Exception {
        Page p = new Page();
        p.id = row.getString("id");
        p.title = row.optString("title", "Без названия");
        p.space = row.optJSONObject("space") == null ? "Confluence" : row.getJSONObject("space").optString("name", "Confluence");
        JSONObject version = row.optJSONObject("version");
        if (version != null) { p.version = version.optInt("number", 1); p.updated = version.optString("when", ""); }
        JSONObject body = row.optJSONObject("body");
        JSONObject storage = body == null ? null : body.optJSONObject("storage");
        if (requireBody && storage == null) throw new Failure("API не вернул тело статьи. Проверьте права доступа к странице.", 422);
        if (storage != null) p.body = storage.optString("value", "");
        // Do not trust an arbitrary server-supplied _links.base/webui URL.
        p.url = base + "/pages/viewpage.action?pageId=" + SitePolicy.encode(p.id);
        return p;
    }
    private static JSONArray results(JSONObject data) throws Failure {
        JSONArray rows = data.optJSONArray("results");
        if (rows == null) throw new Failure("Ответ не похож на Confluence REST API: нет списка results. Проверьте путь установки.", 422);
        return rows;
    }
    private static int next(JSONObject data, int start, int count) {
        JSONObject links = data.optJSONObject("_links");
        return count > 0 && links != null && !links.optString("next").isEmpty() ? start + count : -1;
    }
    private JSONObject request(String path) throws Exception {
        String address = base + "/rest/api" + path;
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(25000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", userAgent);
        connection.setRequestProperty("Referer", base + "/");
        connection.setUseCaches(false);
        String cookie = cookies.get(address);
        if (cookie != null && !cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
        try {
            int status = connection.getResponseCode();
            for (java.util.Map.Entry<String, java.util.List<String>> header : connection.getHeaderFields().entrySet()) {
                if (header.getKey() != null && header.getKey().equalsIgnoreCase("Set-Cookie")) {
                    for (String value : header.getValue()) cookies.set(address, value);
                }
            }
            if (status == 401) throw new Failure("Сессия закончилась или вход ещё не выполнен. Войдите в аккаунт заново.", status);
            if (status == 403) throw new Failure("Confluence запретил запрос (HTTP 403). Возможны недостаточные права, CAPTCHA или блокировка сессии.", status);
            if (status >= 300 && status < 400) throw new Failure("Сервер перенаправляет API-запрос. Проверьте вход и путь установки Confluence.", 401);
            if (status == 404) throw new Failure("REST API не найден (HTTP 404). Если Confluence установлен в /confluence или /wiki, укажите этот путь в настройках.", status);
            if (status < 200 || status >= 300) throw new Failure("Сервер вернул HTTP " + status + ". Попробуйте ещё раз позже.", status);
            String text;
            try (InputStream stream = connection.getInputStream()) { text = readLimited(stream); }
            return parseJsonResponse(text, connection.getContentType());
        } finally { connection.disconnect(); }
    }
    public byte[] image(String address) throws Exception {
        if (!SitePolicy.isTrusted(address) || !URIPath.isImagePath(address)) throw new Failure("Недоверенный источник изображения.", 400);
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setInstanceFollowRedirects(false); c.setConnectTimeout(15000); c.setReadTimeout(25000);
        c.setRequestProperty("User-Agent", userAgent); c.setRequestProperty("Accept", "image/*");
        String cookie = cookies.get(address); if (cookie != null && !cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
        try {
            int status = c.getResponseCode();
            if (status != 200 || c.getContentType() == null || !c.getContentType().toLowerCase(java.util.Locale.ROOT).startsWith("image/")) throw new Failure("Изображение недоступно.", status);
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) { if (bytes.size() + n > 6 * 1024 * 1024) throw new Failure("Изображение больше 6 МБ.", 413); bytes.write(buffer, 0, n); }
                return bytes.toByteArray();
            }
        } finally { c.disconnect(); }
    }
    private static final class URIPath {
        static boolean isImagePath(String url) {
            String path = java.net.URI.create(url).getPath();
            return path.contains("/download/attachments/") || path.contains("/download/thumbnails/") || path.contains("/images/") || path.contains("/download/resources/");
        }
    }
    static JSONObject parseJsonResponse(String text, String contentType) throws Failure {
        String trimmed = text.trim();
        if (trimmed.startsWith("<") || (contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).contains("text/html"))) {
            throw new Failure("Вместо REST API пришла HTML-страница. Возможно, сессия истекла или указан неверный путь Confluence. Откройте вход и проверьте настройки.", 422);
        }
        try { return new JSONObject(text); }
        catch (JSONException e) { throw new Failure("Сервер вернул некорректный JSON. Это не подтверждает ошибку пароля.", 422); }
    }
    static String readLimited(InputStream stream) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            if (bytes.size() + read > 12 * 1024 * 1024) throw new IOException("Статья слишком большая (более 12 МБ). Откройте её на сайте.");
            bytes.write(buffer, 0, read);
        }
        return bytes.toString(StandardCharsets.UTF_8.name());
    }
}
