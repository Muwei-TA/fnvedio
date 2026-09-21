package com.fnvideo.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Native catalogue shell. It deliberately knows about catalogue/details and
 * local watch state, while all media URL resolution stays in MainActivity.
 */
public final class LibraryActivity extends Activity {
    private static final int BG = Color.rgb(9, 12, 17);
    private static final int SURFACE = Color.rgb(19, 24, 32);
    private static final int SURFACE_2 = Color.rgb(27, 34, 44);
    private static final int PRIMARY = Color.rgb(244, 243, 238);
    private static final int SECONDARY = Color.rgb(161, 167, 176);
    private static final int ACCENT = Color.rgb(239, 188, 120);
    private static final int MUTED = Color.rgb(104, 114, 128);
    private static final int REQUEST_LOGIN = 1401;
    private static final long SEARCH_DELAY_MS = 300L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final ArrayList<MediaRepository.Video> catalogItems = new ArrayList<>();
    private final ArrayList<MediaRepository.Video> detailEpisodes = new ArrayList<>();
    private final ArrayList<MediaRepository.Video> watchQueue = new ArrayList<>();

    private PosterLoader posterLoader;
    private MediaRepository repository;
    private WatchStateStore watchStore;
    private Future<?> catalogRequest;
    private Future<?> detailRequest;
    private Future<?> libraryRequest;
    private long catalogGeneration;
    private long detailGeneration;
    private String nextCursor = "";
    private String serverBase = "";
    private String sessionToken = "";
    private String accountId = "";
    private boolean legacyIdentity;
    private String currentLibraryId = "";
    private String currentLibraryTitle = "全部媒体";
    private String currentKind = "";
    private String currentQuery = "";
    private String selectedSeasonKey = "";
    private MediaRepository.Video detailVideo;
    private int detailReturnSection;
    private boolean detailReturnSearchMode;
    private boolean searchMode;
    private boolean showLater;
    private boolean metadataFromDetail;
    private int watchCursor;
    private int section;

