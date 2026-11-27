package tw.fatminmin.xposed.minminguard.next.ui.dialog;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.util.TypedValue;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import tw.fatminmin.xposed.minminguard.next.R;
import tw.fatminmin.xposed.minminguard.next.orm.AppData;
import tw.fatminmin.xposed.minminguard.next.orm.AppDataDao;
import tw.fatminmin.xposed.minminguard.next.orm.DaoMaster;
import tw.fatminmin.xposed.minminguard.next.orm.DaoSession;
import tw.fatminmin.xposed.minminguard.next.stats.EventStore;
import tw.fatminmin.xposed.minminguard.next.ui.BarChartView;

/**
 * Blocking statistics: totals for today / 7 / 30 / 90 days, a daily chart, the breakdown by
 * ad SDK and by app (with the SDKs behind each app), a log of the latest blocks and the
 * ad networks detected per app.
 */
public class StatsDialogFragment extends DialogFragment
{
    private static final int[] PERIOD_DAYS = {1, 7, 30, 90};
    private static final int[] PERIOD_IDS = {R.id.period_today, R.id.period_7, R.id.period_30, R.id.period_90};

    private static final int APPS_LIMIT = 8;
    private static final int RECENT_LIMIT = 20;

    private View root;
    private int days = 7;
    private boolean showAllApps;
    private boolean showAllRecent;
    private final Map<String, String> appNames = new HashMap<>();
    private final Set<String> collapsed = new HashSet<>();

    public static StatsDialogFragment newInstance()
    {
        return new StatsDialogFragment();
    }

