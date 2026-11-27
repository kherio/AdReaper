package tw.fatminmin.xposed.minminguard.next.blocker;

import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import tw.fatminmin.xposed.minminguard.next.Main;

/**
 * Blocks connections to ad domains from inside the app process.
 *
 * Unlike a DNS or hosts blocker this works for every Java network stack of the app
 * (HttpURLConnection, OkHttp, ...), including apps that use their own DNS-over-HTTPS,
 * and does not depend on the name of the ad SDK classes, so obfuscated or unknown
 * SDKs are cut off as well. WebView sub-resources (ads inside web pages) are blocked
 * through shouldInterceptRequest.
 *
 * Domains come from the bundled host list (exact names) and from the "blocked_hosts"
 * rules (the domain and all its subdomains).
 */
public final class NetworkBlocking
{
    private static final long REPORT_INTERVAL_MS = 10000;

    private static final Map<String, Boolean> DECISIONS = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_REPORT = new ConcurrentHashMap<>();
    private static final Set<Class<?>> HOOKED_CLIENTS = ConcurrentHashMap.newKeySet();

    private NetworkBlocking()
    {
    }

    public static void install(final String pkg, XC_LoadPackage.LoadPackageParam lpparam)
    {
        try
        {
            XC_MethodHook dns = new XC_MethodHook()
            {
                @Override
                protected void beforeHookedMethod(MethodHookParam param)
                {
                    if (param.args.length == 0 || !(param.args[0] instanceof String))
                    {
                        return;
                    }
                    String host = (String) param.args[0];
                    if (isBlocked(pkg, host))
                    {
                        report(pkg, host);
                        param.setThrowable(new UnknownHostException(
                                "Unable to resolve host \"" + host + "\": blocked by AdReaper"));
                    }
                }
            };
            XposedBridge.hookAllMethods(InetAddress.class, "getAllByName", dns);
            XposedBridge.hookAllMethods(InetAddress.class, "getByName", dns);
        }
        catch (Throwable e)
        {
            Util.log(pkg, "NetworkBlocking (dns): " + e);
        }

        try
        {
            hookWebViews(pkg);
        }
        catch (Throwable e)
        {
            Util.log(pkg, "NetworkBlocking (webview): " + e);
        }
    }

    private static void hookWebViews(final String pkg)
    {
        final XC_MethodHook intercept = new XC_MethodHook()
        {
            @Override
            protected void beforeHookedMethod(MethodHookParam param)
            {
                String host = null;
                if (param.args.length > 1)
                {
                    Object a = param.args[1];
                    if (a instanceof WebResourceRequest)
                    {
                        host = ((WebResourceRequest) a).getUrl().getHost();
                    }
                    else if (a instanceof String)
                    {
                        host = android.net.Uri.parse((String) a).getHost();
                    }
                }
                if (host != null && isBlocked(pkg, host))
                {
                    report(pkg, host);
                    param.setResult(new WebResourceResponse("text/plain", "UTF-8",
                            new ByteArrayInputStream(new byte[0])));
                }
            }
        };

        hookInterceptMethods(WebViewClient.class, intercept);

        /* Apps subclass WebViewClient and override shouldInterceptRequest, so hook the
           concrete class as soon as the app registers its client. */
        XposedBridge.hookAllMethods(WebView.class, "setWebViewClient", new XC_MethodHook()
        {
            @Override
            protected void beforeHookedMethod(MethodHookParam param)
            {
                Object client = param.args.length > 0 ? param.args[0] : null;
                if (client == null)
                {
                    return;
                }
                Class<?> c = client.getClass();
                while (c != null && c != Object.class && c != WebViewClient.class)
                {
                    if (HOOKED_CLIENTS.add(c))
                    {
                        hookInterceptMethods(c, intercept);
                    }
                    c = c.getSuperclass();
                }
            }
        });
    }

    private static void hookInterceptMethods(Class<?> clazz, XC_MethodHook hook)
    {
        try
        {
            for (Method m : clazz.getDeclaredMethods())
            {
                if (m.getName().equals("shouldInterceptRequest")
                        && m.getReturnType() == WebResourceResponse.class)
                {
                    XposedBridge.hookMethod(m, hook);
                }
            }
        }
        catch (Throwable ignored)
        {
        }
    }

    static boolean isBlocked(String pkg, String rawHost)
    {
        if (rawHost == null || rawHost.isEmpty())
        {
            return false;
        }
        String host = rawHost.toLowerCase(Locale.ROOT);
        if (host.endsWith("."))
        {
            host = host.substring(0, host.length() - 1);
        }

        Boolean cached = DECISIONS.get(host);
        if (cached != null)
        {
            return cached;
        }
        boolean blocked = Main.patterns.contains(host) || AppRules.matchesHost(pkg, host);
        DECISIONS.put(host, blocked);
        return blocked;
    }

    private static void report(String pkg, String host)
    {
        long now = System.currentTimeMillis();
        Long last = LAST_REPORT.get(host);
        if (last != null && now - last < REPORT_INTERVAL_MS)
        {
            return;
        }
        LAST_REPORT.put(host, now);
        Util.notifyBlocked(pkg, SdkNames.labelForHost(host), 1, "host " + host);
    }
}
