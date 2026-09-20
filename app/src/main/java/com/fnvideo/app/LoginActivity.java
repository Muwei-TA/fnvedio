package com.fnvideo.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
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

/** Uses the NAS's own login form: passwords never enter native app storage. */
public final class LoginActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView web;
    private TextView message;
    private String origin;
    private boolean finished;
    private boolean cookiesReady;
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
        getWindow().setStatusBarColor(Color.rgb(16,21,29));
        getWindow().setNavigationBarColor(Color.rgb(16,21,29));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20),dp(12),dp(20),0); root.setBackgroundColor(Color.rgb(16,21,29));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            root.setPadding(dp(20), dp(12) + insets.getSystemWindowInsetTop(),
                    dp(20), insets.getSystemWindowInsetBottom());
            return insets;
        });
        TextView title = new TextView(this); title.setText("连接你的飞牛影视"); title.setTextSize(23); title.setTextColor(Color.WHITE);
        root.addView(title);
        message = new TextView(this); message.setTextColor(Color.LTGRAY); message.setTextSize(13);
        message.setText("连接同一局域网，用飞牛账号登录。登录信息仅保存在本机。"); root.addView(message);
        LinearLayout address = new LinearLayout(this);
        EditText input = new EditText(this); input.setSingleLine(true); input.setTextColor(Color.WHITE);
        input.setText(getIntent().getStringExtra("base_url") == null ? SessionStore.base(this) : getIntent().getStringExtra("base_url"));
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.addView(input,new LinearLayout.LayoutParams(0,dp(56),1));
        Button connect = new Button(this); connect.setText("连接"); connect.setEnabled(false);
        address.addView(connect); root.addView(address);
        web = new WebView(this); root.addView(web,new LinearLayout.LayoutParams(-1,0,1)); setContentView(root);
        WebSettings settings = web.getSettings(); settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (sameOrigin(request.getUrl().toString())) return false;
                message.setText("请使用当前飞牛影视服务的账号登录；其他站点不会在此打开。"); return true;
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) message.setText("连接失败，请检查 Wi-Fi、服务器地址和端口，再点连接重试。");
            }
        });
        connect.setOnClickListener(v -> connect(input.getText().toString()));
        if (savedInstanceState != null && savedInstanceState.containsKey("server_origin")) {
            origin = savedInstanceState.getString("server_origin");
            input.setText(origin);
            cookiesReady = true;
            connect.setEnabled(true);
            if (web.restoreState(savedInstanceState) == null) connect(origin);
        } else {
            WebStorage.getInstance().deleteAllData();
            CookieManager.getInstance().removeAllCookies(done -> {
                if (finished || web == null) return;
                cookiesReady = true;
                connect.setEnabled(true);
                connect(input.getText().toString());
            });
        }
        handler.postDelayed(poll,1000);
    }
    private void connect(String value) {
        if (finished || web == null || !cookiesReady) return;
        try {
            Uri uri = Uri.parse(value.trim());
            if ((!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null)
                throw new IllegalArgumentException();
            origin = uri.getScheme()+"://"+uri.getEncodedAuthority();
            message.setText("在下方飞牛官方页面登录，成功后自动返回播放页。");
            web.loadUrl(origin+"/v/login");
        } catch (Exception e) { message.setText("请输入完整的飞牛影视服务地址"); }
    }
    private boolean sameOrigin(String url) {
        if (url == null || origin == null) return false;
        Uri a = Uri.parse(origin), b = Uri.parse(url);
        return a.getScheme().equals(b.getScheme()) && a.getEncodedAuthority().equals(b.getEncodedAuthority());
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
        if (origin != null && web != null) {
            state.putString("server_origin", origin);
            web.saveState(state);
        }
    }
    @Override protected void onDestroy() {
        finished = true; handler.removeCallbacksAndMessages(null);
        if (web != null) { web.stopLoading(); web.destroy(); web = null; }
        if (isFinishing()) {
            CookieManager.getInstance().removeAllCookies(null);
            WebStorage.getInstance().deleteAllData();
        }
        super.onDestroy();
    }
}
