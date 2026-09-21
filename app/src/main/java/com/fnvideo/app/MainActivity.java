package com.fnvideo.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The one playback Activity. LibraryActivity owns browsing and never creates
 * a playback request; this Activity is entered only with an explicit
 * PlaybackRequest and owns the app's single ExoPlayer instance.
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class MainActivity extends Activity implements FeedAdapter.Listener {
    private static final int BG = Color.rgb(5, 7, 10);
    private static final int PRIMARY = Color.rgb(246, 247, 249);
    private static final int SECONDARY = Color.rgb(174, 181, 193);
    private static final int ACCENT = Color.rgb(239, 188, 120);
    private static final int SURFACE = Color.rgb(19, 24, 32);
    private static final long CONTROLS_HIDE_MS = 2_800L;
    private static final long MAX_BIND_WAIT_MS = 1_600L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final PlaybackSession playbackSession = new PlaybackSession();
    private final Runnable progressTicker = this::updateProgress;
    private final Runnable hideControls = this::hideControlsIfPlaying;

    private ExecutorService networkExecutor;
    private Future<?> playbackRequest;
    private Future<?> directoryRequest;
    private ExoPlayer player;
    private PosterLoader posterLoader;
    private FeedAdapter adapter;
    private ViewPager2 pager;
    private FrameLayout root;
    private FrameLayout messageOverlay;
    private ProgressBar messageSpinner;
    private TextView messageTitle;
    private TextView messageDetail;
    private TextView messageAction;
    private TextView topTitle;
    private TextView topSubtitle;
    private TextView advanceBanner;

    private MediaRepository repository;
    private WatchStateStore watchStore;
    private String serverBase = "";
    private String sessionToken = "";
    private String accountId = "";
    private String mode = "movie";
    private final ArrayList<MediaRepository.Video> queue = new ArrayList<>();
    private int queueIndex;
    private MediaRepository.Video currentVideo;
    private FeedAdapter.VideoViewHolder activeHolder;
    private boolean foreground;
    private boolean destroyed;
    private boolean userPaused;
    private boolean wasPlayingBeforePause;
    private boolean compatibilityAttempted;
    private boolean advancing;
    private boolean currentCompleted;
    private boolean fullDirectoryLoaded;
    private boolean directoryLoading;
    private long requestGeneration;
    private long lastSavedPosition = -1L;
    private Runnable advanceRunnable;
    private float lastHorizontalSeekPx;
    private boolean horizontalSeekStarted;
    private boolean wasPlayingBeforeSeek;
    private boolean wasPlayingBeforeSpeed;
    private boolean zoomMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        networkExecutor = Executors.newSingleThreadExecutor();
        restoreSession();
        repository = hasSession() ? new FnApi(serverBase, sessionToken) : null;
        if (hasSession()) {
            try {
                accountId = safe(SessionStore.accountId(this));
                watchStore = new WatchStateStore(this, serverBase, accountId);
            } catch (RuntimeException ignored) {
                watchStore = null;
            }
        }
        currentVideo = PlaybackRequest.video(getIntent());
        mode = safe(PlaybackRequest.mode(getIntent()));
        if (mode.isEmpty()) mode = "movie";
        queue.addAll(PlaybackRequest.queue(getIntent()));
        queueIndex = Math.max(0, PlaybackRequest.queueIndex(getIntent()));
        if (currentVideo != null && queue.isEmpty()) queue.add(currentVideo);
        if (currentVideo != null && !containsId(queue, currentVideo.id)) {
            queueIndex = Math.min(queueIndex, queue.size());
            queue.add(queueIndex, currentVideo);
        }
        if (!queue.isEmpty()) {
            queueIndex = Math.min(queueIndex, queue.size() - 1);
            currentVideo = queue.get(queueIndex);
        }
        buildUi();
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        if (currentVideo == null || !hasSession()) {
            showMessage("没有可播放内容", hasSession()
                    ? "请从片库或随看中选择一部影片。"
                    : "登录已失效，请返回片库重新连接。", false, "返回片库", this::returnToLibrary);
            return;
        }
        player = new ExoPlayer.Builder(this)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                .setHandleAudioBecomingNoisy(true)
                .build();
        player.addListener(createPlayerListener());
        updateHeader();
        fullDirectoryLoaded = !"series".equalsIgnoreCase(mode);
        requestFullSeriesDirectory();
        // onResume starts the explicit request after the Activity is in the
        // foreground; no cold-start source is resolved while hidden.
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
        root.setBackgroundColor(Color.BLACK);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            root.setPadding(0, insets.getSystemWindowInsetTop(), 0,
                    insets.getSystemWindowInsetBottom());
            return insets;
        });

        adapter = new FeedAdapter(this, this, posterLoader = new PosterLoader());
        adapter.setVerticalSwipeEnabled("watch".equalsIgnoreCase(mode));
        adapter.setVideos(currentVideo == null
                ? Collections.emptyList() : Collections.singletonList(currentVideo));
        pager = new ViewPager2(this);
        pager.setOrientation(ViewPager2.ORIENTATION_VERTICAL);
        pager.setUserInputEnabled(false);
        pager.setOffscreenPageLimit(1);
        pager.setAdapter(adapter);
        root.addView(pager, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        buildTopBar();
        buildAdvanceBanner();
        buildMessageOverlay();
        setContentView(root);
        root.requestApplyInsets();
    }

    private void buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), dp(10), dp(14), dp(8));
        bar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(232, 5, 7, 10), Color.TRANSPARENT}));
        root.addView(bar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(74), Gravity.TOP));

        TextView back = iconButton("‹", "返回片库");
        back.setTextSize(32);
        back.setOnClickListener(view -> returnToLibrary());
        bar.addView(back, buttonParams());

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        labels.setPadding(dp(10), 0, dp(8), 0);
        bar.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        topTitle = label("", 15, PRIMARY, Typeface.BOLD);
        topSubtitle = label("", 11, SECONDARY, Typeface.NORMAL);
        labels.addView(topTitle, wrapParams(0, 4));
        labels.addView(topSubtitle, wrapParams(0, 0));

        TextView more = iconButton("⋯", "播放选项");
        more.setOnClickListener(view -> showPlaybackOptions());
        bar.addView(more, buttonParams());
    }

    private void buildAdvanceBanner() {
        advanceBanner = pillButton("", ACCENT);
        advanceBanner.setVisibility(View.GONE);
        advanceBanner.setContentDescription("下一集倒计时");
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(50),
                Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        params.bottomMargin = dp(126);
        root.addView(advanceBanner, params);
    }

    private void buildMessageOverlay() {
        messageOverlay = new FrameLayout(this);
        messageOverlay.setBackgroundColor(Color.argb(246, 5, 7, 10));
        messageOverlay.setVisibility(View.GONE);
        root.addView(messageOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(26), dp(24), dp(26), dp(22));
        card.setBackground(roundBackground(SURFACE, 22));
        messageOverlay.addView(card, new FrameLayout.LayoutParams(
                dp(330), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        TextView brand = label("牛影随看", 21, PRIMARY, Typeface.BOLD);
        brand.setGravity(Gravity.CENTER);
        card.addView(brand, wrapParams(0, 18));
        messageTitle = label("", 17, PRIMARY, Typeface.BOLD);
        messageTitle.setGravity(Gravity.CENTER);
        card.addView(messageTitle, wrapParams(0, 10));
        messageDetail = label("", 13, SECONDARY, Typeface.NORMAL);
        messageDetail.setGravity(Gravity.CENTER);
        messageDetail.setMaxLines(7);
        card.addView(messageDetail, wrapParams(0, 16));
        messageSpinner = new ProgressBar(this);
        messageSpinner.setVisibility(View.GONE);
        card.addView(messageSpinner, new LinearLayout.LayoutParams(dp(42), dp(42)));
        messageAction = pillButton("", ACCENT);
        messageAction.setVisibility(View.GONE);
        card.addView(messageAction, new LinearLayout.LayoutParams(dp(190), dp(48)));
    }

    private Player.Listener createPlayerListener() {
        return new Player.Listener() {
            @Override
            public void onTracksChanged(@NonNull Tracks tracks) {
                if (!hasLoadedPlayback() || !PlaybackCompatibility.hasUnsupportedAudio(tracks)) return;
                if (!tryCompatiblePlayback()) {
                    saveWatchState(false);
                    if (activeHolder != null) activeHolder.showError(
                            "当前设备无法播放此音轨，请检查 NAS 转码能力或换一部影片。");
                }
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (activeHolder == null || !hasLoadedPlayback()) return;
                if (state == Player.STATE_BUFFERING) {
                    activeHolder.showBuffering();
                } else if (state == Player.STATE_READY) {
                    activeHolder.showReady();
                    activeHolder.setSeekEnabled(player.getDuration() > 0
                            && player.getDuration() != C.TIME_UNSET);
                    if (foreground && !userPaused) {
                        player.play();
                        showControlsAndScheduleHide();
                    }
                } else if (state == Player.STATE_ENDED) {
                    activeHolder.setPlaying(false);
                    saveWatchState(true);
                    onCurrentEnded();
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (activeHolder != null && hasLoadedPlayback()) {
                    activeHolder.setPlaying(isPlaying && player.getPlaybackState() != Player.STATE_ENDED);
                    if (isPlaying) showControlsAndScheduleHide();
                    else showControls();
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
                    showControls();
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                if (activeHolder == null || !hasLoadedPlayback()) return;
                if (isDecodingError(error) && tryCompatiblePlayback()) return;
                activeHolder.showError(actionablePlaybackError(error));
                showControls();
            }
        };
    }

    private void prepareCurrent(boolean compatible, long requestedPosition) {
        if (destroyed || currentVideo == null || !hasSession() || repository == null
                || player == null || !foreground) return;
        long generation = ++requestGeneration;
        compatibilityAttempted = compatible;
        advancing = false;
        currentCompleted = false;
        cancel(playbackRequest);
        dismissAdvance();
        stopPlayer();
        activeHolder = null;
        playbackSession.invalidate();
        PlaybackSession.Ticket ticket = playbackSession.select(serverBase, currentVideo.id, generation);
        final MediaRepository.Video requestVideo = currentVideo;
        final MediaRepository requestRepository = repository;
        long position = requestedPosition > 0 ? requestedPosition : readPosition(requestVideo);
        adapter.setVideos(Collections.singletonList(requestVideo));
        pager.setCurrentItem(0, false);
        updateHeader();
        showMessage("准备播放", "正在向 NAS 请求当前媒体的播放源…", true, null, null);
        playbackRequest = networkExecutor.submit(() -> {
            try {
                MediaRepository.Source source = compatible
                        ? requestRepository.resolveCompatible(requestVideo)
                        : requestRepository.resolve(requestVideo);
                mainHandler.post(() -> {
                    if (isCurrent(ticket, requestVideo, requestRepository) && foreground) {
                        attachPlayback(ticket, requestVideo, source, position);
                    }
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (!isCurrent(ticket, requestVideo, requestRepository)) return;
                    if (RepositoryFailure.requiresLogin(error)) {
                        showMessage("登录已失效", "请返回片库重新连接 NAS。", false,
                                "返回片库", this::returnToLibrary);
                    } else {
                        hideMessage();
                        if (activeHolder != null) activeHolder.showError(actionablePlaybackError(error));
                    }
                });
            }
        });
        pager.postDelayed(() -> bindHolder(ticket, requestVideo, 0), 40L);
    }

    private void bindHolder(PlaybackSession.Ticket ticket, MediaRepository.Video video, int attempt) {
        if (destroyed || !foreground || !playbackSession.isCurrent(ticket)
                || currentVideo != video || adapter.getItemCount() == 0) return;
        FeedAdapter.VideoViewHolder holder = findHolder();
        if (holder == null || holder.getVideo() == null || !video.id.equals(holder.getVideo().id)) {
            if (attempt * 80L < MAX_BIND_WAIT_MS) {
                pager.postDelayed(() -> bindHolder(ticket, video, attempt + 1), 80L);
            }
            return;
        }
        activeHolder = holder;
        holder.setFitMode(zoomMode);
        holder.setControlsVisible(true);
        holder.showLoading();
    }

    private void attachPlayback(PlaybackSession.Ticket ticket, MediaRepository.Video video,
                                MediaRepository.Source source, long position) {
        if (destroyed || !foreground || activeHolder == null
                || !playbackSession.isCurrent(ticket) || currentVideo != video) return;
        try {
            MediaItem.Builder builder = new MediaItem.Builder()
                    .setMediaId(ticket.videoId).setUri(Uri.parse(source.url));
            if (!safe(source.mimeType).isEmpty()) builder.setMimeType(source.mimeType);
            player.setMediaSource(new DefaultMediaSourceFactory(
                    PlaybackDataSource.forSource(source, ticket.serverOrigin)).createMediaSource(builder.build()));
            playbackSession.markLoaded(ticket);
            activeHolder.playerView.setPlayer(player);
            activeHolder.setFitMode(zoomMode);
            activeHolder.showLoading();
            if (position > 0) player.seekTo(position);
            player.prepare();
            hideMessage();
            if (!userPaused) player.play();
            showControlsAndScheduleHide();
        } catch (Exception error) {
            stopPlayer();
            hideMessage();
            activeHolder.showError(actionablePlaybackError(error));
        }
    }

    private boolean isCurrent(PlaybackSession.Ticket ticket, MediaRepository.Video video,
                              MediaRepository requestRepository) {
        return !destroyed && foreground && currentVideo == video
                && repository == requestRepository && playbackSession.isCurrent(ticket);
    }

    private boolean tryCompatiblePlayback() {
        if (compatibilityAttempted || currentVideo == null || !hasLoadedPlayback()) return false;
        compatibilityAttempted = true;
        long position = Math.max(0L, player.getCurrentPosition());
        prepareCurrent(true, position);
        return true;
    }

    private void onCurrentEnded() {
        if ("series".equalsIgnoreCase(mode) && !fullDirectoryLoaded) {
            requestFullSeriesDirectory();
            advanceBanner.setText("正在读取完整分集目录…");
            advanceBanner.setVisibility(View.VISIBLE);
            return;
        }
        if (!PlaybackRequest.autoAdvance(getIntent()) || !canAdvance()) {
            showControls();
            return;
        }
        showAdvanceCountdown();
    }

    private boolean canAdvance() {
        if (queueIndex < 0 || queueIndex + 1 >= queue.size()) return false;
        MediaRepository.Video current = queue.get(queueIndex);
        MediaRepository.Video next = queue.get(queueIndex + 1);
        if (!sameSeason(current, next)) return false;
        if (current.episode > 0 && next.episode > current.episode + 1) return false;
        if (current.episode > 0 && next.episode > 0 && next.episode <= current.episode) return false;
        return true;
    }

    private void showAdvanceCountdown() {
        dismissAdvance();
        final long[] remaining = {5};
        advanceBanner.setText("下一集将在 5 秒后播放  ·  取消");
        advanceBanner.setVisibility(View.VISIBLE);
        advanceBanner.setOnClickListener(view -> dismissAdvance());
        advanceRunnable = new Runnable() {
            @Override public void run() {
                if (destroyed || !advanceBanner.isShown()) return;
                remaining[0]--;
                if (remaining[0] <= 0) {
                    advanceBanner.setVisibility(View.GONE);
                    advanceRunnable = null;
                    selectQueueIndex(queueIndex + 1);
                } else {
                    advanceBanner.setText("下一集将在 " + remaining[0] + " 秒后播放  ·  取消");
                    mainHandler.postDelayed(this, 1_000L);
                }
            }
        };
        mainHandler.postDelayed(advanceRunnable, 1_000L);
    }

    private void selectQueueIndex(int index) {
        if (index < 0 || index >= queue.size()) return;
        // Selecting an episode is a navigation action. Completion is written
        // only by STATE_ENDED; a manual jump must not mark the old item done.
        saveWatchState(false);
        queueIndex = index;
        currentVideo = queue.get(index);
        userPaused = false;
        lastSavedPosition = -1L;
        prepareCurrent(false, 0L);
    }

    /** Fill the bounded Binder handoff with the server's complete episode directory. */
    private void requestFullSeriesDirectory() {
        if (fullDirectoryLoaded || directoryLoading || repository == null
                || currentVideo == null || !"series".equalsIgnoreCase(mode)) return;
        String seriesId = safe(currentVideo.seriesId);
        if (seriesId.isEmpty()) seriesId = safe(currentVideo.parentId);
        if (seriesId.isEmpty()) return;
        final String requestedSeriesId = seriesId;
        final String currentId = currentVideo.id;
        final MediaRepository requestRepository = repository;
        directoryLoading = true;
        directoryRequest = networkExecutor.submit(() -> {
            try {
                MediaRepository.Video series = new MediaRepository.Video();
                series.id = requestedSeriesId;
                series.type = "TV";
                series.seriesId = requestedSeriesId;
                List<MediaRepository.Video> values = requestRepository.seriesEpisodes(series);
                mainHandler.post(() -> {
                    if (destroyed || repository != requestRepository
                            || currentVideo == null || !currentId.equals(currentVideo.id)) return;
                    directoryLoading = false;
                    mergeFullDirectory(values, currentId);
                    fullDirectoryLoaded = true;
                    if (currentCompleted && player != null
                            && player.getPlaybackState() == Player.STATE_ENDED) onCurrentEnded();
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    directoryLoading = false;
                    if (!destroyed && currentVideo != null && currentId.equals(currentVideo.id)) {
                        advanceBanner.setText("分集目录读取失败，当前队列已停止在这里");
                        advanceBanner.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
    }

    private void mergeFullDirectory(List<MediaRepository.Video> values, String currentId) {
        LinkedHashMap<String, MediaRepository.Video> unique = new LinkedHashMap<>();
        if (values != null) {
            for (MediaRepository.Video value : values) {
                if (value != null && !safe(value.id).isEmpty()) unique.put(value.id, value);
            }
        }
        MediaRepository.Video current = null;
        for (MediaRepository.Video value : queue) {
            if (value != null && currentId.equals(value.id)) current = value;
        }
        if (current == null) current = currentVideo;
        unique.put(currentId, current);
        ArrayList<MediaRepository.Video> merged = new ArrayList<>(unique.values());
        merged.sort(Comparator.comparingInt((MediaRepository.Video value) -> value.season)
                .thenComparingInt(value -> value.episode)
                .thenComparing(value -> safe(value.id)));
        queue.clear();
        queue.addAll(merged);
        queueIndex = 0;
        for (int i = 0; i < queue.size(); i++) {
            if (currentId.equals(queue.get(i).id)) {
                queueIndex = i;
                break;
            }
        }
    }

    private void updateProgress() {
        if (!destroyed && foreground && activeHolder != null && hasLoadedPlayback()) {
            long position = player.getCurrentPosition();
            long duration = player.getDuration();
            activeHolder.updateProgress(position, duration);
            if (player.isPlaying() && Math.abs(position - lastSavedPosition) >= 5_000L) {
                saveWatchState(false);
            }
        }
        if (!destroyed && foreground) mainHandler.postDelayed(progressTicker, 500L);
    }

    private void saveWatchState(boolean completed) {
        if (watchStore == null || currentVideo == null || !hasLoadedPlayback()) return;
        currentCompleted = currentCompleted || completed;
        long position = Math.max(0L, player.getCurrentPosition());
        long duration = player.getDuration();
        watchStore.save(currentVideo, position, duration, currentCompleted);
        lastSavedPosition = position;
    }

    private long readPosition(MediaRepository.Video video) {
        if (watchStore == null || video == null) return 0L;
        return Math.max(0L, watchStore.position(video));
    }

    private void showControls() {
        mainHandler.removeCallbacks(hideControls);
        if (activeHolder != null) activeHolder.setControlsVisible(true);
    }

    private void hideControlsIfPlaying() {
        if (foreground && activeHolder != null && hasLoadedPlayback()
                && player != null && player.getPlayWhenReady()) {
            activeHolder.setControlsVisible(false);
        }
    }

    private void showControlsAndScheduleHide() {
        showControls();
        if (foreground && activeHolder != null && player != null && player.getPlayWhenReady()) {
            mainHandler.postDelayed(hideControls, CONTROLS_HIDE_MS);
        }
    }

    private boolean hasLoadedPlayback() {
        PlaybackSession.Ticket loaded = playbackSession.loaded();
        MediaItem item = player == null ? null : player.getCurrentMediaItem();
        return loaded != null && item != null && loaded.videoId.equals(item.mediaId);
    }

    private void stopPlayer() {
        playbackSession.clearLoaded();
        if (player != null) {
            player.pause();
            player.stop();
            player.clearMediaItems();
        }
    }

    private FeedAdapter.VideoViewHolder findHolder() {
        if (pager == null) return null;
        View child = pager.getChildAt(0);
        if (!(child instanceof RecyclerView)) return null;
        RecyclerView.ViewHolder holder = ((RecyclerView) child).findViewHolderForAdapterPosition(0);
        return holder instanceof FeedAdapter.VideoViewHolder
                ? (FeedAdapter.VideoViewHolder) holder : null;
    }

    private void updateHeader() {
        if (topTitle == null || currentVideo == null) return;
        String title = safe(currentVideo.title);
        topTitle.setText(title.isEmpty() ? "未命名视频" : title);
        StringBuilder subtitle = new StringBuilder();
        if (currentVideo.season > 0) subtitle.append("第 ").append(currentVideo.season).append(" 季");
        if (currentVideo.episode > 0) {
            if (subtitle.length() > 0) subtitle.append(" · ");
            subtitle.append("第 ").append(currentVideo.episode).append(" 集");
        }
        if (subtitle.length() == 0 && "watch".equalsIgnoreCase(mode)) subtitle.append("随看队列");
        topSubtitle.setText(subtitle.toString());
    }

    private void showPlaybackOptions() {
        LinearLayout card = dialogCard();
        addDialogTitle(card, "播放选项");
        TextView fit = dialogButton(zoomMode ? "适应画面" : "铺满画面", ACCENT);
        TextView episodes = dialogButton("打开选集", PRIMARY);
        TextView close = dialogButton("关闭", SECONDARY);
        card.addView(fit, buttonParamsFull());
        if (queue.size() > 1) card.addView(episodes, buttonParamsFull());
        card.addView(close, buttonParamsFull());
        Dialog dialog = showDialog(card);
        fit.setOnClickListener(view -> {
            zoomMode = !zoomMode;
            if (activeHolder != null) activeHolder.setFitMode(zoomMode);
            dialog.dismiss();
        });
        episodes.setOnClickListener(view -> {
            dialog.dismiss();
            showQueueDialog();
        });
        close.setOnClickListener(view -> dialog.dismiss());
    }

    private void showQueueDialog() {
        LinearLayout card = dialogCard();
        addDialogTitle(card, "当前选集");
        ScrollView scroll = new ScrollView(this);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(360)));
        TextView close = dialogButton("关闭", SECONDARY);
        card.addView(close, buttonParamsFull());
        Dialog dialog = showDialog(card);
        close.setOnClickListener(view -> dialog.dismiss());
        if (!fullDirectoryLoaded && "series".equalsIgnoreCase(mode)) {
            TextView loading = label(directoryLoading ? "正在读取完整分集目录…"
                    : "分集目录尚未完整读取", 12, SECONDARY, Typeface.NORMAL);
            card.addView(loading, wrapParams(0, 10));
        }
        for (int i = 0; i < queue.size(); i++) {
            MediaRepository.Video item = queue.get(i);
            String subtitle = episodeLabel(item) + (i == queueIndex ? "  ·  正在播放" : "");
            TextView row = dialogRow(safe(item.title).isEmpty() ? "未命名分集" : item.title, subtitle);
            if (i == queueIndex) row.setTextColor(ACCENT);
            final int target = i;
            row.setOnClickListener(view -> {
                dialog.dismiss();
                selectQueueIndex(target);
            });
            rows.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));
        }
    }

    @Override
    public void onPageTapped(FeedAdapter.VideoViewHolder holder) {
        if (holder != activeHolder || !hasLoadedPlayback() || player.getPlayerError() != null) return;
        showControls();
        if (player.getPlayWhenReady()) {
            player.pause();
            userPaused = true;
        } else {
            if (player.getPlaybackState() == Player.STATE_ENDED) {
                currentCompleted = false;
                player.seekTo(0L);
            }
            userPaused = false;
            if (foreground) player.play();
            showControlsAndScheduleHide();
        }
    }

    @Override
    public void onVerticalSwipe(FeedAdapter.VideoViewHolder holder, float deltaY) {
        if (!"watch".equalsIgnoreCase(mode) || holder != activeHolder || advancing) return;
        int next = deltaY < 0 ? queueIndex + 1 : queueIndex - 1;
        if (next >= 0 && next < queue.size()) selectQueueIndex(next);
    }

    @Override
    public void onFitToggle(FeedAdapter.VideoViewHolder holder) {
        if (holder != activeHolder) return;
        zoomMode = !zoomMode;
        holder.setFitMode(zoomMode);
        showControlsAndScheduleHide();
    }

    @Override
    public void onRetry(FeedAdapter.VideoViewHolder holder) {
        if (holder != activeHolder) activeHolder = holder;
        prepareCurrent(compatibilityAttempted, readPosition(currentVideo));
    }

    @Override
    public void onSeekStart(FeedAdapter.VideoViewHolder holder) {
        if (holder != activeHolder || !hasLoadedPlayback()) return;
        showControls();
        holder.setSeeking(true);
        wasPlayingBeforeSeek = player.getPlayWhenReady();
        player.pause();
    }

    @Override
    public void onSeekChanged(FeedAdapter.VideoViewHolder holder, int progress) {
        if (holder == activeHolder && hasLoadedPlayback()) holder.showSeekPreview(progress, player.getDuration());
    }

    @Override
    public void onSeekStop(FeedAdapter.VideoViewHolder holder, int progress) {
        if (holder != activeHolder || !hasLoadedPlayback()) return;
        long duration = player.getDuration();
        if (duration > 0 && duration != C.TIME_UNSET) {
            currentCompleted = false;
            player.seekTo(duration * progress / 1000L);
            saveWatchState(false);
        }
        if (wasPlayingBeforeSeek && foreground) {
            userPaused = false;
            player.play();
            showControlsAndScheduleHide();
        }
        holder.setSeeking(false);
    }

    @Override
    public void onHorizontalSeekStart(FeedAdapter.VideoViewHolder holder) {
        if (holder != activeHolder || !hasLoadedPlayback()) return;
        horizontalSeekStarted = true;
        lastHorizontalSeekPx = 0f;
        holder.setSeeking(true);
        wasPlayingBeforeSeek = player.getPlayWhenReady();
        player.pause();
    }

    @Override
    public void onHorizontalSeek(FeedAdapter.VideoViewHolder holder, float deltaPx) {
        if (holder != activeHolder || !horizontalSeekStarted || !hasLoadedPlayback()) return;
        long duration = player.getDuration();
        if (duration <= 0 || duration == C.TIME_UNSET) return;
        long deltaMs = (long) ((deltaPx - lastHorizontalSeekPx) * 500f);
        long target = Math.max(0L, Math.min(duration - 250L,
                player.getCurrentPosition() + deltaMs));
        lastHorizontalSeekPx = deltaPx;
        player.seekTo(target);
        holder.showSeekPreview((int) Math.min(1000L, target * 1000L / duration), duration);
    }

    @Override
    public void onHorizontalSeekEnd(FeedAdapter.VideoViewHolder holder) {
        if (holder != activeHolder) return;
        horizontalSeekStarted = false;
        lastHorizontalSeekPx = 0f;
        if (!hasLoadedPlayback()) return;
        currentCompleted = false;
        saveWatchState(false);
        if (wasPlayingBeforeSeek && foreground) {
            userPaused = false;
            player.play();
            showControlsAndScheduleHide();
        }
        holder.setSeeking(false);
    }

    @Override
    public void onSpeedPressStart(FeedAdapter.VideoViewHolder holder) {
        if (holder != activeHolder || !hasLoadedPlayback()
                || player.getPlaybackState() != Player.STATE_READY) return;
        wasPlayingBeforeSpeed = player.getPlayWhenReady();
        player.setPlaybackSpeed(2f);
        holder.showSpeedIndicator(2f);
        showControls();
        if (!player.getPlayWhenReady() && foreground) player.play();
    }

    @Override
    public void onSpeedPressEnd(FeedAdapter.VideoViewHolder holder) {
        if (holder != activeHolder || !hasLoadedPlayback()) return;
        player.setPlaybackSpeed(1f);
        holder.hideSpeedIndicator();
        if (!wasPlayingBeforeSpeed && foreground) player.pause();
        showControls();
    }

    @Override
    public void onEpisodesBrowse(FeedAdapter.VideoViewHolder holder) {
        if (holder == activeHolder) showQueueDialog();
    }

    private void restoreSession() {
        try {
            serverBase = ServerAddress.normalize(SessionStore.base(this));
            sessionToken = safe(SessionStore.token(this));
        } catch (IllegalArgumentException error) {
            serverBase = "";
            sessionToken = "";
        }
    }

    private boolean hasSession() {
        return !serverBase.isEmpty() && !sessionToken.isEmpty();
    }

    private void returnToLibrary() {
        dismissAdvance();
        finish();
    }

    private void dismissAdvance() {
        if (advanceRunnable != null) mainHandler.removeCallbacks(advanceRunnable);
        advanceRunnable = null;
        if (advanceBanner != null) advanceBanner.setVisibility(View.GONE);
    }

    private void showMessage(String title, String detail, boolean loading,
                             String action, Runnable runnable) {
        messageTitle.setText(title);
        messageDetail.setText(detail);
        messageSpinner.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (action == null || action.isEmpty()) {
            messageAction.setVisibility(View.GONE);
            messageAction.setOnClickListener(null);
        } else {
            messageAction.setText(action);
            messageAction.setVisibility(View.VISIBLE);
            messageAction.setOnClickListener(view -> {
                if (runnable != null) runnable.run();
            });
        }
        messageOverlay.setVisibility(View.VISIBLE);
    }

    private void hideMessage() {
        if (messageOverlay != null) messageOverlay.setVisibility(View.GONE);
    }

    private Dialog showDialog(View content) {
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
        }
        return dialog;
    }

    private LinearLayout dialogCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(18), dp(20), dp(16));
        card.setBackground(roundBackground(SURFACE, 22));
        return card;
    }

    private void addDialogTitle(LinearLayout card, String title) {
        card.addView(label(title, 18, PRIMARY, Typeface.BOLD), wrapParams(0, 14));
    }

    private TextView dialogButton(String title, int color) {
        TextView button = pillButton(title, color);
        button.setTextSize(14);
        return button;
    }

    private TextView dialogRow(String title, String subtitle) {
        TextView row = label(title + "\n" + subtitle, 14, PRIMARY, Typeface.NORMAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), 0, dp(12), 0);
        row.setBackground(roundBackground(Color.rgb(29, 35, 44), 12));
        return row;
    }

    private LinearLayout.LayoutParams buttonParamsFull() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
    }

    private String episodeLabel(MediaRepository.Video video) {
        StringBuilder value = new StringBuilder();
        if (video.season > 0) value.append("第 ").append(video.season).append(" 季");
        if (video.episode > 0) value.append(value.length() == 0 ? "" : " · ")
                .append("第 ").append(video.episode).append(" 集");
        return value.length() == 0 ? "顺序待确认" : value.toString();
    }

    private static boolean sameSeason(MediaRepository.Video a, MediaRepository.Video b) {
        if (a == null || b == null) return false;
        String aSeries = safe(a.seriesId);
        String bSeries = safe(b.seriesId);
        if (aSeries.isEmpty()) aSeries = safe(a.parentId);
        if (bSeries.isEmpty()) bSeries = safe(b.parentId);
        if (!aSeries.isEmpty() && !bSeries.isEmpty() && !aSeries.equals(bSeries)) return false;
        String aSeason = safe(a.seasonId);
        String bSeason = safe(b.seasonId);
        if (!aSeason.isEmpty() || !bSeason.isEmpty()) return aSeason.equals(bSeason);
        return a.season == b.season;
    }

    private static boolean containsId(List<MediaRepository.Video> values, String id) {
        for (MediaRepository.Video value : values) {
            if (value != null && safe(id).equals(safe(value.id))) return true;
        }
        return false;
    }

    private static boolean isDecodingError(PlaybackException error) {
        return error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                || error.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED
                || error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
                || error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
                || error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                || error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED;
    }

    private String actionablePlaybackError(Exception error) {
        return "请检查媒体是否存在、账号是否有播放权限，然后重试。\n原因：" + errorReason(error);
    }

    private String actionablePlaybackError(PlaybackException error) {
        return isDecodingError(error)
                ? "当前设备无法解码此视频，请尝试其他影片或检查 NAS 转码。\n错误："
                        + PlaybackException.getErrorCodeName(error.errorCode)
                : "请检查媒体格式、NAS 播放权限或网络连接，然后重试。\n错误："
                        + PlaybackException.getErrorCodeName(error.errorCode);
    }

    private String errorReason(Throwable error) {
        Throwable current = error;
        for (int i = 0; current != null && i < 10; i++, current = current.getCause()) {
            String value = safe(current.getMessage());
            if (!value.isEmpty()) return value.length() > 180 ? value.substring(0, 180) : value;
        }
        return "服务端未提供详细错误";
    }

    private static void cancel(Future<?> future) {
        if (future != null && !future.isDone()) future.cancel(true);
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

    private TextView iconButton(String value, String description) {
        TextView button = label(value, 19, PRIMARY, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(description);
        button.setClickable(true);
        button.setFocusable(true);
        button.setBackground(roundBackground(Color.argb(125, 20, 23, 30), 15));
        return button;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private TextView pillButton(String value, int color) {
        TextView button = label(value, 13, color, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setMinHeight(dp(44));
        button.setClickable(true);
        button.setFocusable(true);
        button.setBackground(roundBackground(Color.argb(210, 31, 35, 44), 18));
        return button;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(46), dp(46));
        params.leftMargin = dp(3);
        params.rightMargin = dp(3);
        return params;
    }

    private LinearLayout.LayoutParams wrapParams(int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(top);
        params.bottomMargin = dp(bottom);
        return params;
    }

    private GradientDrawable roundBackground(int fill, int radiusDp) {
        GradientDrawable value = new GradientDrawable();
        value.setColor(fill);
        value.setCornerRadius(dp(radiusDp));
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
        mainHandler.removeCallbacks(hideControls);
        saveWatchState(false);
        wasPlayingBeforePause = hasLoadedPlayback() && player != null && player.getPlayWhenReady();
        cancel(playbackRequest);
        if (!hasLoadedPlayback()) playbackSession.invalidate();
        if (player != null) player.pause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
        mainHandler.removeCallbacks(progressTicker);
        mainHandler.post(progressTicker);
        if (player == null || currentVideo == null) return;
        if (!hasLoadedPlayback()) {
            prepareCurrent(false, readPosition(currentVideo));
        } else if (wasPlayingBeforePause && !userPaused
                && player.getPlaybackState() != Player.STATE_ENDED) {
            player.play();
            showControlsAndScheduleHide();
        } else {
            showControls();
        }
        wasPlayingBeforePause = false;
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        foreground = false;
        saveWatchState(false);
        dismissAdvance();
        mainHandler.removeCallbacksAndMessages(null);
        cancel(playbackRequest);
        cancel(directoryRequest);
        if (networkExecutor != null) networkExecutor.shutdownNow();
        if (posterLoader != null) posterLoader.shutdown();
        playbackSession.invalidate();
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