    @Override
    public void onStart()
    {
        super.onStart();
        if (getDialog() != null && getDialog().getWindow() != null)
        {
            Window w = getDialog().getWindow();
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.96f);
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState)
    {
        getDialog().setTitle(R.string.action_statistics);
        root = inflater.inflate(R.layout.dialog_stats, container, false);

        MaterialButtonToggleGroup group = root.findViewById(R.id.stats_period);
        group.check(R.id.period_7);
        group.addOnButtonCheckedListener((g, checkedId, isChecked) ->
        {
            if (!isChecked) return;
            for (int i = 0; i < PERIOD_IDS.length; i++)
            {
                if (PERIOD_IDS[i] == checkedId) days = PERIOD_DAYS[i];
            }
            bindPeriod();
        });

        root.findViewById(R.id.stats_reset).setOnClickListener(v ->
                new MaterialAlertDialogBuilder(requireContext())
                        .setMessage(R.string.stats_reset_confirm)
                        .setPositiveButton(android.R.string.ok, (d, w) ->
                        {
                            EventStore.get(requireContext()).clearStats();
                            bindPeriod();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show());

        section("sdks", R.id.stats_sdks_title, R.id.stats_sdks, R.string.stats_by_sdk);
        section("apps", R.id.stats_apps_title, R.id.stats_apps_container, R.string.stats_top_apps);
        section("recent", R.id.stats_recent_title, R.id.stats_recent, R.string.stats_recent);
        section("nets", R.id.stats_networks_title, R.id.stats_networks, R.string.stats_top_networks);

        bindNetworks();
        bindPeriod();
        return root;
    }

    /** Makes a section header collapse / expand its body. */
    private void section(final String key, int titleId, final int bodyId, final int labelRes)
    {
        final TextView title = root.findViewById(titleId);
        final View body = root.findViewById(bodyId);
        View.OnClickListener apply = v ->
        {
            if (v != null)
            {
                if (!collapsed.remove(key)) collapsed.add(key);
            }
            boolean closed = collapsed.contains(key);
            body.setVisibility(closed ? View.GONE : View.VISIBLE);
            title.setText((closed ? "\u25B8  " : "\u25BE  ") + getString(labelRes));
        };
        title.setOnClickListener(apply);
        apply.onClick(null);
    }

    private String appLabel(String pkg)
    {
        String name = appNames.get(pkg);
        if (name == null)
        {
            name = pkg;
            try
            {
                PackageManager pm = requireContext().getPackageManager();
                name = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
            }
            catch (PackageManager.NameNotFoundException ignored)
            {
            }
            appNames.put(pkg, name);
        }
        return name;
    }

    private String sdkLabel(String sdk)
    {
        return "Other".equals(sdk) ? getString(R.string.stats_other) : sdk;
    }

    private int dp(int v)
    {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView line(CharSequence text, boolean selectable)
    {
        TextView t = new TextView(requireContext());
        t.setText(text);
        t.setTextSize(12);
        t.setPadding(0, dp(2), 0, dp(2));
        t.setTextColor(com.google.android.material.color.MaterialColors.getColor(t,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        t.setTextIsSelectable(selectable);
        return t;
    }

    private interface Detail
    {
        void build(LinearLayout container);
    }

    /** A row that expands to show details, built the first time it is opened. */
    private View expandableRow(Drawable icon, String name, String subtitle, int count, final Detail detail)
    {
        final View row = LayoutInflater.from(requireContext()).inflate(R.layout.item_stats_app, null, false);
        ((TextView) row.findViewById(R.id.stats_app_name)).setText(name);
        ((TextView) row.findViewById(R.id.stats_app_count)).setText(String.valueOf(count));
        TextView sub = row.findViewById(R.id.stats_app_sdks);
        if (subtitle == null || subtitle.isEmpty()) sub.setVisibility(View.GONE);
        else sub.setText(subtitle);
        ImageView img = row.findViewById(R.id.stats_app_icon);
        if (icon != null) img.setImageDrawable(icon);
        else img.setVisibility(View.GONE);

        final LinearLayout box = row.findViewById(R.id.stats_app_detail);
        final TextView chevron = row.findViewById(R.id.stats_row_chevron);
        chevron.setText("\u25B8");
        row.findViewById(R.id.stats_row_header).setOnClickListener(v ->
        {
            boolean open = box.getVisibility() == View.VISIBLE;
            if (!open && box.getChildCount() == 0) detail.build(box);
            box.setVisibility(open ? View.GONE : View.VISIBLE);
            chevron.setText(open ? "\u25B8" : "\u25BE");
        });
        return row;
    }

    private View showAllButton(int total, final Runnable action)
    {
        TextView t = line(getString(R.string.stats_show_all, total), false);
        t.setTextSize(13);
        t.setPadding(0, dp(8), 0, dp(8));
        t.setTextColor(com.google.android.material.color.MaterialColors.getColor(t,
                com.google.android.material.R.attr.colorPrimary));
        t.setOnClickListener(v -> action.run());
        return t;
    }

    /** Everything that depends on the selected period. */
    private void bindPeriod()
    {
        EventStore store = EventStore.get(requireContext());

        int total = store.total(days);
        ((TextView) root.findViewById(R.id.stats_total)).setText(String.valueOf(total));

        LinkedHashMap<String, Integer> sdks = store.sdkTotals(days);
        LinkedHashMap<String, Integer> apps = store.appTotals(days);
        ((TextView) root.findViewById(R.id.stats_summary)).setText(total == 0 ? ""
                : getString(R.string.stats_summary, apps.size(), sdks.size(),
                String.format(Locale.getDefault(), "%.1f", total / (float) days)));

        /* Chart: 7 bars for today / 7 days, 30 bars for the longer periods. */
        int chartDays = days <= 7 ? 7 : 30;
        Map<Long, Integer> daily = store.dailyTotals(chartDays);
        int[] values = new int[daily.size()];
        String[] labels = new String[daily.size()];
        SimpleDateFormat weekday = new SimpleDateFormat("EEE", Locale.getDefault());
        SimpleDateFormat dayOfMonth = new SimpleDateFormat("d", Locale.getDefault());
        weekday.setTimeZone(TimeZone.getTimeZone("UTC"));
        dayOfMonth.setTimeZone(TimeZone.getTimeZone("UTC"));
        int i = 0;
        for (Map.Entry<Long, Integer> e : daily.entrySet())
        {
            values[i] = e.getValue();
            Date date = new Date(e.getKey() * 86400000L);
            if (chartDays == 7) labels[i] = weekday.format(date);
            else labels[i] = (i % 5 == 0 || i == daily.size() - 1) ? dayOfMonth.format(date) : "";
            i++;
        }
        ((BarChartView) root.findViewById(R.id.stats_chart)).setData(values, labels);

        bindSdks(store, sdks);
        bindApps(store, apps);
        bindRecent(store);
    }

    private void bindSdks(final EventStore store, LinkedHashMap<String, Integer> sdks)
    {
        LinearLayout list = root.findViewById(R.id.stats_sdks);
        list.removeAllViews();
        if (sdks.isEmpty())
        {
            list.addView(line(getString(R.string.stats_none_period), false));
            return;
        }
        for (Map.Entry<String, Integer> e : sdks.entrySet())
        {
            final String sdk = e.getKey();
            list.addView(expandableRow(null, sdkLabel(sdk), null, e.getValue(), box ->
            {
                for (Map.Entry<String, Integer> a : store.appTotalsForSdk(sdk, days).entrySet())
                {
                    box.addView(line(appLabel(a.getKey()) + "  \u00B7  " + a.getValue(), false));
                }
            }));
        }
    }

    private void bindApps(final EventStore store, LinkedHashMap<String, Integer> apps)
    {
        LinearLayout list = root.findViewById(R.id.stats_apps_container);
        list.removeAllViews();
        PackageManager pm = requireContext().getPackageManager();
        if (apps.isEmpty())
        {
            list.addView(line(getString(R.string.stats_empty), false));
            return;
        }
        int shown = 0;
        for (Map.Entry<String, Integer> e : apps.entrySet())
        {
            if (!showAllApps && shown >= APPS_LIMIT)
            {
                list.addView(showAllButton(apps.size(), () ->
                {
                    showAllApps = true;
                    bindPeriod();
                }));
                break;
            }
            shown++;
            final String pkg = e.getKey();
            Drawable icon = null;
            try
            {
                icon = pm.getApplicationIcon(pkg);
            }
            catch (PackageManager.NameNotFoundException ignored)
            {
            }

            LinkedHashMap<String, Integer> perSdk = store.sdkTotalsFor(pkg, days);
            StringBuilder sub = new StringBuilder();
            int n = 0;
            for (Map.Entry<String, Integer> s : perSdk.entrySet())
            {
                if (n++ > 0) sub.append("  \u00B7  ");
                sub.append(sdkLabel(s.getKey())).append(" ").append(s.getValue());
                if (n >= 3) break;
            }
            if (perSdk.size() > 3) sub.append("  \u00B7  +").append(perSdk.size() - 3);

            list.addView(expandableRow(icon, appLabel(pkg), sub.toString(), e.getValue(), box ->
            {
                box.addView(line(pkg, true));
                for (Map.Entry<String, Integer> s : store.sdkTotalsFor(pkg, days).entrySet())
                {
                    box.addView(line(sdkLabel(s.getKey()) + "  \u00B7  " + s.getValue(), false));
                }
                List<String[]> recent = store.recentFor(pkg, 15);
                TextView head = line(getString(R.string.stats_latest_here), false);
                head.setPadding(0, dp(8), 0, dp(2));
                head.setTypeface(null, android.graphics.Typeface.BOLD);
                box.addView(head);
                if (recent.isEmpty())
                {
                    box.addView(line(getString(R.string.stats_no_details), false));
                }
                DateFormat time = new SimpleDateFormat("d MMM HH:mm", Locale.getDefault());
                for (String[] r : recent)
                {
                    box.addView(line(time.format(new Date(Long.parseLong(r[0]))) + "  [" + sdkLabel(r[2])
                            + "]\n" + r[3], true));
                }
            }));
        }
    }

    private void bindRecent(EventStore store)
    {
        List<String[]> recent = store.recent(200);
        View title = root.findViewById(R.id.stats_recent_title);
        LinearLayout list = root.findViewById(R.id.stats_recent);
        list.removeAllViews();
        title.setVisibility(recent.isEmpty() ? View.GONE : View.VISIBLE);
        if (recent.isEmpty())
        {
            list.setVisibility(View.GONE);
            return;
        }
        list.setVisibility(collapsed.contains("recent") ? View.GONE : View.VISIBLE);

        DateFormat time = new SimpleDateFormat("d MMM HH:mm", Locale.getDefault());
        int shown = 0;
        for (String[] r : recent)
        {
            if (!showAllRecent && shown >= RECENT_LIMIT)
            {
                list.addView(showAllButton(recent.size(), () ->
                {
                    showAllRecent = true;
                    bindPeriod();
                }));
                break;
            }
            shown++;
            final TextView t = line(time.format(new Date(Long.parseLong(r[0]))) + "  " + appLabel(r[1])
                    + "  [" + sdkLabel(r[2]) + "]\n" + r[3], false);
            t.setMaxLines(2);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setOnClickListener(v ->
            {
                boolean open = t.getMaxLines() > 2;
                t.setMaxLines(open ? 2 : Integer.MAX_VALUE);
                t.setEllipsize(open ? android.text.TextUtils.TruncateAt.END : null);
            });
            list.addView(t);
        }
    }

    /** Ad networks detected per app (from the provider database, all time). */
    private void bindNetworks()
    {
        LinearLayout list = root.findViewById(R.id.stats_networks);
        TextView txtAll = root.findViewById(R.id.stats_alltime);

        DaoMaster.DevOpenHelper helper = new DaoMaster.DevOpenHelper(requireContext(), "mmg", null);
        SQLiteDatabase db = helper.getWritableDatabase();
        DaoSession session = new DaoMaster(db).newSession();
        AppDataDao dao = session.getAppDataDao();
        List<AppData> all = dao.loadAll();
        helper.close();

        long total = 0;
        final Map<String, List<String>> networkApps = new HashMap<>();
        for (AppData data : all)
        {
            if (data.getBlockNum() != null) total += data.getBlockNum();
            if (data.getAdNetworks() != null)
            {
                for (String net : data.getAdNetworks().split(" "))
                {
                    if (net.trim().length() > 0)
                    {
                        List<String> pkgs = networkApps.get(net);
                        if (pkgs == null)
                        {
                            pkgs = new ArrayList<>();
                            networkApps.put(net, pkgs);
                        }
                        pkgs.add(data.getPkgName());
                    }
                }
            }
        }
        txtAll.setText(getString(R.string.stats_alltime, (int) total));

        List<Map.Entry<String, List<String>>> nets = new ArrayList<>(networkApps.entrySet());
        Collections.sort(nets, new Comparator<Map.Entry<String, List<String>>>()
        {
            @Override
            public int compare(Map.Entry<String, List<String>> l, Map.Entry<String, List<String>> r)
            {
                return Integer.compare(r.getValue().size(), l.getValue().size());
            }
        });

        list.removeAllViews();
        if (nets.isEmpty())
        {
            list.addView(line(getString(R.string.stats_empty), false));
            return;
        }
        for (Map.Entry<String, List<String>> e : nets)
        {
            final List<String> pkgs = e.getValue();
            list.addView(expandableRow(null, e.getKey(), null, pkgs.size(), box ->
            {
                for (String p : pkgs) box.addView(line(appLabel(p), false));
            }));
        }
    }
}
