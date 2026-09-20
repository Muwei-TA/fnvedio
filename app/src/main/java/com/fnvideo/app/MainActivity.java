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
import java.util.HashMap;
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

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable progressTicker = this::updateActiveProgress;

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
    private FrameLayout messageOverlay;
    private ProgressBar messageSpinner;
    private TextView messageTitle;
    private TextView messageDetail;
    private TextView messageAction;
    private TextView libraryButton;
    private TextView searchButton;
    private TextView settingsButton;

    private FeedAdapter.VideoViewHolder activeHolder;
    private int activePosition = RecyclerView.NO_POSITION;
    private long libraryGeneration;
    private int playbackGeneration;
    private boolean loadingNextPage;
    private boolean firstPagePending;
    private String serverBase = "";
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
        buildUi();
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        player = new ExoPlayer.Builder(this)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                .setHandleAudioBecomingNoisy(true)
                .build();
        player.addListener(createPlayerListener());
        mainHandler.post(progressTicker);

        if (hasSession()) {
            loadLibrary("", false);
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

        adapter = new FeedAdapter(this, this);
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
                if (activeHolder == null || !PlaybackCompatibility.hasUnsupportedAudio(tracks)) {
                    return;
                }
                if (!tryCompatiblePlayback()) {
                    player.stop();
                    activeHolder.showError("当前设备无法播放此音轨，兼容播放也未成功。请检查 NAS 转码能力或换一部影片。");
                }
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                FeedAdapter.VideoViewHolder holder = activeHolder;
                if (holder == null) {
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
                if (holder != null) {
                    holder.setPlaying(isPlaying);
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                FeedAdapter.VideoViewHolder holder = activeHolder;
                if (holder == null) {
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
        if (compatibilityAttempted || destroyed || activeHolder == null
                || activePosition == RecyclerView.NO_POSITION) {
            return false;
        }
        compatibilityAttempted = true;
        long position = Math.max(0L, player.getCurrentPosition());
        preparePlayback(activePosition, activeHolder, true, position);
        return true;
    }

    private void restoreSession() {
        serverBase = normalizeBase(SessionStore.base(this));
        sessionToken = safe(SessionStore.token(this));
    }

    private boolean hasSession() {
        return !serverBase.isEmpty() && !sessionToken.isEmpty();
    }

    private void refreshRepository() {
        repository = hasSession() ? new FnApi(serverBase, sessionToken) : null;
    }

    private void loadLibrary(String query, boolean keepCurrent) {
        if (!hasSession()) {
            showSignedOut();
            return;
        }
        currentQuery = safe(query);
        saveResumePosition(false);
        final long generation = feedState.reset();
        libraryGeneration = generation;
        loadingNextPage = false;
        firstPagePending = true;
        cancel(libraryRequest);
        cancel(librariesRequest);
        cancel(playbackRequest);
        playbackGeneration++;

        if (!keepCurrent) {
            detachPlayer();
            adapter.setVideos(Collections.emptyList());
            activePosition = RecyclerView.NO_POSITION;
            activeHolder = null;
            pager.setVisibility(View.GONE);
        }
        if (adapter.getItemCount() == 0) {
            showMessage("正在连接片库", "正在从 NAS 获取真实媒体列表，请稍候。", true,
                    null, null);
        }
        requestNextPage(generation);
    }

    private void requestNextPage(long generation) {
        if (destroyed || generation != feedState.generation() || loadingNextPage
                || !feedState.hasMore() || repository == null) {
            return;
        }
        loadingNextPage = true;
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
        if (page == null) {
            onLibraryFailed(new IllegalStateException("服务端返回了空分页"));
            return;
        }
        if (!feedState.append(generation, page)) {
            return;
        }
        List<MediaRepository.Video> values = feedState.items();
        if (firstPagePending) {
            firstPagePending = false;
            saveResumePosition(false);
            detachPlayer();
            activePosition = RecyclerView.NO_POSITION;
            activeHolder = null;
            adapter.setVideos(values);
        } else if (page != null) {
            adapter.appendVideos(values);
        }
        if (values.isEmpty()) {
            if (feedState.hasMore()) {
                showMessage("正在继续加载片库", "当前页没有可播放条目，正在请求下一页。",
                        true, null, null);
                requestNextPage(generation);
            } else {
                pager.setVisibility(View.GONE);
                showMessage("片库暂无结果", currentQuery.isEmpty()
                                ? "服务端返回了空片库。请确认账号有媒体权限。"
                                : "没有匹配“" + currentQuery + "”的媒体。",
                        false, "重新加载", () -> loadLibrary(currentQuery, false));
            }
            return;
        }
        hideMessage();
        pager.setVisibility(View.VISIBLE);
        if (activePosition == RecyclerView.NO_POSITION) {
            pager.setCurrentItem(0, false);
            pager.post(() -> activatePage(0));
        }
    }

    private void onLibraryFailed(Exception error) {
        loadingNextPage = false;
        String message = actionableLibraryError(error);
        if (adapter.getItemCount() == 0) {
            pager.setVisibility(View.GONE);
            showMessage("片库加载失败", message, false, "重新加载",
                    () -> loadLibrary(currentQuery, false));
        } else {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }
    }

    private void activatePage(int position) {
        if (destroyed || position < 0 || position >= adapter.getItemCount()) {
            return;
        }
        maybeLoadNextPage(position);
        if (position == activePosition && activeHolder != null) {
            return;
        }
        saveResumePosition(false);
        cancel(playbackRequest);
        playbackGeneration++;
        detachPlayer();
        activePosition = position;
        compatibilityAttempted = false;
        activeHolder = null;
        userPaused = false;
        lastSavedPosition = -1L;
        pager.post(() -> {
            if (destroyed || activePosition != position) {
                return;
            }
            FeedAdapter.VideoViewHolder holder = findHolder(position);
            if (holder == null) {
                pager.postDelayed(() -> activatePage(position), 80L);
                return;
            }
            activeHolder = holder;
            preparePlayback(position, holder);
        });
    }

    private void maybeLoadNextPage(int position) {
        if (adapter.getItemCount() - position <= 3) {
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

    private void preparePlayback(int position, FeedAdapter.VideoViewHolder holder) {
        preparePlayback(position, holder, false);
    }

    private void preparePlayback(int position, FeedAdapter.VideoViewHolder holder, boolean compatible) {
        preparePlayback(position, holder, compatible, readResumePosition(adapter.getVideo(position)));
    }

    private void preparePlayback(int position, FeedAdapter.VideoViewHolder holder,
                                 boolean compatible, long resumePosition) {
        MediaRepository.Video video = adapter.getVideo(position);
        MediaRepository requestRepository = repository;
        if (video == null || !hasSession() || requestRepository == null) {
            holder.showError("登录状态或媒体信息已失效，请回到设置重新连接。 ");
            return;
        }
        final int generation = ++playbackGeneration;
        cancel(playbackRequest);
        player.stop();
        holder.showLoading();
        holder.setFitMode(zoomMode);
        playbackRequest = networkExecutor.submit(() -> {
            try {
                MediaRepository.Source source = compatible
                        ? requestRepository.resolveCompatible(video) : requestRepository.resolve(video);
                mainHandler.post(() -> {
                    if (!destroyed && generation == playbackGeneration
                            && position == activePosition && activeHolder == holder
                            && repository == requestRepository) {
                        attachPlayback(holder, source, resumePosition);
                    }
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (!destroyed && generation == playbackGeneration
                            && position == activePosition && activeHolder == holder
                            && repository == requestRepository) {
                        holder.showError(actionablePlaybackError(error));
                    }
                });
            }
        });
    }

    private void attachPlayback(FeedAdapter.VideoViewHolder holder, MediaRepository.Source source,
                                long resumePosition) {
        try {
            if (activeHolder != holder) {
                return;
            }
            // Keep NAS auth headers on the resolved origin. Cross-protocol redirects
            // are rejected so a signed media request cannot forward them elsewhere.
            MediaItem.Builder itemBuilder = new MediaItem.Builder().setUri(Uri.parse(source.url));
            if (!safe(source.mimeType).isEmpty()) {
                itemBuilder.setMimeType(source.mimeType);
            }
            MediaItem item = itemBuilder.build();
            player.setMediaSource(new DefaultMediaSourceFactory(PlaybackDataSource.forSource(source, serverBase))
                    .createMediaSource(item));
            holder.playerView.setPlayer(player);
            holder.setFitMode(zoomMode);
            holder.setSeekEnabled(false);
            holder.showLoading();
            player.prepare();
            if (resumePosition > 0) {
                player.seekTo(resumePosition);
            }
            if (foreground && !userPaused) {
                player.play();
            }
        } catch (Exception error) {
            holder.showError(actionablePlaybackError(error));
        }
    }

    private void detachPlayer() {
        if (activeHolder != null && player != null) {
            activeHolder.playerView.setPlayer(null);
        }
        if (player != null) {
            player.stop();
        }
    }

    private void updateActiveProgress() {
        if (!destroyed && activeHolder != null && player != null) {
            long position = player.getCurrentPosition();
            long duration = player.getDuration();
            if (activeHolder.isSeeking()) {
                activeHolder.showSeekPreview(activeHolder.itemView.getTag() instanceof Integer
                        ? (Integer) activeHolder.itemView.getTag() : 0, duration);
            } else {
                activeHolder.updateProgress(position, duration);
            }
            if (player.isPlaying() && position - lastSavedPosition >= 5_000L) {
                saveResumePosition(false);
            }
        }
        mainHandler.postDelayed(progressTicker, 500L);
    }

    private void saveResumePosition(boolean ended) {
        if (player == null || activeHolder == null || activeHolder.getVideo() == null) {
            return;
        }
        String id = safe(activeHolder.getVideo().id);
        if (id.isEmpty()) {
            return;
        }
        long position = player.getCurrentPosition();
        long duration = player.getDuration();
        String key = resumeKey(id);
        if (ended || (duration > 0 && duration != C.TIME_UNSET
                && position >= duration - RESUME_END_MARGIN_MS)) {
            resumePreferences.edit().remove(key).apply();
            lastSavedPosition = -1L;
            return;
        }
        if (position >= RESUME_MIN_MS) {
            resumePreferences.edit().putLong(key, position).apply();
            lastSavedPosition = position;
        }
    }

    private long readResumePosition(MediaRepository.Video video) {
        if (video == null || safe(video.id).isEmpty()) {
            return 0L;
        }
        return resumePreferences.getLong(resumeKey(video.id), 0L);
    }

    private String resumeKey(String videoId) {
        return "position:" + serverBase + ":" + videoId;
    }

    @Override
    public void onPageTapped(FeedAdapter.VideoViewHolder holder) {
        if (holder != activeHolder || player == null) {
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
            player.play();
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
        if (holder != activeHolder || player == null) {
            return;
        }
        holder.setSeeking(true);
        wasPlayingBeforeSeek = player.getPlayWhenReady();
        player.pause();
    }

    @Override
    public void onSeekChanged(FeedAdapter.VideoViewHolder holder, int progress) {
        if (holder == activeHolder && player != null) {
            holder.showSeekPreview(progress, player.getDuration());
            holder.itemView.setTag(progress);
        }
    }

    @Override
    public void onSeekStop(FeedAdapter.VideoViewHolder holder, int progress) {
        if (holder != activeHolder || player == null) {
            return;
        }
        long duration = player.getDuration();
        holder.setSeeking(false);
        if (duration > 0 && duration != C.TIME_UNSET) {
            player.seekTo(duration * progress / 1000L);
        }
        if (wasPlayingBeforeSeek && foreground) {
            userPaused = false;
            player.play();
        }
    }

    private void showSignedOut() {
        cancel(libraryRequest);
        cancel(librariesRequest);
        cancel(playbackRequest);
        libraryGeneration = feedState.reset();
        loadingNextPage = false;
        playbackGeneration++;
        repository = null;
        currentLibraryId = "";
        currentLibraryTitle = "";
        availableLibraries = Collections.emptyList();
        detachPlayer();
        activeHolder = null;
        activePosition = RecyclerView.NO_POSITION;
        adapter.setVideos(Collections.emptyList());
        pager.setVisibility(View.GONE);
        setBrowseButtonsEnabled(false);
        showMessage("连接你的私人片库", "登录 NAS 后，这里会显示服务端返回的真实媒体。\n\n"
                        + "客户端不会伪造视频，也不会扫描本地文件。",
                false, "登录并连接", this::startLogin);
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
        startActivityForResult(intent, REQUEST_LOGIN);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_LOGIN || resultCode != RESULT_OK || data == null) {
            return;
        }
        String base = normalizeBase(data.getStringExtra("base_url"));
        String token = safe(data.getStringExtra("token"));
        if (base.isEmpty() || token.isEmpty()) {
            showMessage("登录信息不完整", "登录页没有返回服务器地址和会话令牌，请重新登录。",
                    false, "重新登录", this::startLogin);
            return;
        }
        try {
            SessionStore.save(this, base, token);
        } catch (IllegalStateException error) {
            showMessage("登录信息保存失败", "Android 安全存储不可用，请检查系统锁屏设置后重试。\n原因："
                            + errorReason(error),
                    false, "重新登录", this::startLogin);
            return;
        }
        serverBase = base;
        sessionToken = token;
        refreshRepository();
        currentQuery = "";
        currentLibraryId = "";
        currentLibraryTitle = "";
        setBrowseButtonsEnabled(true);
        loadLibrary("", false);
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
            loadLibrary(value, true);
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
            loadLibrary("", false);
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
            String base = normalizeBase(baseField.getText().toString());
            if (base.isEmpty()) {
                baseField.setError("请输入服务器地址");
                return;
            }
            try {
                SessionStore.save(this, base, sessionToken);
            } catch (IllegalStateException error) {
                baseField.setError("安全存储不可用：" + errorReason(error));
                return;
            }
            serverBase = base;
            refreshRepository();
            dialog.dismiss();
            if (hasSession()) {
                loadLibrary(currentQuery, false);
            } else {
                showSignedOut();
            }
        });
        login.setOnClickListener(v -> {
            dialog.dismiss();
            startLogin();
        });
        logout.setOnClickListener(v -> {
            SessionStore.clear(this);
            dialog.dismiss();
            serverBase = "";
            sessionToken = "";
            repository = null;
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
        String reason = errorReason(error);
        if (reason.contains("401") || reason.contains("403") || reason.contains("unauthor")) {
            return "账号没有通过服务端鉴权。请重新登录并确认媒体库权限。\n原因：" + reason;
        }
        if (reason.contains("rejected request") || reason.contains("failed with HTTP")) {
            return "已连接 NAS，但服务端未能完成片库请求。请重试或切换媒体库。\n原因：" + reason;
        }
        return "片库请求未完成，请检查网络连接后重试。\n原因：" + reason;
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
        while (current != null) {
            String message = safe(current.getMessage());
            if (!message.isEmpty()) {
                return message.length() > 180 ? message.substring(0, 180) : message;
            }
            current = current.getCause();
        }
        return "服务端未提供详细错误";
    }

    private String videoTitle(MediaRepository.Video video) {
        if (video == null) {
            return "未命名视频";
        }
        String title = safe(video.title);
        return title.isEmpty() ? safe(video.id) : title;
    }

    private String videoSubtitle(MediaRepository.Video video) {
        return video == null ? "" : safe(video.subtitle);
    }

    private static void cancel(Future<?> request) {
        if (request != null && !request.isDone()) {
            request.cancel(true);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeBase(String value) {
        String base = safe(value);
        while (base.endsWith("/") && base.length() > 1) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
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
        saveResumePosition(false);
        wasPlayingBeforePause = player != null && player.isPlaying();
        if (player != null) {
            player.pause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
        if (!userPaused && activeHolder != null && player != null
                && (wasPlayingBeforePause || player.getPlaybackState() != Player.STATE_IDLE)) {
            player.play();
        }
        wasPlayingBeforePause = false;
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        libraryGeneration = feedState.reset();
        playbackGeneration++;
        cancel(libraryRequest);
        cancel(librariesRequest);
        cancel(playbackRequest);
        saveResumePosition(false);
        mainHandler.removeCallbacks(progressTicker);
        if (networkExecutor != null) {
            networkExecutor.shutdownNow();
        }
        if (player != null) {
            player.release();
            player = null;
        }
        repository = null;
        super.onDestroy();
    }
}
