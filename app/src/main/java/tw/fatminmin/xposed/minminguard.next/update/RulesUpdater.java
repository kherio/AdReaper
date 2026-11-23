package tw.fatminmin.xposed.minminguard.next.update;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import tw.fatminmin.xposed.minminguard.next.Common;
import tw.fatminmin.xposed.minminguard.next.R;

/**
 * Downloads the latest block rules from the repository so rule fixes reach users
 * without a new release. The rules are stored in the module preferences, where the
 * hooks read them (the target app must be restarted to pick them up).
 */
public final class RulesUpdater
{
    private static final String RULES_URL =
            "https://raw.githubusercontent.com/kherio/AdReaper/master/app/src/main/assets/rules/app_rules.json";
    private static final int MAX_BYTES = 512 * 1024;

    private RulesUpdater()
    {
    }

    /** @param manual true when started by the user: also reports the result. */
    public static void update(final Activity activity, final boolean manual)
    {
        final Context app = activity.getApplicationContext();
        new Thread(() ->
        {
            final Handler main = new Handler(Looper.getMainLooper());
            try
            {
                String json = download();
                int apps = new JSONObject(json).getJSONObject("apps").length(); // validates schema root

                Context dev = ContextCompat.createDeviceProtectedStorageContext(app);
                if (dev == null) dev = app;
                SharedPreferences pref = dev.getSharedPreferences(Common.MOD_PREFS, Common.getPrefMode());
                boolean changed = !json.equals(pref.getString(Common.KEY_REMOTE_APP_RULES, ""));
                pref.edit()
                        .putString(Common.KEY_REMOTE_APP_RULES, json)
                        .putLong(Common.KEY_REMOTE_RULES_TIME, System.currentTimeMillis())
                        .apply();

                if (manual)
                {
                    final int res = changed ? R.string.rules_updated : R.string.rules_up_to_date;
                    main.post(() -> Toast.makeText(app, app.getString(res, apps), Toast.LENGTH_LONG).show());
                }
            }
            catch (Exception e)
            {
                if (manual)
                {
                    main.post(() -> Toast.makeText(app, R.string.rules_update_failed, Toast.LENGTH_LONG).show());
                }
            }
        }, "AdReaper-rules-update").start();
    }

    private static String download() throws Exception
    {
        HttpURLConnection c = (HttpURLConnection) new URL(RULES_URL).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try
        {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream())
            {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0)
                {
                    bos.write(buf, 0, n);
                    if (bos.size() > MAX_BYTES) throw new Exception("too large");
                }
            }
            return new String(bos.toByteArray(), "UTF-8");
        }
        finally
        {
            c.disconnect();
        }
    }
}
