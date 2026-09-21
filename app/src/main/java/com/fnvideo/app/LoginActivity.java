package com.fnvideo.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Native connection form. Passwords are transient and never saved in view state. */
public final class LoginActivity extends Activity {
    private static final int BACKGROUND = Color.rgb(8, 10, 14);
    private static final int SURFACE = Color.rgb(23, 27, 35);
    private static final int ACCENT = Color.rgb(255, 183, 77);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Set<String> found = new HashSet<>();
    private LanMediaDiscovery discovery;
    private EditText address;
    private EditText username;
    private EditText password;
    private TextView message;
    private TextView discoveryMessage;
    private LinearLayout devices;
    private Button login;
    private Button scan;
    private Future<?> request;
    private FnApi.LoginCall pendingLogin;
    private long generation;
    private boolean busy;
    private boolean active;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);
        LinearLayout root = column();
        root.setPadding(dp(24), dp(32), dp(24), dp(32));
        scroll.addView(root);
        TextView brand = label("牛影随看", 14, ACCENT);
        brand.setTypeface(null, Typeface.BOLD);
        root.addView(brand);
        TextView title = label("你的片库，\n就在身边", 32, Color.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, dp(20), 0, dp(12));
        root.addView(title);
        root.addView(label("连接家中的影视库，继续上一次的故事。", 14, 0xffa6afbd));

        LinearLayout serverCard = card(root);
        serverCard.addView(label("01  选择影视库", 16, Color.WHITE));
        address = input("服务器 IP 或地址", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setId(View.generateViewId());
        String saved = state == null ? getIntent().getStringExtra("base_url") : state.getString("address");
        if (saved == null) saved = SessionStore.base(this);
        if (saved != null && !saved.contains("nas.example.test")) address.setText(saved);
        serverCard.addView(address, fieldParams());
        serverCard.addView(label("可输入 IP:端口；省略端口时使用 5666", 12, 0xffa6afbd));
        scan = button("查找附近影视库", false);
        serverCard.addView(scan, fieldParams());
        discoveryMessage = label("连接与 NAS 相同的 Wi-Fi，可自动查找。", 12, 0xffa6afbd);
        serverCard.addView(discoveryMessage);
        devices = column();
        serverCard.addView(devices);

        LinearLayout accountCard = card(root);
        accountCard.addView(label("02  登录账号", 16, Color.WHITE));
        username = input("用户名", InputType.TYPE_CLASS_TEXT);
        username.setAutofillHints(View.AUTOFILL_HINT_USERNAME);
        if (state != null) username.setText(state.getString("username", ""));
        accountCard.addView(username, fieldParams());
        password = input("密码", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setAutofillHints(View.AUTOFILL_HINT_PASSWORD);
        password.setSaveEnabled(false);
        password.setImeOptions(EditorInfo.IME_ACTION_DONE);
        accountCard.addView(password, fieldParams());
        message = label("使用拥有影视访问权限的账号。密码不会保存在本机。", 12, 0xffa6afbd);
        accountCard.addView(message);
        login = button("连接并登录", true);
        root.addView(login, fieldParams());
        setContentView(scroll);
        discovery = new LanMediaDiscovery(this);
        scan.setOnClickListener(v -> startDiscovery());
        login.setOnClickListener(v -> connect());
        password.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_DONE) { connect(); return true; }
            return false;
        });
    }

    @Override protected void onStart() {
        super.onStart();
        active = true;
        startDiscovery();
    }

    private void startDiscovery() {
        if (!active || busy) return;
        found.clear();
        devices.removeAllViews();
        scan.setText("重新查找");
        discoveryMessage.setText("正在查找同一局域网的影视库…");
        discovery.start(new LanMediaDiscovery.Callback() {
            @Override public void onFound(String origin) {
                if (!active || busy || !found.add(origin)) return;
                Button device = button("影视库  ·  " + origin, false);
                device.setAllCaps(false);
                device.setOnClickListener(v -> {
                    address.setText(origin);
                    message.setText("已选择影视库，请输入用户名和密码。");
                    username.requestFocus();
                });
                devices.addView(device, fieldParams());
            }
            @Override public void onFinished(String result) {
                if (active && !busy) discoveryMessage.setText(result);
            }
        });
    }

    private void connect() {
        if (busy || !active) return;
        final String origin;
        try {
            origin = ServerAddress.fromUserInput(address.getText().toString());
        } catch (IllegalArgumentException invalid) {
            message.setText(invalid.getMessage());
            address.requestFocus();
            return;
        }
        final String user = username.getText().toString().trim();
        if (user.isEmpty() || password.length() == 0) {
            message.setText("请输入用户名和密码。");
            return;
        }
        char[] secret = new char[password.length()];
        password.getText().getChars(0, secret.length, secret, 0);
        password.setText("");
        discovery.cancel();
        discoveryMessage.setText("已选择 " + origin);
        setBusy(true);
        message.setText("正在连接影视库…");
        long ticket = ++generation;
        FnApi.LoginCall attempt = new FnApi.LoginCall(origin, user, secret);
        pendingLogin = attempt;
        request = executor.submit(() -> {
            String token = null;
            String error = null;
            try {
                token = attempt.execute();
            } catch (Exception failure) {
                error = failure.getMessage();
            } finally {
                java.util.Arrays.fill(secret, '\0');
            }
            final String resultToken = token;
            final String resultError = error;
            handler.post(() -> {
                if (!active || ticket != generation) return;
                setBusy(false);
                if (resultToken != null) {
                    setResult(RESULT_OK, new Intent().putExtra("base_url", origin)
                            .putExtra("token", resultToken));
                    finish();
                } else {
                    message.setText(resultError == null ? "连接失败，请重试。" : resultError);
                    password.requestFocus();
                }
            });
        });
    }

    private void setBusy(boolean value) {
        busy = value;
        login.setEnabled(!value);
        scan.setEnabled(!value);
        address.setEnabled(!value);
        username.setEnabled(!value);
        password.setEnabled(!value);
        login.setText(value ? "正在登录…" : "连接并登录");
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("address", address.getText().toString());
        state.putString("username", username.getText().toString());
        super.onSaveInstanceState(state);
    }

    @Override protected void onStop() {
        active = false;
        generation++;
        discovery.cancel();
        if (pendingLogin != null) pendingLogin.cancel();
        if (request != null) request.cancel(true);
        password.setText("");
        if (busy) message.setText("登录已取消，请重新输入密码。");
        setBusy(false);
        super.onStop();
    }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private LinearLayout column() {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.VERTICAL);
        return value;
    }

    private LinearLayout card(LinearLayout root) {
        LinearLayout value = column();
        value.setPadding(dp(18), dp(20), dp(18), dp(20));
        value.setBackground(background(SURFACE));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(24);
        root.addView(value, params);
        return value;
    }

    private TextView label(String value, int size, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        return text;
    }

    private EditText input(String hint, int type) {
        EditText value = new EditText(this);
        value.setHint(hint);
        value.setContentDescription(hint);
        value.setInputType(type);
        value.setSingleLine(true);
        value.setSaveEnabled(false);
        value.setTextColor(Color.WHITE);
        value.setHintTextColor(0xff818b9a);
        value.setTextSize(16);
        value.setPadding(dp(14), dp(12), dp(14), dp(12));
        value.setBackground(background(0xff10141b));
        return value;
    }

    private Button button(String title, boolean primary) {
        Button value = new Button(this);
        value.setText(title);
        value.setAllCaps(false);
        value.setTextColor(primary ? BACKGROUND : ACCENT);
        value.setTextSize(15);
        value.setMinHeight(dp(52));
        value.setBackground(background(primary ? ACCENT : 0xff272c36));
        return value;
    }

    private GradientDrawable background(int color) {
        GradientDrawable value = new GradientDrawable();
        value.setColor(color);
        value.setCornerRadius(dp(16));
        return value;
    }

    private LinearLayout.LayoutParams fieldParams() {
        LinearLayout.LayoutParams value = new LinearLayout.LayoutParams(-1, -2);
        value.topMargin = dp(14);
        value.bottomMargin = dp(12);
        return value;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
