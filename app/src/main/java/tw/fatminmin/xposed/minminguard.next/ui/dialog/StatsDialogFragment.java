package tw.fatminmin.xposed.minminguard.next.ui.dialog;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    private View root;
    private int days = 7;

    public static StatsDialogFragment newInstance()
    {
        return new StatsDialogFragment();
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

        bindNetworks();
        bindPeriod();
        return root;
    }

    /** Everything that depends on the selected period. */
    private void bindPeriod()
    {
        EventStore store = EventStore.get(requireContext());

        ((TextView) root.findViewById(R.id.stats_total)).setText(String.valueOf(store.total(days)));

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

        /* By SDK. */
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Map.Entry<String, Integer> e : store.sdkTotals(days).entrySet())
        {
            sb.append(e.getKey()).append(" - ").append(e.getValue()).append("\n");
            if (++shown >= 10) break;
        }
        ((TextView) root.findViewById(R.id.stats_sdks)).setText(
                shown == 0 ? getString(R.string.stats_none_period) : sb.toString().trim());

        bindApps(store);
        bindRecent(store);
    }

    private void bindApps(EventStore store)
    {
        LinearLayout list = root.findViewById(R.id.stats_apps_container);
        list.removeAllViews();
        PackageManager pm = requireContext().getPackageManager();
        LayoutInflater inflater = LayoutInflater.from(requireContext());

        int shown = 0;
        for (Map.Entry<String, Integer> e : store.appTotals(days).entrySet())
        {
            View row = inflater.inflate(R.layout.item_stats_app, list, false);
            String pkg = e.getKey();
            String name = pkg;
            Drawable icon = null;
            try
            {
                ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                name = String.valueOf(pm.getApplicationLabel(info));
                icon = pm.getApplicationIcon(info);
            }
            catch (PackageManager.NameNotFoundException ignored)
            {
            }
            ((TextView) row.findViewById(R.id.stats_app_name)).setText(name);
            ((TextView) row.findViewById(R.id.stats_app_count)).setText(String.valueOf(e.getValue()));
            ImageView img = row.findViewById(R.id.stats_app_icon);
            if (icon != null) img.setImageDrawable(icon);

            StringBuilder sdks = new StringBuilder();
            int n = 0;
            for (Map.Entry<String, Integer> s : store.sdkTotalsFor(pkg, days).entrySet())
            {
                if (n++ > 0) sdks.append("  ·  ");
                sdks.append(s.getKey()).append(" ").append(s.getValue());
                if (n >= 4) break;
            }
            ((TextView) row.findViewById(R.id.stats_app_sdks)).setText(sdks);

            list.addView(row);
            if (++shown >= 15) break;
        }
        if (shown == 0)
        {
            TextView empty = new TextView(requireContext());
            empty.setText(R.string.stats_empty);
            list.addView(empty);
        }
    }

    private void bindRecent(EventStore store)
    {
        List<String[]> recent = store.recent(30);
        View title = root.findViewById(R.id.stats_recent_title);
        TextView text = root.findViewById(R.id.stats_recent);
        title.setVisibility(recent.isEmpty() ? View.GONE : View.VISIBLE);
        text.setVisibility(recent.isEmpty() ? View.GONE : View.VISIBLE);

        PackageManager pm = requireContext().getPackageManager();
        DateFormat time = new SimpleDateFormat("d MMM HH:mm", Locale.getDefault());
        Map<String, String> names = new HashMap<>();
        StringBuilder sb = new StringBuilder();
        for (String[] r : recent)
        {
            String name = names.get(r[1]);
            if (name == null)
            {
                name = r[1];
                try
                {
                    name = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(r[1], 0)));
                }
                catch (PackageManager.NameNotFoundException ignored)
                {
                }
                names.put(r[1], name);
            }
            sb.append(time.format(new Date(Long.parseLong(r[0])))).append("  ").append(name)
                    .append("  [").append(r[2]).append("]\n    ").append(r[3]).append("\n");
        }
        text.setText(sb.toString().trim());
    }

    /** Ad networks detected per app (from the provider database, all time). */
    private void bindNetworks()
    {
        TextView txtNetworks = root.findViewById(R.id.stats_networks);
        TextView txtAll = root.findViewById(R.id.stats_alltime);

        DaoMaster.DevOpenHelper helper = new DaoMaster.DevOpenHelper(requireContext(), "mmg", null);
        SQLiteDatabase db = helper.getWritableDatabase();
        DaoSession session = new DaoMaster(db).newSession();
        AppDataDao dao = session.getAppDataDao();
        List<AppData> all = dao.loadAll();
        helper.close();

        long total = 0;
        Map<String, Integer> networkCount = new HashMap<>();
        for (AppData data : all)
        {
            if (data.getBlockNum() != null) total += data.getBlockNum();
            if (data.getAdNetworks() != null)
            {
                for (String net : data.getAdNetworks().split(" "))
                {
                    if (net.trim().length() > 0)
                    {
                        Integer count = networkCount.get(net);
                        networkCount.put(net, count == null ? 1 : count + 1);
                    }
                }
            }
        }
        txtAll.setText(getString(R.string.stats_alltime, (int) total));

        List<Map.Entry<String, Integer>> nets = new ArrayList<>(networkCount.entrySet());
        Collections.sort(nets, new Comparator<Map.Entry<String, Integer>>()
        {
            @Override
            public int compare(Map.Entry<String, Integer> l, Map.Entry<String, Integer> r)
            {
                return Integer.compare(r.getValue(), l.getValue());
            }
        });

        StringBuilder netsSb = new StringBuilder();
        int shown = 0;
        for (Map.Entry<String, Integer> e : nets)
        {
            netsSb.append(e.getKey()).append(" - ")
                    .append(getString(R.string.stats_apps_count, e.getValue())).append("\n");
            if (++shown >= 10) break;
        }
        txtNetworks.setText(shown == 0 ? getString(R.string.stats_empty) : netsSb.toString().trim());
    }
}
