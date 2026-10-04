package com.spacexfantracker.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import org.json.JSONArray;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * The app: the live site (spacexfantracker.com) in the app's own WebView - not a browser tab, so it doesn't depend on
 * the phone's default browser or its settings. Every update to the site shows up here at once.
 *
 * What the app adds, through window.AndroidApp (see the site's index.html, which checks for it):
 *  - launch reminders as system alarms + notifications, which arrive even when the app is closed
 *  - the share sheet, and saving a poster / calendar file (a WebView has neither navigator.share nor downloads)
 *  - the phone's Back button closes the site's open window first
 */
public class MainActivity extends Activity {
    static final String SITE_URL = "https://spacexfantracker.com/";
    private static final String SITE_HOST = "spacexfantracker.com";
    private static final int REQUEST_NOTIFICATIONS = 1;

    private WebView webView;
    private FrameLayout root;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ReminderReceiver.ensureChannel(this);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#09090B"));
        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#09090B"));
        root.addView(webView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

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

        if (savedInstanceState != null) webView.restoreState(savedInstanceState);
        else webView.loadUrl(urlFromIntent(getIntent()));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getData() != null) webView.loadUrl(urlFromIntent(intent));
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
    }

    @Override
    protected void onPause() {
        webView.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
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

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (!request.isForMainFrame()) return;
            String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
                    + "<body style='background:#09090b;color:#e4e4e7;font-family:sans-serif;display:flex;flex-direction:column;"
                    + "align-items:center;justify-content:center;height:100vh;margin:0;text-align:center'>"
                    + "<div style='font-size:20px;margin-bottom:20px'>" + getString(R.string.offline_title) + "</div>"
                    + "<a href='" + SITE_URL + "' style='color:#000;background:#10b981;padding:12px 28px;border-radius:14px;"
                    + "text-decoration:none;font-weight:bold'>" + getString(R.string.offline_retry) + "</a></body></html>";
            view.loadDataWithBaseURL(SITE_URL, html, "text/html", "utf-8", SITE_URL);
        }
    }

    private class ChromeClient extends WebChromeClient {
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
    }
}
