package tw.fatminmin.xposed.minminguard.next.blocker;

import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import tw.fatminmin.xposed.minminguard.next.Common;
import tw.fatminmin.xposed.minminguard.next.Main;

/**
 * Data-driven blocking rules.
 *
 * Instead of hard-coding every SDK / app quirk in Java, rules are described in JSON and merged
 * from two sources:
 *
 *  1. the bundled {@code assets/rules/app_rules.json} (shipped with the module), and
 *  2. the user's own rules (Settings -> Custom app rules), stored in the module preferences.
 *
 * Both use the same schema:
 *
 * <pre>
 * {
 *   "apps": {
 *     "*": { ... },                       // applies to every enabled app
 *     "com.example.app": {                // applies to this package only
 *       "hide_view_classes": ["com.foo.ads.BannerView"],   // class name prefixes
 *       "hide_view_ids":     ["ad_container", "promo_*"],  // view id names ("*" suffix = prefix)
 *       "kill_activities":   ["com.foo.ads.FullScreenAdActivity"],  // class name prefixes
 *       "block_methods": [
 *         { "class": "com.foo.ads.Interstitial", "method": "show" },
 *         { "class": "com.foo.ads.Config", "method": "isEnabled", "result": false },
 *         "com.foo.ads.Loader#load"       // shorthand for class#method
 *       ]
 *     }
 *   }
 * }
 * </pre>
 *
 * A blocked method returns a harmless default for its return type (nothing for {@code void},
 * {@code false}/{@code 0} for primitives, {@code null} for objects) unless {@code "result"} is given.
 * Hooks on classes that do not exist in the target app are skipped silently, so a single global
 * rule list can cover many SDKs.
 */
public final class AppRules
{
    private static final String BUNDLED_ASSET = "assets/rules/app_rules.json";
    private static final String GLOBAL = "*";

    /** Parsed bundled rules (read once in the Zygote, inherited by every app process). */
    private static volatile JSONObject bundledRoot = null;

    /** Cached rule set for the (single) package this process runs. */
    private static volatile String cachedPkg = null;
    private static volatile RuleSet cachedSet = null;

    private AppRules() throws InstantiationException
    {
        throw new InstantiationException("This class is not for instantiation");
    }

    private static final class MethodRule
    {
        String clazz;
        String method;
        boolean hasResult;
        Object result;
    }

    private static final class RuleSet
    {
        final List<String> viewClassPrefixes = new ArrayList<>();
        final Set<String> viewIds = new HashSet<>();
        final List<String> viewIdPrefixes = new ArrayList<>();
        final List<String> activityPrefixes = new ArrayList<>();
        final List<MethodRule> methods = new ArrayList<>();

        boolean hasViewRules()
        {
            return !viewClassPrefixes.isEmpty() || !viewIds.isEmpty() || !viewIdPrefixes.isEmpty();
        }
    }

