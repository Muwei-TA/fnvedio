package com.fnvideo.app;

import android.app.Activity;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class NativeLoginActivityTest {
    private View find(View view, String label) {
        if (label.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())
                || view instanceof TextView && label.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void assertNoWebView(View view) {
        assertFalse(view instanceof WebView);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) assertNoWebView(group.getChildAt(i));
        }
    }

    @Test public void formIsNativeAndPasswordIsNotSavedAcrossRecreation() {
        try (ActivityController<LoginActivity> controller = Robolectric.buildActivity(LoginActivity.class).setup()) {
            LoginActivity activity = controller.get();
            View root = activity.getWindow().getDecorView();
            assertNoWebView(root);
            EditText password = (EditText) find(root, "密码");
            assertNotNull(password);
            assertFalse(password.isSaveEnabled());
            password.setText("synthetic-password");
            Bundle saved = new Bundle();
            activity.onSaveInstanceState(saved);
            assertFalse(saved.containsKey("password"));
            controller.pause().stop();
            assertEquals("", password.getText().toString());
        }
    }

    @Test public void nativeFormReturnsSessionForExactlyTheEnteredOrigin() throws Exception {
        try (MockWebServer server = new MockWebServer();
             ActivityController<LoginActivity> controller = Robolectric.buildActivity(LoginActivity.class).setup()) {
            server.start();
            server.enqueue(new MockResponse().setBody("{\"code\":0,\"data\":{\"token\":\"synthetic-session\"}}"));
            LoginActivity activity = controller.get();
            View root = activity.getWindow().getDecorView();
            ((EditText) find(root, "服务器 IP 或地址")).setText(server.url("/").toString());
            ((EditText) find(root, "用户名")).setText("synthetic-user");
            EditText password = (EditText) find(root, "密码");
            password.setText("synthetic-password");
            ((Button) find(root, "连接并登录")).performClick();
            assertEquals("", password.getText().toString());
            for (int i = 0; i < 300 && !activity.isFinishing(); i++) {
                Thread.sleep(10);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
            }
            assertTrue("Native login should finish with a session", activity.isFinishing());
            assertEquals(Activity.RESULT_OK, Shadows.shadowOf(activity).getResultCode());
            assertEquals("synthetic-session", Shadows.shadowOf(activity).getResultIntent().getStringExtra("token"));
            assertEquals(ServerAddress.normalize(server.url("/").toString()),
                    Shadows.shadowOf(activity).getResultIntent().getStringExtra("base_url"));
        }
    }
}
