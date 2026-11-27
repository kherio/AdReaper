package tw.fatminmin.xposed.minminguard.next.blocker;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Collapses native ads that are part of a RecyclerView feed. These have no ad SDK view
 * to match: they are ordinary rows that carry a small visible label such as "Ad" or
 * "Sponsored". After each bind the row is scanned and, if a TextView holds exactly one
 * of those labels, the whole row is collapsed. Rows are restored when the holder is
 * recycled for a normal item.
 */
public final class FeedAdBlocking
{
    private static final String COLLAPSED = "adreaper_collapsed_height";
    private static final int MAX_VIEWS = 80;
    private static final int MAX_DEPTH = 8;

    private static final Set<String> LABELS = new HashSet<>(Arrays.asList(
            "ad", "ads", "advert", "advertisement", "sponsored", "promoted", "promotion",
            "anuncio", "publicidad", "patrocinado", "promocionado",
            "annonce", "publicite", "publicité", "sponsorise", "sponsorisé",
            "werbung", "anzeige", "gesponsert",
            "annuncio", "pubblicita", "pubblicità", "sponsorizzato",
            "anuncio patrocinado", "publicidade", "patrocinado por"));

    private FeedAdBlocking()
    {
    }

    public static void install(final String pkg, XC_LoadPackage.LoadPackageParam lpparam)
    {
        final XC_MethodHook bind = new XC_MethodHook()
        {
            @Override
            protected void afterHookedMethod(MethodHookParam param)
            {
                try
                {
                    if (param.args.length < 1 || param.args[0] == null)
                    {
                        return;
                    }
                    Field f = param.args[0].getClass().getField("itemView");
                    View item = (View) f.get(param.args[0]);
                    if (item == null)
                    {
                        return;
                    }
                    if (hasAdLabel(item, 0, new int[]{0}))
                    {
                        collapse(pkg, item);
                    }
                    else
                    {
                        restore(item);
                    }
                }
                catch (Throwable ignored)
                {
                }
            }
        };

        String[] adapters = {
                "androidx.recyclerview.widget.RecyclerView$Adapter",
                "android.support.v7.widget.RecyclerView$Adapter"
        };
        for (String name : adapters)
        {
            Class<?> c = XposedHelpers.findClassIfExists(name, lpparam.classLoader);
            if (c != null)
            {
                try
                {
                    /* bindViewHolder is final and calls the app's onBindViewHolder */
                    XposedBridge.hookAllMethods(c, "bindViewHolder", bind);
                }
                catch (Throwable t)
                {
                    Util.log(pkg, "FeedAdBlocking: " + t);
                }
            }
        }
    }

    static boolean hasAdLabel(View v, int depth, int[] seen)
    {
        if (++seen[0] > MAX_VIEWS || depth > MAX_DEPTH)
        {
            return false;
        }
        if (v instanceof TextView)
        {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.length() > 0 && t.length() <= 20 && v.getVisibility() == View.VISIBLE)
            {
                String s = t.toString().trim().toLowerCase(Locale.ROOT);
                while (s.endsWith(".") || s.endsWith(":") || s.endsWith("·"))
                {
                    s = s.substring(0, s.length() - 1).trim();
                }
                if (LABELS.contains(s))
                {
                    return true;
                }
            }
        }
        if (v instanceof ViewGroup)
        {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++)
            {
                if (hasAdLabel(g.getChildAt(i), depth + 1, seen))
                {
                    return true;
                }
            }
        }
        return false;
    }

    private static void collapse(String pkg, View item)
    {
        ViewGroup.LayoutParams lp = item.getLayoutParams();
        if (lp == null || XposedHelpers.getAdditionalInstanceField(item, COLLAPSED) != null)
        {
            return;
        }
        XposedHelpers.setAdditionalInstanceField(item, COLLAPSED, lp.height);
        lp.height = 0;
        if (lp instanceof ViewGroup.MarginLayoutParams)
        {
            ((ViewGroup.MarginLayoutParams) lp).setMargins(0, 0, 0, 0);
        }
        item.setLayoutParams(lp);
        item.setVisibility(View.GONE);
        Util.notifyBlocked(pkg, "Feed", 1, "native ad row");
    }

    private static void restore(View item)
    {
        Object h = XposedHelpers.getAdditionalInstanceField(item, COLLAPSED);
        if (h == null)
        {
            return;
        }
        XposedHelpers.removeAdditionalInstanceField(item, COLLAPSED);
        ViewGroup.LayoutParams lp = item.getLayoutParams();
        if (lp != null)
        {
            lp.height = (Integer) h;
            item.setLayoutParams(lp);
        }
        item.setVisibility(View.VISIBLE);
    }
}