    /** Reads the bundled rules from the module APK. Safe to call more than once. */
    public static void init(String modulePath)
    {
        if (modulePath == null)
        {
            return;
        }

        try (ZipFile zip = new ZipFile(new File(modulePath)))
        {
            ZipEntry entry = zip.getEntry(BUNDLED_ASSET);
            if (entry == null)
            {
                return;
            }

            InputStream in = zip.getInputStream(entry);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1)
            {
                bos.write(buf, 0, n);
            }
            in.close();

            bundledRoot = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
            Util.log(Main.class.getPackage().getName(), "Loaded bundled app rules");
        }
        catch (Throwable e)
        {
            Util.log(Main.class.getPackage().getName(), "Failed to load bundled app rules: " + e);
        }
    }

    private static RuleSet forPackage(String pkg)
    {
        /* Hot path (called for every view that is added): lock-free read of the cache. */
        RuleSet set = cachedSet;
        if (set != null && pkg.equals(cachedPkg))
        {
            return set;
        }

        synchronized (AppRules.class)
        {
            if (cachedSet != null && pkg.equals(cachedPkg))
            {
                return cachedSet;
            }

            RuleSet built = new RuleSet();
            mergeRoot(built, bundledRoot, pkg);
            mergeRoot(built, readPrefJson(Common.KEY_REMOTE_APP_RULES), pkg);
            mergeRoot(built, readPrefJson(Common.KEY_USER_APP_RULES), pkg);

            cachedSet = built;
            cachedPkg = pkg;
            return built;
        }
    }

    private static JSONObject readPrefJson(String key)
    {
        try
        {
            XSharedPreferences pref = new XSharedPreferences(Main.class.getPackage().getName(), Common.MOD_PREFS);
            if (!pref.getFile().canRead())
            {
                return null;
            }

            String json = pref.getString(key, "");
            if (json == null || json.trim().isEmpty())
            {
                return null;
            }
            return new JSONObject(json);
        }
        catch (Throwable e)
        {
            Util.log(Main.class.getPackage().getName(), "Invalid app rules (" + key + "): " + e);
            return null;
        }
    }

    private static void mergeRoot(RuleSet set, JSONObject root, String pkg)
    {
        if (root == null)
        {
            return;
        }

        JSONObject apps = root.optJSONObject("apps");
        if (apps == null)
        {
            return;
        }

        merge(set, apps.optJSONObject(GLOBAL));
        if (!GLOBAL.equals(pkg))
        {
            merge(set, apps.optJSONObject(pkg));
        }
    }

    private static void merge(RuleSet set, JSONObject o)
    {
        if (o == null)
        {
            return;
        }

        addStrings(o.optJSONArray("hide_view_classes"), set.viewClassPrefixes);
        addStrings(o.optJSONArray("kill_activities"), set.activityPrefixes);

        JSONArray ids = o.optJSONArray("hide_view_ids");
        if (ids != null)
        {
            for (int i = 0; i < ids.length(); i++)
            {
                String id = ids.optString(i, "").trim();
                if (id.isEmpty())
                {
                    continue;
                }
                if (id.endsWith("*"))
                {
                    String prefix = id.substring(0, id.length() - 1);
                    if (!prefix.isEmpty())
                    {
                        set.viewIdPrefixes.add(prefix);
                    }
                }
                else
                {
                    set.viewIds.add(id);
                }
            }
        }

        JSONArray methods = o.optJSONArray("block_methods");
        if (methods != null)
        {
            for (int i = 0; i < methods.length(); i++)
            {
                MethodRule rule = parseMethodRule(methods.opt(i));
                if (rule != null)
                {
                    set.methods.add(rule);
                }
            }
        }
    }

    private static MethodRule parseMethodRule(Object element)
    {
        MethodRule rule = new MethodRule();

        if (element instanceof String)
        {
            String[] parts = ((String) element).split("#", 2);
            if (parts.length != 2)
            {
                return null;
            }
            rule.clazz = parts[0].trim();
            rule.method = parts[1].trim();
        }
        else if (element instanceof JSONObject)
        {
            JSONObject m = (JSONObject) element;
            rule.clazz = m.optString("class", "").trim();
            rule.method = m.optString("method", "").trim();
            if (m.has("result"))
            {
                rule.hasResult = true;
                rule.result = m.isNull("result") ? null : m.opt("result");
            }
        }
        else
        {
            return null;
        }

        if (rule.clazz.isEmpty() || rule.method.isEmpty())
        {
            return null;
        }
        return rule;
    }

    private static void addStrings(JSONArray array, List<String> out)
    {
        if (array == null)
        {
            return;
        }
        for (int i = 0; i < array.length(); i++)
        {
            String s = array.optString(i, "").trim();
            if (!s.isEmpty())
            {
                out.add(s);
            }
        }
    }

    /** Hooks every method listed in the rules for this package. */
    public static void applyMethodRules(final String pkg, final XC_LoadPackage.LoadPackageParam lpparam)
    {
        RuleSet set = forPackage(pkg);

        /* Rule health, for verifying the rule list on a device (Inspector mode only):
           which SDK classes the app has, and which rules name a method that does not exist. */
        final boolean report = Inspector.isEnabled();
        int hooked = 0, noClass = 0;
        StringBuilder stale = new StringBuilder();

        for (final MethodRule rule : set.methods)
        {
            try
            {
                Class<?> clazz = XposedHelpers.findClassIfExists(rule.clazz, lpparam.classLoader);
                if (clazz == null)
                {
                    noClass++;
                    continue;
                }

                Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(clazz, rule.method, new XC_MethodHook()
                {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param)
                    {
                        Member member = param.method;
                        Class<?> returnType = member instanceof Method ? ((Method) member).getReturnType() : void.class;

                        Util.log(pkg, "AppRules: blocked " + rule.clazz + "#" + rule.method);
                        Util.notifyBlocked(pkg, SdkNames.label(rule.clazz), 1);

                        param.setResult(defaultResult(returnType, rule));
                    }
                });

                if (hooks.isEmpty())
                {
                    Util.log(pkg, "AppRules: no method " + rule.method + " in " + rule.clazz);
                    stale.append(" ").append(rule.clazz).append("#").append(rule.method);
                }
                else
                {
                    hooked++;
                }
            }
            catch (Throwable e)
            {
                Util.log(pkg, "AppRules: " + rule.clazz + "#" + rule.method + " -> " + e);
            }
        }

        if (report)
        {
            reportRuleHealth(pkg, set.methods.size(), hooked, noClass, stale.toString());
        }
    }

    private static void reportRuleHealth(String pkg, int total, int hooked, int noClass, String stale)
    {
        XposedBridge.log("[AdReaper/Rules] " + pkg + ": " + total + " method rules, " + hooked
                + " hooked, " + noClass + " classes not present"
                + (stale.isEmpty() ? "" : ", class found but method missing (rule needs fixing):" + stale));
    }

    private static Object defaultResult(Class<?> returnType, MethodRule rule)
    {
        if (returnType == void.class)
        {
            return null;
        }

        Object v = rule.hasResult ? rule.result : null;

        if (returnType == boolean.class)
        {
            return v instanceof Boolean ? v : Boolean.FALSE;
        }
        if (returnType == char.class)
        {
            return Character.valueOf((char) 0);
        }
        if (returnType.isPrimitive())
        {
            Number n = v instanceof Number ? (Number) v : Integer.valueOf(0);
            if (returnType == int.class) return n.intValue();
            if (returnType == long.class) return n.longValue();
            if (returnType == float.class) return n.floatValue();
            if (returnType == double.class) return n.doubleValue();
            if (returnType == short.class) return n.shortValue();
            if (returnType == byte.class) return n.byteValue();
        }

        if (v instanceof String || v instanceof Boolean || v == null)
        {
            return v;
        }
        return null;
    }

    /** True if the view matches a "hide_view_classes" or "hide_view_ids" rule of this package. */
    public static boolean matchesView(String pkg, View view)
    {
        RuleSet set = forPackage(pkg);
        if (!set.hasViewRules())
        {
            return false;
        }

        String className = view.getClass().getName();
        for (String prefix : set.viewClassPrefixes)
        {
            if (className.startsWith(prefix))
            {
                return true;
            }
        }

        if (set.viewIds.isEmpty() && set.viewIdPrefixes.isEmpty())
        {
            return false;
        }

        int id = view.getId();
        /* 0x01xxxxxx ids belong to the Android framework (android:id/content, ...). */
        if (id == View.NO_ID || (id >>> 24) == 0x01)
        {
            return false;
        }

        try
        {
            String entry = view.getResources().getResourceEntryName(id);
            if (entry == null)
            {
                return false;
            }
            if (set.viewIds.contains(entry))
            {
                return true;
            }
            for (String prefix : set.viewIdPrefixes)
            {
                if (entry.startsWith(prefix))
                {
                    return true;
                }
            }
        }
        catch (Throwable ignored)
        {
            /* Resources.NotFoundException for ids without a resource entry (generated ids). */
        }

        return false;
    }

    /** True if the activity class matches a "kill_activities" rule of this package. */
    public static boolean matchesActivity(String pkg, String activityClass)
    {
        RuleSet set = forPackage(pkg);
        for (String prefix : set.activityPrefixes)
        {
            if (activityClass.startsWith(prefix))
            {
                return true;
            }
        }
        return false;
    }
}
