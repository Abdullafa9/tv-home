package app.tvhome.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Full-screen home screen: shows assets/index.html and opens TV apps for it. */
public class MainActivity extends Activity {
    private static final String TAG = "TVHome";
    /** The only hosts the page may read through the bridge. */
    private static final String[] ALLOWED_HOSTS = {"api.open-meteo.com", "site.api.espn.com"};
    /** Opened by the "all apps" tile. */
    private static final String APPS_SCREEN = "com.spocky.projengmenu";

    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(5, 7, 15));
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "TVBridge");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
        web.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        web.requestFocus();
        web.evaluateJavascript("window.__tvResume && window.__tvResume()", null);
    }

    @Override
    protected void onPause() {
        web.onPause();
        super.onPause();
    }

    /** This is the home screen: Back has nowhere to go. */
    @Override
    public void onBackPressed() {
    }

    private void openApp(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            boolean all = "all".equals(pkg);
            String target = all ? APPS_SCREEN : pkg;
            Intent i = pm.getLeanbackLaunchIntentForPackage(target);
            if (i == null) i = pm.getLaunchIntentForPackage(target);
            if (i == null && all) i = new Intent(Settings.ACTION_APPLICATION_SETTINGS);
            if (i == null) {
                Log.w(TAG, "no launch intent for " + target);
                return;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Log.e(TAG, "launch failed for " + pkg, e);
        }
    }

    private static boolean allowed(String host) {
        for (String h : ALLOWED_HOSTS) {
            if (h.equalsIgnoreCase(host)) return true;
        }
        return false;
    }

    /** Reads one URL off the main thread and hands the text back to the page. */
    private void fetch(String id, String address) {
        boolean ok = false;
        String body;
        HttpURLConnection c = null;
        try {
            URL url = new URL(address);
            if (!"https".equals(url.getProtocol()) || !allowed(url.getHost())) {
                throw new SecurityException("host not allowed: " + url.getHost());
            }
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (in != null) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
            }
            ok = code >= 200 && code < 300;
            body = ok ? out.toString("UTF-8") : "HTTP " + code;
            Log.i(TAG, "fetch " + code + " " + out.size() + "b " + address);
        } catch (Exception e) {
            body = String.valueOf(e);
            Log.w(TAG, "fetch failed: " + address, e);
        } finally {
            if (c != null) c.disconnect();
        }
        final String js = "window.__tvDone(" + JSONObject.quote(id) + "," + ok + "," + JSONObject.quote(body) + ")";
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (web != null) web.evaluateJavascript(js, null);
            }
        });
    }

    /** What the page can ask the TV to do. */
    private class Bridge {
        @JavascriptInterface
        public void launch(final String pkg) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    openApp(pkg);
                }
            });
        }

        @JavascriptInterface
        public void get(final String id, final String url) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    fetch(id, url);
                }
            }).start();
        }

        @JavascriptInterface
        public void log(String message) {
            Log.i(TAG, String.valueOf(message));
        }
    }
}
