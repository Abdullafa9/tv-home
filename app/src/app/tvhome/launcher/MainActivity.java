package app.tvhome.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen home screen. Shows the page in assets/index.html, opens TV apps for it, and picks up
 * newer versions of that page from this project's repository so design changes need no reinstall.
 */
public class MainActivity extends Activity {
    private static final String TAG = "TVHome";
    /** The only hosts the page may read through the bridge. */
    private static final String[] ALLOWED_HOSTS = {"api.open-meteo.com", "site.api.espn.com", "commons.wikimedia.org"};
    /** Opened by the "all apps" tile. */
    private static final String APPS_SCREEN = "com.spocky.projengmenu";
    /** Where the newest version of the page lives. */
    private static final String PAGE_URL =
            "https://raw.githubusercontent.com/Abdullafa9/tv-home/main/app/assets/index.html";
    private static final long PAGE_CHECK_EVERY_MS = 10 * 60 * 1000;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
                    + " TVHome/1 (+https://github.com/Abdullafa9/tv-home)";

    private WebView web;
    private volatile String currentHtml;
    private long lastPageCheck;

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
        showPage();
        web.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        HomeKeyService.allowStockHome(false);
        web.onResume();
        web.requestFocus();
        web.evaluateJavascript("window.__tvResume && window.__tvResume()", null);
        checkForNewPage();
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

    // ---------------------------------------------------------------- the page

    /** One cached copy per installed version, so a fresh install always starts from its own page. */
    private File cacheFile() {
        int code = 0;
        try {
            code = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception e) {
            Log.w(TAG, "no version code", e);
        }
        return new File(getFilesDir(), "index-" + code + ".html");
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    private String readCached() {
        File f = cacheFile();
        if (!f.exists()) return null;
        try {
            InputStream in = new FileInputStream(f);
            try {
                return readAll(in);
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private String readBundled() {
        try {
            InputStream in = getAssets().open("index.html");
            try {
                return readAll(in);
            } finally {
                in.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "bundled page missing", e);
            return null;
        }
    }

    /** Guards against saving a cut-off download or something that is not this page. */
    private static boolean looksComplete(String html) {
        return html != null && html.length() > 5000 && html.contains("__tvResume")
                && html.trim().endsWith("</html>");
    }

    private void showPage() {
        String html = readCached();
        if (!looksComplete(html)) html = readBundled();
        currentHtml = html;
        if (html != null) {
            web.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null);
        } else {
            web.loadUrl("file:///android_asset/index.html");
        }
    }

    private void checkForNewPage() {
        long now = System.currentTimeMillis();
        if (now - lastPageCheck < PAGE_CHECK_EVERY_MS) return;
        lastPageCheck = now;
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(PAGE_URL).openConnection();
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(20000);
                    c.setUseCaches(false);
                    int code = c.getResponseCode();
                    if (code != 200) {
                        Log.i(TAG, "page check HTTP " + code);
                        return;
                    }
                    final String fresh;
                    InputStream in = c.getInputStream();
                    try {
                        fresh = readAll(in);
                    } finally {
                        in.close();
                    }
                    if (!looksComplete(fresh)) {
                        Log.w(TAG, "page check: download incomplete, ignored");
                        return;
                    }
                    if (fresh.equals(currentHtml)) {
                        Log.i(TAG, "page check: no change");
                        return;
                    }
                    FileOutputStream out = new FileOutputStream(cacheFile());
                    try {
                        out.write(fresh.getBytes("UTF-8"));
                    } finally {
                        out.close();
                    }
                    Log.i(TAG, "page check: new version saved, reloading");
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            showPage();
                        }
                    });
                } catch (Exception e) {
                    Log.w(TAG, "page check failed", e);
                } finally {
                    if (c != null) c.disconnect();
                }
            }
        }).start();
    }

    // ---------------------------------------------------------------- what the page can ask for

    /** The "all apps" tile opens the Google TV launcher, which lists every app. */
    private boolean openStockHome() {
        try {
            HomeKeyService.allowStockHome(true);
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_HOME);
            i.setPackage(HomeKeyService.STOCK_HOME);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception e) {
            HomeKeyService.allowStockHome(false);
            Log.w(TAG, "could not open the Google launcher", e);
            return false;
        }
    }

    /** The app's own TV banner (or its icon) as a PNG data URL, so tiles show the real artwork. */
    private String artwork(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            Drawable d = null;
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launch != null && launch.getComponent() != null) d = pm.getActivityBanner(launch.getComponent());
            if (d == null) d = pm.getApplicationBanner(pkg);
            boolean banner = d != null;
            if (d == null) d = pm.getApplicationIcon(pkg);
            if (d == null) return "";
            int w = banner ? 480 : 192, h = banner ? 270 : 192;
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, w, h);
            d.draw(new Canvas(bmp));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            bmp.recycle();
            return (banner ? "banner|" : "icon|") + "data:image/png;base64,"
                    + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            Log.w(TAG, "no artwork for " + pkg + ": " + e);
            return "";
        }
    }

    /** Switches the TV to one of its HDMI ports (1, 2, 3 ...). */
    private void openInput(int number) {
        try {
            TvInputManager tm = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            List<TvInputInfo> ports = new ArrayList<TvInputInfo>();
            TvInputInfo match = null;
            for (TvInputInfo info : tm.getTvInputList()) {
                CharSequence label = info.loadLabel(this);
                Log.i(TAG, "input id=" + info.getId() + " label=" + label + " type=" + info.getType()
                        + " parent=" + info.getParentId());
                if (info.getType() != TvInputInfo.TYPE_HDMI || info.getParentId() != null) continue;
                ports.add(info);
                String text = (label + " " + info.getId()).toUpperCase();
                if (match == null && text.matches(".*HDMI[ _-]?" + number + "(\\D.*)?")) match = info;
            }
            if (match == null && number >= 1 && number <= ports.size()) match = ports.get(number - 1);
            if (match == null) {
                Log.w(TAG, "HDMI " + number + " not found among " + ports.size() + " ports");
                return;
            }
            Log.i(TAG, "opening HDMI " + number + " -> " + match.getId());
            Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(match.getId()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Log.e(TAG, "could not switch to HDMI " + number, e);
        }
    }

    private void openApp(String pkg) {
        try {
            if (pkg != null && pkg.startsWith("hdmi:")) {
                openInput(Integer.parseInt(pkg.substring(5)));
                return;
            }
            PackageManager pm = getPackageManager();
            boolean all = "all".equals(pkg);
            if (all && openStockHome()) return;
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
            c.setRequestProperty("User-Agent", USER_AGENT);
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = "";
            if (in != null) {
                try {
                    text = readAll(in);
                } finally {
                    in.close();
                }
            }
            ok = code >= 200 && code < 300;
            if (ok) {
                body = text;
                Log.i(TAG, "fetch " + code + " " + text.length() + " chars " + address);
            } else {
                String snippet = text.length() > 200 ? text.substring(0, 200) : text;
                body = "HTTP " + code + " " + snippet;
                Log.w(TAG, "fetch " + code + " " + address + " -> " + snippet);
            }
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
        public String artwork(String pkg) {
            return MainActivity.this.artwork(pkg);
        }

        @JavascriptInterface
        public void log(String message) {
            Log.i(TAG, String.valueOf(message));
        }
    }
}
