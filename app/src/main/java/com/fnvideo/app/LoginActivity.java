package com.fnvideo.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Uses the NAS login form; native code never stores a password. */
public final class LoginActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView web;
    private TextView message;
    private Button connectButton;
    private String origin;
    private boolean finished;
    private boolean cookiesReady;
    private long cookieGeneration;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (finished || web == null) return;
            if (cookiesReady && sameOrigin(web.getUrl())) {
                String token = cookieToken(CookieManager.getInstance().getCookie(web.getUrl()));
                if (!token.isEmpty()) {
                    finished = true;
                    setResult(RESULT_OK, new Intent().putExtra("base_url", origin).putExtra("token", token));
                    finish();
                    return;
                }
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(16, 21, 29));
        getWindow().setNavigationBarColor(Color.rgb(16, 21, 29));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(12), dp(20), 0);
        root.setBackgroundColor(Color.rgb(16, 21, 29));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            root.setPadding(dp(20), dp(12) + insets.getSystemWindowInsetTop(),
                    dp(20), insets.getSystemWindowInsetBottom());
            return insets;
        });
        TextView title = new TextView(this);
        title.setText("连接你的飞牛影视");
        title.setTextSize(23);
        title.setTextColor(Color.WHITE);
        root.addView(title);
        message = new TextView(this);
        message.setTextColor(Color.LTGRAY);
        message.setTextSize(13);
        message.setText("连接同一局域网，用飞牛账号登录。登录信息仅保存在本机。");
        root.addView(message);
        LinearLayout address = new LinearLayout(this);
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextColor(Color.WHITE);
        input.setText(getIntent().getStringExtra("base_url") == null
                ? SessionStore.base(this) : getIntent().getStringExtra("base_url"));
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.addView(input, new LinearLayout.LayoutParams(0, dp(56), 1));
        connectButton = new Button(this);
        connectButton.setText("连接");
        connectButton.setEnabled(false);
        address.addView(connectButton);
        root.addView(address);
        web = createLoginWebView();
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        connectButton.setOnClickListener(v -> connect(input.getText().toString()));
        if (!restoreBrowser(savedInstanceState, input)) {
            clearBrowserSession(() -> connect(input.getText().toString()));
        }
        handler.postDelayed(poll, 1000);
    }

    private WebView createLoginWebView() {
        WebView view = new WebView(this);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false);
        view.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (sameOrigin(request.getUrl().toString())) return false;
                message.setText("请使用当前飞牛影视服务的账号登录；其他站点不会在此打开。");
                return true;
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    message.setText("连接失败，请检查 Wi-Fi、服务器地址和端口，再点连接重试。");
                }
            }
        });
        return view;
    }

    private boolean restoreBrowser(Bundle state, EditText input) {
        if (state == null || !state.containsKey("server_origin")) return false;
        try {
            origin = ServerAddress.normalize(state.getString("server_origin"));
            input.setText(origin);
            cookiesReady = true;
            connectButton.setEnabled(true);
            if (web.restoreState(state) == null || !sameOrigin(web.getUrl())) {
                loadLoginOrigin(origin);
            }
            return true;
        } catch (IllegalArgumentException invalidSavedOrigin) {
            origin = null;
            return false;
        }
    }

    private void connect(String value) {
        if (finished || web == null || !cookiesReady) return;
        final String nextOrigin;
        try {
            nextOrigin = ServerAddress.normalize(value);
        } catch (IllegalArgumentException invalidAddress) {
            message.setText("请输入完整服务器地址，例如 http://nas.example.test:5666");
            return;
        }
        getIntent().putExtra("base_url", nextOrigin);
        if (origin != null && !ServerAddress.sameOrigin(origin, nextOrigin)) {
            // Cookies are shared across ports. Destroy the old document before
            // clearing them, and do not load or poll the new origin until done.
            origin = null;
            cookiesReady = false;
            ViewGroup parent = (ViewGroup) web.getParent();
            destroyBrowser();
            web = createLoginWebView();
            parent.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
            message.setText("正在切换服务器并清除旧登录状态。");
            clearBrowserSession(() -> loadLoginOrigin(nextOrigin));
        } else {
            loadLoginOrigin(nextOrigin);
        }
    }

    private void clearBrowserSession(Runnable afterClear) {
        long generation = ++cookieGeneration;
        cookiesReady = false;
        connectButton.setEnabled(false);
        WebStorage.getInstance().deleteAllData();
        CookieManager.getInstance().removeAllCookies(removed -> {
            if (finished || web == null || generation != cookieGeneration) return;
            cookiesReady = true;
            connectButton.setEnabled(true);
            afterClear.run();
        });
    }

    private void loadLoginOrigin(String value) {
        if (finished || web == null || !cookiesReady) return;
        origin = value;
        message.setText("在下方飞牛官方页面登录，成功后自动返回播放页。");
        web.loadUrl(origin + "/v/login");
    }

    private boolean sameOrigin(String url) {
        return ServerAddress.sameOrigin(origin, url);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String cookieToken(String cookies) {
        if (cookies == null) return "";
        for (String cookie : cookies.split(";")) {
            String entry = cookie.trim();
            if (entry.startsWith("Trim-MC-token=")) {
                return Uri.decode(entry.substring("Trim-MC-token=".length()));
            }
        }
        return "";
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (cookiesReady && origin != null && web != null) {
            state.putString("server_origin", origin);
            web.saveState(state);
        }
    }

    private void destroyBrowser() {
        if (web == null) return;
        ViewGroup parent = (ViewGroup) web.getParent();
        if (parent != null) parent.removeView(web);
        web.stopLoading();
        web.destroy();
        web = null;
    }

    @Override protected void onDestroy() {
        finished = true;
        cookieGeneration++;
        handler.removeCallbacksAndMessages(null);
        destroyBrowser();
        if (isFinishing()) {
            CookieManager.getInstance().removeAllCookies(null);
            WebStorage.getInstance().deleteAllData();
        }
        super.onDestroy();
    }
}
