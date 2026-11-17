package app.tvhome.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Google TV always sends the Home button to its own launcher. While this service is switched on it
 * brings this home screen forward instead, in two ways: it catches the Home key where the TV lets it,
 * and it steps in whenever the Google launcher comes to the front. The "all apps" tile may open the
 * Google launcher on purpose; that visit is left alone until another app is opened from it.
 */
public class HomeKeyService extends AccessibilityService {
    private static final String TAG = "TVHome";
    static final String STOCK_HOME = "com.google.android.apps.tv.launcherx";
    private static final long STOCK_VISIT_MAX_MS = 15 * 60 * 1000L;

    private static volatile long stockAllowedUntil;

    static void allowStockHome(boolean allow) {
        stockAllowedUntil = allow ? System.currentTimeMillis() + STOCK_VISIT_MAX_MS : 0;
    }

    private static boolean stockAllowed() {
        return System.currentTimeMillis() < stockAllowedUntil;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "home service connected");
        openHome();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_HOME) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                Log.i(TAG, "home key caught");
                allowStockHome(false);
                openHome();
            }
            return true;
        }
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence name = event.getPackageName();
        if (name == null) return;
        String pkg = name.toString();
        if (STOCK_HOME.equals(pkg)) {
            if (!stockAllowed()) {
                Log.i(TAG, "Google launcher came forward, opening this home screen");
                openHome();
            }
            return;
        }
        if (stockAllowed() && !getPackageName().equals(pkg) && isApp(pkg)) {
            allowStockHome(false);
        }
    }

    @Override
    public void onInterrupt() {
    }

    private boolean isApp(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getLeanbackLaunchIntentForPackage(pkg) != null || pm.getLaunchIntentForPackage(pkg) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private void openHome() {
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(i);
        } catch (Exception e) {
            Log.e(TAG, "could not open the home screen", e);
        }
    }
}
