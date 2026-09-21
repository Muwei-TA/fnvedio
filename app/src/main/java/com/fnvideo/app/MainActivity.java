package com.fnvideo.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Native cinema feed. The Activity coordinates page state and one shared
 * ExoPlayer; FnApi remains the only boundary for NAS data and playback URLs.
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class MainActivity extends Activity implements FeedAdapter.Listener {
    private static final int REQUEST_LOGIN = 1401;
    private static final int BG = Color.rgb(8, 10, 14);
    private static final int PRIMARY = Color.rgb(246, 247, 249);
    private static final int SECONDARY = Color.rgb(174, 181, 193);
    private static final int ACCENT = Color.rgb(255, 183, 77);
    private static final int CARD = Color.rgb(20, 23, 30);
    private static final long RESUME_MIN_MS = 3_000L;
    private static final long RESUME_END_MARGIN_MS = 5_000L;
    private static final int MAX_HOLDER_BIND_ATTEMPTS = 20;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable progressTicker = this::updateActiveProgress;
    private final PlaybackSession playbackSession = new PlaybackSession();

    private ExecutorService networkExecutor;
    private Future<?> libraryRequest;
    private Future<?> librariesRequest;
    private Future<?> playbackRequest;
    private boolean compatibilityAttempted;
    private ExoPlayer player;
    private SharedPreferences resumePreferences;
    private MediaRepository repository;
    private final FeedState feedState = new FeedState();

    private FrameLayout root;
    private ViewPager2 pager;
    private FeedAdapter adapter;
    private PosterLoader posterLoader;
    private FrameLayout messageOverlay;
    private ProgressBar messageSpinner;
    private TextView messageTitle;
    private TextView messageDetail;
    private TextView messageAction;
    private TextView libraryButton;
    private TextView searchButton;
    private TextView settingsButton;
    private TextView paginationAction;

    private FeedAdapter.VideoViewHolder activeHolder;
    private int activePosition = RecyclerView.NO_POSITION;
    private long libraryGeneration;
    private boolean loadingNextPage;
    private boolean firstPagePending;
    private String serverBase = "";
    private String legacyResumeBase = "";
    private String sessionToken = "";
    private String currentQuery = "";
    private String currentLibraryId = "";
    private String currentLibraryTitle = "";
    private List<MediaRepository.Library> availableLibraries = Collections.emptyList();
    private boolean zoomMode;
    private boolean wasPlayingBeforePause;
    private boolean wasPlayingBeforeSeek;
    private boolean userPaused;
    private boolean foreground;
    private boolean destroyed;
    private long lastSavedPosition = -1L;
    private Runnable messageActionRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        networkExecutor = Executors.newFixedThreadPool(2);
        resumePreferences = getSharedPreferences("resume_positions", MODE_PRIVATE);
        restoreSession();
        refreshRepository();
        posterLoader = new PosterLoader();
        buildUi();
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        player = new ExoPlayer.Builder(this)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                .setHandleAudioBecomingNoisy(true)
                .build();
        player.addListener(createPlayerListener());

        if (hasSession()) {
            loadLibrary("");
        } else {
            showSignedOut();
        }
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.BLACK);
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            root.setPadding(0, top, 0, bottom);
            return insets;
        });

        adapter = new FeedAdapter(this, this, posterLoader);
        pager = new ViewPager2(this);
        pager.setOrientation(ViewPager2.ORIENTATION_VERTICAL);
        pager.setUserInputEnabled(true);
        pager.setOffscreenPageLimit(1);
        pager.setAdapter(adapter);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                if (pager.getScrollState() == ViewPager2.SCROLL_STATE_IDLE) activatePage(position);
            }

            @Override
            public void onPageScrollStateChanged(int state) {
                if (state == ViewPager2.SCROLL_STATE_IDLE) activatePage(pager.getCurrentItem());
            }
        });
        root.addView(pager, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        buildMessageOverlay();
        buildTopBar();
        paginationAction = pillButton("", ACCENT);
        paginationAction.setVisibility(View.GONE);
        FrameLayout.LayoutParams paginationParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        paginationParams.topMargin = dp(70);
        root.addView(paginationAction, paginationParams);
        setContentView(root);
        root.requestApplyInsets();
    }

    private void buildTopBar() {
        LinearLayout topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(14), dp(8), dp(12), dp(6));
        GradientDrawable topBackground = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(230, 5, 7, 10), Color.TRANSPARENT});
        topBar.setBackground(topBackground);
        topBar.setElevation(dp(4));
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(66), Gravity.TOP);
        root.addView(topBar, topParams);

        TextView brand = label("牛影 · 随看", 19, PRIMARY, Typeface.BOLD);
        brand.setContentDescription("牛影随看");
        topBar.addView(brand, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        libraryButton = iconButton("库", "打开媒体库");
        searchButton = iconButton("⌕", "搜索视频");
        settingsButton = iconButton("⚙", "设置连接");
        topBar.addView(libraryButton, buttonParams());
        topBar.addView(searchButton, buttonParams());
        topBar.addView(settingsButton, buttonParams());

        libraryButton.setOnClickListener(v -> showLibraryDialog());
        searchButton.setOnClickListener(v -> showSearchDialog());
        settingsButton.setOnClickListener(v -> showSettingsDialog());
    }

    private void buildMessageOverlay() {
        messageOverlay = new FrameLayout(this);
        messageOverlay.setBackgroundColor(Color.argb(246, 8, 10, 14));
        messageOverlay.setVisibility(View.GONE);
        root.addView(messageOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(28), dp(28), dp(28), dp(24));
        card.setBackground(roundBackground(Color.argb(215, 20, 23, 30), 24));
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                dp(330), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        messageOverlay.addView(card, cardParams);

        TextView mark = label("牛影 · 随看", 24, PRIMARY, Typeface.BOLD);
        mark.setGravity(Gravity.CENTER);
        card.addView(mark, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        messageTitle = label("", 18, PRIMARY, Typeface.BOLD);
        messageTitle.setGravity(Gravity.CENTER);
        messageTitle.setPadding(0, dp(22), 0, 0);
        card.addView(messageTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        messageDetail = label("", 14, SECONDARY, Typeface.NORMAL);
        messageDetail.setGravity(Gravity.CENTER);
        messageDetail.setMaxLines(6);
        messageDetail.setPadding(0, dp(10), 0, dp(18));
        card.addView(messageDetail, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        messageSpinner = new ProgressBar(this);
        messageSpinner.setIndeterminate(true);
        messageSpinner.setVisibility(View.GONE);
        card.addView(messageSpinner, new LinearLayout.LayoutParams(dp(42), dp(42)));

        messageAction = pillButton("", ACCENT);
        messageAction.setVisibility(View.GONE);
        messageAction.setOnClickListener(v -> {
            if (messageActionRunnable != null) {
                messageActionRunnable.run();
            }
        });
        card.addView(messageAction, new LinearLayout.LayoutParams(dp(190), dp(48)));
    }

    private Player.Listener createPlayerListener() {
        return new Player.Listener() {
            @Override
            public void onTracksChanged(@NonNull Tracks tracks) {
                if (!hasLoadedPlayback() || !PlaybackCompatibility.hasUnsupportedAudio(tracks)) {
                    return;
                }
                if (!tryCompatiblePlayback()) {
                    saveResumePosition(false);
                    stopPlayer();
                    activeHolder.showError("当前设备无法播放此音轨，兼容播放也未成功。请检查 NAS 转码能力或换一部影片。");
                }
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                FeedAdapter.VideoViewHolder holder = activeHolder;
                if (holder == null || !hasLoadedPlayback()) {
                    return;
                }
                if (state == Player.STATE_BUFFERING) {
                    holder.showBuffering();
                } else if (state == Player.STATE_READY) {
                    holder.showReady();
                    holder.setSeekEnabled(player.getDuration() > 0 && player.getDuration() != C.TIME_UNSET);
                } else if (state == Player.STATE_ENDED) {
                    holder.setPlaying(false);
                    saveResumePosition(true);
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                FeedAdapter.VideoViewHolder holder = activeHolder;
                if (holder != null && hasLoadedPlayback()) {
                    holder.setPlaying(player.getPlayWhenReady()
                            && player.getPlaybackState() != Player.STATE_ENDED);
                }
            }

            @Override
            public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                if (activeHolder != null && hasLoadedPlayback()) {
                    activeHolder.setPlaying(playWhenReady
                            && player.getPlaybackState() != Player.STATE_ENDED);
                }
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) {
                    userPaused = true;
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                FeedAdapter.VideoViewHolder holder = activeHolder;
                if (holder == null || !hasLoadedPlayback()) {
                    return;
                }
                if ((isDecodingError(error)
                        || error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)
                        && tryCompatiblePlayback()) {
                    return;
                }
                holder.showError(actionablePlaybackError(error));
            }
        };
    }

    private boolean tryCompatiblePlayback() {
        if (compatibilityAttempted || destroyed || !hasLoadedPlayback()
                || activeHolder == null || activePosition == RecyclerView.NO_POSITION) {
            return false;
        }
        compatibilityAttempted = true;
        long position = Math.max(0L, player.getCurrentPosition());
        preparePlayback(activePosition, activeHolder, true, position);
        return true;
    }

    private void restoreSession() {
        legacyResumeBase = safe(SessionStore.base(this));
        try {
            serverBase = ServerAddress.normalize(legacyResumeBase);
            sessionToken = safe(SessionStore.token(this));
        } catch (IllegalArgumentException invalidAddress) {
            SessionStore.reset(this);
            serverBase = "";
            sessionToken = "";
            legacyResumeBase = "";
        }
    }

    private boolean hasSession() {
        return !serverBase.isEmpty() && !sessionToken.isEmpty();
    }

    private void refreshRepository() {
        repository = hasSession() ? new FnApi(serverBase, sessionToken) : null;
    }

    private void loadLibrary(String query) {
        if (!hasSession()) {
            showSignedOut();
            return;
        }
        currentQuery = safe(query);
        saveResumePosition(false);
        libraryGeneration = feedState.reset();
        loadingNextPage = false;
        firstPagePending = true;
        cancel(libraryRequest);
        cancel(librariesRequest);
        cancel(playbackRequest);
        detachPlayer();
        activePosition = RecyclerView.NO_POSITION;
        activeHolder = null;
        adapter.setVideos(Collections.emptyList());
        pager.setVisibility(View.GONE);
        paginationAction.setVisibility(View.GONE);
        setBrowseButtonsEnabled(false);
        showMessage("正在连接片库", "正在从 NAS 获取真实媒体列表，请稍候。", true,
                null, null);
        requestNextPage(libraryGeneration);
    }

    private void requestNextPage(long generation) {
        if (destroyed || generation != feedState.generation() || loadingNextPage
                || !feedState.hasMore() || repository == null) {
            return;
        }
        loadingNextPage = true;
        paginationAction.setVisibility(View.GONE);
        final String cursor = feedState.nextCursor();
        final String requestQuery = currentQuery;
        final String requestLibraryId = currentLibraryId;
        final MediaRepository requestRepository = repository;
        libraryRequest = networkExecutor.submit(() -> {
            try {
                MediaRepository.Page page = requestRepository.page(
                        new MediaRepository.Query(requestQuery, requestLibraryId), cursor);
                postIfCurrentLibrary(generation, () -> onPageLoaded(generation, page));
            } catch (Exception error) {
                postIfCurrentLibrary(generation, () -> onLibraryFailed(error));
            }
        });
    }

    private void postIfCurrentLibrary(long generation, Runnable action) {
        mainHandler.post(() -> {
            if (!destroyed && generation == libraryGeneration
                    && generation == feedState.generation()) {
                action.run();
            }
        });
    }

    private void onPageLoaded(long generation, MediaRepository.Page page) {
        loadingNextPage = false;
        try {
            if (!feedState.append(generation, page)) {
                return;
            }
        } catch (IllegalArgumentException | IllegalStateException invalidPage) {
            onLibraryFailed(invalidPage);
            return;
        }
        List<MediaRepository.Video> values = feedState.items();
        if (firstPagePending) {
            firstPagePending = false;
            adapter.setVideos(values);
        } else if (values.size() > adapter.getItemCount()) {
            adapter.appendVideos(values.subList(adapter.getItemCount(), values.size()));
        }
        if (values.isEmpty()) {
            setBrowseButtonsEnabled(true);
            if (feedState.shouldAutoLoad(0)) {
                showMessage("正在继续加载片库", "当前页没有可播放条目，正在请求下一页。",
                        true, null, null);
                requestNextPage(generation);
            } else if (feedState.hasMore()) {
                showMessage("暂未发现可播放视频", "连续多页没有新视频，已暂停自动加载。",
                        false, "继续加载", this::continuePagination);
            } else {
                pager.setVisibility(View.GONE);
                showMessage("片库暂无结果", currentQuery.isEmpty()
                                ? "服务端返回了空片库。请确认账号有媒体权限。"
                                : "没有匹配“" + currentQuery + "”的媒体。",
                        false, "重新加载", () -> loadLibrary(currentQuery));
            }
            return;
        }
        hideMessage();
        pager.setVisibility(View.VISIBLE);
        if (activePosition == RecyclerView.NO_POSITION) {
            pager.setCurrentItem(0, false);
            pager.post(() -> {
                if (!destroyed && generation == libraryGeneration && pager.getCurrentItem() == 0) {
                    activatePage(0);
                }
            });
        }
        int position = Math.max(0, activePosition);
        if (feedState.shouldAutoLoad(position)) {
            requestNextPage(generation);
        } else if (feedState.hasMore() && feedState.isNearEnd(position)) {
            showPaginationAction("后续页面暂无新视频，点此继续加载", this::continuePagination);
        }
    }

    private void continuePagination() {
        feedState.restartEmptyPageScan();
        if (adapter.getItemCount() == 0) {
            showMessage("正在继续加载片库", "正在请求后续页面。", true, null, null);
        }
        requestNextPage(libraryGeneration);
    }

    private void showPaginationAction(String text, Runnable action) {
        paginationAction.setText(text);
        paginationAction.setPadding(dp(14), 0, dp(14), 0);
        paginationAction.setOnClickListener(view -> action.run());
        paginationAction.setVisibility(View.VISIBLE);
    }

    private void onLibraryFailed(Exception error) {
        loadingNextPage = false;
        if (handleAuthenticationFailure(error)) {
            return;
        }
        setBrowseButtonsEnabled(hasSession());
        String message = actionableLibraryError(error);
        if (adapter.getItemCount() == 0) {
            pager.setVisibility(View.GONE);
            showMessage("片库加载失败", message, false, "重新加载",
                    () -> loadLibrary(currentQuery));
        } else {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            showPaginationAction("后续页面加载失败，点此重试", this::continuePagination);
        }
    }

    private void activatePage(int position) {
        if (destroyed || firstPagePending || !hasSession()
                || position < 0 || position >= adapter.getItemCount()
                || position != pager.getCurrentItem()
                || pager.getScrollState() != ViewPager2.SCROLL_STATE_IDLE) {
            return;
        }
        maybeLoadNextPage(position);
        if (position == activePosition && activeHolder != null) {
            return;
        }
        saveResumePosition(false);
        cancel(playbackRequest);
        detachPlayer();
        activePosition = position;
        compatibilityAttempted = false;
        activeHolder = null;
        userPaused = false;
        lastSavedPosition = -1L;
        PlaybackSession.Ticket ticket = playbackSession.select(
                serverBase, adapter.getVideo(position).id, libraryGeneration);
        pager.post(() -> bindSelectedPage(position, ticket, 0));
    }

    private void bindSelectedPage(int position, PlaybackSession.Ticket ticket, int attempt) {
        if (destroyed || !playbackSession.isCurrent(ticket)
                || ticket.feedGeneration != libraryGeneration || activePosition != position
                || pager.getCurrentItem() != position
                || pager.getScrollState() != ViewPager2.SCROLL_STATE_IDLE) {
            return;
        }
        FeedAdapter.VideoViewHolder holder = findHolder(position);
        if (holder == null || holder.getVideo() == null || !ticket.videoId.equals(holder.getVideo().id)) {
            if (attempt < MAX_HOLDER_BIND_ATTEMPTS) {
                pager.postDelayed(() -> bindSelectedPage(position, ticket, attempt + 1), 80L);
            } else {
                showMessage("播放页面尚未就绪", "请重试或切换媒体库。", false, "重试", () -> {
                    hideMessage();
                    activatePage(pager.getCurrentItem());
                });
            }
            return;
        }
        activeHolder = holder;
        preparePlayback(position, holder, false);
    }

    private void maybeLoadNextPage(int position) {
        if (feedState.shouldAutoLoad(position)) {
            requestNextPage(libraryGeneration);
        }
    }

    private FeedAdapter.VideoViewHolder findHolder(int position) {
        View child = pager.getChildAt(0);
        if (!(child instanceof RecyclerView)) {
            return null;
        }
        RecyclerView recycler = (RecyclerView) child;
        RecyclerView.ViewHolder holder = recycler.findViewHolderForAdapterPosition(position);
        return holder instanceof FeedAdapter.VideoViewHolder
                ? (FeedAdapter.VideoViewHolder) holder : null;
    }

    private void preparePlayback(int position, FeedAdapter.VideoViewHolder holder, boolean compatible) {
        saveResumePosition(false);
        preparePlayback(position, holder, compatible, readResumePosition(adapter.getVideo(position)));
    }

    private void preparePlayback(int position, FeedAdapter.VideoViewHolder holder,
                                 boolean compatible, long resumePosition) {
        MediaRepository.Video video = adapter.getVideo(position);
        MediaRepository requestRepository = repository;
        if (destroyed || holder != activeHolder || position != activePosition
                || video == null || !hasSession() || requestRepository == null) {
            return;
        }
        saveResumePosition(false);
        cancel(playbackRequest);
        final PlaybackSession.Ticket ticket = playbackSession.select(serverBase, video.id, libraryGeneration);
        stopPlayer();
        holder.setSeeking(false);
        holder.setSeekEnabled(false);
        holder.showLoading();
        holder.setFitMode(zoomMode);
        playbackRequest = networkExecutor.submit(() -> {
            try {
                MediaRepository.Source source = compatible
                        ? requestRepository.resolveCompatible(video) : requestRepository.resolve(video);
                mainHandler.post(() -> {
                    if (isCurrentPlayback(ticket, position, holder, requestRepository)) {
                        attachPlayback(holder, source, resumePosition, ticket);
                    }
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (isCurrentPlayback(ticket, position, holder, requestRepository)
                            && !handleAuthenticationFailure(error)) {
                        holder.showError(actionablePlaybackError(error));
                    }
                });
            }
        });
    }

    private boolean isCurrentPlayback(PlaybackSession.Ticket ticket, int position,
                                      FeedAdapter.VideoViewHolder holder, MediaRepository requestRepository) {
        return !destroyed && playbackSession.isCurrent(ticket)
                && ticket.feedGeneration == libraryGeneration && position == activePosition
                && activeHolder == holder && holder.getVideo() != null
                && ticket.videoId.equals(holder.getVideo().id) && repository == requestRepository;
    }

    private void attachPlayback(FeedAdapter.VideoViewHolder holder, MediaRepository.Source source,
                                long resumePosition, PlaybackSession.Ticket ticket) {
        try {
            if (activeHolder != holder || !playbackSession.isCurrent(ticket)) {
                return;
            }
            // Both transport credentials and resume ownership use the captured server identity.
            MediaItem.Builder itemBuilder = new MediaItem.Builder()
                    .setMediaId(ticket.videoId).setUri(Uri.parse(source.url));
            if (!safe(source.mimeType).isEmpty()) {
                itemBuilder.setMimeType(source.mimeType);
            }
            MediaItem item = itemBuilder.build();
            player.setMediaSource(new DefaultMediaSourceFactory(
                    PlaybackDataSource.forSource(source, ticket.serverOrigin)).createMediaSource(item));
            if (resumePosition > 0) {
                player.seekTo(resumePosition);
            }
            playbackSession.markLoaded(ticket);
            holder.playerView.setPlayer(player);
            holder.setFitMode(zoomMode);
            holder.setSeekEnabled(false);
            holder.showLoading();
            player.prepare();
            if (foreground && !userPaused) {
                player.play();
            }
        } catch (Exception error) {
            stopPlayer();
            holder.showError(actionablePlaybackError(error));
        }
    }

    private boolean hasLoadedPlayback() {
        PlaybackSession.Ticket loaded = playbackSession.loaded();
        MediaItem item = player == null ? null : player.getCurrentMediaItem();
        return loaded != null && item != null && loaded.videoId.equals(item.mediaId);
    }

    private void stopPlayer() {
        // stop() alone retains the previous timeline/position and playWhenReady.
        playbackSession.clearLoaded();
        if (player != null) {
            player.pause();
            player.stop();
            player.clearMediaItems();
        }
    }

    private void detachPlayer() {
        playbackSession.invalidate();
        if (activeHolder != null && player != null) {
            activeHolder.playerView.setPlayer(null);
        }
        stopPlayer();
    }

    private void updateActiveProgress() {
        if (destroyed || !foreground) {
            return;
        }
        if (activeHolder != null && hasLoadedPlayback()) {
            long position = player.getCurrentPosition();
            long duration = player.getDuration();
            if (activeHolder.isSeeking()) {
                activeHolder.showSeekPreview(activeHolder.itemView.getTag() instanceof Integer
                        ? (Integer) activeHolder.itemView.getTag() : 0, duration);
            } else {
                activeHolder.updateProgress(position, duration);
            }
            if (player.isPlaying() && Math.abs(position - lastSavedPosition) >= 5_000L) {
                saveResumePosition(false);
            }
        }
        mainHandler.postDelayed(progressTicker, 500L);
    }

    private void saveResumePosition(boolean ended) {
        if (!hasLoadedPlayback()) {
            return;
        }
        PlaybackSession.Ticket loaded = playbackSession.loaded();
        long position = player.getCurrentPosition();
        long duration = player.getDuration();
        String key = loaded.resumeKey();
        if (ended || (duration > 0 && duration != C.TIME_UNSET
                && position >= duration - RESUME_END_MARGIN_MS)) {
            resumePreferences.edit().remove(key).apply();
            lastSavedPosition = -1L;
            return;
        }
        if (position >= RESUME_MIN_MS) {
            resumePreferences.edit().putLong(key, position).apply();
            lastSavedPosition = position;
        } else {
            resumePreferences.edit().remove(key).apply();
            lastSavedPosition = position;
        }
    }

    private long readResumePosition(MediaRepository.Video video) {
        if (video == null || safe(video.id).isEmpty()) {
            return 0L;
        }
        String key = "position:" + serverBase + ":" + video.id;
        if (!resumePreferences.contains(key) && !legacyResumeBase.equals(serverBase)
                && ServerAddress.sameOrigin(legacyResumeBase, serverBase)) {
            String legacyKey = "position:" + legacyResumeBase + ":" + video.id;
            if (resumePreferences.contains(legacyKey)) {
                long position = resumePreferences.getLong(legacyKey, 0L);
                resumePreferences.edit().putLong(key, position).remove(legacyKey).apply();
            }
        }
        return resumePreferences.getLong(key, 0L);
    }

    @Override
    public void onPageTapped(FeedAdapter.VideoViewHolder holder) {
        if (holder != activeHolder || !hasLoadedPlayback() || player.getPlayerError() != null) {
            return;
        }
        if (player.getPlayWhenReady()) {
            player.pause();
            userPaused = true;
        } else {
            if (player.getPlaybackState() == Player.STATE_ENDED) {
                player.seekTo(0L);
            }
            userPaused = false;
            if (foreground) player.play();
        }
    }

    @Override
    public void onFitToggle(FeedAdapter.VideoViewHolder holder) {
        if (holder != activeHolder) {
            return;
        }
        zoomMode = !zoomMode;
        holder.setFitMode(zoomMode);
    }

    @Override
    public void onRetry(FeedAdapter.VideoViewHolder holder) {
        if (holder == activeHolder && activePosition != RecyclerView.NO_POSITION) {
            preparePlayback(activePosition, holder, compatibilityAttempted);
        }
    }

    @Override
    public void onSeekStart(FeedAdapter.VideoViewHolder holder) {
        if (holder != activeHolder || !hasLoadedPlayback()) {
            return;
        }
        holder.setSeeking(true);
        wasPlayingBeforeSeek = player.getPlayWhenReady();
        player.pause();
    }

    @Override
    public void onSeekChanged(FeedAdapter.VideoViewHolder holder, int progress) {
        if (holder == activeHolder && hasLoadedPlayback()) {
            holder.showSeekPreview(progress, player.getDuration());
            holder.itemView.setTag(progress);
        }
    }

    @Override
    public void onSeekStop(FeedAdapter.VideoViewHolder holder, int progress) {
        if (holder != activeHolder || !hasLoadedPlayback()) {
            return;
        }
        long duration = player.getDuration();
        if (duration > 0 && duration != C.TIME_UNSET) {
            player.seekTo(duration * progress / 1000L);
            saveResumePosition(false);
        }
        if (wasPlayingBeforeSeek && foreground) {
            userPaused = false;
            player.play();
        }
        holder.setPlaying(player.getPlayWhenReady()
                && player.getPlaybackState() != Player.STATE_ENDED);
        holder.setSeeking(false);
    }

    private static final float HORIZONTAL_SEEK_MS_PER_PX = 500f;
    private static final float SPEED_PLAYBACK_RATE = 2.0f;
    private boolean horizontalSeekStarted;
    private boolean wasPlayingBeforeSpeed;

    @Override
    public void onHorizontalSeekStart(FeedAdapter.VideoViewHolder holder) {
        if (holder != activeHolder || !hasLoadedPlayback()) {
            return;
        }
        horizontalSeekStarted = true;
        holder.setSeeking(true);
        wasPlayingBeforeSeek = player.getPlayWhenReady();
        player.pause();
    }

    @Override
    public void onHorizontalSeek(FeedAdapter.VideoViewHolder holder, float deltaPx) {
        if (holder != activeHolder || !hasLoadedPlayback() || !horizontalSeekStarted) {
            return;
        }
        long duration = player.getDuration();
        if (duration <= 0 || duration == C.TIME_UNSET) {
            return;
        }
        long deltaMs = (long) (deltaPx * HORIZONTAL_SEEK_MS_PER_PX);
        long target = Math.min(duration - 250L, Math.max(0L,
                player.getCurrentPosition() + deltaMs - (long) (lastHorizontalSeekPx * HORIZONTAL_SEEK_MS_PER_PX)));
        lastHorizontalSeekPx = deltaPx;
        player.seekTo(target);
        holder.showSeekPreview((int) Math.min(1000L, target * 1000L / duration), duration);
        holder.itemView.setTag((int) Math.min(1000L, target * 1000L / duration));
    }

    @Override
    public void onHorizontalSeekEnd(FeedAdapter.VideoViewHolder holder) {
        if (holder != activeHolder) {
            horizontalSeekStarted = false;
            lastHorizontalSeekPx = 0f;
            return;
        }
        boolean started = horizontalSeekStarted;
        horizontalSeekStarted = false;
        lastHorizontalSeekPx = 0f;
        if (!hasLoadedPlayback()) {
            return;
        }
        if (started) {
            saveResumePosition(false);
        }
        if (wasPlayingBeforeSeek && foreground) {
            userPaused = false;
            player.play();
        }
        holder.setPlaying(player.getPlayWhenReady()
                && player.getPlaybackState() != Player.STATE_ENDED);
        holder.setSeeking(false);
    }

    @Override
    public void onSpeedPressStart(FeedAdapter.VideoViewHolder holder) {
        if (holder != activeHolder || !hasLoadedPlayback()
                || player.getPlaybackState() != Player.STATE_READY) {
            return;
        }
        wasPlayingBeforeSpeed = player.getPlayWhenReady();
        player.setPlaybackSpeed(SPEED_PLAYBACK_RATE);
        holder.showSpeedIndicator(SPEED_PLAYBACK_RATE);
        if (!player.getPlayWhenReady() && foreground) {
            player.play();
        }
    }

    @Override
    public void onSpeedPressEnd(FeedAdapter.VideoViewHolder holder) {
        if (holder != activeHolder || !hasLoadedPlayback()) {
            return;
        }
        player.setPlaybackSpeed(1.0f);
        holder.hideSpeedIndicator();
        if (!wasPlayingBeforeSpeed && foreground) {
            player.pause();
        }
    }

    @Override
    public void onEpisodesBrowse(FeedAdapter.VideoViewHolder holder) {
        if (holder != activeHolder || !hasSession()) {
            return;
        }
        MediaRepository.Video current = holder.getVideo();
        String parentId = current == null ? "" : safe(current.parentId);
        if (parentId.isEmpty()) {
            Toast.makeText(this, "此视频没有所属剧集信息", Toast.LENGTH_SHORT).show();
            return;
        }
        showEpisodesDialog(parentId, current);
    }

    private void showEpisodesDialog(String parentId, MediaRepository.Video current) {
        LinearLayout card = dialogCard();
        addDialogTitle(card, "剧集目录");
        TextView status = label("正在从 NAS 读取分集列表…", 13, SECONDARY, Typeface.NORMAL);
        card.addView(status, wrapParams(0, dp(10)));
        ScrollView scroll = new ScrollView(this);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(320)));
        TextView close = dialogButton("关闭", SECONDARY);
        card.addView(close, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        Dialog dialog = showDialog(card, false);
        close.setOnClickListener(v -> dialog.dismiss());

        final MediaRepository requestRepository = repository;
        if (requestRepository == null) {
            status.setText("当前没有可用连接，请重新登录。");
            return;
        }
        final long generation = libraryGeneration;
        cancel(librariesRequest);
        librariesRequest = networkExecutor.submit(() -> {
            try {
                List<MediaRepository.Video> episodes = requestRepository.seriesEpisodes(
                        episodeParent(parentId, current));
                mainHandler.post(() -> {
                    if (destroyed || !dialog.isShowing()
                            || repository != requestRepository || generation != libraryGeneration) {
                        return;
                    }
                    renderEpisodeRows(dialog, rows, episodes, current);
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (!destroyed && dialog.isShowing()
                            && repository == requestRepository && generation == libraryGeneration) {
                        if (RepositoryFailure.requiresLogin(error)) {
                            dialog.dismiss();
                            handleAuthenticationFailure(error);
                            return;
                        }
                        status.setText("分集读取失败：" + errorReason(error) + "。请重试。");
                    }
                });
            }
        });
    }

    private MediaRepository.Video episodeParent(String parentId, MediaRepository.Video current) {
        MediaRepository.Video parent = new MediaRepository.Video();
        parent.id = parentId;
        parent.title = current == null ? "" : safe(current.title);
        return parent;
    }

    private void renderEpisodeRows(Dialog dialog, LinearLayout rows,
                                   List<MediaRepository.Video> episodes, MediaRepository.Video current) {
        rows.removeAllViews();
        if (episodes == null || episodes.isEmpty()) {
            return;
        }
        String currentId = current == null ? "" : safe(current.id);
        for (MediaRepository.Video episode : episodes) {
            if (episode == null || !FeedPolicy.isPlayable(episode)) {
                continue;
            }
            String episodeId = safe(episode.id);
            String label = episodeLabel(episode);
            String subtitle = episodeId.equals(currentId) ? "正在播放" : "点击切换";
            LinearLayout row = dialogRow(label, subtitle);
            row.setOnClickListener(v -> {
                dialog.dismiss();
                jumpToEpisode(episode);
            });
            rows.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(60)));
        }
    }

    private String episodeLabel(MediaRepository.Video episode) {
        StringBuilder label = new StringBuilder();
        if (episode.season > 0) {
            label.append("第 ").append(episode.season).append(" 季");
        }
        if (episode.episode > 0) {
            if (label.length() > 0) {
                label.append(" · ");
            }
            label.append("第 ").append(episode.episode).append(" 集");
        }
        String title = safe(episode.title);
        if (!title.isEmpty()) {
            if (label.length() > 0) {
                label.append(" · ");
            }
            label.append(title);
        }
        return label.length() > 0 ? label.toString() : "未命名分集";
    }

    private void jumpToEpisode(MediaRepository.Video episode) {
        int existing = -1;
        List<MediaRepository.Video> videos = adapter.getVideos();
        for (int i = 0; i < videos.size(); i++) {
            if (episode.id.equals(videos.get(i).id)) {
                existing = i;
                break;
            }
        }
        if (existing >= 0) {
            pager.setCurrentItem(existing, true);
            return;
        }
        List<MediaRepository.Video> extended = new java.util.ArrayList<>(videos);
        extended.add(episode);
        feedState.append(libraryGeneration, new MediaRepository.Page(
                java.util.Collections.singletonList(episode), ""));
        adapter.setVideos(extended);
        pager.setCurrentItem(extended.size() - 1, true);
    }
    private float lastHorizontalSeekPx;

    private void showSignedOut() {
        saveResumePosition(false);
        cancel(libraryRequest);
        cancel(librariesRequest);
        cancel(playbackRequest);
        libraryGeneration = feedState.reset();
        loadingNextPage = false;
        firstPagePending = false;
        repository = null;
        currentLibraryId = "";
        currentLibraryTitle = "";
        availableLibraries = Collections.emptyList();
        detachPlayer();
        activeHolder = null;
        activePosition = RecyclerView.NO_POSITION;
        adapter.setVideos(Collections.emptyList());
        pager.setVisibility(View.GONE);
        paginationAction.setVisibility(View.GONE);
        setBrowseButtonsEnabled(false);
        showMessage("连接你的私人片库", "登录 NAS 后，这里会显示服务端返回的真实媒体。\n\n"
                        + "客户端不会伪造视频，也不会扫描本地文件。",
                false, "登录并连接", this::startLogin);
    }

    private boolean handleAuthenticationFailure(Throwable error) {
        if (!RepositoryFailure.requiresLogin(error)) {
            return false;
        }
        SessionStore.clear(this);
        sessionToken = "";
        showSignedOut();
        showMessage("登录已失效", "请重新登录。旧会话不会继续用于重试请求。",
                false, "重新登录", this::startLogin);
        return true;
    }

    private void showMessage(String title, String detail, boolean loading,
                             String action, Runnable actionRunnable) {
        messageTitle.setText(title);
        messageDetail.setText(detail);
        messageSpinner.setVisibility(loading ? View.VISIBLE : View.GONE);
        messageActionRunnable = actionRunnable;
        if (action == null || action.trim().isEmpty()) {
            messageAction.setVisibility(View.GONE);
        } else {
            messageAction.setText(action);
            messageAction.setVisibility(View.VISIBLE);
        }
        messageOverlay.setVisibility(View.VISIBLE);
    }

    private void hideMessage() {
        messageActionRunnable = null;
        messageOverlay.setVisibility(View.GONE);
        setBrowseButtonsEnabled(true);
    }

    private void setBrowseButtonsEnabled(boolean enabled) {
        if (libraryButton != null) {
            libraryButton.setEnabled(enabled);
            libraryButton.setAlpha(enabled ? 1f : 0.38f);
        }
        if (searchButton != null) {
            searchButton.setEnabled(enabled);
            searchButton.setAlpha(enabled ? 1f : 0.38f);
        }
    }

    private void startLogin() {
        Intent intent = new Intent(this, LoginActivity.class);
        if (!serverBase.isEmpty()) intent.putExtra("base_url", serverBase);
        startActivityForResult(intent, REQUEST_LOGIN);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_LOGIN || resultCode != RESULT_OK || data == null) {
            return;
        }
        String base;
        String token = safe(data.getStringExtra("token"));
        try {
            base = ServerAddress.normalize(data.getStringExtra("base_url"));
            if (token.isEmpty()) throw new IllegalArgumentException("缺少登录令牌");
            SessionStore.save(this, base, token);
        } catch (IllegalArgumentException | IllegalStateException error) {
            showMessage("登录信息保存失败", "请检查服务器地址与 Android 安全存储后重试。\n原因："
                            + errorReason(error),
                    false, "重新登录", this::startLogin);
            return;
        }
        saveResumePosition(false);
        if (!ServerAddress.sameOrigin(serverBase, base)) legacyResumeBase = base;
        serverBase = base;
        sessionToken = token;
        refreshRepository();
        currentQuery = "";
        currentLibraryId = "";
        currentLibraryTitle = "";
        setBrowseButtonsEnabled(true);
        loadLibrary("");
    }

    private void showSearchDialog() {
        if (!hasSession()) {
            startLogin();
            return;
        }
        LinearLayout card = dialogCard();
        addDialogTitle(card, "搜索全部片库");
        EditText query = editText("输入标题或关键词", currentQuery);
        card.addView(query, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        LinearLayout buttons = dialogButtons();
        TextView cancel = dialogButton("取消", SECONDARY);
        TextView search = dialogButton("搜索", ACCENT);
        buttons.addView(cancel, buttonWeightParams());
        buttons.addView(search, buttonWeightParams());
        card.addView(buttons);
        Dialog dialog = showDialog(card, true);
        cancel.setOnClickListener(v -> dialog.dismiss());
        search.setOnClickListener(v -> {
            String value = safe(query.getText().toString());
            dialog.dismiss();
            hideKeyboard(query);
            currentLibraryId = "";
            currentLibraryTitle = "全部媒体";
            loadLibrary(value);
        });
        query.requestFocus();
        dialog.getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }

    private void showLibraryDialog() {
        if (!hasSession()) {
            startLogin();
            return;
        }
        LinearLayout card = dialogCard();
        addDialogTitle(card, "选择媒体库");
        TextView status = label("正在读取 NAS 媒体库…", 13, SECONDARY, Typeface.NORMAL);
        card.addView(status, wrapParams(0, dp(10)));
        ScrollView scroll = new ScrollView(this);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300)));
        TextView close = dialogButton("关闭", SECONDARY);
        card.addView(close, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        Dialog dialog = showDialog(card, false);
        close.setOnClickListener(v -> dialog.dismiss());
        requestLibraries(dialog, status, rows);
    }

    private void requestLibraries(Dialog dialog, TextView status, LinearLayout rows) {
        final MediaRepository requestRepository = repository;
        if (requestRepository == null) {
            status.setText("当前没有可用连接，请重新登录。");
            return;
        }
        cancel(librariesRequest);
        librariesRequest = networkExecutor.submit(() -> {
            try {
                List<MediaRepository.Library> values = requestRepository.libraries();
                mainHandler.post(() -> {
                    if (destroyed || !dialog.isShowing() || repository != requestRepository) {
                        return;
                    }
                    availableLibraries = values == null ? Collections.emptyList() : values;
                    renderLibraryRows(dialog, status, rows, availableLibraries);
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (!destroyed && dialog.isShowing() && repository == requestRepository) {
                        if (RepositoryFailure.requiresLogin(error)) {
                            dialog.dismiss();
                            handleAuthenticationFailure(error);
                            return;
                        }
                        status.setText("媒体库读取失败：" + errorReason(error)
                                + "。请检查连接后重试。");
                    }
                });
            }
        });
    }

    private void renderLibraryRows(Dialog dialog, TextView status, LinearLayout rows,
                                   List<MediaRepository.Library> values) {
        rows.removeAllViews();
        if (values == null || values.isEmpty()) {
            status.setText("服务端没有返回可用媒体库。请确认账号权限。");
            return;
        }
        status.setText("选择后会重新加载该媒体库的第一页。");
        addLibraryRow(dialog, rows, new MediaRepository.Library("", "全部媒体"));
        for (MediaRepository.Library library : values) {
            if (library == null) {
                continue;
            }
            addLibraryRow(dialog, rows, library);
        }
    }

    private void addLibraryRow(Dialog dialog, LinearLayout rows, MediaRepository.Library library) {
        String id = safe(library.id);
        String title = safe(library.title).isEmpty() ? id : safe(library.title);
        if (title.isEmpty()) {
            title = "全部媒体";
        }
        String subtitle = id.equals(currentLibraryId) ? "当前媒体库" : "切换到此媒体库";
        LinearLayout row = dialogRow(title, subtitle);
        String selectedTitle = title;
        row.setOnClickListener(v -> {
            currentLibraryId = id;
            currentLibraryTitle = selectedTitle;
            dialog.dismiss();
            loadLibrary("");
        });
        rows.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60)));
    }

    private void showSettingsDialog() {
        LinearLayout card = dialogCard();
        addDialogTitle(card, "连接设置");
        EditText baseField = editText("服务器地址，例如 http://nas.example.test:5666/v",
                serverBase);
        baseField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        card.addView(baseField, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        TextView status = label(hasSession() ? "已保存会话令牌" : "当前未登录",
                13, SECONDARY, Typeface.NORMAL);
        status.setPadding(0, dp(10), 0, dp(8));
        card.addView(status, wrapParams(0, 0));

        LinearLayout buttons = dialogButtons();
        TextView cancel = dialogButton("取消", SECONDARY);
        TextView save = dialogButton("保存并刷新", ACCENT);
        buttons.addView(cancel, buttonWeightParams());
        buttons.addView(save, buttonWeightParams());
        card.addView(buttons);

        TextView login = dialogButton(hasSession() ? "重新登录" : "登录", PRIMARY);
        card.addView(login, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        TextView logout = dialogButton("退出登录并清除令牌", Color.rgb(255, 126, 126));
        card.addView(logout, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        Dialog dialog = showDialog(card, false);
        cancel.setOnClickListener(v -> dialog.dismiss());
        save.setOnClickListener(v -> {
            String base;
            String retainedToken;
            try {
                base = ServerAddress.normalize(baseField.getText().toString());
                retainedToken = SessionStore.changeServer(this, base);
            } catch (IllegalArgumentException | IllegalStateException error) {
                baseField.setError(errorReason(error));
                return;
            }
            saveResumePosition(false);
            boolean changedOrigin = !ServerAddress.sameOrigin(serverBase, base);
            if (changedOrigin) {
                currentQuery = "";
                currentLibraryId = "";
                currentLibraryTitle = "";
                legacyResumeBase = base;
            }
            serverBase = base;
            sessionToken = retainedToken;
            refreshRepository();
            dialog.dismiss();
            if (hasSession()) {
                loadLibrary(currentQuery);
            } else {
                showSignedOut();
                if (changedOrigin) {
                    showMessage("服务器已更换", "原服务器令牌已清除，请登录当前服务器。",
                            false, "登录当前服务器", this::startLogin);
                }
            }
        });
        login.setOnClickListener(v -> {
            dialog.dismiss();
            startLogin();
        });
        logout.setOnClickListener(v -> {
            SessionStore.clear(this);
            dialog.dismiss();
            sessionToken = "";
            showSignedOut();
        });
    }

    private Dialog showDialog(View content, boolean keyboard) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(content);
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(380)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setSoftInputMode(keyboard
                    ? android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    : android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        }
        return dialog;
    }

    private LinearLayout dialogCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(18), dp(20), dp(16));
        card.setBackground(roundBackground(CARD, 22));
        return card;
    }

    private void addDialogTitle(LinearLayout card, String title) {
        TextView value = label(title, 19, PRIMARY, Typeface.BOLD);
        value.setPadding(0, 0, 0, dp(16));
        card.addView(value, wrapParams(0, 0));
    }

    private EditText editText(String hint, String value) {
        EditText field = new EditText(this);
        field.setText(value == null ? "" : value);
        field.setHint(hint);
        field.setHintTextColor(Color.rgb(119, 127, 140));
        field.setTextColor(PRIMARY);
        field.setTextSize(14);
        field.setSingleLine(true);
        field.setPadding(dp(14), 0, dp(14), 0);
        field.setBackground(roundBackground(Color.rgb(31, 35, 44), 14));
        return field;
    }

    private LinearLayout dialogButtons() {
        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setPadding(0, dp(14), 0, dp(8));
        return buttons;
    }

    private TextView dialogButton(String text, int color) {
        TextView value = pillButton(text, color);
        value.setTextSize(14);
        return value;
    }

    private LinearLayout dialogRow(String title, String subtitle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(7), dp(12), dp(7));
        row.setBackground(roundBackground(Color.rgb(29, 33, 41), 12));
        TextView titleView = label(title, 14, PRIMARY, Typeface.BOLD);
        TextView subtitleView = label(subtitle, 11, SECONDARY, Typeface.NORMAL);
        row.addView(titleView, wrapParams(0, 0));
        row.addView(subtitleView, wrapParams(2, 0));
        return row;
    }

    private void hideKeyboard(View view) {
        InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (manager != null) {
            manager.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    private String actionableLibraryError(Exception error) {
        if (error instanceof RepositoryFailure
                && ((RepositoryFailure) error).kind == RepositoryFailure.Kind.PERMISSION_DENIED) {
            return "账号没有媒体库访问权限。请确认服务端授权。";
        }
        return "片库请求未完成，请检查网络连接或服务端状态后重试。\n原因：" + errorReason(error);
    }

    private String actionablePlaybackError(Exception error) {
        return "请检查该媒体是否存在、账号是否有播放权限，然后重试。\n原因："
                + errorReason(error);
    }

    private String actionablePlaybackError(PlaybackException error) {
        if (isDecodingError(error)) {
            return "当前设备无法解码此视频，兼容播放也未成功。可尝试其他影片或在 NAS 检查转码能力。\n错误："
                    + PlaybackException.getErrorCodeName(error.errorCode);
        }
        return "请检查媒体格式、NAS 播放权限或网络连接，然后重试。\n错误："
                + PlaybackException.getErrorCodeName(error.errorCode);
    }

    private static boolean isDecodingError(PlaybackException error) {
        return error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                || error.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED
                || error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
                || error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
                || error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED;
    }

    private String errorReason(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 16; depth++) {
            String message = safe(current.getMessage());
            if (!message.isEmpty()) {
                return message.length() > 180 ? message.substring(0, 180) : message;
            }
            current = current.getCause();
        }
        return "服务端未提供详细错误";
    }

    private static void cancel(Future<?> request) {
        if (request != null && !request.isDone()) {
            request.cancel(true);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private TextView label(String value, float sizeSp, int color, int style) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sizeSp);
        text.setTextColor(color);
        text.setTypeface(Typeface.create("sans-serif", style));
        text.setIncludeFontPadding(false);
        return text;
    }

    private TextView iconButton(String value, String description) {
        TextView button = label(value, 18, PRIMARY, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(description);
        button.setClickable(true);
        button.setFocusable(true);
        button.setBackground(roundBackground(Color.argb(125, 20, 23, 30), 15));
        return button;
    }

    private TextView pillButton(String value, int color) {
        TextView button = label(value, 14, color, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setClickable(true);
        button.setFocusable(true);
        button.setBackground(roundBackground(Color.argb(210, 31, 35, 44), 18));
        return button;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(46), dp(46));
        params.leftMargin = dp(4);
        return params;
    }

    private LinearLayout.LayoutParams buttonWeightParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1f);
        params.leftMargin = dp(4);
        params.rightMargin = dp(4);
        return params;
    }

    private LinearLayout.LayoutParams wrapParams(int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = top;
        params.bottomMargin = bottom;
        return params;
    }

    private GradientDrawable roundBackground(int fillColor, float radiusDp) {
        GradientDrawable value = new GradientDrawable();
        value.setColor(fillColor);
        value.setCornerRadius(dp(Math.round(radiusDp)));
        value.setStroke(dp(1), Color.argb(70, 255, 255, 255));
        return value;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onPause() {
        foreground = false;
        mainHandler.removeCallbacks(progressTicker);
        saveResumePosition(false);
        wasPlayingBeforePause = hasLoadedPlayback() && player.getPlayWhenReady();
        if (player != null) {
            player.pause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
        mainHandler.removeCallbacks(progressTicker);
        mainHandler.post(progressTicker);
        if (!userPaused && activeHolder != null && hasLoadedPlayback()
                && player.getPlaybackState() != Player.STATE_ENDED
                && player.getPlayerError() == null
                && (wasPlayingBeforePause || player.getPlaybackState() != Player.STATE_IDLE)) {
            player.play();
        }
        wasPlayingBeforePause = false;
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        saveResumePosition(false);
        libraryGeneration = feedState.reset();
        playbackSession.invalidate();
        cancel(libraryRequest);
        cancel(librariesRequest);
        cancel(playbackRequest);
        mainHandler.removeCallbacksAndMessages(null);
        if (networkExecutor != null) {
            networkExecutor.shutdownNow();
        }
        if (posterLoader != null) {
            posterLoader.shutdown();
        }
        if (player != null) {
            player.release();
            player = null;
        }
        repository = null;
        super.onDestroy();
    }
}
