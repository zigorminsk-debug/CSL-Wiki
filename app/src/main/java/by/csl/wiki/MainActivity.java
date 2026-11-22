package by.csl.wiki;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native Android UI. WebView is used only for the real site's login and article rendering. */
public final class MainActivity extends Activity {
    private static final int BG = 0xff0b1220, CARD = 0xff131f31, BORDER = 0xff26374c;
    private static final int TEXT = 0xffeef3fa, MUTED = 0xff94a7bf, ACCENT = 0xff48dcc3, ERROR = 0xffffa5a5;
    private LinearLayout root, content, bottom;
    private TextView toolbarTitle;
    private SharedPreferences prefs;
    private OfflineStore store;
    private ConfluenceApi api;
    private final ExecutorService worker = Executors.newFixedThreadPool(2);
    private volatile int screenToken = 0;
    private String screen = "home", lastError = "", searchQuery = "", readerReturnScreen = "home";
    private ConfluenceApi.Space activeSpace;
    private ConfluenceApi.Page activePage;
    private WebView webView;
    private boolean checkingLogin;
    private volatile long sessionEpoch;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        store = new OfflineStore(this);
        CookieManager.getInstance().setAcceptCookie(true);
        rebuildApi();
        if (prefs.getString("identity", "").isEmpty()) showWelcome(); else showHome();
    }
    private void rebuildApi() {
        final long epoch = sessionEpoch;
        api = new ConfluenceApi(base(), new ConfluenceApi.Cookies() {
            public String get(String url) { return epoch == sessionEpoch ? CookieManager.getInstance().getCookie(url) : null; }
            public void set(String url, String value) { runOnUiThread(() -> { if (epoch == sessionEpoch && !isDestroyed()) CookieManager.getInstance().setCookie(url, value); }); }
        });
    }
    private String base() { return prefs.getString("base", SitePolicy.DEFAULT_BASE); }
    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private TextView label(String text, int size, int color, boolean bold) {
        TextView v = new TextView(this); v.setText(text); v.setTextSize(size); v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setLineSpacing(dp(3), 1); return v;
    }
    private void gap(LinearLayout target, int height) { View v = new View(this); target.addView(v, new LinearLayout.LayoutParams(1, dp(height))); }
    private void block(LinearLayout target, View view, int bottomMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(bottomMargin); target.addView(view, lp);
    }
    private Button button(String text, boolean primary, Runnable click) {
        Button b = new Button(this); b.setText(text); b.setAllCaps(false); b.setTextSize(15);
        b.setTextColor(primary ? BG : TEXT); b.setBackground(shape(primary ? ACCENT : CARD, 13));
        b.setMinHeight(dp(50)); b.setPadding(dp(12), dp(8), dp(12), dp(8));
        b.setOnClickListener(v -> click.run()); return b;
    }
    private Button icon(String text, String description, Runnable click) {
        Button b = button(text, false, click); b.setTextSize(22); b.setMinWidth(0); b.setMinimumWidth(0);
        b.setContentDescription(description); return b;
    }
    private void releaseWebView() {
        if (webView == null) return;
        webView.stopLoading();
        if (webView.getParent() instanceof ViewGroup) ((ViewGroup) webView.getParent()).removeView(webView);
        webView.destroy(); webView = null;
    }
    private void shell(String title, String target, boolean showTabs, Runnable back) {
        screenToken++; checkingLogin = false; screen = target; releaseWebView();
        root = column(); root.setBackgroundColor(BG); root.setFitsSystemWindows(true);
        // Android 15 enforces edge-to-edge for target 35: keep controls clear of all system insets.
        if (android.os.Build.VERSION.SDK_INT >= 30) root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        setContentView(root);
        LinearLayout header = row(); header.setPadding(dp(18), dp(10), dp(18), dp(10));
        if (back != null) {
            Button arrow = icon("‹", "Назад", back); header.addView(arrow, new LinearLayout.LayoutParams(dp(42), dp(42)));
        } else {
            TextView mark = label("C", 23, BG, true); mark.setGravity(Gravity.CENTER); mark.setBackground(shape(ACCENT, 12));
            header.addView(mark, new LinearLayout.LayoutParams(dp(40), dp(40)));
        }
        toolbarTitle = label(title, 20, TEXT, true); toolbarTitle.setPadding(dp(12), 0, 0, 0);
        header.addView(toolbarTitle, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(icon("⋯", "Настройки и сведения", this::showSettings), new LinearLayout.LayoutParams(dp(42), dp(42)));
        root.addView(header);
        View line = new View(this); line.setBackgroundColor(BORDER); root.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
        content = column(); root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        if (showTabs) {
            bottom = row(); bottom.setPadding(dp(10), dp(10), dp(10), dp(12)); bottom.setBackgroundColor(BG);
            nav("Обзор", "home", this::showHome); nav("Поиск", "search", this::showSearch); nav("Сохранённое", "saved", this::showSaved);
            root.addView(bottom);
        }
    }
    private void nav(String name, String target, Runnable action) {
        Button tab = button(name, false, action); tab.setTextSize(12);
        boolean active = screen.equals(target); tab.setTextColor(active ? ACCENT : MUTED);
        tab.setBackground(shape(active ? 0xff163834 : BG, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1); lp.setMargins(dp(3), 0, dp(3), 0); bottom.addView(tab, lp);
    }
    private LinearLayout scroll() {
        ScrollView scroller = new ScrollView(this); scroller.setFillViewport(true);
        LinearLayout inner = column(); inner.setPadding(dp(20), dp(24), dp(20), dp(24)); scroller.addView(inner);
        content.addView(scroller, new LinearLayout.LayoutParams(-1, -1)); return inner;
    }
    private void heading(LinearLayout target, String eyebrow, String title, String description) {
        TextView overline = label(eyebrow, 11, ACCENT, true); overline.setLetterSpacing(.13f); block(target, overline, 10);
        block(target, label(title, 30, TEXT, true), 10);
        if (!description.isEmpty()) block(target, label(description, 14, MUTED, false), 24);
    }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = column(); c.setPadding(dp(18), dp(16), dp(18), dp(16)); c.setBackground(shape(CARD, 16)); block(parent, c, 12); return c;
    }
    private void section(LinearLayout parent, String name) { gap(parent, 18); TextView t = label(name, 11, MUTED, true); t.setLetterSpacing(.12f); block(parent, t, 14); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }

    private void showWelcome() {
        shell("CSL Wiki", "welcome", false, null);
        LinearLayout body = scroll(); gap(body, 24);
        TextView badge = label("W / CSL", 15, ACCENT, true); block(body, badge, 24);
        heading(body, "ВАША КОРПОРАТИВНАЯ БАЗА ЗНАНИЙ", "Знания команды.\nВсегда под рукой.", "Читайте Confluence, находите ответы и сохраняйте важное для работы без интернета.");
        LinearLayout detail = card(body);
        block(detail, label("Прямое подключение", 16, TEXT, true), 6);
        block(detail, label("w.csl.by · без стороннего прокси", 13, MUTED, false), 0);
        detail = card(body);
        block(detail, label("Пароль остаётся на сайте", 16, TEXT, true), 6);
        block(detail, label("Вход на настоящей странице Confluence. Клиент не хранит ваш пароль.", 13, MUTED, false), 0);
        gap(body, 20);
        block(body, button("Войти в Confluence  →", true, this::showLogin), 12);
        block(body, button("Параметры подключения", false, this::connectionSettings), 12);
        block(body, label("Адрес: " + base() + "\nКлиент только для чтения. Вложения и сложные макросы доступны на сайте.", 12, MUTED, false), 0);
    }
    @SuppressLint("SetJavaScriptEnabled")
    private void showLogin() {
        sessionEpoch++; rebuildApi();
        shell("Вход в Confluence", "login", false, () -> { if (hasSession()) showHome(); else showWelcome(); });
        int token = screenToken;
        TextView info = label("Войдите на w.csl.by, затем нажмите «Проверить вход». Пароль вводится только на странице сервера.", 13, MUTED, false);
        info.setPadding(dp(20), dp(14), dp(20), dp(10)); content.addView(info);
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true); content.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));
        webView = new WebView(this); configureWebView(webView, true);
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (SitePolicy.isTrusted(request.getUrl().toString())) return false;
                toast("Переход на другой сервер заблокирован. Этот клиент поддерживает вход только на w.csl.by."); return true;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return SitePolicy.isTrusted(request.getUrl().toString()) ? null : blocked();
            }
            @Override public void onPageFinished(WebView view, String url) { if (token == screenToken) { progress.setVisibility(View.GONE); CookieManager.getInstance().flush(); } }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel(); runOnUiThread(() -> { info.setTextColor(ERROR); info.setText("Сертификат сервера не прошёл проверку. Проверьте дату телефона, VPN и доверенный корпоративный сертификат. Защита TLS не отключается."); });
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) { info.setTextColor(ERROR); info.setText("Страница не загрузилась: " + error.getDescription() + ". Проверьте сеть/VPN и адрес установки."); progress.setVisibility(View.GONE); }
            }
        });
        content.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout actions = column(); actions.setPadding(dp(16), dp(12), dp(16), dp(16));
        Button check = button("Проверить вход", true, () -> checkLogin(token)); block(actions, check, 8);
        block(actions, button("Путь установки / диагностика", false, this::connectionSettings), 0); content.addView(actions);
        webView.loadUrl(base() + "/login.action");
    }
    private boolean hasSession() { return !prefs.getString("identity", "").isEmpty(); }
    private void checkLogin(int token) {
        if (checkingLogin) return;
        checkingLogin = true; CookieManager.getInstance().flush(); toast("Проверяем сессию через REST API…");
        ConfluenceApi client = api;
        worker.execute(() -> {
            if (token != screenToken) return;
            try {
                ConfluenceApi.User user = client.currentUser();
                runOnUiThread(() -> {
                    if (token != screenToken || isFinishing()) return;
                    checkingLogin = false;
                    String previous = prefs.getString("identity", "");
                    if (!previous.equals(user.identity)) { store.clear(); activeSpace = null; activePage = null; }
                    prefs.edit().putString("identity", user.identity).putString("name", user.name).apply();
                    showHome();
                });
            } catch (Exception e) { runOnUiThread(() -> { if (token == screenToken && !isFinishing()) { checkingLogin = false; showFailureDialog(e); } }); }
        });
    }

    private void showHome() {
        shell("CSL Wiki", "home", true, null);
        LinearLayout body = scroll();
        String name = prefs.getString("name", "");
        heading(body, "РАБОЧЕЕ ПРОСТРАНСТВО", "База знаний", name.isEmpty() ? "Документация вашей команды в одном месте." : "Рады видеть вас, " + name + ".");
        LinearLayout stats = card(body); stats.setOnClickListener(v -> showSaved());
        block(stats, label("Доступно без интернета", 12, ACCENT, true), 8);
        block(stats, label(store.savedCount() + " сохранённых статей", 23, TEXT, true), 3);
        block(stats, label("Откройте статью и нажмите «Сохранить»  →", 12, MUTED, false), 0);
        block(body, button("Поиск по базе знаний", false, this::showSearch), 4);
        section(body, "ПРОСТРАНСТВА");
        LinearLayout spaces = column(); block(body, spaces, 4); loadSpaces(spaces, 0);
        List<ConfluenceApi.Page> recent = store.list(false, "");
        if (!recent.isEmpty()) { section(body, "НЕДАВНО ОТКРЫТЫЕ"); for (int i = 0; i < Math.min(4, recent.size()); i++) pageCard(body, recent.get(i), true); }
        section(body, "ПОДКЛЮЧЕНИЕ");
        block(body, label(base() + "\nСборка " + BuildConfig.VERSION_NAME + " · " + BuildConfig.BUILD_REVISION, 12, MUTED, false), 0);
    }
    private void loadSpaces(LinearLayout target, int start) {
        if (start == 0) target.removeAllViews();
        int token = screenToken;
        ProgressBar loading = loading(target);
        ConfluenceApi client = api;
        worker.execute(() -> {
            if (token != screenToken) return;
            try {
                ConfluenceApi.Slice<ConfluenceApi.Space> slice = client.spaces(start);
                runOnUiThread(() -> {
                    if (token != screenToken || isFinishing()) return;
                    target.removeView(loading);
                    if (slice.items.isEmpty() && start == 0) empty(target, "Пока нет пространств", "Сервер не вернул доступных пространств. Проверьте права вашей учётной записи.");
                    for (ConfluenceApi.Space space : slice.items) {
                        LinearLayout c = card(target);
                        block(c, label(space.key, 11, ACCENT, true), 6); block(c, label(space.name + "  ›", 17, TEXT, true), 0);
                        c.setContentDescription("Пространство " + space.name); c.setOnClickListener(v -> { activeSpace = space; showSpace(); });
                    }
                    if (slice.next >= 0) more(target, () -> loadSpaces(target, slice.next));
                });
            } catch (Exception e) { runOnUiThread(() -> { if (token == screenToken && !isFinishing()) { target.removeView(loading); errorCard(target, e, () -> loadSpaces(target, start)); } }); }
        });
    }
    private void showSpace() {
        if (activeSpace == null) { showHome(); return; }
        shell("Пространство", "space", true, this::showHome);
        LinearLayout body = scroll(); heading(body, activeSpace.key, activeSpace.name, "Страницы, доступные вашей учётной записи.");
        LinearLayout list = column(); body.addView(list); loadPages(list, false, 0);
    }
    private void showSearch() {
        shell("Поиск", "search", true, null);
        LinearLayout body = scroll(); heading(body, "НАЙДИТЕ НУЖНОЕ", "Есть вопрос?", "Поиск по названиям и содержимому статей Confluence.");
        EditText query = new EditText(this); query.setText(searchQuery); query.setSingleLine(true); query.setTextColor(TEXT); query.setHintTextColor(MUTED);
        query.setTextSize(16); query.setHint("Например: настройка VPN"); query.setPadding(dp(16), dp(14), dp(16), dp(14)); query.setBackground(shape(CARD, 12));
        query.setInputType(InputType.TYPE_CLASS_TEXT); query.setImeOptions(EditorInfo.IME_ACTION_SEARCH); block(body, query, 12);
        LinearLayout result = column();
        Runnable search = () -> {
            searchQuery = query.getText().toString().trim();
            if (searchQuery.isEmpty() || searchQuery.length() > 200) { toast("Введите запрос от 1 до 200 символов"); return; }
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(query.getWindowToken(), 0);
            query.clearFocus(); loadPages(result, true, 0);
        };
        block(body, button("Найти статьи", true, search), 16);
        query.setOnEditorActionListener((v, action, event) -> { if (action == EditorInfo.IME_ACTION_SEARCH) { search.run(); return true; } return false; });
        body.addView(result);
        if (!searchQuery.isEmpty()) search.run();
        else empty(result, "Ответы ближе, чем кажется", "Введите несколько слов. Если сеть недоступна, покажем совпадения среди ранее открытых статей.");
    }
    private void loadPages(LinearLayout target, boolean searching, int start) {
        if (start == 0) { target.removeAllViews(); target.setTag(new Object()); }
        Object batch = target.getTag();
        int token = screenToken;
        ProgressBar loading = loading(target);
        ConfluenceApi client = api;
        String query = searchQuery;
        String spaceKey = activeSpace == null ? "" : activeSpace.key;
        worker.execute(() -> {
            if (token != screenToken) return;
            try {
                ConfluenceApi.Slice<ConfluenceApi.Page> slice = searching ? client.search(query, start) : client.pages(spaceKey, start);
                runOnUiThread(() -> {
                    if (token != screenToken || isFinishing() || target.getTag() != batch) return;
                    target.removeView(loading);
                    if (slice.items.isEmpty() && start == 0) empty(target, "Ничего не найдено", searching ? "Попробуйте более короткий запрос или другое слово." : "В этом пространстве нет доступных страниц.");
                    for (ConfluenceApi.Page page : slice.items) pageCard(target, page, false);
                    if (slice.next >= 0) more(target, () -> loadPages(target, searching, slice.next));
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (token != screenToken || isFinishing() || target.getTag() != batch) return;
                    target.removeView(loading); errorCard(target, e, () -> loadPages(target, searching, start));
                    if (searching && start == 0 && !(e instanceof ConfluenceApi.Failure && ((ConfluenceApi.Failure) e).authentication())) {
                        List<ConfluenceApi.Page> local = store.list(false, query);
                        if (!local.isEmpty()) { section(target, "СОВПАДЕНИЯ В ЛОКАЛЬНОМ КЭШЕ"); for (ConfluenceApi.Page p : local) pageCard(target, p, true); }
                    }
                });
            }
        });
    }
    private void showSaved() {
        shell("Сохранённое", "saved", true, null);
        LinearLayout body = scroll(); heading(body, "БЕЗ ИНТЕРНЕТА", "Ваша библиотека", "Текст сохранённых статей доступен офлайн. Картинки и вложения могут требовать сеть.");
        List<ConfluenceApi.Page> saved = store.list(true, "");
        if (saved.isEmpty()) empty(body, "Здесь будет важное", "Откройте статью и нажмите «Сохранить». Она останется на этом устройстве.");
        for (ConfluenceApi.Page page : saved) pageCard(body, page, true);
    }
    private void pageCard(LinearLayout target, ConfluenceApi.Page page, boolean local) {
        LinearLayout c = card(target);
        block(c, label(page.space + (local ? "  ·  НА УСТРОЙСТВЕ" : ""), 11, local ? ACCENT : MUTED, true), 8);
        block(c, label(page.title + "  ›", 17, TEXT, true), 6);
        block(c, label("Версия " + page.version + (page.updated.isEmpty() ? "" : " · " + page.updated.substring(0, Math.min(10, page.updated.length()))), 12, MUTED, false), 0);
        c.setContentDescription(page.title); c.setOnClickListener(v -> openPage(page.id, local));
    }
    private void openPage(String id, boolean localFirst) {
        if (!screen.equals("reader")) readerReturnScreen = screen;
        String previousScreen = readerReturnScreen;
        shell("Статья", "reader", false, () -> returnTo(previousScreen));
        int token = screenToken;
        ConfluenceApi.Page cached = store.get(id);
        if (localFirst && cached != null) { renderArticle(cached, true, previousScreen); return; }
        ProgressBar loading = loading(content);
        ConfluenceApi client = api;
        worker.execute(() -> {
            if (token != screenToken) return;
            try {
                ConfluenceApi.Page page = client.page(id);
                runOnUiThread(() -> {
                    if (token != screenToken || isFinishing()) return;
                    store.put(page); page.saved = store.get(page.id).saved;
                    renderArticle(page, false, previousScreen);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (token != screenToken || isFinishing()) return;
                    if (cached != null && !(e instanceof ConfluenceApi.Failure && ((ConfluenceApi.Failure) e).authentication())) {
                        toast("Сеть недоступна: открыта сохранённая копия."); renderArticle(cached, true, previousScreen);
                    } else { content.removeView(loading); LinearLayout body = scroll(); errorCard(body, e, () -> openPage(id, false)); }
                });
            }
        });
    }
    private void returnTo(String target) {
        switch (target) { case "saved": showSaved(); break; case "search": showSearch(); break; case "space": showSpace(); break; default: showHome(); }
    }
    private void renderArticle(ConfluenceApi.Page page, boolean local, String returnScreen) {
        content.removeAllViews(); activePage = page;
        TextView state = label(local ? "Сохранённая копия · версия " + page.version : "Загружено с w.csl.by · версия " + page.version, 12, local ? ACCENT : MUTED, false);
        state.setPadding(dp(20), dp(10), dp(20), dp(8)); content.addView(state);
        LinearLayout actions = row(); actions.setPadding(dp(16), 0, dp(16), dp(8));
        Button save = button(page.saved ? "✓ Сохранено" : "Сохранить", false, () -> {});
        save.setOnClickListener(v -> { page.saved = !page.saved; store.setSaved(page.id, page.saved); save.setText(page.saved ? "✓ Сохранено" : "Сохранить"); toast(page.saved ? "Текст статьи доступен офлайн" : "Убрано из сохранённых; копия остаётся в истории чтения"); });
        actions.addView(save, new LinearLayout.LayoutParams(0, dp(46), 1));
        Button refresh = icon("↻", "Обновить статью с сервера", () -> openPage(page.id, false));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(48), dp(46)); iconLp.leftMargin = dp(8); actions.addView(refresh, iconLp);
        Button site = icon("↗", "Открыть оригинал", () -> openTrustedSite(page.url)); actions.addView(site, new LinearLayout.LayoutParams(dp(48), dp(46)));
        content.addView(actions);
        webView = new WebView(this); configureWebView(webView, false);
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.hasGesture()) return true;
                Uri uri = request.getUrl();
                if (SitePolicy.isTrusted(uri.toString())) {
                    String id = uri.getQueryParameter("pageId");
                    if (id != null && id.matches("[0-9]+")) { openPage(id, false); return true; }
                    openTrustedSite(uri.toString());
                } else if ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) {
                    new AlertDialog.Builder(MainActivity.this).setTitle("Внешняя ссылка").setMessage("Открыть в браузере?\n" + uri.getHost())
                        .setPositiveButton("Открыть", (d, which) -> external(uri.toString())).setNegativeButton("Отмена", null).show();
                }
                return true;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return SitePolicy.isTrusted(request.getUrl().toString()) ? null : blocked();
            }
        });
        content.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1));
        webView.loadDataWithBaseURL(base() + "/", ArticleHtml.render(page), "text/html", "UTF-8", null);
    }
    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView(WebView view, boolean login) {
        view.setBackgroundColor(BG);
        WebSettings s = view.getSettings(); s.setJavaScriptEnabled(login); s.setDomStorageEnabled(login);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setAllowFileAccess(false); s.setAllowContentAccess(false); s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSupportMultipleWindows(false); s.setJavaScriptCanOpenWindowsAutomatically(false); s.setSaveFormData(false);
        s.setBuiltInZoomControls(!login); s.setDisplayZoomControls(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false);
        view.setDownloadListener((url, userAgent, disposition, mimeType, length) -> { if (SitePolicy.isTrusted(url)) openTrustedSite(url); });
    }
    private static WebResourceResponse blocked() { return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0])); }
    private void openTrustedSite(String url) { if (SitePolicy.isTrusted(url)) external(url); else toast("Недоверенный адрес заблокирован"); }
    private void external(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (android.content.ActivityNotFoundException e) { toast("На устройстве нет приложения для этой ссылки"); }
    }
    private ProgressBar loading(LinearLayout target) { ProgressBar bar = new ProgressBar(this); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(30)); lp.gravity = Gravity.CENTER_HORIZONTAL; lp.setMargins(0, dp(18), 0, dp(18)); target.addView(bar, lp); return bar; }
    private void more(LinearLayout target, Runnable action) {
        Button b = button("Загрузить ещё", false, () -> {});
        b.setOnClickListener(v -> { target.removeView(b); action.run(); }); block(target, b, 12);
    }
    private void empty(LinearLayout parent, String title, String description) {
        LinearLayout c = card(parent); block(c, label(title, 18, TEXT, true), 8); block(c, label(description, 14, MUTED, false), 0);
    }
    private String errorMessage(Exception e) {
        if (e instanceof javax.net.ssl.SSLException) return "Ошибка TLS-сертификата. Проверьте время телефона и доверие к корпоративному сертификату. Проверка TLS не отключается.";
        if (e instanceof java.net.UnknownHostException) return "Не найден w.csl.by. Проверьте интернет, корпоративный DNS и VPN.";
        if (e instanceof java.net.SocketTimeoutException) return "Сервер не ответил вовремя. Проверьте сеть/VPN и повторите запрос.";
        if (e instanceof java.net.ConnectException) return "Не удалось подключиться к w.csl.by. Возможно, нужен корпоративный VPN.";
        if (e instanceof ConfluenceApi.Failure || e instanceof IllegalArgumentException) return e.getMessage();
        return "Не удалось загрузить данные: " + e.getClass().getSimpleName() + ". Проверьте сеть или попробуйте позже.";
    }
    private void errorCard(LinearLayout parent, Exception e, Runnable retry) {
        lastError = errorMessage(e);
        LinearLayout c = card(parent); block(c, label("Не удалось загрузить", 17, ERROR, true), 8); block(c, label(lastError, 14, MUTED, false), 12);
        if (e instanceof ConfluenceApi.Failure && ((ConfluenceApi.Failure) e).authentication()) block(c, button("Открыть вход", true, this::showLogin), 8);
        block(c, button("Повторить", false, () -> { parent.removeView(c); retry.run(); }), 0);
    }
    private void showFailureDialog(Exception e) {
        lastError = errorMessage(e);
        new AlertDialog.Builder(this).setTitle("Вход пока не подтверждён").setMessage(lastError + "\n\nЗапрос: " + base() + "/rest/api/user/current")
            .setPositiveButton("Понятно", null).setNeutralButton("Настройки", (d, w) -> connectionSettings()).show();
    }
    private void showSettings() {
        String[] options = hasSession() ? new String[]{"Параметры подключения", "Открыть Confluence в браузере", "Скопировать диагностику", "Очистить локальные статьи", "Выйти из аккаунта", "Повторно открыть вход"}
            : new String[]{"Параметры подключения", "Открыть Confluence в браузере", "Скопировать диагностику", "Очистить локальные статьи"};
        new AlertDialog.Builder(this).setTitle("CSL Wiki · " + BuildConfig.VERSION_NAME).setItems(options, (d, which) -> {
            switch (which) {
                case 0: connectionSettings(); break;
                case 1: openTrustedSite(base()); break;
                case 2:
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("CSL Wiki — диагностика", "CSL Wiki " + BuildConfig.VERSION_NAME + " (" + BuildConfig.BUILD_REVISION + ")\nAndroid " + android.os.Build.VERSION.RELEASE + "\nREST: " + base() + "/rest/api/user/current\nОшибка: " + lastError));
                    toast("Диагностика скопирована. Паролей и cookies в ней нет."); break;
                case 3:
                    new AlertDialog.Builder(this).setTitle("Удалить локальные статьи?").setMessage("Сохранённые статьи и история чтения будут удалены с устройства. На сервере ничего не изменится.")
                        .setPositiveButton("Удалить", (x, y) -> { screenToken++; store.clear(); if (hasSession()) showHome(); else showWelcome(); }).setNegativeButton("Отмена", null).show(); break;
                case 4: confirmLogout(); break;
                case 5: showLogin(); break;
            }
        }).show();
    }
    private void connectionSettings() {
        EditText input = new EditText(this); input.setText(base()); input.setSingleLine(true); input.setTextSize(14); input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout wrap = column(); wrap.setPadding(dp(22), dp(12), dp(22), dp(8));
        block(wrap, label("Только w.csl.by. Если Confluence установлен в подпапке, добавьте её: https://w.csl.by/confluence. Не добавляйте /rest/api. Изменение адреса очистит сессию и локальный кэш.", 14, MUTED, false), 12); wrap.addView(input);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Адрес Confluence").setView(wrap).setPositiveButton("Сохранить", null).setNegativeButton("Отмена", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String normalized = SitePolicy.normalizeBase(input.getText().toString());
                dialog.dismiss();
                if (!normalized.equals(base())) clearSession(normalized); else toast("Адрес не изменён");
            } catch (IllegalArgumentException e) { input.setError(e.getMessage()); }
        })); dialog.show();
    }
    private void confirmLogout() {
        new AlertDialog.Builder(this).setTitle("Выйти из Confluence?").setMessage("Сессия, история чтения и все офлайн-статьи будут удалены с устройства. Данные на сервере не изменятся.")
            .setPositiveButton("Выйти", (d, w) -> clearSession(base())).setNegativeButton("Отмена", null).show();
    }
    private void clearSession(String newBase) {
        screenToken++; sessionEpoch++;
        WebView cacheView = webView == null ? new WebView(this) : webView;
        cacheView.clearCache(true);
        if (webView == null) cacheView.destroy();
        releaseWebView(); store.clear();
        prefs.edit().clear().putString("base", newBase).apply(); activePage = null; activeSpace = null; searchQuery = "";
        android.webkit.WebStorage.getInstance().deleteAllData();
        shell("Выход", "clearing", false, null);
        content.addView(label("Удаляем сессию с устройства…", 16, MUTED, false));
        CookieManager.getInstance().removeAllCookies(done -> { if (!isFinishing() && !isDestroyed()) { CookieManager.getInstance().flush(); rebuildApi(); showWelcome(); } });
    }
    @Override public void onBackPressed() {
        if (screen.equals("login") && webView != null && webView.canGoBack()) { webView.goBack(); return; }
        if (screen.equals("reader")) { returnTo(readerReturnScreen); }
        else if (screen.equals("home") || screen.equals("welcome")) super.onBackPressed();
        else if (hasSession()) showHome(); else showWelcome();
    }
    @Override protected void onPause() { CookieManager.getInstance().flush(); super.onPause(); }
    @Override protected void onDestroy() { screenToken++; releaseWebView(); worker.shutdownNow(); store.close(); super.onDestroy(); }
}
