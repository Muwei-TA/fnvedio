package com.fnvideo.app;

import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

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
import org.robolectric.shadows.ShadowDialog;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Behavior regression coverage for the catalogue shell and its player handoff. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public final class LibraryActivityTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        SessionStore.reset(context);
        context.getSharedPreferences(WatchStateStore.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit();
        context.getSharedPreferences(WatchStateStore.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @Test public void launcherColdStartDoesNotResolvePlaybackSource() throws Exception {
        FakeRepository repository = new FakeRepository();
        try (ActivityController<LibraryActivity> controller =
                     Robolectric.buildActivity(LibraryActivity.class).setup()) {
            LibraryActivity activity = controller.get();
            attachSession(activity, repository);
            invoke(activity, "showLibraryPage");
            invoke(activity, "reloadCatalog");
            waitFor(() -> repository.catalogCalls.get() > 0, 1_000L);
            idleMain();
            assertEquals("Cold catalogue entry must not resolve a playback URL", 0,
                    repository.resolveCalls.get());
        }
    }

    @Test public void selectingAnEpisodeHandsOffThatExactPlaybackId() throws Exception {
        FakeRepository repository = new FakeRepository();
        MediaRepository.Video series = video("series-7", "示例剧集", "TV");
        MediaRepository.Video first = episode("episode-s1e1", "第一集", 1, 1, "season-1");
        MediaRepository.Video second = episode("episode-s1e2", "第二集", 1, 2, "season-1");
        MediaRepository.Video otherSeason = episode("episode-s2e1", "第二季第一集", 2, 1, "season-2");
        repository.details = series;
        repository.episodes = Arrays.asList(first, second, otherSeason);

        try (ActivityController<LibraryActivity> controller =
                     Robolectric.buildActivity(LibraryActivity.class).setup()) {
            LibraryActivity activity = controller.get();
            attachSession(activity, repository);
            invoke(activity, "showDetails", series);
            waitFor(() -> repository.episodeCalls.get() > 0, 1_000L);
            waitFor(() -> findDescriptionContaining(activity.getWindow().getDecorView(),
                    "第 2 集 · 第二集") != null,
                    1_000L);
            idleMain();

            View row = findDescriptionContaining(activity.getWindow().getDecorView(),
                    "第 2 集 · 第二集");
            assertNotNull("The exact episode row should be rendered", row);
            row.performClick();

            Intent started = Shadows.shadowOf(activity).getNextStartedActivity();
            assertNotNull(started);
            assertEquals("episode-s1e2", PlaybackRequest.video(started).id);
            List<MediaRepository.Video> queue = PlaybackRequest.queue(started);
            assertEquals("episode-s1e2", queue.get(PlaybackRequest.queueIndex(started)).id);
            assertEquals("series", PlaybackRequest.mode(started));
            assertTrue(PlaybackRequest.autoAdvance(started));
            assertEquals(0, repository.resolveCalls.get());
        }
    }

    @Test public void returningToLibraryDoesNotStartPlayback() throws Exception {
        FakeRepository repository = new FakeRepository();
        MediaRepository.Video movie = video("movie-1", "示例电影", "Movie");
        repository.details = movie;
        try (ActivityController<LibraryActivity> controller =
                     Robolectric.buildActivity(LibraryActivity.class).setup()) {
            LibraryActivity activity = controller.get();
            attachSession(activity, repository);
            invoke(activity, "showDetails", movie);
            waitFor(() -> repository.detailsCalls.get() > 0, 1_000L);
            idleMain();
            activity.onBackPressed();
            idleMain();

            assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
            assertEquals(0, repository.resolveCalls.get());
            assertNotNull(find(activity.getWindow().getDecorView(), "最近加入"));
        }
    }

    @Test public void missingFirstEpisodeDoesNotAutoAdvanceToLaterEpisode() throws Exception {
        FakeRepository repository = new FakeRepository();
        MediaRepository.Video series = video("series-7", "缺第一集的剧集", "TV");
        repository.details = series;
        repository.episodes = Collections.singletonList(
                episode("episode-s1e2", "第二集", 1, 2, "season-1"));
        try (ActivityController<LibraryActivity> controller =
                     Robolectric.buildActivity(LibraryActivity.class).setup()) {
            LibraryActivity activity = controller.get();
            attachSession(activity, repository);
            invoke(activity, "showDetails", series);
            waitFor(() -> repository.episodeCalls.get() > 0, 1_000L);
            waitFor(() -> find(activity.getWindow().getDecorView(), "▷ 开始播放") != null,
                    1_000L);
            View play = find(activity.getWindow().getDecorView(), "▷ 开始播放");
            assertNotNull(play);
            play.performClick();
            idleMain();

            assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
            assertEquals(0, repository.resolveCalls.get());
        }
    }

    @Test public void lateSearchResponseCannotReplaceNewQuery() throws Exception {
        FakeRepository repository = new FakeRepository();
        repository.oldQueryRelease = new CountDownLatch(1);
        repository.newQueryStarted = new CountDownLatch(1);
        repository.newQueryRelease = new CountDownLatch(1);
        repository.oldResult = video("old-result", "旧词结果", "Movie");
        repository.newResult = video("new-result", "新词结果", "Movie");

        try (ActivityController<LibraryActivity> controller =
                     Robolectric.buildActivity(LibraryActivity.class).setup()) {
            LibraryActivity activity = controller.get();
            attachSession(activity, repository);
            setField(activity, "section", 0);
            setField(activity, "searchMode", true);
            setField(activity, "currentQuery", "old");
            invoke(activity, "reloadCatalog");
            waitFor(() -> repository.catalogCalls.get() > 0, 1_000L);

            setField(activity, "currentQuery", "new");
            invoke(activity, "reloadCatalog");
            repository.oldQueryRelease.countDown();
            assertTrue(repository.newQueryStarted.await(1, TimeUnit.SECONDS));
            repository.newQueryRelease.countDown();
            waitFor(() -> repository.newQueryCalls.get() > 0, 1_000L);
            waitFor(() -> {
                List<MediaRepository.Video> values = getField(activity, "catalogItems");
                return values.size() == 1 && "new-result".equals(values.get(0).id);
            }, 1_000L);
            idleMain();

            List<MediaRepository.Video> values = getField(activity, "catalogItems");
            assertEquals(1, values.size());
            assertEquals("new-result", values.get(0).id);
        }
    }

    @Test public void cancellingSearchDraftKeepsExistingCatalogContext() throws Exception {
        FakeRepository repository = new FakeRepository();
        try (ActivityController<LibraryActivity> controller =
                     Robolectric.buildActivity(LibraryActivity.class).setup()) {
            LibraryActivity activity = controller.get();
            attachSession(activity, repository);
            MediaRepository.Video movie = video("movie-1", "保留影片", "Movie");
            List<MediaRepository.Video> catalog = getField(activity, "catalogItems");
            catalog.add(movie);
            setField(activity, "currentKind", "Movie");
            setField(activity, "currentQuery", "draft query");
            invoke(activity, "showLibraryPage");
            View search = find(activity.getWindow().getDecorView(), "搜索片库");
            assertNotNull(search);
            search.performClick();

            View back = find(activity.getWindow().getDecorView(), "返回片库");
            assertNotNull(back);
            back.performClick();
            idleMain();

            List<MediaRepository.Video> after = getField(activity, "catalogItems");
            assertEquals(1, after.size());
            assertEquals("movie-1", after.get(0).id);
            assertEquals("Movie", getField(activity, "currentKind"));
        }
    }

    @Test public void cancellingFilterDraftKeepsExistingCatalogContext() throws Exception {
        FakeRepository repository = new FakeRepository();
        try (ActivityController<LibraryActivity> controller =
                     Robolectric.buildActivity(LibraryActivity.class).setup()) {
            LibraryActivity activity = controller.get();
            attachSession(activity, repository);
            List<MediaRepository.Video> catalog = getField(activity, "catalogItems");
            catalog.add(video("movie-1", "保留影片", "Movie"));
            setField(activity, "currentKind", "Movie");
            invoke(activity, "showLibraryPage");

            // The current shell has no committed filter sheet yet. Treating
            // its filter affordance as a cancelled draft must preserve state.
            View filter = find(activity.getWindow().getDecorView(), "筛选");
            assertNotNull(filter);
            filter.performClick();
            android.app.Dialog dialog = ShadowDialog.getLatestDialog();
            assertNotNull(dialog);
            View dialogRoot = dialog.getWindow().getDecorView();
            View series = findTextContaining(dialogRoot, "剧集");
            assertNotNull(series);
            series.performClick();
            View cancel = find(dialogRoot, "取消");
            assertNotNull(cancel);
            cancel.performClick();
            idleMain();

            List<MediaRepository.Video> after = getField(activity, "catalogItems");
            assertEquals(1, after.size());
            assertEquals("movie-1", after.get(0).id);
            assertEquals("Movie", getField(activity, "currentKind"));
        }
    }

    @Test public void signedOutBottomNavigationClicksAreSafe() {
        try (ActivityController<LibraryActivity> controller =
                     Robolectric.buildActivity(LibraryActivity.class).setup()) {
            LibraryActivity activity = controller.get();
            for (String description : Arrays.asList("打开片库", "打开随看", "打开我的")) {
                View tab = find(activity.getWindow().getDecorView(), description);
                assertNotNull(tab);
                tab.performClick();
                idleMain();
                assertNotNull(find(activity.getWindow().getDecorView(), "登录并连接"));
            }
        }
    }

    private void attachSession(LibraryActivity activity, FakeRepository repository) throws Exception {
        setField(activity, "serverBase", "http://nas.example.test:5666");
        setField(activity, "sessionToken", "synthetic-session");
        setField(activity, "accountId", "synthetic-user");
        setField(activity, "repository", repository);
        invoke(activity, "initStore");
    }

    private static void idleMain() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void waitFor(Check check, long timeoutMs) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            idleMain();
            if (check.value()) return;
            Thread.sleep(10L);
        }
        idleMain();
        assertTrue("Timed out waiting for asynchronous UI state", check.value());
    }

    private static View find(View view, String label) {
        String description = view.getContentDescription() == null
                ? "" : view.getContentDescription().toString();
        if (label.equals(description)) return view;
        if (view instanceof TextView && label.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View findDescriptionContaining(View view, String fragment) {
        String description = view.getContentDescription() == null
                ? "" : view.getContentDescription().toString();
        if (description.contains(fragment)) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findDescriptionContaining(group.getChildAt(i), fragment);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View findTextContaining(View view, String fragment) {
        if (view instanceof TextView && ((TextView) view).getText() != null
                && ((TextView) view).getText().toString().contains(fragment)) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findTextContaining(group.getChildAt(i), fragment);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static <T> T getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(target);
    }

    private static Object invoke(Object target, String name, Object... arguments) throws Exception {
        for (Method method : target.getClass().getDeclaredMethods()) {
            if (!method.getName().equals(name) || method.getParameterTypes().length != arguments.length) continue;
            method.setAccessible(true);
            try {
                return method.invoke(target, arguments);
            } catch (InvocationTargetException error) {
                Throwable cause = error.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                if (cause instanceof Error) throw (Error) cause;
                throw error;
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static MediaRepository.Video video(String id, String title, String type) {
        MediaRepository.Video value = new MediaRepository.Video();
        value.id = id;
        value.title = title;
        value.type = type;
        value.poster = "";
        value.overview = "";
        return value;
    }

    private static MediaRepository.Video episode(String id, String title, int season,
                                                int episode, String seasonId) {
        MediaRepository.Video value = video(id, title, "Episode");
        value.season = season;
        value.episode = episode;
        value.seriesId = "series-7";
        value.seasonId = seasonId;
        return value;
    }

    private interface Check {
        boolean value() throws Exception;
    }

    private static final class FakeRepository implements MediaRepository {
        final AtomicInteger catalogCalls = new AtomicInteger();
        final AtomicInteger newQueryCalls = new AtomicInteger();
        final AtomicInteger detailsCalls = new AtomicInteger();
        final AtomicInteger episodeCalls = new AtomicInteger();
        final AtomicInteger resolveCalls = new AtomicInteger();
        volatile MediaRepository.Video details;
        volatile List<MediaRepository.Video> episodes = Collections.emptyList();
        volatile CountDownLatch oldQueryRelease;
        volatile CountDownLatch newQueryStarted;
        volatile CountDownLatch newQueryRelease;
        volatile MediaRepository.Video oldResult;
        volatile MediaRepository.Video newResult;

        @Override public List<MediaRepository.Library> libraries() { return Collections.emptyList(); }

        @Override public MediaRepository.Page page(MediaRepository.Query query, String cursor) {
            return new MediaRepository.Page(Collections.emptyList(), "");
        }

        @Override public MediaRepository.Page catalogPage(MediaRepository.Query query, String cursor) {
            catalogCalls.incrementAndGet();
            if ("old".equals(query.query) && oldQueryRelease != null) {
                awaitUninterruptibly(oldQueryRelease);
                return new MediaRepository.Page(Collections.singletonList(oldResult), "");
            }
            if ("new".equals(query.query) && newQueryRelease != null) {
                newQueryStarted.countDown();
                awaitUninterruptibly(newQueryRelease);
                newQueryCalls.incrementAndGet();
                return new MediaRepository.Page(Collections.singletonList(newResult), "");
            }
            return new MediaRepository.Page(Collections.singletonList(video("catalog-1", "目录影片", "Movie")), "");
        }

        @Override public MediaRepository.Video details(MediaRepository.Video item) {
            detailsCalls.incrementAndGet();
            return details == null ? item : details;
        }

        @Override public MediaRepository.Source resolve(MediaRepository.Video video) {
            resolveCalls.incrementAndGet();
            return new MediaRepository.Source("http://nas.example.test/media/" + video.id,
                    Collections.emptyMap(), "video/mp4");
        }

        @Override public MediaRepository.Source resolveCompatible(MediaRepository.Video video) {
            resolveCalls.incrementAndGet();
            return new MediaRepository.Source("http://nas.example.test/media/" + video.id,
                    Collections.emptyMap(), "video/mp4");
        }

        @Override public List<MediaRepository.Video> seriesEpisodes(MediaRepository.Video series) {
            episodeCalls.incrementAndGet();
            return episodes;
        }

        private static void awaitUninterruptibly(CountDownLatch latch) {
            while (true) {
                try {
                    latch.await();
                    break;
                } catch (InterruptedException ignored) {
                }
            }
        }
    }
}
