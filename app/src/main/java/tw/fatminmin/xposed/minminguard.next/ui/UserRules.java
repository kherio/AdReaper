package tw.fatminmin.xposed.minminguard.next.ui;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

import tw.fatminmin.xposed.minminguard.next.Common;
import tw.fatminmin.xposed.minminguard.next.stats.EventStore;

/** Adds rules to the user's custom rule JSON (Settings -> Custom app rules). */
public final class UserRules
{
    private UserRules()
    {
    }

    /** @param items each entry is {kind, value}, kinds as in {@link EventStore}. */
    public static boolean add(SharedPreferences modPref, String pkg, List<String[]> items)
    {
        try
        {
            String current = modPref.getString(Common.KEY_USER_APP_RULES, "");
            JSONObject root = current.trim().isEmpty() ? new JSONObject() : new JSONObject(current);
            JSONObject apps = root.optJSONObject("apps");
            if (apps == null) root.put("apps", apps = new JSONObject());
            JSONObject app = apps.optJSONObject(pkg);
            if (app == null) apps.put(pkg, app = new JSONObject());

            for (String[] item : items)
            {
                String key;
                if (EventStore.KIND_CLASS.equals(item[0])) key = "hide_view_classes";
                else if (EventStore.KIND_ID.equals(item[0])) key = "hide_view_ids";
                else key = "kill_activities";

                JSONArray arr = app.optJSONArray(key);
                if (arr == null) app.put(key, arr = new JSONArray());
                boolean exists = false;
                for (int i = 0; i < arr.length(); i++)
                {
                    if (item[1].equals(arr.optString(i))) exists = true;
                }
                if (!exists) arr.put(item[1]);
            }

            modPref.edit().putString(Common.KEY_USER_APP_RULES, root.toString(2)).apply();
            return true;
        }
        catch (Exception e)
        {
            return false;
        }
    }
}
