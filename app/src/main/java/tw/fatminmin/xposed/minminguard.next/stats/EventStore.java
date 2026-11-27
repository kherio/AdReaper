package tw.fatminmin.xposed.minminguard.next.stats;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Small SQLite store next to the greenDAO one. Keeps
 *  - daily block counters per app and ad SDK (statistics), and
 *  - ad candidates reported by the Inspector (to turn into rules from the UI).
 */
public final class EventStore extends SQLiteOpenHelper
{
    public static final String KIND_CLASS = "class";
    public static final String KIND_ID = "id";
    public static final String KIND_ACTIVITY = "activity";

    private static final int KEEP_DAYS = 90;

    private static EventStore instance;

    public static synchronized EventStore get(Context ctx)
    {
        if (instance == null)
        {
            instance = new EventStore(ctx.getApplicationContext());
        }
        return instance;
    }

    private EventStore(Context ctx)
    {
        super(ctx, "adreaper_events", null, 2);
    }

    @Override
    public void onCreate(SQLiteDatabase db)
    {
        db.execSQL("CREATE TABLE daily (day INTEGER NOT NULL, pkg TEXT NOT NULL, sdk TEXT NOT NULL,"
                + " n INTEGER NOT NULL, PRIMARY KEY (day, pkg, sdk))");
        db.execSQL("CREATE TABLE candidates (pkg TEXT NOT NULL, kind TEXT NOT NULL, value TEXT NOT NULL,"
                + " seen INTEGER NOT NULL, PRIMARY KEY (pkg, kind, value))");
        createRecent(db);
    }

    private static void createRecent(SQLiteDatabase db)
    {
        db.execSQL("CREATE TABLE IF NOT EXISTS recent (ts INTEGER NOT NULL, pkg TEXT NOT NULL,"
                + " sdk TEXT NOT NULL, what TEXT NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion)
    {
        if (oldVersion < 2)
        {
            createRecent(db);
        }
    }

    private static final int RECENT_KEEP = 200;
    private static final long RECENT_DEDUPE_MS = 5000;

    /** Logs what was blocked (class / rule). The same thing in the same app within 5 s counts once. */
    public synchronized void addRecent(String pkg, String sdk, String what)
    {
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();
        Cursor c = db.rawQuery("SELECT 1 FROM recent WHERE pkg = ? AND what = ? AND ts > ? LIMIT 1",
                new String[]{pkg, what, String.valueOf(now - RECENT_DEDUPE_MS)});
        boolean dup;
        try
        {
            dup = c.moveToFirst();
        }
        finally
        {
            c.close();
        }
        if (dup) return;

        ContentValues v = new ContentValues();
        v.put("ts", now);
        v.put("pkg", pkg);
        v.put("sdk", sdk);
        v.put("what", what);
        db.insert("recent", null, v);
        db.execSQL("DELETE FROM recent WHERE rowid NOT IN (SELECT rowid FROM recent ORDER BY ts DESC LIMIT "
                + RECENT_KEEP + ")");
    }

    /** Each entry is {timestamp, pkg, sdk, what}, newest first. */
    public synchronized List<String[]> recent(int limit)
    {
        List<String[]> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT ts, pkg, sdk, what FROM recent ORDER BY ts DESC LIMIT " + limit, null);
        try
        {
            while (c.moveToNext())
            {
                out.add(new String[]{c.getString(0), c.getString(1), c.getString(2), c.getString(3)});
            }
        }
        finally
        {
            c.close();
        }
        return out;
    }

    /** Blocked ads per app over the last {@code days} days (1 = today), biggest first. */
    public synchronized LinkedHashMap<String, Integer> appTotals(int days)
    {
        LinkedHashMap<String, Integer> out = new LinkedHashMap<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT pkg, SUM(n) AS t FROM daily WHERE day > ? GROUP BY pkg ORDER BY t DESC",
                new String[]{String.valueOf(today() - days)});
        try
        {
            while (c.moveToNext()) out.put(c.getString(0), c.getInt(1));
        }
        finally
        {
            c.close();
        }
        return out;
    }

    /** Per-SDK breakdown for one app over the last {@code days} days. */
    public synchronized LinkedHashMap<String, Integer> sdkTotalsFor(String pkg, int days)
    {
        LinkedHashMap<String, Integer> out = new LinkedHashMap<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT sdk, SUM(n) AS t FROM daily WHERE pkg = ? AND day > ? GROUP BY sdk ORDER BY t DESC",
                new String[]{pkg, String.valueOf(today() - days)});
        try
        {
            while (c.moveToNext()) out.put(c.getString(0), c.getInt(1));
        }
        finally
        {
            c.close();
        }
        return out;
    }

