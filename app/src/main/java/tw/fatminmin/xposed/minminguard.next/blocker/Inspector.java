package tw.fatminmin.xposed.minminguard.next.blocker;

import android.app.Activity;
import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import tw.fatminmin.xposed.minminguard.next.Common;
import tw.fatminmin.xposed.minminguard.next.Main;

/**
 * Discovery mode for ads that are not blocked yet.
 *
 * When enabled (Settings -> Inspector mode) every activity of an enabled app is scanned shortly
 * after it comes to the foreground. Views and activities whose class or id name looks like an ad
 * are written to the Xposed log (LSPosed manager -> Logs -> Modules) together with a ready-to-paste
 * rule for Settings -> Custom app rules.
 *
 * Nothing is blocked by the inspector itself; it only reports.
 */
public final class Inspector
{
    private static final String TAG = "[AdReaper/Inspector] ";
    private static final long SCAN_DELAY_MS = 3000;
    private static final long RESCAN_INTERVAL_MS = 15000;
    private static final int MAX_NODES = 4000;

    private static final String[] NAME_HINTS = {
            "adview", "ad_view", "adcontainer", "ad_container", "adlayout", "ad_layout",
            "adframe", "ad_frame", "adslot", "ad_slot", "adholder", "ad_holder", "adbanner",
            "ad_banner", "banner", "sponsor", "admob", "nativead", "native_ad", "advert",
            "interstitial", "rewarded"
    };

    private static final Map<String, Long> lastScan = new HashMap<>();

    private Inspector() throws InstantiationException
    {
        throw new InstantiationException("This class is not for instantiation");
    }

    public static boolean isEnabled()
    {
        try
        {
            XSharedPreferences pref = new XSharedPreferences(Main.class.getPackage().getName(), Common.MOD_PREFS);
            return pref.getFile().canRead() && pref.getBoolean(Common.KEY_INSPECTOR_MODE, false);
        }
        catch (Throwable ignored)
        {
            return false;
        }
    }

    public static void install(final String pkg, final XC_LoadPackage.LoadPackageParam lpparam)
    {
        if (!isEnabled())
        {
            return;
        }

        try
        {
            XposedHelpers.findAndHookMethod("android.app.Activity", lpparam.classLoader, "onResume", new XC_MethodHook()
            {
                @Override
                protected void afterHookedMethod(MethodHookParam param)
                {
                    final Activity activity = (Activity) param.thisObject;
                    final String clazz = activity.getClass().getName();

                    long now = System.currentTimeMillis();
                    synchronized (lastScan)
                    {
                        Long last = lastScan.get(clazz);
                        if (last != null && now - last < RESCAN_INTERVAL_MS)
                        {
                            return;
                        }
                        lastScan.put(clazz, now);
                    }

                    try
                    {
                        activity.getWindow().getDecorView().postDelayed(new Runnable()
                        {
                            @Override
                            public void run()
                            {
                                scan(pkg, activity);
                            }
                        }, SCAN_DELAY_MS);
                    }
                    catch (Throwable e)
                    {
                        XposedBridge.log(TAG + "could not schedule scan: " + e);
                    }
                }
            });

            XposedBridge.log(TAG + "active for " + pkg);
        }
        catch (Throwable e)
        {
            XposedBridge.log(TAG + "install failed for " + pkg + ": " + e);
        }
    }

