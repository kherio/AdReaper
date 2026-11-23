package tw.fatminmin.xposed.minminguard.next.ui.dialog;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;
import tw.fatminmin.xposed.minminguard.next.Common;
import tw.fatminmin.xposed.minminguard.next.R;
import tw.fatminmin.xposed.minminguard.next.orm.AppData;

/**
 * Created by fatminmin on 2015/10/25.
 */
public class AppDetailDialogFragment extends DialogFragment
{

    private ImageView imgAppIcon;
    private TextView txtAppName;
    private TextView txtPkgName;
    private TextView txtAdNetworks;
    private TextView txtAdsBlocked;
    private SwitchCompat swtUrlFilter;

    private String appName;
    private String pkgName;
    private String adNetworks;
    private Integer blockNum;

    private SharedPreferences mPref;

    public static AppDetailDialogFragment newInstance(String appName, String pkgName, AppData appData)
    {
        Bundle args = new Bundle();
        args.putString("appName", appName);
        args.putString("pkgName", pkgName);
        if (appData != null)
        {
            args.putString("adNetworks", appData.getAdNetworks());
            args.putInt("blockNum", appData.getBlockNum());
        }

        AppDetailDialogFragment fragment = new AppDetailDialogFragment();
        fragment.setArguments(args);

        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        Bundle args = getArguments();
        appName = args.getString("appName");
        pkgName = args.getString("pkgName");
        adNetworks = args.getString("adNetworks");
        blockNum = args.getInt("blockNum");

        Context ctx = ContextCompat.createDeviceProtectedStorageContext(getActivity());
        if (ctx == null) ctx = getActivity();

        mPref = ctx.getSharedPreferences(Common.MOD_PREFS, Common.getPrefMode());
    }

    /** Ads found by the Inspector in this app, selectable to become blocking rules. */
    private void bindCandidates(View v)
    {
        final android.widget.LinearLayout container = v.findViewById(R.id.candidates_container);
        final View hint = v.findViewById(R.id.txt_candidates_hint);
        final View btn = v.findViewById(R.id.btn_block_candidates);
        final tw.fatminmin.xposed.minminguard.next.stats.EventStore store =
                tw.fatminmin.xposed.minminguard.next.stats.EventStore.get(requireContext());

        final java.util.List<String[]> items = store.candidates(pkgName);
        final java.util.List<android.widget.CheckBox> boxes = new java.util.ArrayList<>();
        if (items.isEmpty())
        {
            return;
        }
        hint.setVisibility(View.GONE);
        btn.setVisibility(View.VISIBLE);
        for (String[] item : items)
        {
            android.widget.CheckBox cb = new android.widget.CheckBox(requireContext());
            cb.setText(item[0] + ": " + item[1]);
            cb.setTextSize(13);
            cb.setChecked(true);
            container.addView(cb);
            boxes.add(cb);
        }
        btn.setOnClickListener(view ->
        {
            java.util.List<String[]> chosen = new java.util.ArrayList<>();
            for (int i = 0; i < boxes.size(); i++)
            {
                if (boxes.get(i).isChecked()) chosen.add(items.get(i));
            }
            if (chosen.isEmpty()) return;
            boolean ok = tw.fatminmin.xposed.minminguard.next.ui.UserRules.add(mPref, pkgName, chosen);
            if (ok)
            {
                for (String[] c : chosen) store.removeCandidate(pkgName, c[0], c[1]);
            }
            android.widget.Toast.makeText(requireContext(),
                    ok ? R.string.candidates_added : R.string.candidates_failed,
                    android.widget.Toast.LENGTH_LONG).show();
            if (ok) dismiss();
        });
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState)
    {

        getDialog().getWindow().requestFeature(Window.FEATURE_NO_TITLE);

        View v = inflater.inflate(R.layout.dialog_app_detail, container, false);
        imgAppIcon = v.findViewById(R.id.detail_app_icon);
        txtAppName = v.findViewById(R.id.detail_app_name);
        txtPkgName = v.findViewById(R.id.detail_pkg_name);
        txtAdNetworks = v.findViewById(R.id.txt_ad_networks);
        txtAdsBlocked = v.findViewById(R.id.txt_ads_blocked);
        swtUrlFilter = v.findViewById(R.id.switch_url_filter);

        txtAppName.setText(appName);
        txtPkgName.setText(pkgName);
        if (adNetworks != null && adNetworks.length() > 0)
        {
            txtAdNetworks.setText(adNetworks);
        }
        if (blockNum != null)
        {
            txtAdsBlocked.setText(blockNum.toString());
        }

        /* set appicon */
        final PackageManager pm = v.getContext().getPackageManager();
        try
        {
            Drawable appIcon = pm.getApplicationIcon(pkgName);
            if (appIcon != null)
            {
                imgAppIcon.setImageDrawable(appIcon);
            }
        }
        catch (PackageManager.NameNotFoundException e)
        {
            e.printStackTrace();
        }

        bindCandidates(v);

        swtUrlFilter.setChecked(mPref.getBoolean(pkgName + "_url", false));
        swtUrlFilter.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                mPref.edit().putBoolean(pkgName + "_url", swtUrlFilter.isChecked()).commit();
            }
        });

        return v;
    }
}
