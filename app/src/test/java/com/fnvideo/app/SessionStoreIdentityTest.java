package com.fnvideo.app;

import android.app.Activity;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class SessionStoreIdentityTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        SessionStore.reset(context);
    }

    @Test public void loginResultDoesNotReplaceIdentityBeforeAtomicCallerSave() throws Exception {
        SessionStore.rememberAccountId(context, "old-user");
        try (MockWebServer server = new MockWebServer();
             ActivityController<LoginActivity> controller =
                     Robolectric.buildActivity(LoginActivity.class).setup()) {
            server.start();
            server.enqueue(new MockResponse().setBody(
                    "{\"code\":0,\"data\":{\"token\":\"synthetic-token\"}}"));
            LoginActivity activity = controller.get();
            View root = activity.getWindow().getDecorView();
            ((EditText) find(root, "服务器 IP 或地址")).setText(server.url("/").toString());
            ((EditText) find(root, "用户名")).setText("new-user");
            ((EditText) find(root, "密码")).setText("synthetic-password");
            ((Button) find(root, "连接并登录")).performClick();
            for (int i = 0; i < 300 && !activity.isFinishing(); i++) {
                Thread.sleep(10L);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
            }

            assertTrue(activity.isFinishing());
            assertEquals("old-user", SessionStore.accountId(context));
            assertEquals("new-user", Shadows.shadowOf(activity).getResultIntent()
                    .getStringExtra("account_id"));
            assertEquals("synthetic-token", Shadows.shadowOf(activity).getResultIntent()
                    .getStringExtra("token"));
        }
    }

    @Test public void emptyTokenSaveClearsIdentityAtomically() {
        SessionStore.rememberAccountId(context, "old-user");
        SessionStore.save(context, "http://nas.example.test:5666", "", "new-user");
        assertEquals("", SessionStore.accountId(context));
        assertEquals("", SessionStore.token(context));
    }

    private static View find(View view, String label) {
        String description = view.getContentDescription() == null
                ? "" : view.getContentDescription().toString();
        if (label.equals(description) || view instanceof TextView
                && label.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }
}
