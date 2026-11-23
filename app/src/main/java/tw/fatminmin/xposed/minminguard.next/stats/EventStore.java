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
        super(ctx, "adreaper_events", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db)
    {
        db.execSQL("CREATE TABLE daily (day INTEGER NOT NULL, pkg TEXT NOT NULL, sdk TEXT NOT NULL,"
                + " n INTEGER NOT NULL, PRIMARY KEY (day, pkg, sdk))");
        db.execSQL("CREATE TABLE candidates (pkg TEXT NOT NULL, kind TEXT NOT NULL, value TEXT NOT NULL,"
                + " seen INTEGER NOT NULL, PRIMARY KEY (pkg, kind, value))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion)
    {
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
