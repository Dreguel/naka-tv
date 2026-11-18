package cx.naka.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String HOME_URL = "https://naka.cx/app-tv";
    private static final String HOME_HOST = "naka.cx";
    private static final int ACCENT = 0xFFFFC400;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private WebView web;
    private ProgressBar progress;
    private FrameLayout fullscreenBox;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private LinearLayout errorBox;
    private TextView errorDetail;
    private TextView errorHint;
    private TextView osd;
    private CursorView cursor;
    private String navScript = "";

    private boolean cursorMode = false;
    private float curX = -1, curY = -1;
    private boolean iframeFocused = false;
    private boolean centerDown = false;
    private boolean centerLongDone = false;
    private long centerDownAt = 0;
    private long lastBackAt = 0;
    private int backToken = 0;
    private boolean errorVisible = false;
    private boolean loadFailed = false;
    private boolean progressInjected = false;
    private String mainUrl = null;
    private long pageStartedAt = 0;

    private final Runnable hideOsd = new Runnable() {
        @Override public void run() { if (osd != null) osd.setVisibility(View.GONE); }
    };
    private final Runnable autoRetry = new Runnable() {
        @Override public void run() { if (errorVisible) retry(); }
    };

    // ------------------------------------------------------------------ cycle de vie

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        navScript = readAsset("tvnav.js");

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        createWebView();

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setIndeterminate(false);
        progress.setProgressTintList(ColorStateList.valueOf(ACCENT));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(0x22FFFFFF));
        progress.setFocusable(false);
        root.addView(progress, new FrameLayout.LayoutParams(MATCH, dp(4), Gravity.TOP));

        fullscreenBox = new FrameLayout(this);
        fullscreenBox.setBackgroundColor(Color.BLACK);
        fullscreenBox.setVisibility(View.GONE);
        root.addView(fullscreenBox, new FrameLayout.LayoutParams(MATCH, MATCH));

        buildErrorBox();

        osd = new TextView(this);
        osd.setTextColor(Color.WHITE);
        osd.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        osd.setPadding(dp(22), dp(12), dp(22), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xD9000000);
        bg.setCornerRadius(dp(12));
        osd.setBackground(bg);
        osd.setVisibility(View.GONE);
        osd.setFocusable(false);
        FrameLayout.LayoutParams olp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        olp.bottomMargin = dp(56);
        root.addView(osd, olp);

        cursor = new CursorView(this);
        cursor.setVisibility(View.GONE);
        cursor.setFocusable(false);
        root.addView(cursor, new FrameLayout.LayoutParams(dp(30), dp(36)));

        web.loadUrl(HOME_URL);
        showTips();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (web != null) web.onResume();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
    }

    @Override
    protected void onStop() {
        if (web != null) {
            web.evaluateJavascript("window.__ntv&&__ntv.media('pauseAll',0)", null);
            web.onPause();
        }
        CookieManager.getInstance().flush();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (web != null) {
            root.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    // ------------------------------------------------------------------ WebView

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void createWebView() {
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        if (Build.VERSION.SDK_INT >= 26) {
            web.setDefaultFocusHighlightEnabled(false);
            web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true);
        }

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setTextZoom(100);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setUserAgentString(cleanUa(s.getUserAgentString()));

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new Bridge(), "NakaTVNative");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        web.setDownloadListener((url, ua, cd, mime, len) -> showOsd("Téléchargement non pris en charge sur la TV", 2000));

        root.addView(web, 0, new FrameLayout.LayoutParams(MATCH, MATCH));
        web.requestFocus();
    }

    private void rebuildWebView() {
        if (web == null) return;
        String url = web.getUrl();
        if (customView != null) {
            customView = null;
            customCallback = null;
            fullscreenBox.removeAllViews();
            fullscreenBox.setVisibility(View.GONE);
        }
        root.removeView(web);
        web.destroy();
        createWebView();
        web.loadUrl(url != null && url.startsWith("http") ? url : HOME_URL);
    }

    private static String cleanUa(String ua) {
        if (ua == null) return null;
        return ua.replace("; wv)", ")").replaceAll("Version/[0-9.]+ ", "");
    }

    private void inject() {
        if (web == null || navScript.isEmpty()) return;
        web.evaluateJavascript(navScript + "\n;window.__ntv&&__ntv.setCursor(" + cursorMode + ");", null);
    }

    private static boolean isHomeHost(String url) {
        if (url == null) return false;
        try {
            String h = Uri.parse(url).getHost();
            if (h == null) return false;
            h = h.toLowerCase(Locale.ROOT);
            return h.equals(HOME_HOST) || h.endsWith("." + HOME_HOST);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isHttp(String url) {
        String l = url.toLowerCase(Locale.ROOT);
        return l.startsWith("http://") || l.startsWith("https://");
    }

    private boolean handleUrl(String url) {
        if (url == null) return true;
        String l = url.toLowerCase(Locale.ROOT);
        if (isHttp(url)) return false;
        if (l.startsWith("about:") || l.startsWith("data:") || l.startsWith("blob:") || l.startsWith("javascript:")) return false;
        if (l.startsWith("intent:")) {
            try {
                Intent i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                String fb = i.getStringExtra("browser_fallback_url");
                if (fb != null && isHttp(fb)) web.loadUrl(fb);
            } catch (Exception ignored) { }
            return true;
        }
        // market:, tel:, mailto:, schémas d'applis… inutiles sur TV
        return true;
    }

    private class Client extends WebViewClient {
        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView v, String url) {
            return handleUrl(url);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            String url = r.getUrl().toString();
            // bloque les redirections pub automatiques (sans clic) vers un autre site
            if (r.isForMainFrame() && !r.hasGesture() && !r.isRedirect()
                    && isHttp(url) && !isHomeHost(url) && isHomeHost(v.getUrl())
                    && SystemClock.uptimeMillis() - pageStartedAt > 3000) {
                showOsd("Redirection bloquée", 1500);
                return true;
            }
            return handleUrl(url);
        }

        @Override
        public void onPageStarted(WebView v, String url, Bitmap favicon) {
            mainUrl = url;
            loadFailed = false;
            progressInjected = false;
            iframeFocused = false;
            pageStartedAt = SystemClock.uptimeMillis();
        }

        @Override
        public void onPageCommitVisible(WebView v, String url) {
            inject();
        }

        @Override
        public void onPageFinished(WebView v, String url) {
            inject();
            if (!loadFailed) hideError();
            CookieManager.getInstance().flush();
        }

        @Override
        public void doUpdateVisitedHistory(WebView v, String url, boolean isReload) {
            inject();
        }

        @Override
        public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
            if (!r.isForMainFrame()) return;
            String d = String.valueOf(e.getDescription());
            if (d.contains("ERR_ABORTED")) return;
            loadFailed = true;
            showError(d);
        }

        @SuppressWarnings("deprecation")
        @Override
        public void onReceivedError(WebView v, int code, String desc, String failingUrl) {
            if (Build.VERSION.SDK_INT >= 23) return;
            if (desc != null && desc.contains("ERR_ABORTED")) return;
            loadFailed = true;
            showError(desc);
        }

        @Override
        public void onReceivedSslError(WebView v, SslErrorHandler h, SslError err) {
            h.cancel();
            String u = err.getUrl();
            if (u != null && u.equals(mainUrl)) {
                loadFailed = true;
                showError("Certificat de sécurité refusé. Mets à jour le système de ta TV.");
            }
        }

        @Override
        public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
            if (v == web) ui.post(MainActivity.this::rebuildWebView);
            return true;
        }
    }

    private class Chrome extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView v, int p) {
            if (progress == null) return;
            progress.setProgress(p);
            progress.setVisibility(p >= 100 ? View.GONE : View.VISIBLE);
            if (p >= 60 && !progressInjected) {
                progressInjected = true;
                inject();
            }
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback cb) {
            enterFullscreen(view, cb);
        }

        @Override
        public void onHideCustomView() {
            leaveFullscreen();
        }

        @Override
        public Bitmap getDefaultVideoPoster() {
            // évite la grosse icône grise "play" de la WebView
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        }

        @Override
        public void onPermissionRequest(PermissionRequest r) {
            for (String res : r.getResources()) {
                if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(res)) {
                    r.grant(new String[]{res});
                    return;
                }
            }
            r.deny();
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            if (!isUserGesture) return false; // pop-up automatique = pub
            final WebView tmp = new WebView(MainActivity.this);
            final boolean[] done = {false};
            tmp.setWebViewClient(new WebViewClient() {
                private void take(WebView v, String url) {
                    if (done[0] || url == null || url.startsWith("about:")) return;
                    done[0] = true;
                    v.stopLoading();
                    if (isHomeHost(url)) web.loadUrl(url);
                    else showOsd("Pop-up bloquée", 1500);
                    ui.post(v::destroy);
                }

                @SuppressWarnings("deprecation")
                @Override
                public boolean shouldOverrideUrlLoading(WebView v, String url) {
                    take(v, url);
                    return true;
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                    take(v, r.getUrl().toString());
                    return true;
                }

                @Override
                public void onPageStarted(WebView v, String url, Bitmap favicon) {
                    take(v, url);
                }
            });
            ui.postDelayed(() -> {
                if (!done[0]) {
                    done[0] = true;
                    tmp.destroy();
                }
            }, 10000);
            WebView.WebViewTransport t = (WebView.WebViewTransport) resultMsg.obj;
            t.setWebView(tmp);
            resultMsg.sendToTarget();
            return true;
        }
    }

    // ------------------------------------------------------------------ plein écran vidéo

    private void enterFullscreen(View view, WebChromeClient.CustomViewCallback cb) {
        if (customView != null) {
            cb.onCustomViewHidden();
            return;
        }
        customView = view;
        customCallback = cb;
        fullscreenBox.addView(view, new FrameLayout.LayoutParams(MATCH, MATCH));
        fullscreenBox.setVisibility(View.VISIBLE);
        fullscreenBox.bringToFront();
        osd.bringToFront();
        cursor.bringToFront();
        view.setFocusable(true);
        view.requestFocus();
        hideSystemUi();
    }

    private void leaveFullscreen() {
        if (customView == null) return;
        fullscreenBox.removeAllViews();
        fullscreenBox.setVisibility(View.GONE);
        customView = null;
        customCallback = null;
        if (web != null) web.requestFocus();
        hideSystemUi();
    }

    private void exitFullscreen() {
        WebChromeClient.CustomViewCallback cb = customCallback;
        leaveFullscreen();
        if (cb != null) {
            try { cb.onCustomViewHidden(); } catch (Exception ignored) { }
        }
    }

    // ------------------------------------------------------------------ erreurs réseau

    private void buildErrorBox() {
        errorBox = new LinearLayout(this);
        errorBox.setOrientation(LinearLayout.VERTICAL);
        errorBox.setGravity(Gravity.CENTER);
        errorBox.setBackgroundColor(0xF2101014);
        errorBox.setVisibility(View.GONE);
        errorBox.setFocusable(false);
        errorBox.setClickable(true);
        errorBox.setOnClickListener(v -> retry());

        TextView title = new TextView(this);
        title.setText("Connexion impossible");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        title.setGravity(Gravity.CENTER);
        errorBox.addView(title);

        errorDetail = new TextView(this);
        errorDetail.setTextColor(0xFFAAAAAA);
        errorDetail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        errorDetail.setGravity(Gravity.CENTER);
        errorDetail.setPadding(dp(40), dp(12), dp(40), dp(24));
        errorBox.addView(errorDetail);

        errorHint = new TextView(this);
        errorHint.setTextColor(ACCENT);
        errorHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        errorHint.setGravity(Gravity.CENTER);
        errorBox.addView(errorHint);

        root.addView(errorBox, new FrameLayout.LayoutParams(MATCH, MATCH));
    }

    private void showError(String detail) {
        errorVisible = true;
        errorDetail.setText(detail == null ? "" : detail);
        errorHint.setText("Appuie sur OK pour réessayer (nouvel essai auto toutes les 8 s)");
        errorBox.setVisibility(View.VISIBLE);
        errorBox.bringToFront();
        cursor.bringToFront();
        ui.removeCallbacks(autoRetry);
        ui.postDelayed(autoRetry, 8000);
    }

    private void hideError() {
        if (!errorVisible) return;
        errorVisible = false;
        errorBox.setVisibility(View.GONE);
        ui.removeCallbacks(autoRetry);
        if (web != null) web.requestFocus();
    }

    private void retry() {
        if (web == null) return;
        errorHint.setText("Connexion…");
        ui.removeCallbacks(autoRetry);
        ui.postDelayed(autoRetry, 8000);
        String u = web.getUrl();
        if (u == null || !u.startsWith("http")) web.loadUrl(HOME_URL);
        else web.reload();
    }

    // ------------------------------------------------------------------ télécommande

    private static boolean isCenter(int kc) {
        return kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER
                || kc == KeyEvent.KEYCODE_NUMPAD_ENTER || kc == KeyEvent.KEYCODE_BUTTON_A;
    }

    private static boolean isArrow(int kc) {
        return kc == KeyEvent.KEYCODE_DPAD_UP || kc == KeyEvent.KEYCODE_DPAD_DOWN
                || kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (web == null) return super.dispatchKeyEvent(e);
        int kc = e.getKeyCode();
        int action = e.getAction();
        boolean down = action == KeyEvent.ACTION_DOWN;
        int rep = e.getRepeatCount();

        // Retour
        if (kc == KeyEvent.KEYCODE_BACK || kc == KeyEvent.KEYCODE_ESCAPE || kc == KeyEvent.KEYCODE_BUTTON_B) {
            if (down && rep == 0) onBackKey();
            return true;
        }

        // OK : appui court = clic, appui long = mode pointeur
        if (isCenter(kc)) {
            if (down) {
                if (rep == 0) {
                    centerDown = true;
                    centerLongDone = false;
                    centerDownAt = e.getEventTime();
                } else if (centerDown && !centerLongDone && e.getEventTime() - centerDownAt >= 600) {
                    centerLongDone = true;
                    toggleCursor();
                }
            } else if (action == KeyEvent.ACTION_UP) {
                boolean was = centerDown;
                centerDown = false;
                if (was && !centerLongDone) {
                    if (e.getEventTime() - centerDownAt >= 600) toggleCursor();
                    else onOk(kc);
                }
            }
            return true;
        }

        // Menu = bascule pointeur
        if (kc == KeyEvent.KEYCODE_MENU) {
            if (down && rep == 0) toggleCursor();
            return true;
        }

        // Touches multimédia
        if (handleMediaKey(kc, down, rep)) return true;

        if (isArrow(kc)) {
            if (cursorMode) {
                if (down) moveCursor(kc, rep);
                return true;
            }
            if (customView != null && (kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                if (down && (rep == 0 || rep % 4 == 0)) {
                    jsMedia("seek", kc == KeyEvent.KEYCODE_DPAD_RIGHT ? 10 : -10, rep == 0 ? kc : 0);
                }
                return true;
            }
            if (customView == null && iframeFocused) {
                if (down) {
                    web.evaluateJavascript("window.__ntv&&__ntv.escapeIframe('" + dirName(kc) + "')", null);
                    iframeFocused = false;
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private static String dirName(int kc) {
        switch (kc) {
            case KeyEvent.KEYCODE_DPAD_UP: return "up";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "down";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "left";
            default: return "right";
        }
    }

    private boolean handleMediaKey(int kc, boolean down, int rep) {
        String action = null;
        double arg = 0;
        boolean repeatable = false;
        switch (kc) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                action = "toggle"; break;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                action = "play"; break;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
                action = "pause"; break;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_STEP_FORWARD:
                action = "seek"; arg = 30; repeatable = true; break;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD:
            case KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD:
                action = "seek"; arg = -30; repeatable = true; break;
            default:
                return false;
        }
        if (down && (rep == 0 || (repeatable && rep % 3 == 0))) jsMedia(action, arg, rep == 0 ? kc : 0);
        return true;
    }

    private void onOk(int kc) {
        if (errorVisible) { retry(); return; }
        if (cursorMode) { tapOn(root, curX, curY); return; }
        if (customView != null) { jsMedia("toggle", 0, kc); return; }
        passKey(kc);
    }

    private void passKey(int kc) {
        View target = customView != null ? customView : web;
        if (target == null) return;
        long t = SystemClock.uptimeMillis();
        target.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, kc, 0));
        target.dispatchKeyEvent(new KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, kc, 0));
    }

    private void jsMedia(final String action, final double arg, final int fallbackKey) {
        if (web == null) return;
        web.evaluateJavascript("window.__ntv?__ntv.media('" + action + "'," + arg + "):null", v -> {
            JSONObject o = null;
            try { if (v != null && v.startsWith("{")) o = new JSONObject(v); } catch (Exception ignored) { }
            if (o != null && o.optBoolean("ok")) {
                if ("seek".equals(action)) {
                    String dir = arg > 0 ? "» +" : "« ";
                    double d = o.optDouble("d", 0);
                    String time = fmt(o.optDouble("t", 0)) + (d > 0 ? "  /  " + fmt(d) : "");
                    showOsd(dir + (int) arg + " s     " + time, 1500);
                } else if ("toggle".equals(action) || "play".equals(action) || "pause".equals(action)) {
                    showOsd(o.optBoolean("paused") ? "Pause" : "Lecture", 900);
                }
            } else if (fallbackKey != 0) {
                passKey(fallbackKey);
            }
        });
    }

    private static String fmt(double sec) {
        if (Double.isNaN(sec) || sec < 0) sec = 0;
        long s = (long) sec;
        long h = s / 3600, m = (s % 3600) / 60, r = s % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, r)
                : String.format(Locale.ROOT, "%d:%02d", m, r);
    }

    private void onBackKey() {
        if (customView != null) { exitFullscreen(); return; }
        if (errorVisible) {
            hideError();
            navBack();
            return;
        }
        final int token = ++backToken;
        final boolean[] done = {false};
        web.evaluateJavascript("(window.__ntv&&__ntv.back())||false", v -> {
            if (token != backToken || done[0]) return;
            done[0] = true;
            if (!"true".equals(v)) navBack();
        });
        ui.postDelayed(() -> {
            if (token == backToken && !done[0]) {
                done[0] = true;
                navBack();
            }
        }, 400);
    }

    private void navBack() {
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastBackAt < 2000) {
            finish();
        } else {
            lastBackAt = now;
            Toast.makeText(this, "Appuie encore sur Retour pour quitter", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ mode pointeur

    private void toggleCursor() {
        cursorMode = !cursorMode;
        if (cursorMode) {
            if (curX < 0 || curY < 0) {
                curX = root.getWidth() / 2f;
                curY = root.getHeight() / 2f;
            }
            placeCursor();
            cursor.setVisibility(View.VISIBLE);
            cursor.bringToFront();
            showOsd("Mode pointeur  ·  maintiens OK pour revenir", 2200);
        } else {
            cursor.setVisibility(View.GONE);
            showOsd("Mode télécommande", 1400);
        }
        if (web != null) web.evaluateJavascript("window.__ntv&&__ntv.setCursor(" + cursorMode + ")", null);
    }

    private void placeCursor() {
        cursor.setTranslationX(curX - dp(2));
        cursor.setTranslationY(curY - dp(2));
    }

    private void moveCursor(int kc, int rep) {
        int w = root.getWidth(), h = root.getHeight();
        if (w == 0 || h == 0) return;
        float step = dp(7) * Math.min(1f + rep * 0.4f, 7f);
        float dx = 0, dy = 0;
        switch (kc) {
            case KeyEvent.KEYCODE_DPAD_LEFT: dx = -step; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: dx = step; break;
            case KeyEvent.KEYCODE_DPAD_UP: dy = -step; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: dy = step; break;
        }
        float nx = Math.max(0, Math.min(w - 1, curX + dx));
        float ny = Math.max(0, Math.min(h - 1, curY + dy));
        float ox = (curX + dx) - nx, oy = (curY + dy) - ny;
        curX = nx;
        curY = ny;
        placeCursor();
        if ((ox != 0 || oy != 0) && customView == null && web != null) {
            float amount = Math.max(step, dp(24)) * 2;
            float sx = Math.signum(ox) * amount, sy = Math.signum(oy) * amount;
            web.evaluateJavascript("window.__ntv&&__ntv.scrollAt(" + curX + "," + curY + "," + sx + "," + sy + "," + web.getWidth() + ")", null);
        }
        long t = SystemClock.uptimeMillis();
        MotionEvent hover = MotionEvent.obtain(t, t, MotionEvent.ACTION_HOVER_MOVE, curX, curY, 0);
        hover.setSource(InputDevice.SOURCE_MOUSE);
        root.dispatchGenericMotionEvent(hover);
        hover.recycle();
    }

    private void tapOn(final View target, final float x, final float y) {
        if (target == null) return;
        final long t = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
        d.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        target.dispatchTouchEvent(d);
        d.recycle();
        ui.postDelayed(() -> {
            MotionEvent u = MotionEvent.obtain(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0);
            u.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            target.dispatchTouchEvent(u);
            u.recycle();
        }, 50);
    }

    // ------------------------------------------------------------------ pont JS

    private class Bridge {
        @JavascriptInterface
        public void showKeyboard() {
            ui.post(() -> {
                if (web == null) return;
                web.requestFocus();
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(web, 0);
            });
        }

        @JavascriptInterface
        public void iframeFocus(boolean focused) {
            ui.post(() -> iframeFocused = focused);
        }

        @JavascriptInterface
        public void tap(float x, float y, float viewportWidth) {
            ui.post(() -> {
                if (web == null || viewportWidth <= 0) return;
                float k = web.getWidth() / viewportWidth;
                tapOn(web, x * k, y * k);
            });
        }
    }

    // ------------------------------------------------------------------ utilitaires

    private void showOsd(String text, int ms) {
        if (osd == null) return;
        osd.setText(text);
        osd.setVisibility(View.VISIBLE);
        osd.bringToFront();
        if (cursorMode) cursor.bringToFront();
        ui.removeCallbacks(hideOsd);
        ui.postDelayed(hideOsd, ms);
    }

    private void showTips() {
        SharedPreferences p = getSharedPreferences("ntv", MODE_PRIVATE);
        int n = p.getInt("tips", 0);
        if (n < 3) {
            p.edit().putInt("tips", n + 1).apply();
            ui.postDelayed(() -> showOsd("Astuce : maintiens OK (ou touche Menu) pour le mode pointeur", 4500), 3000);
        }
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** Flèche de souris dessinée à la main. */
    static class CursorView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        CursorView(Context c) {
            super(c);
            fill.setColor(Color.WHITE);
            fill.setStyle(Paint.Style.FILL);
            stroke.setColor(Color.BLACK);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setStrokeWidth(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1.6f, c.getResources().getDisplayMetrics()));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), o = stroke.getStrokeWidth();
            path.reset();
            path.moveTo(o, o);
            path.lineTo(o, h * 0.80f);
            path.lineTo(w * 0.28f, h * 0.62f);
            path.lineTo(w * 0.46f, h - o);
            path.lineTo(w * 0.62f, h * 0.92f);
            path.lineTo(w * 0.44f, h * 0.56f);
            path.lineTo(w - o, h * 0.56f);
            path.close();
            c.drawPath(path, fill);
            c.drawPath(path, stroke);
        }
    }
}
