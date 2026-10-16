package com.spacexfantracker.app;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Base64;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * The app: the live site (spacexfantracker.com) in the app's own WebView - not a browser tab, so it doesn't depend on
 * the phone's default browser or its settings. Every update to the site shows up here at once.
 *
 * What the app adds, through window.AndroidApp (see the site's index.html, which checks for it):
 *  - launch reminders as system alarms + notifications, which arrive even when the app is closed
 *  - the share sheet, and saving a poster / calendar file (a WebView has neither navigator.share nor downloads)
 *  - the phone's Back button closes the site's open window first
 *  - the phone's top and bottom bars in the site theme's colors, a light vibration on the site's main buttons
 *
 * And of its own: a splash screen (the logo) while the site loads, the site's messages as the phone's own dialogs,
 * and a "no connection" screen that reloads by itself once the phone is back online.
 */
public class MainActivity extends Activity {
    static final String SITE_URL = "https://spacexfantracker.com/";
    private static final String SITE_HOST = "spacexfantracker.com";
    /** the home screen widget's "Watch live": the launch whose broadcast to open (NextLaunchWidget) */
    static final String EXTRA_WATCH_LIVE = "watch_live";
    /** a row of the upcoming launches widget: the launch whose window to open (LaunchListWidget) */
    static final String EXTRA_OPEN_LAUNCH = "open_launch";
    /** a tap on the home screen widget: the app grows out of the widget (expandFromWidget) */
    static final String EXTRA_FROM_WIDGET = "from_widget";
    private static final int REQUEST_NOTIFICATIONS = 1;
    // the splash stays at least this long (no flash of the logo on a fast load), and at most this long
    private static final long SPLASH_MIN_MS = 700;
    private static final long SPLASH_MAX_MS = 8000;

    private WebView webView;
    private FrameLayout root;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private View splash;
    private long splashShownAt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    // the site theme's colors for the phone's bars (setBarColors) - the app's own dark until the site sends them
    private int barTop = Color.parseColor("#09090B");
    private int barBottom = Color.parseColor("#09090B");
    // the phone's own bottom bar (the gesture line / the buttons): the page goes on under it - see edgeToEdge
    private int navInsetPx = 0;
    private String offlineUrl;   // the page that failed to load while the phone was offline, loaded again once it's back
    private String pendingWatchLive;   // the widget's "Watch live" launch, played once the page is there
    private String pendingOpenLaunch;  // the list widget's launch, its window opened once the page is there
    private boolean pageLoaded;
    private ValueAnimator pendingExpand;   // the growth out of the widget, started with the first frame on the screen
    private ConnectivityManager.NetworkCallback networkCallback;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ReminderReceiver.ensureChannel(this);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#09090B"));
        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#09090B"));
        // an app's scrolling: no glow at the ends, no scroll bars
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        root.addView(webView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (savedInstanceState == null) showSplash();
        setContentView(root);
        edgeToEdge();
        watchNetwork();

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);   // the live broadcasts start when the player opens
        s.setUseWideViewPort(true);                      // the page's own <meta name="viewport"> decides the width
        s.setLoadWithOverviewMode(false);
        s.setTextZoom(100);                              // the site's own sizes - it fits its titles to the width itself
        s.setSupportMultipleWindows(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " SpaceXTrackerApp/" + appVersion());

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);   // the translation widget and the video players

        webView.addJavascriptInterface(new AppBridge(), "AndroidApp");
        webView.setWebViewClient(new SiteClient());
        webView.setWebChromeClient(new ChromeClient());

        takeWatchLive(getIntent());
        if (savedInstanceState != null) webView.restoreState(savedInstanceState);
        else webView.loadUrl(urlFromIntent(getIntent()));
        // the background check for the reminders' launches and the home screen widget (it does nothing without either)
        LaunchWatchJob.schedule(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        takeWatchLive(intent);
        expandFromWidget(intent);
        if (intent != null && intent.getData() != null) {
            pageLoaded = false;
            webView.loadUrl(urlFromIntent(intent));
        } else {
            deliverWatchLive();
        }
    }

    /**
     * Back from the background by a tap on the home screen widget: the app grows out of the widget to the full screen
     * (from its place and size, with its rounded corners, over the wallpaper), as it shrinks back into it when it's left -
     * the system's own opening animation is turned off for it. A home screen that doesn't tell where the widget is
     * (Samsung's One UI: no source bounds) gets the same growth from the middle of the screen, fading in. Not where the
     * home screen does this itself (Pixel / AOSP launchers, Android 12+), which would play it twice.
     */
    private void expandFromWidget(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_FROM_WIDGET, false)) return;
        intent.removeExtra(EXTRA_FROM_WIDGET);
        Rect bounds = intent.getSourceBounds();
        int w = root.getWidth(), h = root.getHeight();
        if (w == 0 || h == 0 || launcherAnimatesWidgets()) return;
        if (Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0);
        else overridePendingTransition(0, 0);

        int[] at = new int[2];
        root.getLocationOnScreen(at);
        // where the widget is - or, not told, a card of the whole page at 90% in the middle of the screen
        boolean fromWidget = bounds != null && !bounds.isEmpty();
        Rect from = fromWidget ? bounds : new Rect(at[0] + Math.round(w * 0.05f), at[1] + Math.round(h * 0.05f),
                at[0] + Math.round(w * 0.95f), at[1] + Math.round(h * 0.95f));
        // the whole page scaled to the widget's width, cut to its height, moved onto it
        float s0 = from.width() / (float) w;
        float clipH0 = Math.min(h, from.height() / s0);
        float dx0 = from.exactCenterX() - (at[0] + w / 2f), dy0 = from.exactCenterY() - (at[1] + h / 2f);
        float r0 = 22 * getResources().getDisplayMetrics().density / s0;   // the widget's corners (widget_bg)
        float[] clip = { clipH0, r0 };

        Window win = getWindow();
        win.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        ViewOutlineProvider before = root.getOutlineProvider();
        root.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                int top = Math.round((h - clip[0]) / 2);
                o.setRoundRect(0, top, w, Math.round(top + clip[0]), clip[1]);
            }
        });
        root.setClipToOutline(true);
        root.setPivotX(w / 2f);
        root.setPivotY(h / 2f);
        root.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(fromWidget ? 500 : 350);   // across the screen from the widget; a short way from the middle
        a.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        a.addUpdateListener(an -> {
            float f = (float) an.getAnimatedValue();
            float sc = s0 + (1 - s0) * f;
            root.setScaleX(sc);
            root.setScaleY(sc);
            root.setTranslationX(dx0 * (1 - f));
            root.setTranslationY(dy0 * (1 - f));
            clip[0] = clipH0 + (h - clipH0) * f;
            clip[1] = r0 * (1 - f);
            root.invalidateOutline();
            if (!fromWidget) root.setAlpha(Math.min(1f, f / 0.5f));   // from the middle it also fades in
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                root.setScaleX(1f);
                root.setScaleY(1f);
                root.setTranslationX(0f);
                root.setTranslationY(0f);
                root.setAlpha(1f);
                root.setClipToOutline(false);
                root.setOutlineProvider(before);
                root.setLayerType(View.LAYER_TYPE_NONE, null);
                win.setBackgroundDrawable(new ColorDrawable(Color.parseColor("#09090B")));
                win.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
            }
        });
        // its first frame now, so the screen never shows the whole page before it - it runs from the first frame shown
        a.setCurrentFraction(0f);
        if (pendingExpand != null) pendingExpand.end();
        pendingExpand = a;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) startPendingExpand();
    }

    private void startPendingExpand() {
        if (pendingExpand == null) return;
        ValueAnimator a = pendingExpand;
        pendingExpand = null;
        a.start();
    }

    /** the home screens that open an app out of its widget themselves (the widget's frame is @android:id/background) */
    private boolean launcherAnimatesWidgets() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false;
        try {
            android.content.pm.ResolveInfo home = getPackageManager().resolveActivity(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
            String pkg = home != null && home.activityInfo != null ? home.activityInfo.packageName : "";
            return pkg.equals("com.google.android.apps.nexuslauncher") || pkg.equals("com.android.launcher3");
        } catch (Exception e) {
            return false;
        }
    }

    /** the widget's "Watch live" tap: its launch, kept until the page can open its broadcast */
    private void takeWatchLive(Intent intent) {
        String open = intent != null ? intent.getStringExtra(EXTRA_OPEN_LAUNCH) : null;
        if (open != null && !open.isEmpty()) {
            intent.removeExtra(EXTRA_OPEN_LAUNCH);
            pendingOpenLaunch = open;
        }
        String id = intent != null ? intent.getStringExtra(EXTRA_WATCH_LIVE) : null;
        if (id == null || id.isEmpty()) return;
        intent.removeExtra(EXTRA_WATCH_LIVE);   // not again when the activity is recreated with this intent
        pendingWatchLive = id;
    }

    /** the site opens that launch's broadcast as its own red button does (window.appWatchLive - it waits for its data) */
    private void deliverWatchLive() {
        if (!pageLoaded) return;
        if (pendingOpenLaunch != null) {
            // the launch's own window, as a tap on its card opens it (window.appOpenLaunch - it waits for its data)
            String open = pendingOpenLaunch;
            pendingOpenLaunch = null;
            webView.evaluateJavascript("(function(){try{if(window.appOpenLaunch)window.appOpenLaunch(" + JSONObject.quote(open)
                    + ");}catch(e){}})()", null);
        }
        if (pendingWatchLive == null) return;
        String id = pendingWatchLive;
        pendingWatchLive = null;
        webView.evaluateJavascript("(function(){try{if(window.appWatchLive)window.appWatchLive(" + JSONObject.quote(id)
                + ");}catch(e){}})()", null);
    }

    /** a link to the site (a shared mission link, or a reminder's notification) opens that page; anything else the home page */
    private String urlFromIntent(Intent intent) {
        Uri data = intent != null ? intent.getData() : null;
        if (data != null && isSiteHost(data.getHost())) return data.toString();
        return SITE_URL;
    }

    private static boolean isSiteHost(String host) {
        return host != null && (host.equals(SITE_HOST) || host.endsWith("." + SITE_HOST));
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        // the growth starts once the window is on the screen (it has the focus), or shortly after this at the latest
        if (pendingExpand != null) root.postDelayed(this::startPendingExpand, 300);
    }

    @Override
    protected void onPause() {
        webView.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (networkCallback != null) {
            try {
                ((ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE)).unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) { }
            networkCallback = null;
        }
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    // Back: leave a full-screen video; else let the site close its open window (window.appHandleBack, true when it
    // closed something); else the page's own history; else out of the app.
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (fullscreenView != null) {
            if (fullscreenCallback != null) fullscreenCallback.onCustomViewHidden();
            hideFullscreen();
            return;
        }
        webView.evaluateJavascript("(function(){try{return !!(window.appHandleBack && window.appHandleBack());}catch(e){return false;}})()", value -> {
            if ("true".equals(value)) return;
            if (webView.canGoBack()) webView.goBack();
            else moveTaskToBack(true);
        });
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    // ------------------------------------------------------------------ the splash screen
    // The logo on black over the page while it loads - the same black the system shows as the app opens, so the two
    // read as one screen. Gone (faded) once the site has painted, or after SPLASH_MAX_MS whatever happens.

    private void showSplash() {
        FrameLayout s = new FrameLayout(this);
        s.setBackgroundColor(Color.BLACK);
        s.setClickable(true);   // the page under it can't be tapped yet
        int logoSize = dp(200);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher_foreground);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        s.addView(logo, new FrameLayout.LayoutParams(logoSize, logoSize, Gravity.CENTER));
        ProgressBar spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(Color.parseColor("#10B981")));
        FrameLayout.LayoutParams spinnerLp = new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        spinnerLp.bottomMargin = dp(72);
        s.addView(spinner, spinnerLp);
        root.addView(s, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        logo.setAlpha(0f);
        logo.setScaleX(0.92f);
        logo.setScaleY(0.92f);
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(350).start();
        splash = s;
        splashShownAt = SystemClock.uptimeMillis();
        setBars(Color.BLACK, Color.BLACK);
        handler.postDelayed(this::hideSplash, SPLASH_MAX_MS);
    }

    private void hideSplash() {
        if (splash == null) return;
        long wait = SPLASH_MIN_MS - (SystemClock.uptimeMillis() - splashShownAt);
        if (wait > 0) {
            handler.postDelayed(this::hideSplash, wait);
            return;
        }
        View s = splash;
        splash = null;
        handler.removeCallbacksAndMessages(null);
        setBars(barTop, barBottom);
        s.animate().alpha(0f).setDuration(260).withEndAction(() -> root.removeView(s)).start();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------ the page down to the screen's bottom edge
    // The page goes on under the phone's own bottom bar (now see-through): the site's tab bar reaches the screen's
    // edge, with the gesture line over it, instead of a strip of its own color under it. The site is told how tall
    // that bar is (--app-nav-inset, and --app-nav-pad for its tab bar - see pushNavInset) to keep its content clear
    // of it. The top stays as it was: the page starts under the status bar. The keyboard still pushes the page up.

    private void edgeToEdge() {
        Window w = getWindow();
        WindowCompat.setDecorFitsSystemWindows(w, false);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) w.setNavigationBarContrastEnforced(false);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets status = insets.getInsets(WindowInsetsCompat.Type.statusBars() | WindowInsetsCompat.Type.displayCutout());
            Insets nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
            boolean keyboard = insets.isVisible(WindowInsetsCompat.Type.ime());
            int imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            v.setPadding(nav.left, status.top, nav.right, keyboard ? imeBottom : 0);
            int inset = keyboard ? 0 : nav.bottom;
            if (inset != navInsetPx) {
                navInsetPx = inset;
                pushNavInset();
            }
            return WindowInsetsCompat.CONSUMED;
        });
    }

    /** the phone's bottom bar's height to the page, in its own (CSS) pixels: --app-nav-inset all of it, --app-nav-pad
     *  what the tab bar keeps clear - all of a buttons bar, a gesture bar less 10px (its thin line sits low in it) */
    private void pushNavInset() {
        if (webView == null) return;
        float density = getResources().getDisplayMetrics().density;
        int inset = Math.round(navInsetPx / density);
        int pad = inset >= 40 ? inset : Math.max(inset - 10, 0);
        webView.evaluateJavascript("(function(){var s=document.documentElement&&document.documentElement.style;if(!s)return;"
                + "s.setProperty('--app-nav-inset','" + inset + "px');s.setProperty('--app-nav-pad','" + pad + "px');})()", null);
    }

    // ------------------------------------------------------------------ the phone's bars

    /** the top (status) and bottom (navigation) bars' colors, with dark or light icons on them to match */
    private void setBars(int top, int bottom) {
        Window w = getWindow();
        w.setStatusBarColor(top);
        // the bottom bar is see-through over the page (edgeToEdge); its line / buttons still take the page's light or
        // dark (the tab bar's color, below)
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (root != null) root.setBackgroundColor(top);
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(w, w.getDecorView());
        bars.setAppearanceLightStatusBars(Color.luminance(top) > 0.5f);
        bars.setAppearanceLightNavigationBars(Color.luminance(bottom) > 0.5f);
    }

    // ------------------------------------------------------------------ offline

    /** Back online after a page failed to load: that page again, by itself */
    private void watchNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    runOnUiThread(() -> {
                        if (offlineUrl == null || webView == null) return;
                        String url = offlineUrl;
                        offlineUrl = null;
                        webView.loadUrl(url);
                    });
                }
            };
            cm.registerDefaultNetworkCallback(networkCallback);
        } catch (Exception e) {
            networkCallback = null;
        }
    }

    private String offlinePage(String retryUrl) {
        String safeUrl = TextUtils.htmlEncode(retryUrl);
        return "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>html,body{margin:0;height:100%;background:#09090b;color:#e4e4e7;font-family:sans-serif;"
                + "-webkit-user-select:none;user-select:none;-webkit-tap-highlight-color:transparent}"
                + "body{display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;padding:0 32px;box-sizing:border-box}"
                + ".ic{width:88px;height:88px;border-radius:50%;background:rgba(16,185,129,.12);display:flex;align-items:center;justify-content:center;margin-bottom:24px}"
                + "h1{font-size:20px;margin:0 0 10px;font-weight:700}p{font-size:15px;line-height:1.5;color:#a1a1aa;margin:0 0 28px;max-width:320px}"
                + "a{color:#000;background:#10b981;padding:13px 32px;border-radius:999px;text-decoration:none;font-weight:700;font-size:15px}"
                + "</style></head><body dir='auto'>"
                + "<div class='ic'><svg width='44' height='44' viewBox='0 0 24 24' fill='none' stroke='#34d399' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'>"
                + "<path d='M2 2l20 20'/><path d='M8.5 16.5a5 5 0 0 1 7 0'/><path d='M2 8.82a15 15 0 0 1 4.17-2.65'/>"
                + "<path d='M10.66 5c4.01-.36 8.14.9 11.34 3.76'/><path d='M16.85 11.25a10 10 0 0 1 2.22 1.68'/><path d='M5 13a10 10 0 0 1 5.24-2.76'/>"
                + "<path d='M12 20h.01'/></svg></div>"
                + "<h1>" + TextUtils.htmlEncode(getString(R.string.offline_title)) + "</h1>"
                + "<p>" + TextUtils.htmlEncode(getString(R.string.offline_text)) + "</p>"
                + "<a href='" + safeUrl + "'>" + TextUtils.htmlEncode(getString(R.string.offline_retry)) + "</a></body></html>";
    }

    private void hideFullscreen() {
        if (fullscreenView != null) root.removeView(fullscreenView);
        fullscreenView = null;
        fullscreenCallback = null;
        webView.setVisibility(View.VISIBLE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    private void openExternally(Uri uri) {
        try {
            Intent intent = Intent.parseUri(uri.toString(), uri.getScheme() != null && uri.getScheme().equals("intent") ? Intent.URI_INTENT_SCHEME : 0);
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            intent.setComponent(null);
            intent.setSelector(null);
            startActivity(intent);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (ActivityNotFoundException ignored) { }
        }
    }

    // ------------------------------------------------------------------ the page

    private class SiteClient extends WebViewClient {
        // The site's own translation requests (translateTextViaGoogle: the launch dossiers, the patches' descriptions):
        // Google answers this address with a redirect to its robot check (google.com/sorry) when the asker says it is
        // Android - the WebView ("; wv") or the app itself - and the page then gets nothing it can read (found
        // 2026-10-10 on the owner's phone; the same request as the phone's Chrome gets the translation). So the app asks
        // Google itself, as the phone's Chrome would, and hands the page the answer, marked readable by it. Only this
        // one address; anything going wrong leaves the request to the WebView as before.
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (uri == null || !"GET".equalsIgnoreCase(request.getMethod())
                    || !"translate.googleapis.com".equals(uri.getHost()) || !"/translate_a/single".equals(uri.getPath())) {
                return null;
            }
            HttpURLConnection conn = null;
            try {
                String chromeUa = WebSettings.getDefaultUserAgent(MainActivity.this)
                        .replace("; wv", "").replaceAll(" Version/[0-9.]+", "");
                conn = (HttpURLConnection) new URL(uri.toString()).openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("User-Agent", chromeUa);
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) return null;
                InputStream in = conn.getInputStream();
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) != -1; ) body.write(buf, 0, n);
                in.close();
                Map<String, String> headers = new HashMap<>();
                headers.put("Access-Control-Allow-Origin", "*");
                headers.put("Cache-Control", "no-store");
                return new WebResourceResponse("application/json", "UTF-8", code, "OK", headers,
                        new ByteArrayInputStream(body.toByteArray()));
            } catch (Exception e) {
                return null;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme();
            // the site's own pages stay here; frames inside a page (video players, the translation widget) too
            if (!request.isForMainFrame()) return false;
            if ((scheme.equals("https") || scheme.equals("http")) && isSiteHost(uri.getHost())) return false;
            // everything else (X, YouTube, news articles, PayPal, mail, WhatsApp...) in its own app or the browser
            openExternally(uri);
            return true;
        }

        // the site has painted: the splash can go (and the page learns the bottom bar's height - a new page each load)
        @Override
        public void onPageCommitVisible(WebView view, String url) {
            pushNavInset();
            hideSplash();
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            pushNavInset();
            hideSplash();
            pageLoaded = true;
            deliverWatchLive();
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (!request.isForMainFrame()) return;
            String failed = request.getUrl() != null && isSiteHost(request.getUrl().getHost()) ? request.getUrl().toString() : SITE_URL;
            offlineUrl = failed;
            barTop = barBottom = Color.parseColor("#09090B");
            if (splash == null) setBars(barTop, barBottom);
            view.loadDataWithBaseURL(SITE_URL, offlinePage(failed), "text/html", "utf-8", null);
            hideSplash();
        }
    }

    private class ChromeClient extends WebChromeClient {
        // the site's messages (a reminder set, a copied link...) as the phone's own dialog - not a web page's box
        // titled with the site's address
        @Override
        public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
            if (isFinishing()) {
                result.cancel();
                return true;
            }
            new AlertDialog.Builder(MainActivity.this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, (d, w) -> result.confirm())
                    .setOnCancelListener(d -> result.cancel())
                    .show();
            return true;
        }

        // a video player's full-screen button
        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (fullscreenView != null) {
                callback.onCustomViewHidden();
                return;
            }
            fullscreenView = view;
            fullscreenCallback = callback;
            root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            webView.setVisibility(View.GONE);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
        }

        @Override
        public void onHideCustomView() {
            hideFullscreen();
        }
    }

    // ------------------------------------------------------------------ window.AndroidApp

    private class AppBridge {
        /** items: JSON array of { at: epoch ms, title, body } - replaces whatever was set for this mission */
        @JavascriptInterface
        public void scheduleReminders(String missionId, String itemsJson) {
            if (missionId == null) return;
            try {
                ReminderStore.schedule(MainActivity.this, missionId, new JSONArray(itemsJson));
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void cancelReminders(String missionId) {
            if (missionId != null) ReminderStore.cancel(MainActivity.this, missionId);
        }

        /** a launch with a reminder, watched with the app closed for a new time / a hold (LaunchWatch):
         *  { name, net (ISO), locale, texts: { title, moved, hold, tbd } } - in the site's language */
        @JavascriptInterface
        public void watchLaunch(String missionId, String infoJson) {
            if (missionId == null) return;
            try {
                LaunchWatch.watch(MainActivity.this, missionId, new JSONObject(infoJson));
                LaunchWatchJob.schedule(MainActivity.this);
            } catch (Exception ignored) { }
        }

        /** the home screen widget's launch: { id, name, t0 (ISO), image (URL), labels: { next, now, day, hour }, rtl, mode,
         *  upcoming: [ { id, name, image } ... ] - the next few launches in the site's language, for the background updates } */
        @JavascriptInterface
        public void setNextLaunch(String json) {
            final Context app = getApplicationContext();
            new Thread(() -> {
                try {
                    JSONObject o = new JSONObject(json);
                    NextLaunchWidget.setNext(app, o.optString("id", ""), o.optString("name", ""),
                            LaunchWatch.parseIso(o.optString("t0", "")), o.optString("image", ""), o, true);
                } catch (Exception ignored) { }
            }).start();
        }

        /** Android 13+ asks the user once; earlier versions allow notifications by default */
        @JavascriptInterface
        public void requestNotificationPermission() {
            if (Build.VERSION.SDK_INT < 33) return;
            runOnUiThread(() -> {
                if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
                }
            });
        }

        @JavascriptInterface
        public void share(String text) {
            runOnUiThread(() -> {
                Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(send, null));
            });
        }

        /** a poster (image/png) goes to the phone's pictures, a calendar file opens in the calendar app */
        @JavascriptInterface
        public void saveFile(String base64, String fileName, String mimeType) {
            runOnUiThread(() -> {
                try {
                    byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                    Uri uri = null;
                    boolean isImage = mimeType != null && mimeType.startsWith("image/");
                    if (isImage && Build.VERSION.SDK_INT >= 29) {
                        ContentValues values = new ContentValues();
                        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                        values.put(MediaStore.Images.Media.MIME_TYPE, mimeType);
                        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SpaceX Tracker");
                        uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                        if (uri != null) {
                            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                                if (out != null) out.write(bytes);
                            }
                            Toast.makeText(MainActivity.this, "✓", Toast.LENGTH_SHORT).show();
                        }
                    }
                    if (uri == null) {
                        File dir = new File(getCacheDir(), "shared");
                        if (!dir.exists() && !dir.mkdirs()) return;
                        File file = new File(dir, fileName);
                        try (FileOutputStream out = new FileOutputStream(file)) {
                            out.write(bytes);
                        }
                        uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", file);
                    }
                    Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try {
                        startActivity(view);
                    } catch (ActivityNotFoundException e) {
                        Intent send = new Intent(Intent.ACTION_SEND).setType(mimeType).putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(send, null));
                    }
                } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface
        public String version() {
            return appVersion();
        }

        /** the site theme's colors ("#rrggbb") for the phone's top and bottom bars - see the site's syncAppBarColors */
        @JavascriptInterface
        public void setBarColors(String top, String bottom) {
            runOnUiThread(() -> {
                try {
                    barTop = Color.parseColor(top);
                    barBottom = Color.parseColor(bottom);
                } catch (Exception e) {
                    return;
                }
                if (splash == null) setBars(barTop, barBottom);
            });
        }

        /** a light tap of the vibration (only if the phone's touch vibration is on) */
        @JavascriptInterface
        public void haptic() {
            runOnUiThread(() -> {
                if (webView != null) webView.performHapticFeedback(Build.VERSION.SDK_INT >= 23
                        ? HapticFeedbackConstants.CONTEXT_CLICK : HapticFeedbackConstants.VIRTUAL_KEY);
            });
        }
    }
}