    private FrameLayout root;
    private FrameLayout content;
    private LinearLayout bottomNav;
    private TextView libraryTab;
    private TextView watchTab;
    private TextView myTab;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        configureWindow();
        posterLoader = new PosterLoader();
        restoreSession();
        buildShell();
        if (hasSession()) {
            initStore();
            showLibraryPage();
            reloadCatalog();
        } else {
            showSignedOut();
        }
    }

    private void configureWindow() {
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    private void buildShell() {
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            // The bottom navigation is a real child of the root. Reserve the
            // gesture/navigation inset below its 72dp hit area so the last
            // row and the selected tab never sit under the system bar.
            root.setPadding(0, insets.getSystemWindowInsetTop(), 0,
                    insets.getSystemWindowInsetBottom());
            return insets;
        });
        content = new FrameLayout(this);
        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        contentParams.bottomMargin = dp(72);
        root.addView(content, contentParams);
        buildBottomNav();
        setContentView(root);
    }

    private void buildBottomNav() {
        bottomNav = new LinearLayout(this);
        bottomNav.setOrientation(LinearLayout.HORIZONTAL);
        bottomNav.setGravity(Gravity.CENTER_VERTICAL);
        bottomNav.setPadding(dp(14), dp(5), dp(14), dp(5));
        bottomNav.setBackground(roundBackground(Color.rgb(13, 17, 24), 0));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(72), Gravity.BOTTOM);
        root.addView(bottomNav, params);
        libraryTab = navTab("▣\n片库", "打开片库");
        watchTab = navTab("▷\n随看", "打开随看");
        myTab = navTab("♙\n我的", "打开我的");
        bottomNav.addView(libraryTab, navParams());
        bottomNav.addView(watchTab, navParams());
        bottomNav.addView(myTab, navParams());
        libraryTab.setOnClickListener(view -> {
            clearDetailContext();
            section = 0;
            searchMode = false;
            showLibraryPage();
        });
        watchTab.setOnClickListener(view -> {
            clearDetailContext();
            section = 1;
            showWatchPage();
        });
        myTab.setOnClickListener(view -> {
            clearDetailContext();
            section = 2;
            showMyPage();
        });
    }

    private void showLibraryPage() {
        if (!hasSession()) {
            showSignedOut();
            return;
        }
        section = 0;
        markNav(libraryTab);
        ScrollView scroll = pageScroll();
        LinearLayout page = column();
        scroll.addView(page);
        LinearLayout top = row();
        TextView brand = label("牛影随看", 24, PRIMARY, Typeface.BOLD);
        top.addView(brand, new LinearLayout.LayoutParams(0, dp(50), 1f));
        TextView search = iconButton("⌕", "搜索片库");
        TextView libraries = iconButton("⋯", "选择媒体库");
        top.addView(search, compactParams());
        top.addView(libraries, compactParams());
        page.addView(top, wrapParams(14, 0));
        TextView scope = label(currentLibraryTitle, 12, SECONDARY, Typeface.NORMAL);
        page.addView(scope, wrapParams(0, 14));

        LinearLayout filters = row();
        filters.addView(filterButton("全部", "", currentKind), filterParams());
        filters.addView(filterButton("电影", "Movie", currentKind), filterParams());
        filters.addView(filterButton("剧集", "TV", currentKind), filterParams());
        TextView filterHint = label("筛选", 12, SECONDARY, Typeface.NORMAL);
        filterHint.setGravity(Gravity.CENTER);
        filterHint.setPadding(dp(12), 0, dp(12), 0);
        filterHint.setBackground(roundBackground(Color.TRANSPARENT, 18));
        filters.addView(filterHint, new LinearLayout.LayoutParams(dp(56), dp(42)));
        page.addView(filters, wrapParams(0, 18));

        WatchStateStore.Entry resume = latestResume();
        if (resume != null) {
            page.addView(resumeCard(resume), wrapParams(0, 22));
        } else {
            page.addView(label("最近加入", 19, PRIMARY, Typeface.BOLD), wrapParams(0, 12));
        }

        if (catalogItems.isEmpty() && catalogRequest != null && !catalogRequest.isDone()) {
            page.addView(skeletonBlock("正在读取片库…"), wrapParams(0, 16));
        } else if (catalogItems.isEmpty()) {
            page.addView(emptyBlock(currentQuery.isEmpty() ? "片库暂无可展示作品" : "没有找到匹配作品",
                    "可切换媒体库或重新搜索。"), wrapParams(0, 16));
        } else {
            page.addView(posterGrid(catalogItems), wrapParams(0, 12));
            if (!nextCursor.isEmpty()) {
                TextView more = actionButton("加载更多作品", ACCENT);
                more.setOnClickListener(view -> requestCatalogPage(catalogGeneration));
                page.addView(more, wrapParams(0, 18));
            }
        }
        if (!currentQuery.isEmpty()) {
            TextView hint = label("搜索结果仅显示服务端返回的作品；无法可靠归并的分集会单独显示。",
                    12, SECONDARY, Typeface.NORMAL);
            page.addView(hint, wrapParams(0, 24));
        }
        setPage(scroll);
        search.setOnClickListener(view -> showSearchPage());
        libraries.setOnClickListener(view -> showLibrariesDialog());
    }

    private void showSearchPage() {
        if (!hasSession()) {
            startLogin();
            return;
        }
        searchMode = true;
        currentKind = "";
        ScrollView scroll = pageScroll();
        LinearLayout page = column();
        scroll.addView(page);
        LinearLayout top = row();
        TextView back = iconButton("‹", "返回片库");
        EditText field = searchField();
        field.setText(currentQuery);
        field.setSelection(field.length());
        TextView clear = iconButton("×", "清除搜索");
        top.addView(back, compactParams());
        top.addView(field, new LinearLayout.LayoutParams(0, dp(46), 1f));
        top.addView(clear, compactParams());
        page.addView(top, wrapParams(12, 18));
        page.addView(label(currentQuery.isEmpty() ? "输入片名或关键词" : "找到的作品",
                20, PRIMARY, Typeface.BOLD), wrapParams(0, 12));
        if (catalogItems.isEmpty() && catalogRequest != null && !catalogRequest.isDone()) {
            page.addView(skeletonBlock("正在搜索…"), wrapParams(0, 16));
        } else if (!catalogItems.isEmpty()) {
            page.addView(posterGrid(catalogItems), wrapParams(0, 14));
        } else if (!currentQuery.isEmpty()) {
            page.addView(emptyBlock("没有找到作品", "可以换一个标题或检查 NAS 连接。"), wrapParams(0, 14));
        }
        page.addView(label("搜索结果由 NAS 返回；客户端不把当前已加载页面冒充完整片库。",
                12, SECONDARY, Typeface.NORMAL), wrapParams(0, 24));
        setPage(scroll);
        back.setOnClickListener(view -> {
            searchMode = false;
            showLibraryPage();
        });
        clear.setOnClickListener(view -> {
            field.setText("");
            field.requestFocus();
            showKeyboard(field);
        });
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                currentQuery = s == null ? "" : s.toString().trim();
                mainHandler.removeCallbacks(searchDebounceRunnable);
                mainHandler.postDelayed(searchDebounceRunnable, SEARCH_DELAY_MS);
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        field.requestFocus();
        showKeyboard(field);
    }

    private final Runnable searchDebounceRunnable = () -> {
        if (searchMode) reloadCatalog();
    };

    private void showWatchPage() {
        if (!hasSession()) {
            showSignedOut();
            return;
        }
        markNav(watchTab);
        ScrollView scroll = pageScroll();
        LinearLayout page = column();
        scroll.addView(page);
        page.addView(label("随看", 25, PRIMARY, Typeface.BOLD), wrapParams(18, 3));
        page.addView(label("主动开始一段不打断当前片库上下文的观看。上下滑切换作品。",
                12, SECONDARY, Typeface.NORMAL), wrapParams(0, 18));
        watchQueue.clear();
        for (MediaRepository.Video item : catalogItems) {
            if (item != null && ("Movie".equalsIgnoreCase(item.type)
                    || "Video".equalsIgnoreCase(item.type))) watchQueue.add(item);
        }
        if (watchQueue.isEmpty()) {
            page.addView(emptyBlock("还没有可随看的影片", "先在片库中加载作品，剧集请从详情选择分集。"), wrapParams(0, 18));
        } else {
            int start = Math.floorMod(watchCursor, watchQueue.size());
            MediaRepository.Video first = watchQueue.get(start);
            LinearLayout hero = watchHero(first);
            page.addView(hero, wrapParams(0, 20));
            TextView startButton = actionButton("开始随看", ACCENT);
            page.addView(startButton, wrapParams(0, 22));
            startButton.setOnClickListener(view -> {
                watchCursor = (start + 1) % Math.max(1, watchQueue.size());
                openPlayback(first, watchQueue, start, false, "watch");
            });
            page.addView(label("本次队列来自已加载的真实作品；没有个性化推荐或自动跨季逻辑。",
                    12, SECONDARY, Typeface.NORMAL), wrapParams(0, 14));
            page.addView(posterGrid(watchQueue), wrapParams(0, 20));
        }
        setPage(scroll);
    }

    private void showMyPage() {
        if (!hasSession()) {
            showSignedOut();
            return;
        }
        markNav(myTab);
        ScrollView scroll = pageScroll();
        LinearLayout page = column();
        scroll.addView(page);
        page.addView(label("我的影院", 25, PRIMARY, Typeface.BOLD), wrapParams(20, 3));
        page.addView(label(currentLibraryTitle + " · 本机观看记录", 12, SECONDARY, Typeface.NORMAL), wrapParams(0, 18));
        if (legacyIdentity || accountId.isEmpty()) {
            LinearLayout identity = column();
            identity.setPadding(dp(14), dp(12), dp(14), dp(12));
            identity.setBackground(roundBackground(SURFACE_2, 14));
            identity.addView(label("旧会话未绑定账号", 14, PRIMARY, Typeface.BOLD), wrapParams(0, 5));
            identity.addView(label("为避免不同账号共用本机历史，请重新登录后启用继续观看和稍后看。",
                    12, SECONDARY, Typeface.NORMAL), wrapParams(0, 9));
            TextView login = actionButton("重新登录并绑定账号", ACCENT);
            identity.addView(login, new LinearLayout.LayoutParams(-1, dp(44)));
            page.addView(identity, wrapParams(0, 16));
            login.setOnClickListener(view -> startLogin());
        }

        List<WatchStateStore.Entry> recent = recentEntries();
        List<MediaRepository.Video> later = watchStore == null
                ? Collections.emptyList() : watchStore.watchLater();
        LinearLayout stats = row();
        stats.addView(statCard(String.format(Locale.US, "%02d", resumableCount(recent)), "继续观看"), statParams());
        stats.addView(statCard(String.format(Locale.US, "%02d", later.size()), "稍后看"), statParams());
        page.addView(stats, wrapParams(0, 18));

        LinearLayout tabs = row();
        TextView continueTab = tabButton("继续观看", !showLater);
        TextView laterTab = tabButton("稍后看", showLater);
        tabs.addView(continueTab, new LinearLayout.LayoutParams(0, dp(48), 1f));
        tabs.addView(laterTab, new LinearLayout.LayoutParams(0, dp(48), 1f));
        page.addView(tabs, wrapParams(0, 12));
        LinearLayout list = column();
        if (showLater) {
            for (MediaRepository.Video video : later) list.addView(watchRow(video, false), wrapParams(0, 10));
        } else {
            for (WatchStateStore.Entry entry : recent) {
                if (!entry.completed && entry.positionMs > 0) list.addView(watchRow(entry.video, true), wrapParams(0, 10));
            }
        }
        if (list.getChildCount() == 0) {
            list.addView(emptyBlock(showLater ? "稍后看还是空的" : "还没有未完成记录",
                    showLater ? "在详情页加入稍后看。" : "播放一部影片后，这里会保留本机续播位置。"));
        }
        page.addView(list, wrapParams(0, 22));
        TextView metadata = menuRow("▤", "影片资料／刮削", "只读查看已有资料");
        TextView connection = menuRow("⌁", "服务器连接", serverBase);
        page.addView(metadata, wrapParams(0, 1));
        page.addView(connection, wrapParams(0, 1));
        TextView continuity = menuRow("▷", "同季连续播放", "播放结束后 5 秒提示下一集");
        page.addView(continuity, wrapParams(0, 1));
        page.addView(label(watchStore == null
                        ? "本机记录需要稳定账号身份；当前会话未绑定账号。"
                        : "观看位置与稍后看仅保存在本机，不写回飞牛影视。",
                12, SECONDARY, Typeface.NORMAL), wrapParams(0, 20));
        setPage(scroll);
        continueTab.setOnClickListener(view -> {
            showLater = false;
            showMyPage();
        });
        laterTab.setOnClickListener(view -> {
            showLater = true;
            showMyPage();
        });
        metadata.setOnClickListener(view -> showMetadataPage());
        connection.setOnClickListener(view -> showConnectionDialog());
    }

    private void showMetadataPage() {
        ScrollView scroll = pageScroll();
        LinearLayout page = column();
        scroll.addView(page);
        LinearLayout top = row();
        TextView back = iconButton("‹", "返回我的");
        top.addView(back, compactParams());
        top.addView(label("影片资料／刮削", 19, PRIMARY, Typeface.BOLD), new LinearLayout.LayoutParams(0, dp(46), 1f));
        page.addView(top, wrapParams(12, 18));
        int checked = catalogItems.size();
        int incomplete = 0;
        for (MediaRepository.Video item : catalogItems) {
            if (missingMetadata(item)) incomplete++;
        }
        LinearLayout summary = column();
        summary.setPadding(dp(18), dp(18), dp(18), dp(18));
        summary.setBackground(roundBackground(SURFACE_2, 17));
        summary.addView(label("让片库共有条理。", 19, PRIMARY, Typeface.BOLD), wrapParams(0, 6));
        summary.addView(label("这里仅展示客户端已经读取到的资料，不重复扫描媒体文件。",
                12, SECONDARY, Typeface.NORMAL), wrapParams(0, 16));
        LinearLayout numbers = row();
        numbers.addView(numberBlock(String.valueOf(checked), "已检查"), statParams());
        numbers.addView(numberBlock(String.valueOf(incomplete), "需完善"), statParams());
        numbers.addView(numberBlock("—", "写入能力"), statParams());
        summary.addView(numbers);
        page.addView(summary, wrapParams(0, 18));
        page.addView(label("V1 只读：资料修正需要在飞牛管理页完成。客户端不会伪造刮削任务状态。",
                12, SECONDARY, Typeface.NORMAL), wrapParams(0, 18));
        page.addView(label("需要完善的影片", 18, PRIMARY, Typeface.BOLD), wrapParams(0, 12));
        for (MediaRepository.Video item : catalogItems) {
            if (missingMetadata(item)) page.addView(metadataRow(item), wrapParams(0, 8));
        }
        TextView manage = actionButton("去飞牛管理", PRIMARY);
        page.addView(manage, wrapParams(0, 16));
        page.addView(label("资料缺失仅根据已读取字段判断，不等同于服务端刮削失败。",
                12, SECONDARY, Typeface.NORMAL), wrapParams(0, 20));
        setPage(scroll);
        final boolean returnToDetail = metadataFromDetail;
        metadataFromDetail = false;
        back.setOnClickListener(view -> {
            if (returnToDetail && detailVideo != null) renderDetailPage(false);
            else showMyPage();
        });
        manage.setOnClickListener(view -> openManagement());
    }

    private void showDetails(MediaRepository.Video item) {
        if (item == null) return;
        detailReturnSection = section;
        detailReturnSearchMode = searchMode;
        detailVideo = item;
        detailEpisodes.clear();
        selectedSeasonKey = "";
        final long generation = ++detailGeneration;
        renderDetailPage(true);
        cancel(detailRequest);
        final MediaRepository requestRepository = repository;
        detailRequest = networkExecutor.submit(() -> {
            try {
                MediaRepository.Video enriched = requestRepository.details(item);
                if (enriched == null) enriched = item;
                List<MediaRepository.Video> episodes = isSeries(enriched)
                        ? requestRepository.seriesEpisodes(enriched) : Collections.emptyList();
                MediaRepository.Video result = enriched;
                List<MediaRepository.Video> resultEpisodes = episodes == null
                        ? Collections.emptyList() : episodes;
                mainHandler.post(() -> {
                    if (generation != detailGeneration || destroyed() || repository != requestRepository) return;
                    detailVideo = result;
                    detailEpisodes.clear();
                    detailEpisodes.addAll(resultEpisodes);
                    detailEpisodes.sort(episodeComparator());
                    selectedSeasonKey = firstSeasonKey(detailEpisodes);
                    renderDetailPage(false);
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (generation != detailGeneration || destroyed()) return;
                    renderDetailPage(false);
                    Toast.makeText(this, "分集读取失败：" + errorReason(error), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!hasSession() || content == null) return;
        // MainActivity writes WatchStateStore while this Activity is paused.
        // Rebuild the visible page from the same in-memory context so the
        // returned detail/list shows the new position without resetting the
        // catalogue query, selected tab, or scroll offset.
        int scrollY = currentScrollY();
        if (detailVideo != null) {
            renderDetailPage(false);
        } else if (section == 1) {
            showWatchPage();
        } else if (section == 2) {
            showMyPage();
        } else if (searchMode) {
            showSearchPage();
        } else {
            showLibraryPage();
        }
        restoreScroll(scrollY);
    }

    private int currentScrollY() {
        if (content == null || content.getChildCount() == 0) return 0;
        View page = content.getChildAt(0);
        return page instanceof ScrollView ? ((ScrollView) page).getScrollY() : 0;
    }

    private void restoreScroll(int scrollY) {
        if (scrollY <= 0 || content == null) return;
        content.post(() -> {
            if (content.getChildCount() == 0) return;
            View page = content.getChildAt(0);
            if (page instanceof ScrollView) ((ScrollView) page).scrollTo(0, scrollY);
        });
    }

    private void renderDetailPage(boolean loading) {
        ScrollView scroll = pageScroll();
        LinearLayout page = column();
        scroll.addView(page);
        LinearLayout top = row();
        TextView back = iconButton("‹", "返回片库");
        top.addView(back, compactParams());
        top.addView(label("详情", 19, PRIMARY, Typeface.BOLD), new LinearLayout.LayoutParams(0, dp(46), 1f));
        TextView more = iconButton("⋯", "资料入口");
        top.addView(more, compactParams());
        page.addView(top, wrapParams(10, 0));
        if (detailVideo == null) {
            page.addView(emptyBlock("无法读取详情", "请返回片库重试。"), wrapParams(0, 20));
            setPage(scroll);
            back.setOnClickListener(view -> returnFromDetail());
            return;
        }
        ImageView hero = posterImage(detailVideo.poster, -1, dp(245));
        hero.setScaleType(ImageView.ScaleType.CENTER_CROP);
        page.addView(hero, wrapParams(0, 14));
        loadPoster(hero, detailVideo.poster, 700, 980);
        page.addView(label(safe(detailVideo.title).isEmpty() ? "未命名作品" : detailVideo.title,
                27, PRIMARY, Typeface.BOLD), wrapParams(0, 6));
        String meta = metadataLine(detailVideo);
        page.addView(label(meta, 13, SECONDARY, Typeface.NORMAL), wrapParams(0, 12));
        if (!safe(detailVideo.overview).isEmpty()) {
            page.addView(label(detailVideo.overview, 14, PRIMARY, Typeface.NORMAL), wrapParams(0, 16));
        } else {
            page.addView(label("简介未知", 14, SECONDARY, Typeface.NORMAL), wrapParams(0, 16));
        }
        LinearLayout actions = row();
        TextView play = actionButton(detailPlayLabel(), ACCENT);
        TextView later = actionButton(watchStore != null && watchStore.isWatchLater(detailVideo.id)
                ? "已加入稍后看" : "＋ 稍后看", PRIMARY);
        actions.addView(play, new LinearLayout.LayoutParams(0, dp(50), 1f));
        LinearLayout.LayoutParams laterParams = new LinearLayout.LayoutParams(0, dp(50), 0.56f);
        laterParams.leftMargin = dp(8);
        actions.addView(later, laterParams);
        page.addView(actions, wrapParams(0, 18));
        if (loading) {
            page.addView(skeletonBlock("正在读取影片资料与分集…"), wrapParams(0, 18));
        } else if (isSeries(detailVideo)) {
            renderEpisodes(page);
        }
        page.addView(label("资料来自 NAS 当前读取结果；缺少字段不会阻止可用文件播放。",
                12, SECONDARY, Typeface.NORMAL), wrapParams(0, 24));
        setPage(scroll);
        back.setOnClickListener(view -> returnFromDetail());
        more.setOnClickListener(view -> {
            metadataFromDetail = true;
            showMetadataPage();
        });
        play.setOnClickListener(view -> playDetail());
        later.setOnClickListener(view -> {
            if (watchStore == null) {
                Toast.makeText(this, "当前会话没有稳定账号身份，无法保存本机片单。", Toast.LENGTH_SHORT).show();
                return;
            }
            watchStore.toggleWatchLater(detailVideo);
            renderDetailPage(false);
        });
    }

    private void renderEpisodes(LinearLayout page) {
        page.addView(label("剧集", 18, PRIMARY, Typeface.BOLD), wrapParams(0, 10));
        if (detailEpisodes.isEmpty()) {
            page.addView(emptyBlock("暂无可用分集", "服务端没有返回可播放分集。"), wrapParams(0, 14));
            return;
        }
        LinearLayout seasons = row();
        for (String seasonKey : seasonKeys(detailEpisodes)) {
            String label = seasonLabel(seasonKey, detailEpisodes);
            TextView chip = chipButton(label, seasonKey.equals(selectedSeasonKey));
            seasons.addView(chip, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
            chip.setOnClickListener(view -> {
                selectedSeasonKey = seasonKey;
                renderDetailPage(false);
            });
        }
        HorizontalScrollView seasonScroll = new HorizontalScrollView(this);
        seasonScroll.setHorizontalScrollBarEnabled(false);
        seasonScroll.setHorizontalFadingEdgeEnabled(false);
        seasonScroll.addView(seasons, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(seasonScroll, wrapParams(0, 12));
        LinearLayout episodes = column();
        List<MediaRepository.Video> selected = episodesForSeason(selectedSeasonKey);
        int previous = 0;
        for (MediaRepository.Video episode : selected) {
            if (previous > 0 && episode.episode > previous + 1) {
                for (int missing = previous + 1; missing < episode.episode && missing <= previous + 20; missing++) {
                    episodes.addView(missingEpisodeRow(missing), wrapParams(0, 8));
                }
            }
            episodes.addView(episodeRow(episode), wrapParams(0, 8));
            if (episode.episode > 0) previous = episode.episode;
        }
        page.addView(episodes);
        page.addView(label("只播放本季；缺集不会自动跳过，分季顺序也不会跨季。",
                12, SECONDARY, Typeface.NORMAL), wrapParams(0, 18));
    }

    private void playDetail() {
        if (detailVideo == null) return;
        if (isSeries(detailVideo)) {
            MediaRepository.Video target = findResumeEpisode(detailVideo);
            if (target == null) {
                List<MediaRepository.Video> season = episodesForSeason(selectedSeasonKey);
                target = season.isEmpty() ? null : season.get(0);
            }
            if (target == null) {
                Toast.makeText(this, "当前没有可播放分集，请在 NAS 确认媒体是否已入库。", Toast.LENGTH_LONG).show();
                return;
            }
            List<MediaRepository.Video> season = episodesForSeason(seasonKey(target));
            int index = indexById(season, target.id);
            openPlayback(target, season, index, true, "series");
        } else {
            openPlayback(detailVideo, Collections.singletonList(detailVideo), 0, false, "movie");
        }
    }

    private void openPlayback(MediaRepository.Video video, List<MediaRepository.Video> values,
                               int index, boolean autoAdvance, String mode) {
        if (video == null || safe(video.id).isEmpty()) return;
        Intent intent = new Intent(this, MainActivity.class);
        PlaybackRequest.put(intent, video, values, Math.max(0, index), autoAdvance, mode);
        startActivity(intent);
    }

    private void reloadCatalog() {
        if (!hasSession() || repository == null) return;
        long generation = ++catalogGeneration;
        cancel(catalogRequest);
        catalogItems.clear();
        nextCursor = "";
        if (section == 0) {
            if (searchMode) showSearchPage(); else showLibraryPage();
        }
        requestCatalogPage(generation);
    }

    private void requestCatalogPage(long generation) {
        if (generation != catalogGeneration || repository == null || nextCursor == null) return;
        final String cursor = nextCursor;
        final String query = currentQuery;
        final String library = currentLibraryId;
        final String kind = currentKind;
        final MediaRepository requestRepository = repository;
        catalogRequest = networkExecutor.submit(() -> {
            try {
                MediaRepository.Page page = requestRepository.catalogPage(
                        new MediaRepository.Query(query, library, kind), cursor);
                mainHandler.post(() -> {
                    if (generation != catalogGeneration || destroyed() || repository != requestRepository) return;
                    addCatalogPage(page);
                    if (section == 0) {
                        if (searchMode) showSearchPage(); else showLibraryPage();
                    }
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (generation != catalogGeneration || destroyed()) return;
                    nextCursor = "";
                    if (section == 0) {
                        if (searchMode) showSearchPage(); else showLibraryPage();
                    }
                    if (RepositoryFailure.requiresLogin(error)) handleAuthenticationFailure();
                    else Toast.makeText(this, "片库读取失败：" + errorReason(error), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void addCatalogPage(MediaRepository.Page page) {
        if (page == null) return;
        Set<String> ids = new HashSet<>();
        for (MediaRepository.Video item : catalogItems) if (item != null) ids.add(item.id);
        for (MediaRepository.Video item : page.items) {
            if (item != null && !safe(item.id).isEmpty() && ids.add(item.id)) catalogItems.add(item);
        }
        nextCursor = safe(page.nextCursor);
    }

    private void showLibrariesDialog() {
        if (repository == null) return;
        LinearLayout card = dialogCard();
        card.addView(label("选择媒体库", 19, PRIMARY, Typeface.BOLD), wrapParams(0, 12));
        TextView status = label("正在读取 NAS 媒体库…", 12, SECONDARY, Typeface.NORMAL);
        card.addView(status, wrapParams(0, 10));
        LinearLayout rows = column();
        ScrollView scroll = new ScrollView(this);
        scroll.addView(rows);
        card.addView(scroll, new LinearLayout.LayoutParams(-1, dp(300)));
        TextView close = actionButton("关闭", SECONDARY);
        card.addView(close, wrapParams(0, 4));
        Dialog dialog = showDialog(card);
        close.setOnClickListener(view -> dialog.dismiss());
        final MediaRepository requestRepository = repository;
        cancel(libraryRequest);
        libraryRequest = networkExecutor.submit(() -> {
            try {
                List<MediaRepository.Library> values = requestRepository.libraries();
                mainHandler.post(() -> {
                    if (!dialog.isShowing() || repository != requestRepository) return;
                    rows.removeAllViews();
                    addLibraryChoice(dialog, rows, "全部媒体", "");
                    for (MediaRepository.Library value : values) {
                        if (value != null) addLibraryChoice(dialog, rows,
                                safe(value.title).isEmpty() ? value.id : value.title, value.id);
                    }
                    status.setText("选择后重新读取作品海报墙。");
                });
            } catch (Exception error) {
                mainHandler.post(() -> status.setText("媒体库读取失败：" + errorReason(error)));
            }
        });
    }

    private void addLibraryChoice(Dialog dialog, LinearLayout rows, String title, String id) {
        TextView choice = dialogRow(title, id.equals(currentLibraryId) ? "当前媒体库" : "切换到这里");
        rows.addView(choice, wrapParams(0, 8));
        choice.setOnClickListener(view -> {
            currentLibraryId = safe(id);
            currentLibraryTitle = safe(title).isEmpty() ? "全部媒体" : title;
            dialog.dismiss();
            reloadCatalog();
        });
    }

    private void showConnectionDialog() {
        LinearLayout card = dialogCard();
        card.addView(label("服务器连接", 19, PRIMARY, Typeface.BOLD), wrapParams(0, 12));
        card.addView(label(serverBase, 13, SECONDARY, Typeface.NORMAL), wrapParams(0, 10));
        TextView login = actionButton("重新登录", ACCENT);
        TextView close = actionButton("关闭", SECONDARY);
        card.addView(login, wrapParams(0, 4));
        card.addView(close, wrapParams(0, 4));
        Dialog dialog = showDialog(card);
        login.setOnClickListener(view -> {
            dialog.dismiss();
            startLogin();
        });
        close.setOnClickListener(view -> dialog.dismiss());
    }

    private void showSignedOut() {
        repository = null;
        watchStore = null;
        markNav(null);
        LinearLayout page = column();
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(dp(28), dp(90), dp(28), dp(24));
        page.addView(label("牛影随看", 27, PRIMARY, Typeface.BOLD), wrapParams(0, 14));
        page.addView(label("连接你的私人片库", 20, PRIMARY, Typeface.BOLD), wrapParams(0, 10));
        page.addView(label("登录 NAS 后显示真实作品、详情与本机续播。\n客户端不会伪造视频，也不会扫描本地文件。",
                14, SECONDARY, Typeface.NORMAL), wrapParams(0, 22));
        TextView login = actionButton("登录并连接", ACCENT);
        page.addView(login, new LinearLayout.LayoutParams(dp(220), dp(50)));
        setPage(page);
        login.setOnClickListener(view -> startLogin());
    }

    private void startLogin() {
        Intent intent = new Intent(this, LoginActivity.class);
        if (!serverBase.isEmpty()) intent.putExtra("base_url", serverBase);
        startActivityForResult(intent, REQUEST_LOGIN);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_LOGIN || resultCode != RESULT_OK || data == null) return;
        String token = safe(data.getStringExtra("token"));
        String account = safe(data.getStringExtra("account_id"));
        try {
            String base = ServerAddress.normalize(data.getStringExtra("base_url"));
            if (token.isEmpty()) throw new IllegalArgumentException("登录未返回会话");
            SessionStore.save(this, base, token, account);
            serverBase = base;
            sessionToken = token;
            accountId = account;
            legacyIdentity = account.isEmpty();
            repository = new FnApi(serverBase, sessionToken);
            initStore();
            currentQuery = "";
            currentKind = "";
            searchMode = false;
            showLibraryPage();
            reloadCatalog();
        } catch (Exception error) {
            Toast.makeText(this, "登录信息保存失败：" + errorReason(error), Toast.LENGTH_LONG).show();
        }
    }

    private void handleAuthenticationFailure() {
        SessionStore.clear(this);
        serverBase = "";
        sessionToken = "";
        accountId = "";
        legacyIdentity = false;
        repository = null;
        watchStore = null;
        showSignedOut();
    }

    private void restoreSession() {
        try {
            serverBase = ServerAddress.normalize(SessionStore.base(this));
            sessionToken = safe(SessionStore.token(this));
            accountId = safe(SessionStore.accountId(this));
            legacyIdentity = !sessionToken.isEmpty() && accountId.isEmpty();
        } catch (Exception error) {
            serverBase = "";
            sessionToken = "";
            accountId = "";
        }
        repository = hasSession() ? new FnApi(serverBase, sessionToken) : null;
    }

    private void initStore() {
        watchStore = null;
        if (!accountId.isEmpty() && hasSession()) {
            try {
                watchStore = new WatchStateStore(this, serverBase, accountId);
            } catch (RuntimeException ignored) {
                watchStore = null;
            }
        }
    }

    private boolean hasSession() {
        return !serverBase.isEmpty() && !sessionToken.isEmpty();
    }

    private WatchStateStore.Entry latestResume() {
        for (WatchStateStore.Entry entry : recentEntries()) {
            if (entry != null && !entry.completed && entry.positionMs > 0 && entry.video != null) return entry;
        }
        return null;
    }

    private List<WatchStateStore.Entry> recentEntries() {
        return watchStore == null ? Collections.emptyList() : watchStore.recent();
    }

    private int resumableCount(List<WatchStateStore.Entry> values) {
        int count = 0;
        for (WatchStateStore.Entry value : values) if (value != null && !value.completed && value.positionMs > 0) count++;
        return count;
    }

    private LinearLayout resumeCard(WatchStateStore.Entry entry) {
        LinearLayout card = row();
        card.setPadding(dp(16), dp(14), dp(12), dp(14));
        card.setBackground(roundBackground(SURFACE_2, 17));
        ImageView image = posterImage(entry.video.poster, dp(78), dp(112));
        card.addView(image, new LinearLayout.LayoutParams(dp(78), dp(112)));
        loadPoster(image, entry.video.poster, 260, 380);
        LinearLayout info = column();
        info.setPadding(dp(12), 0, 0, 0);
        info.addView(label("继续上次的故事", 11, SECONDARY, Typeface.NORMAL), wrapParams(0, 6));
        info.addView(label(safe(entry.video.title).isEmpty() ? "未命名作品" : entry.video.title,
                18, PRIMARY, Typeface.BOLD), wrapParams(0, 5));
        info.addView(label(episodeLabel(entry.video) + " · 已看 " + formatTime(entry.positionMs),
                12, SECONDARY, Typeface.NORMAL), wrapParams(0, 10));
        TextView play = actionButton("继续播放", ACCENT);
        info.addView(play, new LinearLayout.LayoutParams(-1, dp(44)));
        card.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));
        play.setOnClickListener(view -> openPlayback(entry.video,
                Collections.singletonList(entry.video), 0, false, "movie"));
        return card;
    }

    private LinearLayout watchHero(MediaRepository.Video video) {
        LinearLayout card = column();
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(roundBackground(SURFACE_2, 18));
        ImageView image = posterImage(video.poster, -1, dp(260));
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(image, new LinearLayout.LayoutParams(-1, dp(260)));
        loadPoster(image, video.poster, 700, 900);
        card.addView(label(safe(video.title).isEmpty() ? "未命名影片" : video.title,
                23, PRIMARY, Typeface.BOLD), wrapParams(0, 5));
        card.addView(label(metadataLine(video), 12, SECONDARY, Typeface.NORMAL), wrapParams(0, 0));
        return card;
    }

    private LinearLayout watchRow(MediaRepository.Video video, boolean resume) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(8), dp(8));
        row.setBackground(roundBackground(SURFACE, 14));
        ImageView image = posterImage(video.poster, dp(68), dp(96));
        row.addView(image, new LinearLayout.LayoutParams(dp(68), dp(96)));
        loadPoster(image, video.poster, 240, 340);
        LinearLayout info = column();
        info.setPadding(dp(10), 0, dp(8), 0);
        info.addView(label(safe(video.title).isEmpty() ? "未命名作品" : video.title,
                15, PRIMARY, Typeface.BOLD), wrapParams(0, 4));
        String subtitle = metadataLine(video);
        if (resume && watchStore != null) {
            long position = watchStore.position(video);
            if (position > 0) subtitle += " · 已看 " + formatTime(position);
        }
        info.addView(label(subtitle, 11, SECONDARY, Typeface.NORMAL), wrapParams(0, 4));
        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView play = iconButton("▷", "播放 " + safe(video.title));
        row.addView(play, new LinearLayout.LayoutParams(dp(48), dp(48)));
        row.setOnClickListener(view -> showDetails(video));
        play.setOnClickListener(view -> openPlayback(video, Collections.singletonList(video), 0, false, "movie"));
        return row;
    }

    private LinearLayout posterGrid(List<MediaRepository.Video> values) {
        LinearLayout grid = column();
        int columns = 3;
        for (int start = 0; start < values.size(); start += columns) {
            LinearLayout row = row();
            for (int offset = 0; offset < columns; offset++) {
                int index = start + offset;
                if (index >= values.size()) {
                    row.addView(new View(this), gridParams());
                    continue;
                }
                MediaRepository.Video item = values.get(index);
                row.addView(posterCard(item), gridParams());
            }
            grid.addView(row, wrapParams(0, 14));
        }
        return grid;
    }

    private LinearLayout posterCard(MediaRepository.Video item) {
        LinearLayout card = column();
        card.setClickable(true);
        card.setFocusable(true);
        int width = (getResources().getDisplayMetrics().widthPixels - dp(32) - dp(20)) / 3;
        int height = Math.max(dp(130), Math.round(width * 1.42f));
        ImageView image = posterImage(item.poster, width, height);
        card.addView(image, new LinearLayout.LayoutParams(-1, height));
        loadPoster(image, item.poster, width * 2, height * 2);
        TextView title = label(safe(item.title).isEmpty() ? "未命名作品" : item.title,
                13, PRIMARY, Typeface.BOLD);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(0, dp(7), 0, 0);
        card.addView(title, wrapParams(0, 3));
        TextView meta = label(metadataLine(item), 10, SECONDARY, Typeface.NORMAL);
        meta.setMaxLines(1);
        card.addView(meta, wrapParams(0, 0));
        card.setContentDescription(safe(item.title) + "，" + metadataLine(item));
        card.setOnClickListener(view -> showDetails(item));
        return card;
    }

    private LinearLayout episodeRow(MediaRepository.Video episode) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(9), dp(8), dp(10), dp(8));
        row.setBackground(roundBackground(SURFACE, 14));
        ImageView image = posterImage(episode.poster, dp(92), dp(62));
        row.addView(image, new LinearLayout.LayoutParams(dp(92), dp(62)));
        loadPoster(image, episode.poster, 260, 190);
        LinearLayout text = column();
        text.setPadding(dp(10), 0, 0, 0);
        String title = "第 " + (episode.episode > 0 ? episode.episode : "?") + " 集 · "
                + (safe(episode.title).isEmpty() ? "未命名分集" : episode.title);
        text.addView(label(title, 14, PRIMARY, Typeface.BOLD), wrapParams(0, 4));
        WatchStateStore.Entry state = entryFor(episode);
        String sub = state != null && state.completed ? "已看完"
                : state != null && state.positionMs > 0 ? "正在观看 · " + formatTime(state.positionMs)
                : safe(episode.subtitle).isEmpty() ? "可播放" : episode.subtitle;
        text.addView(label(sub, 11, SECONDARY, Typeface.NORMAL), wrapParams(0, 0));
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView arrow = label(state != null && state.completed ? "✓" : "▷", 18,
                state != null && state.completed ? SECONDARY : ACCENT, Typeface.BOLD);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(42), dp(48)));
        row.setContentDescription(title + "，" + sub);
        row.setOnClickListener(view -> {
            List<MediaRepository.Video> season = episodesForSeason(seasonKey(episode));
            openPlayback(episode, season, indexById(season, episode.id), true, "series");
        });
        return row;
    }

    private TextView missingEpisodeRow(int number) {
        TextView row = label("第 " + number + " 集 · 尚未入库\n不会自动跳过此集",
                13, MUTED, Typeface.NORMAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(16), 0);
        row.setBackground(roundBackground(Color.rgb(14, 18, 24), 14));
        row.setContentDescription("第 " + number + " 集尚未入库");
        return row;
    }

    private LinearLayout metadataRow(MediaRepository.Video item) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackground(roundBackground(SURFACE, 13));
        TextView icon = label("▤", 23, SECONDARY, Typeface.NORMAL);
        icon.setGravity(Gravity.CENTER);
        row.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(58)));
        LinearLayout text = column();
        text.setPadding(dp(9), 0, 0, 0);
        text.addView(label(safe(item.title).isEmpty() ? safe(item.id) : item.title,
                13, PRIMARY, Typeface.BOLD), wrapParams(0, 5));
        text.addView(label("资料缺失 · " + metadataMissingReason(item), 11, SECONDARY, Typeface.NORMAL));
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView view = label("查看  ›", 11, ACCENT, Typeface.BOLD);
        row.addView(view, new LinearLayout.LayoutParams(dp(62), dp(44)));
        row.setOnClickListener(v -> showDetails(item));
        return row;
    }

    private TextView menuRow(String icon, String title, String detail) {
        TextView row = label(icon + "   " + title + "\n                         " + detail,
                14, PRIMARY, Typeface.NORMAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), 0, dp(8), 0);
        row.setBackground(roundBackground(Color.TRANSPARENT, 0));
        row.setMinHeight(dp(58));
        return row;
    }

    private LinearLayout emptyBlock(String title, String detail) {
        LinearLayout block = column();
        block.setGravity(Gravity.CENTER_HORIZONTAL);
        block.setPadding(dp(22), dp(26), dp(22), dp(26));
        block.setBackground(roundBackground(SURFACE, 16));
        block.addView(label(title, 17, PRIMARY, Typeface.BOLD), wrapParams(0, 7));
        block.addView(label(detail, 12, SECONDARY, Typeface.NORMAL));
        return block;
    }

    private TextView skeletonBlock(String text) {
        TextView value = label(text, 14, SECONDARY, Typeface.NORMAL);
        value.setGravity(Gravity.CENTER);
        value.setMinHeight(dp(120));
        value.setBackground(roundBackground(SURFACE, 16));
        return value;
    }

    private TextView statCard(String count, String title) {
        TextView value = label(count + "\n" + title, 17, PRIMARY, Typeface.BOLD);
        value.setGravity(Gravity.CENTER_VERTICAL);
        value.setPadding(dp(16), 0, 0, 0);
        value.setBackground(roundBackground(SURFACE, 14));
        return value;
    }

    private TextView numberBlock(String count, String title) {
        TextView value = label(count + "\n" + title, 17, PRIMARY, Typeface.BOLD);
        value.setGravity(Gravity.CENTER_VERTICAL);
        value.setPadding(dp(8), 0, 0, 0);
        return value;
    }

    private TextView tabButton(String title, boolean selected) {
        TextView value = label(title, 14, selected ? PRIMARY : SECONDARY, selected ? Typeface.BOLD : Typeface.NORMAL);
        value.setGravity(Gravity.CENTER_VERTICAL);
        value.setPadding(dp(4), 0, dp(4), 0);
        return value;
    }

    private TextView chipButton(String title, boolean selected) {
        TextView value = label(title, 12, selected ? Color.rgb(9, 12, 17) : PRIMARY,
                selected ? Typeface.BOLD : Typeface.NORMAL);
        value.setGravity(Gravity.CENTER);
        value.setPadding(dp(14), 0, dp(14), 0);
        value.setBackground(roundBackground(selected ? ACCENT : SURFACE_2, 12));
        return value;
    }

    private TextView filterButton(String title, String kind, String selected) {
        boolean active = safe(kind).equalsIgnoreCase(safe(selected));
        TextView value = chipButton(title, active);
        value.setOnClickListener(view -> {
            currentKind = kind;
            searchMode = false;
            reloadCatalog();
        });
        return value;
    }

    private TextView actionButton(String title, int color) {
        TextView value = label(title, 13, color, Typeface.BOLD);
        value.setGravity(Gravity.CENTER);
        value.setMinHeight(dp(46));
        value.setPadding(dp(13), 0, dp(13), 0);
        value.setClickable(true);
        value.setFocusable(true);
        value.setBackground(roundBackground(Color.rgb(31, 35, 44), 14));
        return value;
    }

    private TextView iconButton(String title, String description) {
        TextView value = label(title, 21, PRIMARY, Typeface.BOLD);
        value.setGravity(Gravity.CENTER);
        value.setContentDescription(description);
        value.setClickable(true);
        value.setFocusable(true);
        value.setBackground(roundBackground(Color.rgb(16, 20, 27), 15));
        return value;
    }

    private TextView label(String value, float size, int color, int style) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setTypeface(Typeface.create("sans-serif", style));
        text.setIncludeFontPadding(false);
        return text;
    }

    private EditText searchField() {
        EditText value = new EditText(this);
        value.setSingleLine(true);
        value.setTextSize(14);
        value.setTextColor(PRIMARY);
        value.setHintTextColor(SECONDARY);
        value.setHint("搜索片名");
        value.setPadding(dp(14), 0, dp(14), 0);
        value.setBackground(roundBackground(SURFACE_2, 13));
        value.setContentDescription("搜索片名");
        return value;
    }

    private ImageView posterImage(String url, int width, int height) {
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackground(roundBackground(Color.rgb(27, 34, 44), 10));
        image.setContentDescription("海报");
        return image;
    }

    private void loadPoster(ImageView image, String url, int width, int height) {
        if (posterLoader == null || safe(url).isEmpty()) return;
        posterLoader.load(url, Math.max(120, width), Math.max(160, height), image::setImageBitmap);
    }

    private ScrollView pageScroll() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(16), 0, dp(16), dp(24));
        return scroll;
    }

    private LinearLayout column() {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.VERTICAL);
        return value;
    }

    private LinearLayout row() {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.HORIZONTAL);
        value.setGravity(Gravity.CENTER_VERTICAL);
        return value;
    }

    private void setPage(View page) {
        content.removeAllViews();
        content.addView(page, new FrameLayout.LayoutParams(-1, -1));
    }

    private LinearLayout.LayoutParams wrapParams(int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        params.bottomMargin = dp(bottom);
        return params;
    }

    private LinearLayout.LayoutParams compactParams() {
        return new LinearLayout.LayoutParams(dp(46), dp(46));
    }

    private LinearLayout.LayoutParams navParams() {
        return new LinearLayout.LayoutParams(0, -1, 1f);
    }

    private LinearLayout.LayoutParams filterParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(42), 1f);
        params.rightMargin = dp(7);
        return params;
    }

    private LinearLayout.LayoutParams statParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(86), 1f);
        params.rightMargin = dp(8);
        return params;
    }

    private LinearLayout.LayoutParams gridParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1f);
        params.rightMargin = dp(10);
        return params;
    }

    private TextView navTab(String text, String description) {
        TextView value = label(text, 12, SECONDARY, Typeface.NORMAL);
        value.setGravity(Gravity.CENTER);
        value.setContentDescription(description);
        value.setMinHeight(dp(58));
        return value;
    }

    private void markNav(TextView selected) {
        if (libraryTab == null) return;
        libraryTab.setTextColor(selected == libraryTab ? ACCENT : SECONDARY);
        watchTab.setTextColor(selected == watchTab ? ACCENT : SECONDARY);
        myTab.setTextColor(selected == myTab ? ACCENT : SECONDARY);
        bottomNav.setVisibility(selected == null ? View.GONE : View.VISIBLE);
    }

    private Dialog showDialog(View view) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(view);
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(380)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return dialog;
    }

    private LinearLayout dialogCard() {
        LinearLayout card = column();
        card.setPadding(dp(20), dp(18), dp(20), dp(16));
        card.setBackground(roundBackground(SURFACE, 20));
        return card;
    }

    private TextView dialogRow(String title, String subtitle) {
        TextView value = label(title + "\n" + subtitle, 14, PRIMARY, Typeface.NORMAL);
        value.setGravity(Gravity.CENTER_VERTICAL);
        value.setPadding(dp(12), 0, dp(12), 0);
        value.setBackground(roundBackground(SURFACE_2, 12));
        return value;
    }

    private void openManagement() {
        if (serverBase.isEmpty()) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(serverBase)));
        } catch (Exception error) {
            Toast.makeText(this, "无法打开飞牛管理页", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean missingMetadata(MediaRepository.Video item) {
        return item == null || safe(item.poster).isEmpty() || safe(item.overview).isEmpty();
    }

    private String metadataMissingReason(MediaRepository.Video item) {
        if (item == null) return "条目未知";
        if (safe(item.poster).isEmpty() && safe(item.overview).isEmpty()) return "缺少海报与简介";
        if (safe(item.poster).isEmpty()) return "缺少海报";
        return "缺少简介";
    }

    private String detailPlayLabel() {
        MediaRepository.Video resume = detailVideo == null ? null : findResumeEpisode(detailVideo);
        if (resume != null) return "▷ 继续播放 · " + episodeLabel(resume);
        return isSeries(detailVideo) ? "▷ 开始播放" : "▷ 播放影片";
    }

    private boolean isSeries(MediaRepository.Video item) {
        if (item == null) return false;
        String type = safe(item.type).toLowerCase(Locale.US);
        return type.contains("tv") || type.contains("series") || type.contains("show")
                || type.contains("season");
    }

    private String metadataLine(MediaRepository.Video item) {
        if (item == null) return "资料未知";
        ArrayList<String> values = new ArrayList<>();
        if (!safe(item.year).isEmpty()) values.add(item.year);
        if (!safe(item.type).isEmpty()) values.add(typeLabel(item.type));
        if (item.season > 0 && item.episode > 0) values.add(episodeLabel(item));
        if (values.isEmpty()) return "资料未知";
        return join(values, "  ·  ");
    }

    private String typeLabel(String type) {
        String value = safe(type).toLowerCase(Locale.US);
        if (value.contains("tv") || value.contains("series") || value.contains("show")) return "剧集";
        if (value.contains("movie")) return "电影";
        if (value.contains("episode")) return "分集";
        return type;
    }

    private WatchStateStore.Entry entryFor(MediaRepository.Video video) {
        if (watchStore == null || video == null) return null;
        for (WatchStateStore.Entry entry : watchStore.recent()) {
            if (entry != null && entry.video != null && safe(video.id).equals(safe(entry.video.id))) return entry;
        }
        return null;
    }

    private MediaRepository.Video findResumeEpisode(MediaRepository.Video series) {
        if (watchStore == null || series == null) return null;
        for (WatchStateStore.Entry entry : watchStore.recent()) {
            if (entry == null || entry.completed || entry.positionMs <= 0 || entry.video == null) continue;
            MediaRepository.Video video = entry.video;
            String seriesId = safe(video.seriesId);
            if (safe(series.id).equals(seriesId) || safe(series.id).equals(safe(video.parentId))) return video;
        }
        return null;
    }

    private List<MediaRepository.Video> episodesForSeason(String key) {
        ArrayList<MediaRepository.Video> values = new ArrayList<>();
        for (MediaRepository.Video episode : detailEpisodes) {
            if (seasonKey(episode).equals(key) && FeedPolicy.isPlayable(episode)) values.add(episode);
        }
        values.sort(episodeComparator());
        return values;
    }

    private List<String> seasonKeys(List<MediaRepository.Video> values) {
        LinkedHashMap<String, Boolean> keys = new LinkedHashMap<>();
        for (MediaRepository.Video value : values) keys.put(seasonKey(value), true);
        ArrayList<String> result = new ArrayList<>(keys.keySet());
        result.sort((a, b) -> Integer.compare(seasonNumber(a), seasonNumber(b)));
        return result;
    }

    private String firstSeasonKey(List<MediaRepository.Video> values) {
        List<String> keys = seasonKeys(values);
        return keys.isEmpty() ? "" : keys.get(0);
    }

    private String seasonKey(MediaRepository.Video value) {
        if (value == null) return "unknown";
        if (!safe(value.seasonId).isEmpty()) return "id:" + value.seasonId;
        return "number:" + value.season;
    }

    private int seasonNumber(String key) {
        if (key == null) return Integer.MAX_VALUE;
        int index = key.lastIndexOf(':');
        try { return Integer.parseInt(index < 0 ? key : key.substring(index + 1)); }
        catch (NumberFormatException ignored) { return Integer.MAX_VALUE; }
    }

    private String seasonLabel(String key, List<MediaRepository.Video> values) {
        for (MediaRepository.Video value : values) {
            if (seasonKey(value).equals(key)) return value.season > 0 ? "第 " + value.season + " 季" : "特别篇";
        }
        return "顺序待确认";
    }

    private Comparator<MediaRepository.Video> episodeComparator() {
        return Comparator.comparingInt((MediaRepository.Video value) -> value.season)
                .thenComparingInt(value -> value.episode)
                .thenComparing(value -> safe(value.id));
    }

    private int indexById(List<MediaRepository.Video> values, String id) {
        for (int i = 0; i < values.size(); i++) if (safe(id).equals(safe(values.get(i).id))) return i;
        return 0;
    }

    private String episodeLabel(MediaRepository.Video value) {
        if (value == null) return "分集";
        if (value.season > 0 && value.episode > 0) return "第 " + value.season + " 季 · 第 " + value.episode + " 集";
        if (value.episode > 0) return "第 " + value.episode + " 集";
        return "顺序待确认";
    }

    private String formatTime(long millis) {
        if (millis <= 0) return "00:00";
        long seconds = millis / 1000L;
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long rest = seconds % 60L;
        return hours > 0 ? String.format(Locale.US, "%d:%02d:%02d", hours, minutes, rest)
                : String.format(Locale.US, "%02d:%02d", minutes, rest);
    }

    private String join(List<String> values, String delimiter) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(delimiter);
            result.append(value);
        }
        return result.toString();
    }

    private void showKeyboard(View view) {
        view.post(() -> {
            InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (manager != null) manager.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private String errorReason(Throwable error) {
        Throwable current = error;
        for (int i = 0; current != null && i < 10; i++, current = current.getCause()) {
            String message = safe(current.getMessage());
            if (!message.isEmpty()) return message.length() > 180 ? message.substring(0, 180) : message;
        }
        return "服务端未提供详细错误";
    }

    private static void cancel(Future<?> request) {
        if (request != null && !request.isDone()) request.cancel(true);
    }

    private boolean destroyed() {
        return isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed());
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private GradientDrawable roundBackground(int fill, int radius) {
        GradientDrawable value = new GradientDrawable();
        value.setColor(fill);
        value.setCornerRadius(dp(radius));
        value.setStroke(dp(1), Color.argb(55, 255, 255, 255));
        return value;
    }

    @Override
    public void onBackPressed() {
        if (detailVideo != null) {
            returnFromDetail();
            return;
        }
        if (searchMode) {
            searchMode = false;
            showLibraryPage();
            return;
        }
        super.onBackPressed();
    }

    private void returnFromDetail() {
        clearDetailContext();
        detailVideo = null;
        detailEpisodes.clear();
        searchMode = detailReturnSearchMode;
        if (detailReturnSection == 1) showWatchPage();
        else if (detailReturnSection == 2) showMyPage();
        else if (searchMode) showSearchPage();
        else showLibraryPage();
    }

    private void clearDetailContext() {
        detailGeneration++;
        cancel(detailRequest);
        detailVideo = null;
        detailEpisodes.clear();
    }

    @Override
    protected void onDestroy() {
        cancel(catalogRequest);
        cancel(detailRequest);
        cancel(libraryRequest);
        mainHandler.removeCallbacksAndMessages(null);
        networkExecutor.shutdownNow();
        if (posterLoader != null) posterLoader.shutdown();
        super.onDestroy();
    }
}
