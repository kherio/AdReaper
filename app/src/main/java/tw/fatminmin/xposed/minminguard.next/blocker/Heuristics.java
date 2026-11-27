package tw.fatminmin.xposed.minminguard.next.blocker;

import android.view.View;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XSharedPreferences;
import tw.fatminmin.xposed.minminguard.next.Common;
import tw.fatminmin.xposed.minminguard.next.Main;

/**
 * Name based detection used by "Aggressive mode". Instead of relying on a list of known
 * ad SDKs, it recognises ad views and ad activities by the words in their class and id names
 * ("AdView", "ad_container", "InterstitialActivity", ...).
 *
 * Names are split into words first, so "AdapterView", "AddButton" or "Header" never match:
 * only a real "ad" / "ads" word does.
 */
public final class Heuristics
{
    private static final Set<String> AD_WORDS = new HashSet<>(Arrays.asList(
            "ad", "ads", "admob", "adview", "advert", "advertisement", "advertising", "adunit",
            "nativead", "nativeads", "interstitial", "rewarded", "sponsored", "adslot",
            "adcontainer", "adbanner", "appopen"));

    private static final String[] FRAMEWORK = {
            "android.", "androidx.", "com.android.", "com.google.android.material.", "kotlin.", "java.",
            "com.google.android.exoplayer2.", "androidx.media3."
    };

    private static final ConcurrentHashMap<String, Boolean> CLASS_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, Boolean> ID_CACHE = new ConcurrentHashMap<>();

    private static volatile boolean aggressive;

    private Heuristics()
    {
    }

    /** Reads the setting for this process. Call once when the module is applied to an app. */
    public static void configure()
    {
        try
        {
            XSharedPreferences pref = new XSharedPreferences(Main.class.getPackage().getName(), Common.MOD_PREFS);
            aggressive = pref.getFile().canRead() && pref.getBoolean(Common.KEY_AGGRESSIVE_MODE, false);
        }
        catch (Throwable t)
        {
            aggressive = false;
        }
    }

    public static boolean isAggressive()
    {
        return aggressive;
    }

    public static boolean matchesView(View view)
    {
        if (!aggressive)
        {
            return false;
        }
        String clazz = view.getClass().getName();
        Boolean byClass = CLASS_CACHE.get(clazz);
        if (byClass == null)
        {
            byClass = !isFramework(clazz) && looksLikeAd(clazz);
            CLASS_CACHE.put(clazz, byClass);
        }
        if (byClass)
        {
            return true;
        }

        int id = view.getId();
        if (id == View.NO_ID || (id >>> 24) == 0x01)
        {
            return false;
        }
        Boolean byId = ID_CACHE.get(id);
        if (byId == null)
        {
            String entry = null;
            try
            {
                entry = view.getResources().getResourceEntryName(id);
            }
            catch (Throwable ignored)
            {
            }
            byId = entry != null && looksLikeAd(entry);
            ID_CACHE.put(id, byId);
        }
        return byId;
    }

    /**
     * Activities are only matched when they come from a library, never from the app's own
     * package, so an app's own "AdsSettingsActivity" is left alone.
     */
    public static boolean matchesActivity(String hostPackage, String clazz)
    {
        if (!aggressive || isFramework(clazz) || clazz.startsWith(hostPackage + "."))
        {
            return false;
        }
        return looksLikeAd(clazz);
    }

    private static boolean isFramework(String clazz)
    {
        for (String p : FRAMEWORK)
        {
            if (clazz.startsWith(p))
            {
                return true;
            }
        }
        return false;
    }

    static boolean looksLikeAd(String name)
    {
        /* A lone "banner", "video" or "native" is normal UI; only the ad words count. */
        for (String word : words(name))
        {
            if (AD_WORDS.contains(word))
            {
                return true;
            }
        }
        return false;
    }

    /** Splits "com.foo.AdView" / "ad_container" / "ADActivity" into lower-case words. */
    static String[] words(String name)
    {
        return name
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
                .toLowerCase(Locale.ROOT)
                .split("[^a-z0-9]+");
    }
}