    public synchronized int total(int days)
    {
        Cursor c = getReadableDatabase().rawQuery("SELECT COALESCE(SUM(n), 0) FROM daily WHERE day > ?",
                new String[]{String.valueOf(today() - days)});
        try
        {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
        finally
        {
            c.close();
        }
    }

    /** Deletes the daily counters and the recent log (candidates are kept). */
    public synchronized void clearStats()
    {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("daily", null, null);
        db.delete("recent", null, null);
    }

    public static long today()
    {
        return TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis()
                + java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()));
    }

    public synchronized void addBlocked(String pkg, String sdk, int n)
    {
        SQLiteDatabase db = getWritableDatabase();
        long day = today();
        db.execSQL("INSERT OR IGNORE INTO daily (day, pkg, sdk, n) VALUES (?,?,?,0)",
                new Object[]{day, pkg, sdk});
        db.execSQL("UPDATE daily SET n = n + ? WHERE day = ? AND pkg = ? AND sdk = ?",
                new Object[]{n, day, pkg, sdk});
        db.delete("daily", "day < ?", new String[]{String.valueOf(day - KEEP_DAYS)});
    }

    public synchronized void addCandidate(String pkg, String kind, String value)
    {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("pkg", pkg);
        v.put("kind", kind);
        v.put("value", value);
        v.put("seen", System.currentTimeMillis());
        db.insertWithOnConflict("candidates", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Each entry is {kind, value}. */
    public synchronized List<String[]> candidates(String pkg)
    {
        List<String[]> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT kind, value FROM candidates WHERE pkg = ? ORDER BY seen DESC", new String[]{pkg});
        try
        {
            while (c.moveToNext()) out.add(new String[]{c.getString(0), c.getString(1)});
        }
        finally
        {
            c.close();
        }
        return out;
    }

    public synchronized void removeCandidate(String pkg, String kind, String value)
    {
        getWritableDatabase().delete("candidates", "pkg = ? AND kind = ? AND value = ?",
                new String[]{pkg, kind, value});
    }

    /** Blocked ads per day for the last {@code days} days, oldest first (day index -> count). */
    public synchronized Map<Long, Integer> dailyTotals(int days)
    {
        long today = today();
        Map<Long, Integer> out = new LinkedHashMap<>();
        for (long d = today - days + 1; d <= today; d++) out.put(d, 0);
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT day, SUM(n) FROM daily WHERE day > ? GROUP BY day",
                new String[]{String.valueOf(today - days)});
        try
        {
            while (c.moveToNext()) out.put(c.getLong(0), c.getInt(1));
        }
        finally
        {
            c.close();
        }
        return out;
    }

    /** Blocked ads per SDK over the last {@code days} days, biggest first. */
    public synchronized LinkedHashMap<String, Integer> sdkTotals(int days)
    {
        LinkedHashMap<String, Integer> out = new LinkedHashMap<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT sdk, SUM(n) AS t FROM daily WHERE day > ? GROUP BY sdk ORDER BY t DESC",
                new String[]{String.valueOf(today() - days)});
        try
        {
            while (c.moveToNext()) out.put(c.getString(0), c.getInt(1));
        }
        finally
        {
            c.close();
        }
        return out;
    }
}
