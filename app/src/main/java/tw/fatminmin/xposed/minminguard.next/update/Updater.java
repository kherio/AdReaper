package tw.fatminmin.xposed.minminguard.next.update;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import tw.fatminmin.xposed.minminguard.next.BuildConfig;
import tw.fatminmin.xposed.minminguard.next.R;

/**
 * In-app updater: checks the latest GitHub release, offers the update and
 * installs the APK through PackageInstaller.
 */
public final class Updater
{
    private static final String LATEST_URL =
            "https://api.github.com/repos/kherio/AdReaper/releases/latest";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Updater()
    {
    }

    private static final class Release
    {
        String version;
        String notes;
        String apkUrl;
    }

    /** @param manual true when started by the user: also reports "up to date" and errors. */
    public static void check(final Activity activity, final boolean manual)
    {
        new Thread(() ->
        {
            try
            {
                final Release r = fetchLatest();
                MAIN.post(() ->
                {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    if (r != null && isNewer(r.version, BuildConfig.VERSION_NAME))
                    {
                        showUpdateDialog(activity, r);
                    }
                    else if (manual)
                    {
                        Toast.makeText(activity, R.string.update_up_to_date, Toast.LENGTH_SHORT).show();
                    }
                });
            }
            catch (Exception e)
            {
                if (manual)
                {
                    MAIN.post(() -> Toast.makeText(activity, R.string.update_check_failed,
                            Toast.LENGTH_LONG).show());
                }
            }
        }, "AdReaper-update-check").start();
    }

    private static Release fetchLatest() throws Exception
    {
        HttpURLConnection c = (HttpURLConnection) new URL(LATEST_URL).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        try
        {
            if (c.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (InputStream in = c.getInputStream())
            {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
            }
            JSONObject o = new JSONObject(sb.toString());
            Release r = new Release();
            r.version = o.getString("tag_name");
            r.notes = o.optString("body", "");
            JSONArray assets = o.optJSONArray("assets");
            if (assets != null)
            {
                for (int i = 0; i < assets.length(); i++)
                {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.getString("name").toLowerCase().endsWith(".apk"))
                    {
                        r.apkUrl = a.getString("browser_download_url");
                        break;
                    }
                }
            }
            return r.apkUrl == null ? null : r;
        }
        finally
        {
            c.disconnect();
        }
    }

    static boolean isNewer(String remote, String local)
    {
        int[] a = parse(remote), b = parse(local);
        for (int i = 0; i < Math.max(a.length, b.length); i++)
        {
            int x = i < a.length ? a[i] : 0, y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int[] parse(String v)
    {
        String[] parts = v.replaceFirst("^[^0-9]*", "").split("[^0-9]+");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++)
        {
            try { out[i] = Integer.parseInt(parts[i]); } catch (NumberFormatException e) { out[i] = 0; }
        }
        return out;
    }

    private static void showUpdateDialog(final Activity activity, final Release r)
    {
        String notes = r.notes.trim();
        if (notes.length() > 600) notes = notes.substring(0, 600) + "…";
        String msg = activity.getString(R.string.update_message, r.version, BuildConfig.VERSION_NAME)
                + (notes.isEmpty() ? "" : "\n\n" + notes);
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.update_available)
                .setMessage(msg)
                .setPositiveButton(R.string.update_install, (d, w) -> startDownload(activity, r))
                .setNegativeButton(R.string.update_later, null)
                .show();
    }

    private static void startDownload(final Activity activity, final Release r)
    {
        // Android 8+: the user must allow this app to install packages once.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls())
        {
            Toast.makeText(activity, R.string.update_allow_install, Toast.LENGTH_LONG).show();
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())));
            return;
        }

        final AlertDialog progress = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.update_downloading)
                .setMessage(r.version)
                .setCancelable(false)
                .show();

        final Context app = activity.getApplicationContext();
        new Thread(() ->
        {
            try
            {
                install(app, r.apkUrl);
                MAIN.post(progress::dismiss);
            }
            catch (Exception e)
            {
                MAIN.post(() ->
                {
                    progress.dismiss();
                    Toast.makeText(app, app.getString(R.string.update_failed, String.valueOf(e.getMessage())),
                            Toast.LENGTH_LONG).show();
                });
            }
        }, "AdReaper-update-download").start();
    }

    /** Streams the APK straight into a PackageInstaller session and commits it. */
    private static void install(Context ctx, String apkUrl) throws Exception
    {
        HttpURLConnection c = (HttpURLConnection) new URL(apkUrl).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        PackageInstaller installer = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        int id = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(id);
        try
        {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream();
                 OutputStream out = session.openWrite("update.apk", 0, c.getContentLengthLong()))
            {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            Intent i = new Intent(ctx, UpdateReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, id, i, flags);
            session.commit(pi.getIntentSender());
        }
        catch (Exception e)
        {
            session.abandon();
            throw e;
        }
        finally
        {
            session.close();
            c.disconnect();
        }
    }
}