    private static void scan(String pkg, Activity activity)
    {
        try
        {
            String activityName = activity.getClass().getName();
            StringBuilder viewClasses = new StringBuilder();
            StringBuilder viewIds = new StringBuilder();
            int[] counter = {0};

            XposedBridge.log(TAG + "pkg=" + pkg + " activity=" + activityName);

            boolean activitySuspect = looksLikeAd(activityName.toLowerCase(Locale.ROOT))
                    && !activityName.startsWith(pkg);
            if (activitySuspect)
            {
                XposedBridge.log(TAG + "  suspect activity: " + activityName);
                Util.notifyCandidate(pkg, tw.fatminmin.xposed.minminguard.next.stats.EventStore.KIND_ACTIVITY, activityName);
            }

            View root = activity.getWindow().getDecorView();
            walk(pkg, root, viewClasses, viewIds, counter);

            if (viewClasses.length() == 0 && viewIds.length() == 0 && !activitySuspect)
            {
                XposedBridge.log(TAG + "  nothing suspicious found (" + counter[0] + " views scanned)");
                return;
            }

            StringBuilder rule = new StringBuilder();
            rule.append("{\"apps\":{\"").append(pkg).append("\":{");
            boolean first = true;
            if (viewClasses.length() > 0)
            {
                rule.append("\"hide_view_classes\":[").append(viewClasses).append("]");
                first = false;
            }
            if (viewIds.length() > 0)
            {
                if (!first) rule.append(",");
                rule.append("\"hide_view_ids\":[").append(viewIds).append("]");
                first = false;
            }
            if (activitySuspect)
            {
                if (!first) rule.append(",");
                rule.append("\"kill_activities\":[\"").append(activityName).append("\"]");
            }
            rule.append("}}}");

            XposedBridge.log(TAG + "  suggested rule: " + rule);
        }
        catch (Throwable e)
        {
            XposedBridge.log(TAG + "scan failed: " + e);
        }
    }

    private static void walk(String pkg, View view, StringBuilder classes, StringBuilder ids, int[] counter)
    {
        if (view == null || counter[0] >= MAX_NODES)
        {
            return;
        }
        counter[0]++;

        String className = view.getClass().getName();
        boolean framework = className.startsWith("android.")
                || className.startsWith("androidx.")
                || className.startsWith("com.android.internal.")
                || className.startsWith("com.google.android.material.");

        String entry = idEntryName(view);
        boolean classSuspect = !framework && looksLikeAd(className.toLowerCase(Locale.ROOT));
        boolean idSuspect = entry != null && looksLikeAd(entry.toLowerCase(Locale.ROOT));

        if (classSuspect || idSuspect)
        {
            Resources res = view.getResources();
            float density = res != null ? res.getDisplayMetrics().density : 1f;
            XposedBridge.log(String.format(Locale.ROOT, "%s  suspect view: class=%s id=%s size=%dx%ddp visible=%b",
                    TAG, className, entry, Math.round(view.getWidth() / density),
                    Math.round(view.getHeight() / density), view.getVisibility() == View.VISIBLE));

            if (classSuspect)
            {
                Util.notifyCandidate(pkg, tw.fatminmin.xposed.minminguard.next.stats.EventStore.KIND_CLASS, className);
            }
            if (idSuspect)
            {
                Util.notifyCandidate(pkg, tw.fatminmin.xposed.minminguard.next.stats.EventStore.KIND_ID, entry);
            }

            if (classSuspect && classes.indexOf("\"" + className + "\"") < 0)
            {
                if (classes.length() > 0) classes.append(",");
                classes.append("\"").append(className).append("\"");
            }
            if (idSuspect && ids.indexOf("\"" + entry + "\"") < 0)
            {
                if (ids.length() > 0) ids.append(",");
                ids.append("\"").append(entry).append("\"");
            }
        }

        if (view instanceof ViewGroup)
        {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
            {
                walk(pkg, group.getChildAt(i), classes, ids, counter);
            }
        }
    }

    private static String idEntryName(View view)
    {
        int id = view.getId();
        if (id == View.NO_ID || (id >>> 24) == 0x01)
        {
            return null;
        }
        try
        {
            return view.getResources().getResourceEntryName(id);
        }
        catch (Throwable ignored)
        {
            return null;
        }
    }

    private static boolean looksLikeAd(String lowerName)
    {
        for (String hint : NAME_HINTS)
        {
            if (lowerName.contains(hint))
            {
                return true;
            }
        }

        /* Short ids such as "ad", "ads", "ad_top", "top_ad", "bottom_ads". */
        if (lowerName.equals("ad") || lowerName.equals("ads")
                || lowerName.startsWith("ad_") || lowerName.startsWith("ads_")
                || lowerName.endsWith("_ad") || lowerName.endsWith("_ads")
                || lowerName.contains("_ad_") || lowerName.contains("_ads_"))
        {
            return true;
        }

        /* Class names such as com.foo.AdView or com.foo.ads.Something. */
        return lowerName.contains(".ads.") || lowerName.contains(".adview") || lowerName.contains(".admob");
    }
}
