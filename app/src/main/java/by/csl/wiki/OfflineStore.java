package by.csl.wiki;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** App-private, backup-disabled cache. Cleared on logout and installation-path changes. */
public final class OfflineStore extends SQLiteOpenHelper {
    public OfflineStore(Context context) { super(context, "offline-v1.db", null, 1); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE pages (id TEXT PRIMARY KEY,title TEXT NOT NULL,space TEXT NOT NULL,body TEXT NOT NULL,url TEXT NOT NULL,updated TEXT,version INTEGER,saved INTEGER DEFAULT 0,opened INTEGER NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { /* first schema */ }
    public synchronized void put(ConfluenceApi.Page p) {
        ConfluenceApi.Page existing = get(p.id);
        ContentValues values = new ContentValues();
        values.put("id", p.id); values.put("title", p.title); values.put("space", p.space);
        values.put("body", p.body); values.put("url", p.url); values.put("updated", p.updated);
        values.put("version", p.version); values.put("saved", existing != null && existing.saved ? 1 : 0);
        values.put("opened", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("pages", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        getWritableDatabase().execSQL("DELETE FROM pages WHERE saved=0 AND id NOT IN (SELECT id FROM pages WHERE saved=0 ORDER BY opened DESC LIMIT 100)");
    }
    public synchronized ConfluenceApi.Page get(String id) {
        try (Cursor c = getReadableDatabase().query("pages", null, "id=?", new String[]{id}, null, null, null)) {
            return c.moveToFirst() ? from(c) : null;
        }
    }
    public synchronized void setSaved(String id, boolean saved) {
        ContentValues values = new ContentValues(); values.put("saved", saved ? 1 : 0);
        getWritableDatabase().update("pages", values, "id=?", new String[]{id});
    }
    public synchronized List<ConfluenceApi.Page> list(boolean savedOnly, String query) {
        List<ConfluenceApi.Page> pages = new ArrayList<>();
        String needle = query.trim().toLowerCase(Locale.ROOT);
        try (Cursor c = getReadableDatabase().query("pages", null, savedOnly ? "saved=1" : null, null, null, null, "opened DESC")) {
            while (c.moveToNext()) {
                ConfluenceApi.Page p = from(c);
                if (needle.isEmpty() || p.title.toLowerCase(Locale.ROOT).contains(needle) || p.body.toLowerCase(Locale.ROOT).contains(needle)) pages.add(p);
                if (!savedOnly && pages.size() >= 100) break;
            }
        }
        return pages;
    }
    public synchronized int savedCount() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM pages WHERE saved=1", null)) { c.moveToFirst(); return c.getInt(0); }
    }
    public synchronized void clear() { getWritableDatabase().delete("pages", null, null); }
    private static String text(Cursor c, String key) { return c.getString(c.getColumnIndexOrThrow(key)); }
    private static ConfluenceApi.Page from(Cursor c) {
        ConfluenceApi.Page p = new ConfluenceApi.Page();
        p.id = text(c, "id"); p.title = text(c, "title"); p.space = text(c, "space"); p.body = text(c, "body");
        p.url = text(c, "url"); p.updated = text(c, "updated");
        p.version = c.getInt(c.getColumnIndexOrThrow("version")); p.saved = c.getInt(c.getColumnIndexOrThrow("saved")) == 1;
        return p;
    }
}
