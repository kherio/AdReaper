package tw.fatminmin.xposed.minminguard.next.blocker;

/** Maps an ad class name to a readable SDK name for the statistics. */
public final class SdkNames
{
    private static final String[][] PREFIXES = {
            {"com.google.android.gms.ads", "AdMob"},
            {"com.google.ads", "AdMob"},
            {"com.applovin", "AppLovin"},
            {"com.ironsource", "LevelPlay"},
            {"com.unity3d.mediation", "LevelPlay"},
            {"com.unity3d.ads", "Unity Ads"},
            {"com.unity3d.services", "Unity Ads"},
            {"com.vungle", "Vungle"},
            {"com.liftoff", "Vungle"},
            {"com.facebook.ads", "Meta Audience Network"},
            {"com.chartboost", "Chartboost"},
            {"com.smaato", "Smaato"},
            {"com.mbridge", "Mintegral"},
            {"com.bytedance.sdk.openadsdk", "Pangle"},
            {"com.inmobi", "InMobi"},
            {"com.adcolony", "AdColony"},
            {"com.mopub", "MoPub"},
            {"com.startapp", "StartApp"},
            {"com.fyber", "Fyber"},
            {"com.yandex.mobile.ads", "Yandex Ads"},
            {"net.pubnative", "PubNative"},
    };

    private SdkNames()
    {
    }

    private static final String[][] HOSTS = {
            {"doubleclick", "AdMob"}, {"googlesyndication", "AdMob"}, {"googleadservices", "AdMob"},
            {"admob", "AdMob"}, {"applovin", "AppLovin"}, {"applvn", "AppLovin"},
            {"unityads", "Unity Ads"}, {"supersonic", "LevelPlay"}, {"ssacdn", "LevelPlay"},
            {"ironsrc", "LevelPlay"}, {"vungle", "Vungle"}, {"liftoff", "Vungle"},
            {"chartboost", "Chartboost"}, {"inmobi", "InMobi"}, {"adcolony", "AdColony"},
            {"mopub", "MoPub"}, {"smaato", "Smaato"}, {"mintegral", "Mintegral"},
            {"pangle", "Pangle"}, {"startapp", "StartApp"}, {"fyber", "Fyber"},
            {"tapjoy", "Tapjoy"}, {"adnxs", "AppNexus"}, {"pubmatic", "PubMatic"},
            {"criteo", "Criteo"}, {"taboola", "Taboola"}, {"outbrain", "Outbrain"},
            {"amazon-adsystem", "Amazon Ads"}
    };

    /** Readable ad network name for a blocked host; "Network" when unknown. */
    public static String labelForHost(String host)
    {
        if (host != null)
        {
            for (String[] p : HOSTS)
            {
                if (host.contains(p[0])) return p[1];
            }
        }
        return "Network";
    }

    public static String label(String className)
    {
        if (className != null)
        {
            for (String[] p : PREFIXES)
            {
                if (className.startsWith(p[0])) return p[1];
            }
        }
        return "Other";
    }
}
