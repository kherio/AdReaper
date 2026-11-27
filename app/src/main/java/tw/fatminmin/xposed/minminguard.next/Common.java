package tw.fatminmin.xposed.minminguard.next;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

import tw.fatminmin.xposed.minminguard.next.ui.fragments.MainFragment;

/**
 * Created by fatminmin on 2015/10/1.
 */
public class Common
{
    public final static String PACKAGE_NAME = BuildConfig.APPLICATION_ID;

    public final static String MOD_PREFS = "ModSettings";
    public final static String UI_PREFS = "UI_PREF";

    // UI Pref
    public final static String KEY_FIRST_TIME = "first_time_2_0_alpha_7";
    public final static String KEY_SHOW_SYSTEM_APPS = "show_system_apps";
    public final static String KEY_SHOW_LAUNCHER_ICON = "show_launcher_icon";
    public final static String KEY_ENABLE_CRASH_REPORT = "enable_crash_report";
    public final static String KEY_THEME = "theme";
    public final static String VALUE_THEME_SYSTEM = "system";
    public final static String VALUE_THEME_LIGHT = "light";
    public final static String VALUE_THEME_DARK = "dark";
    public final static String KEY_PALETTE = "palette";
    public final static String VALUE_PALETTE_BLUE = "blue";
    public final static String VALUE_PALETTE_GREEN = "green";
    public final static String VALUE_PALETTE_PURPLE = "purple";
    public final static String VALUE_PALETTE_ORANGE = "orange";
    public final static String VALUE_PALETTE_RED = "red";
    public final static String VALUE_PALETTE_DYNAMIC = "dynamic";
    public final static String KEY_ENABLED_FIRST = "enabled_first";
    public final static String KEY_CHECK_UPDATES = "check_updates";
    public final static String KEY_SORT = "sort";
    public final static String VALUE_SORT_NAME = "name";
    public final static String VALUE_SORT_PACKAGE = "package";
    public final static String VALUE_SORT_BLOCKED = "blocked";

    // Xposed Mod Pref
    public final static String KEY_MODE = "mode";
    public final static String VALUE_MODE_AUTO = "auto";
    public final static String VALUE_MODE_BLACKLIST = "blacklist";
    public final static String VALUE_MODE_WHITELIST = "whitelist";

    // User-defined ad-view class prefixes (newline separated, added in Settings)
    public final static String KEY_USER_BLOCK_PREFIXES = "user_block_prefixes";

    // User-defined per-app rules, JSON (see blocker/AppRules.java for the schema)
    public final static String KEY_USER_APP_RULES = "user_app_rules";

    // Inspector mode: log suspicious ad views / activities of enabled apps
    public final static String KEY_INSPECTOR_MODE = "inspector_mode";

    // Fragment Name
    public final static String FRG_MAIN = "main_fragment";

    // MinMinProvider
    public final static String KEY_PKG_NAME = "PKG_NAME";
    public final static String KEY_NETWORK = "AD_NETWORKS";
    public final static String KEY_BLOCK_NUM = "BLOCK_NUM";
    public final static String KEY_SDK = "SDK";
    public final static String KEY_AGGRESSIVE_MODE = "aggressive_mode";
    public final static String KEY_CANDIDATE_KIND = "CAND_KIND";
    public final static String KEY_CANDIDATE_VALUE = "CAND_VALUE";

    // Rules downloaded from the repository (JSON, same schema as the bundled rules)
    public final static String KEY_REMOTE_APP_RULES = "remote_app_rules";
    public final static String KEY_REMOTE_RULES_TIME = "remote_rules_time";

    // List filter in the app list (UI pref)
    public final static String KEY_LIST_FILTER = "list_filter";
    public final static String VALUE_FILTER_ALL = "all";
    public final static String VALUE_FILTER_ENABLED = "enabled";
    public final static String VALUE_FILTER_WITH_ADS = "with_ads";
    public final static String VALUE_FILTER_UNCONFIGURED = "unconfigured";

    private Common() throws InstantiationException
    {
        throw new InstantiationException("This class is not for instantiation");
    }

    public static String getModeString(MainFragment.FragmentMode mode)
    {
        switch (mode)
        {
            case AUTO:
                return Common.VALUE_MODE_AUTO;
            case BLACKLIST:
                return Common.VALUE_MODE_BLACKLIST;
            case WHITELIST:
                return Common.VALUE_MODE_WHITELIST;
            default:
                break;
        }
        return Common.VALUE_MODE_BLACKLIST;
    }

    public static String getWhiteListKey(String pkgName)
    {
        return pkgName + "_whitelist";
    }

    public static Integer getPrefMode() { return Context.MODE_PRIVATE; }

    /** Whether the theme preference maps to a dark uiMode.
        "System" is resolved against the REAL device state via
        Resources.getSystem() (never the app's own - possibly mutated -
        configuration), so it always reports the actual system night mode. */
    public static boolean isDarkTheme(String theme)
    {
        if (VALUE_THEME_LIGHT.equals(theme))
        {
            return false;
        }
        if (VALUE_THEME_DARK.equals(theme))
        {
            return true;
        }
        return (Resources.getSystem().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }
}
