package by.csl.wiki;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.URLSpan;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.net.URI;
import java.util.List;

/** An Android widget tree: selectable text, native horizontal tables, ImageViews. No WebView. */
public final class NativeArticleView extends LinearLayout {
    public interface Actions { void link(String url); void image(String url, ImageView image, Button state); }
    private final Actions actions;
    private final String base;
    public NativeArticleView(Context context, ConfluenceApi.Page page, Actions actions) {
        super(context); this.actions = actions; this.base = ArticleDocument.base(page);
        setOrientation(VERTICAL);
        int images = 0;
        for (ArticleDocument.Block block : ArticleDocument.parse(page)) {
            switch (block.kind) {
                case "table": table(block); break;
                case "image":
                    if (++images <= 12) image(block, images <= 4);
                    else add(text("[Остальные изображения доступны в оригинале]", 13, false));
                    break;
                case "code":
                    HorizontalScrollView codeScroll = new HorizontalScrollView(context);
                    TextView code = text("", 14, false); code.setText(block.text); code.setTypeface(Typeface.MONOSPACE); code.setPadding(dp(14), dp(12), dp(14), dp(12)); code.setBackgroundColor(0xff142236);
                    codeScroll.addView(code); add(codeScroll); break;
                case "heading": add(text(block.html, Math.max(18, 28 - block.level * 2), true)); break;
                case "bullet": add(text("•  " + block.html, 17, false)); break;
                default: add(text(block.html, 17, false));
            }
        }
    }
    private int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density + .5f); }
    private void add(View view) { LayoutParams p = new LayoutParams(-1, -2); p.bottomMargin = dp(16); addView(view, p); }
    private TextView text(String html, int size, boolean bold) {
        TextView view = new TextView(getContext()); view.setTextColor(0xffdbe4f0); view.setTextSize(size); view.setLineSpacing(dp(4), 1.12f); view.setTextIsSelectable(true);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        SpannableStringBuilder spans = new SpannableStringBuilder(Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT));
        for (URLSpan link : spans.getSpans(0, spans.length(), URLSpan.class)) {
            int start = spans.getSpanStart(link), end = spans.getSpanEnd(link); spans.removeSpan(link);
            try {
                String address = URI.create(base + "/").resolve(link.getURL()).toString();
                if (!address.startsWith("https://") && !address.startsWith("http://")) continue;
                spans.setSpan(new ClickableSpan() {
                    @Override public void onClick(View widget) { actions.link(address); }
                    @Override public void updateDrawState(TextPaint paint) { paint.setColor(0xff48dcc3); paint.setUnderlineText(true); }
                }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } catch (Exception ignored) { /* invalid/non-web links remain plain text */ }
        }
        view.setText(spans); view.setMovementMethod(LinkMovementMethod.getInstance()); return view;
    }
    private void table(ArticleDocument.Block block) {
        HorizontalScrollView scroller = new HorizontalScrollView(getContext());
        LinearLayout rows = new LinearLayout(getContext()); rows.setOrientation(VERTICAL);
        int rowIndex = 0;
        for (List<String> row : block.rows) {
            LinearLayout cells = new LinearLayout(getContext()); cells.setOrientation(HORIZONTAL);
            for (String html : row) {
                TextView cell = text(html, 14, rowIndex == 0); cell.setPadding(dp(12), dp(10), dp(12), dp(10)); cell.setBackgroundColor(rowIndex % 2 == 0 ? 0xff142236 : 0xff101b2a);
                LayoutParams lp = new LayoutParams(dp(170), -1); lp.setMargins(0, 0, dp(1), dp(1)); cells.addView(cell, lp);
            }
            rows.addView(cells); rowIndex++;
        }
        scroller.addView(rows); add(scroller);
    }
    private void image(ArticleDocument.Block block, boolean automatic) {
        if (block.url.isEmpty()) { add(text("[Внешнее изображение не загружается]", 13, false)); return; }
        ImageView image = new ImageView(getContext()); image.setAdjustViewBounds(true); image.setVisibility(GONE); image.setContentDescription(block.text.isEmpty() ? "Изображение статьи" : block.text); add(image);
        Button state = new Button(getContext()); state.setAllCaps(false); state.setTextColor(0xff48dcc3); state.setTextSize(12);
        state.setText(block.text.isEmpty() ? "Показать изображение" : "Показать: " + block.text);
        state.setOnClickListener(v -> { state.setEnabled(false); state.setText("Загрузка изображения…"); actions.image(block.url, image, state); }); add(state);
        if (automatic) state.performClick();
    }
}
