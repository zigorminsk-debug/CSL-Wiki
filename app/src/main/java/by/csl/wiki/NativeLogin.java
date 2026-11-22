package by.csl.wiki;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Standard Confluence Server form authentication over native HTTPS; no WebView or Basic auth. */
public final class NativeLogin {
    public static final String USER_AGENT = "CSLWiki-Native-Android/0.2";
    static final class Form {
        final String action;
        final Map<String, String> fields;
        Form(String action, Map<String, String> fields) { this.action = action; this.fields = fields; }
    }
    public static final class Result {
        public final ConfluenceApi.User user;
        public final List<HttpCookie> cookies;
        Result(ConfluenceApi.User user, List<HttpCookie> cookies) { this.user = user; this.cookies = cookies; }
    }
    public static Result login(String baseUrl, String username, String password) throws Exception {
        return login(baseUrl, username, password, address -> (HttpURLConnection) URI.create(address).toURL().openConnection());
    }
    static Result login(String baseUrl, String username, String password, ConfluenceApi.ConnectionFactory connections) throws Exception {
        String base = SitePolicy.normalizeBase(baseUrl);
        CookieManager jar = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
        ConfluenceApi.Cookies cookies = new ConfluenceApi.Cookies() {
            public String get(String url) {
                try { return String.join("; ", jar.get(URI.create(url), Collections.emptyMap()).getOrDefault("Cookie", Collections.emptyList())); }
                catch (Exception e) { return null; }
            }
            public void set(String url, String value) {
                try { jar.put(URI.create(url), Collections.singletonMap("Set-Cookie", Collections.singletonList(value))); }
                catch (Exception ignored) { /* no secrets in logs */ }
            }
        };
        Reply page = exchange(base + "/login.action", null, cookies, connections);
        for (int redirects = 0; page.status >= 300 && page.status < 400; redirects++) {
            if (redirects >= 4) throw new ConfluenceApi.Failure("Слишком много перенаправлений при входе. Проверьте путь установки.", 422);
            String next = trustedRedirect(page.url, page.location);
            page = exchange(next, null, cookies, connections);
        }
        if (page.status != 200) throw new ConfluenceApi.Failure("Страница авторизации недоступна (HTTP " + page.status + ").", page.status);
        Form form = parseForm(base, page.url, page.body);
        Map<String, String> fields = credentials(form, username, password);
        Reply response = exchange(form.action, encode(fields), cookies, connections);
        if (response.reason.toUpperCase(Locale.ROOT).contains("AUTHENTICATION_DENIED") || hasCaptcha(Jsoup.parse(response.body))) {
            throw new ConfluenceApi.Failure("Сервер требует CAPTCHA или разблокировку входа. Завершите проверку на w.csl.by во внешнем браузере либо обратитесь к администратору, затем повторите вход. Приложение не обходит CAPTCHA.", 428);
        }
        if (response.status >= 300 && response.status < 400) trustedRedirect(response.url, response.location);
        else if (response.status >= 400) throw new ConfluenceApi.Failure("Сервер отклонил вход (HTTP " + response.status + "). Проверьте учётную запись и политику входа.", response.status);
        // A redirect or HTTP 200 by itself is NOT proof of authentication.
        try {
            ConfluenceApi.User user = new ConfluenceApi(base, cookies, USER_AGENT, connections).currentUser();
            return new Result(user, jar.getCookieStore().getCookies());
        } catch (ConfluenceApi.Failure error) {
            if (error.authentication()) throw new ConfluenceApi.Failure("Вход не подтверждён. Проверьте логин и пароль; возможна блокировка учётной записи. Автоматических повторных попыток не будет.", 401);
            throw error;
        }
    }
    static Form parseForm(String base, String url, String html) throws Exception {
        Document document = Jsoup.parse(html);
        if (hasCaptcha(document)) throw new ConfluenceApi.Failure("Confluence требует CAPTCHA. Завершите проверку на сайте или обратитесь к администратору.", 428);
        for (Element form : document.select("form")) {
            if (form.selectFirst("input[name=os_password]") == null || form.selectFirst("input[name=os_username]") == null) continue;
            if (!form.attr("method").equalsIgnoreCase("post")) continue;
            URI action = URI.create(url).resolve(form.attr("action").isEmpty() ? url : form.attr("action"));
            String expected = URI.create(base).getPath();
            if (!SitePolicy.isTrusted(action.toString()) || action.getRawQuery() != null || action.getFragment() != null
                    || !(action.getPath().equals(expected + "/dologin.action") || action.getPath().equals(expected + "/login.action"))) {
                throw new ConfluenceApi.Failure("Сервер указал неподдерживаемый адрес отправки пароля. Данные не отправлены.", 422);
            }
            Map<String, String> fields = new LinkedHashMap<>();
            for (Element field : form.select("input[type=hidden][name]")) {
                String name = field.attr("name");
                if (!name.equals("os_cookie") && !name.equals("os_username") && !name.equals("os_password")) fields.put(name, field.attr("value"));
            }
            fields.put("os_destination", expected + "/index.action");
            return new Form(action.toString(), fields);
        }
        throw new ConfluenceApi.Failure("Не найдена стандартная форма Confluence (os_username/os_password). На сервере может быть SSO или другая схема входа. Пароль не отправлен.", 422);
    }
    static Map<String, String> credentials(Form form, String username, String password) {
        if (username.trim().isEmpty() || password.isEmpty()) throw new IllegalArgumentException("Введите логин и пароль.");
        Map<String, String> fields = new LinkedHashMap<>(form.fields);
        fields.put("os_username", username.trim()); fields.put("os_password", password); fields.put("login", "Log in");
        return fields;
    }
    static String encode(Map<String, String> fields) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (out.length() > 0) out.append('&');
            out.append(SitePolicy.encode(entry.getKey())).append('=').append(SitePolicy.encode(entry.getValue()));
        }
        return out.toString();
    }
    static String trustedRedirect(String from, String location) throws Exception {
        if (location == null || location.isEmpty()) throw new ConfluenceApi.Failure("Сервер вернул перенаправление без адреса.", 422);
        String resolved = URI.create(from).resolve(location).toString();
        if (!SitePolicy.isTrusted(resolved)) throw new ConfluenceApi.Failure("Сервер отправляет на внешний сервис авторизации. Внешний SSO не поддерживается; пароль туда не передаётся.", 422);
        return resolved;
    }
    private static boolean hasCaptcha(Document document) {
        return document.selectFirst("input[name=os_captcha], input[name=captcha], input[name=captchaResponse], iframe[src*=recaptcha]") != null;
    }
    private static final class Reply {
        String url, location, reason, body; int status;
    }
    private static Reply exchange(String address, String body, ConfluenceApi.Cookies cookies, ConfluenceApi.ConnectionFactory connections) throws Exception {
        if (!SitePolicy.isTrusted(address)) throw new IllegalArgumentException("Недоверенный адрес.");
        HttpURLConnection c = connections.open(address);
        c.setInstanceFollowRedirects(false); c.setConnectTimeout(15000); c.setReadTimeout(25000); c.setUseCaches(false);
        c.setRequestProperty("User-Agent", USER_AGENT); c.setRequestProperty("Accept", "text/html,application/xhtml+xml");
        String cookie = cookies.get(address); if (cookie != null && !cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
        try {
            if (body != null) {
                c.setRequestMethod("POST"); c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                c.setRequestProperty("Origin", SitePolicy.DEFAULT_BASE); c.setRequestProperty("Referer", address.replace("/dologin.action", "/login.action"));
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8); c.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream stream = c.getOutputStream()) { stream.write(bytes); }
            }
            Reply reply = new Reply(); reply.url = address; reply.status = c.getResponseCode();
            for (Map.Entry<String, List<String>> entry : c.getHeaderFields().entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase("Set-Cookie")) for (String value : entry.getValue()) cookies.set(address, value);
            }
            reply.location = c.getHeaderField("Location"); reply.reason = c.getHeaderField("X-Seraph-LoginReason");
            if (reply.reason == null) reply.reason = "";
            java.io.InputStream input = reply.status < 400 ? c.getInputStream() : c.getErrorStream();
            try (java.io.InputStream stream = input) { reply.body = stream == null ? "" : ConfluenceApi.readLimited(stream); }
            return reply;
        } finally { c.disconnect(); }
    }
    private NativeLogin() {}
}
