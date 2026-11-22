package by.csl.wiki;

import org.junit.Test;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** Exercises the real login orchestration against an in-memory HTTP backend, no live credentials. */
public class NativeLoginFlowTest {
    private static final String FORM = "<form method=post action=dologin.action><input type=hidden name=atl_token value=csrf-token><input name=os_username><input type=password name=os_password></form>";
    static final class Reply {
        int status; String body; Map<String,List<String>> headers = new LinkedHashMap<>();
        Reply(int status, String body) { this.status = status; this.body = body; headers.put("Content-Type", Collections.singletonList(body.startsWith("{") ? "application/json" : "text/html")); }
        Reply header(String key, String value) { headers.put(key, Collections.singletonList(value)); return this; }
    }
    static final class Connection extends HttpURLConnection {
        final Reply reply; final ByteArrayOutputStream posted = new ByteArrayOutputStream();
        Connection(String address, Reply reply) throws Exception { super(new URL(address)); this.reply = reply; }
        @Override public void connect() {} @Override public void disconnect() {} @Override public boolean usingProxy() { return false; }
        @Override public int getResponseCode() { return reply.status; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(reply.body.getBytes(StandardCharsets.UTF_8)); }
        @Override public InputStream getErrorStream() { return getInputStream(); }
        @Override public OutputStream getOutputStream() { return posted; }
        @Override public Map<String,List<String>> getHeaderFields() { return reply.headers; }
        @Override public String getHeaderField(String key) {
            for (Map.Entry<String,List<String>> entry : reply.headers.entrySet()) if (entry.getKey().equalsIgnoreCase(key)) return entry.getValue().get(0);
            return null;
        }
    }
    static final class Backend implements ConfluenceApi.ConnectionFactory {
        final Deque<Reply> replies = new ArrayDeque<>(); final List<Connection> calls = new ArrayList<>();
        Backend(Reply... replies) { this.replies.addAll(Arrays.asList(replies)); }
        public HttpURLConnection open(String address) throws Exception { assertTrue(SitePolicy.isTrusted(address)); Connection c = new Connection(address, replies.remove()); calls.add(c); return c; }
    }
    private static Reply loginPage() { return new Reply(200, FORM).header("Set-Cookie", "JSESSIONID=initial; Path=/; Secure; HttpOnly"); }
    @Test public void submitsOnePostWithCsrfAndVerifiesSessionAutomatically() throws Exception {
        Backend backend = new Backend(loginPage(), new Reply(302, "").header("Location", "/index.action").header("Set-Cookie", "JSESSIONID=authenticated; Path=/; Secure; HttpOnly"),
            new Reply(200, "{\"type\":\"known\",\"username\":\"alice\",\"displayName\":\"Alice\"}"));
        NativeLogin.Result result = NativeLogin.login("https://w.csl.by", "alice", "s3cr&et", backend);
        assertEquals("Alice", result.user.name); assertEquals(3, backend.calls.size());
        Connection post = backend.calls.get(1); assertEquals("POST", post.getRequestMethod());
        assertEquals("/dologin.action", post.getURL().getPath());
        assertTrue(post.getRequestProperty("Cookie").contains("initial"));
        String body = post.posted.toString("UTF-8"); assertTrue(body.contains("atl_token=csrf-token")); assertTrue(body.contains("os_password=s3cr%26et"));
        Connection verification = backend.calls.get(2); assertEquals("/rest/api/user/current", verification.getURL().getPath());
        assertTrue(verification.getRequestProperty("Cookie").contains("authenticated"));
        assertEquals(0, verification.posted.size()); assertNull(verification.getRequestProperty("Authorization"));
        assertTrue(result.cookies.stream().anyMatch(cookie -> cookie.getValue().equals("authenticated")));
    }
    @Test public void http200OrRedirectWithoutKnownUserIsNotSuccessAndIsNotRetried() throws Exception {
        Backend backend = new Backend(loginPage(), new Reply(200, "<html>Wrong password</html>"), new Reply(200, "{\"type\":\"anonymous\"}"));
        try { NativeLogin.login("https://w.csl.by", "alice", "wrong", backend); fail(); }
        catch (ConfluenceApi.Failure e) { assertEquals(401, e.status); }
        assertEquals(3, backend.calls.size()); assertEquals(1, backend.calls.stream().filter(call -> call.getRequestMethod().equals("POST")).count());
    }
    @Test public void captchaDenialStopsWithoutRepeatedPasswordAttempts() throws Exception {
        Backend backend = new Backend(loginPage(), new Reply(403, "Denied").header("X-Seraph-LoginReason", "AUTHENTICATION_DENIED"));
        try { NativeLogin.login("https://w.csl.by", "alice", "password", backend); fail(); }
        catch (ConfluenceApi.Failure e) { assertEquals(428, e.status); }
        assertEquals(2, backend.calls.size());
    }
    @Test public void externalRedirectNeverReplaysPasswordToAnotherHost() throws Exception {
        Backend backend = new Backend(loginPage(), new Reply(302, "").header("Location", "https://sso.example/login"));
        try { NativeLogin.login("https://w.csl.by", "alice", "password", backend); fail(); }
        catch (ConfluenceApi.Failure e) { assertEquals(422, e.status); }
        assertEquals(2, backend.calls.size());
    }
}
