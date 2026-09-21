package com.fnvideo.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One full-height video page for the portrait feed.
 *
 * The adapter owns only page chrome. MainActivity owns the single ExoPlayer
 * instance and attaches it to the active holder's PlayerView.
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class FeedAdapter extends RecyclerView.Adapter<FeedAdapter.VideoViewHolder> {
    public interface Listener {
        void onPageTapped(VideoViewHolder holder);

        void onFitToggle(VideoViewHolder holder);

        void onRetry(VideoViewHolder holder);

        void onSeekStart(VideoViewHolder holder);

        void onSeekChanged(VideoViewHolder holder, int progress);

        void onSeekStop(VideoViewHolder holder, int progress);

        void onHorizontalSeekStart(VideoViewHolder holder);

        void onHorizontalSeek(VideoViewHolder holder, float deltaPx);

        void onHorizontalSeekEnd(VideoViewHolder holder);

        void onSpeedPressStart(VideoViewHolder holder);

        void onSpeedPressEnd(VideoViewHolder holder);

        void onEpisodesBrowse(VideoViewHolder holder);

        /** Enabled only for the explicit 随看 queue; the legacy feed keeps vertical drags inert. */
        void onVerticalSwipe(VideoViewHolder holder, float deltaY);
    }

    private static final int BACKGROUND = Color.rgb(8, 10, 14);
    private static final int PRIMARY = Color.rgb(246, 247, 249);
    private static final int SECONDARY = Color.rgb(174, 181, 193);
    private static final int ACCENT = Color.rgb(255, 183, 77);
    private static final int SURFACE = Color.argb(190, 20, 23, 30);

    private final Listener listener;
    private final PosterLoader posterLoader;
    private List<MediaRepository.Video> videos = Collections.emptyList();
    private boolean verticalSwipeEnabled;

    public FeedAdapter(@NonNull Context context, @NonNull Listener listener) {
        this(context, listener, null);
    }

    public FeedAdapter(@NonNull Context context, @NonNull Listener listener,
                       PosterLoader posterLoader) {
        this.listener = listener;
        this.posterLoader = posterLoader;
    }

    public void setVideos(List<MediaRepository.Video> values) {
        if (values == null || values.isEmpty()) {
            videos = Collections.emptyList();
        } else {
            videos = Collections.unmodifiableList(new ArrayList<>(values));
        }
        notifyDataSetChanged();
    }

    /** Appends a page while preserving existing holders and the active player view. */
    public void appendVideos(List<MediaRepository.Video> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        ArrayList<MediaRepository.Video> next = new ArrayList<>(videos);
        java.util.HashSet<String> ids = new java.util.HashSet<>();
        for (MediaRepository.Video video : next) {
            if (video != null && video.id != null && !video.id.isEmpty()) {
                ids.add(video.id);
            }
        }
        int oldCount = next.size();
        for (MediaRepository.Video video : values) {
            if (video != null && video.id != null && !video.id.isEmpty() && ids.add(video.id)) {
                next.add(video);
            }
        }
        if (next.size() == oldCount) {
            return;
        }
        videos = Collections.unmodifiableList(next);
        notifyItemRangeInserted(oldCount, next.size() - oldCount);
    }

    public List<MediaRepository.Video> getVideos() {
        return videos;
    }

    public void setVerticalSwipeEnabled(boolean enabled) {
        verticalSwipeEnabled = enabled;
    }

    public MediaRepository.Video getVideo(int position) {
        if (position < 0 || position >= videos.size()) {
            return null;
        }
        return videos.get(position);
    }

    @Override
    public int getItemCount() {
        return videos.size();
    }

    @NonNull
    @Override
    public VideoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return createPage(parent.getContext());
    }

    @Override
    public void onBindViewHolder(@NonNull VideoViewHolder holder, int position) {
        holder.bind(getVideo(position));
    }

    private VideoViewHolder createPage(Context pageContext) {
        FrameLayout page = new FrameLayout(pageContext);
        page.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        page.setBackgroundColor(BACKGROUND);
        page.setClickable(true);
        page.setFocusable(true);

        PlayerView playerView = new PlayerView(pageContext);
        playerView.setUseController(false);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerView.setKeepContentOnPlayerReset(true);
        playerView.setShutterBackgroundColor(Color.BLACK);
        playerView.setBackgroundColor(Color.BLACK);
        playerView.setClickable(true);
        page.addView(playerView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView poster = new ImageView(pageContext);
        poster.setScaleType(ImageView.ScaleType.FIT_CENTER);
        poster.setBackgroundColor(Color.BLACK);
        poster.setVisibility(View.GONE);
        poster.setClickable(false);
        page.addView(poster, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        View bottomShade = new View(pageContext);
        GradientDrawable shade = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.TRANSPARENT, Color.argb(238, 3, 4, 6)});
        bottomShade.setBackground(shade);
        FrameLayout.LayoutParams shadeParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(pageContext, 330), Gravity.BOTTOM);
        page.addView(bottomShade, shadeParams);

        LinearLayout bottomInfo = new LinearLayout(pageContext);
        bottomInfo.setOrientation(LinearLayout.VERTICAL);
        bottomInfo.setPadding(dp(pageContext, 20), 0, dp(pageContext, 20), dp(pageContext, 8));
        bottomInfo.setClickable(false);
        FrameLayout.LayoutParams infoParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        infoParams.bottomMargin = dp(pageContext, 10);
        page.addView(bottomInfo, infoParams);

        TextView title = text(pageContext, 21, PRIMARY, Typeface.BOLD);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bottomInfo.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView subtitle = text(pageContext, 14, SECONDARY, Typeface.NORMAL);
        subtitle.setMaxLines(2);
        subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        subtitle.setPadding(0, dp(pageContext, 5), 0, 0);
        bottomInfo.addView(subtitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView episodesButton = pillButton(pageContext, "剧集");
        episodesButton.setContentDescription("浏览剧集分集");
        episodesButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams episodesParams = new LinearLayout.LayoutParams(
                dp(pageContext, 72), dp(pageContext, 38));
        episodesParams.topMargin = dp(pageContext, 8);
        bottomInfo.addView(episodesButton, episodesParams);

        LinearLayout controls = new LinearLayout(pageContext);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(0, dp(pageContext, 12), 0, 0);
        bottomInfo.addView(controls, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(pageContext, 46)));

        TextView time = text(pageContext, 12, SECONDARY, Typeface.NORMAL);
        time.setGravity(Gravity.CENTER_VERTICAL);
        time.setText("00:00 / --:--");
        controls.addView(time, new LinearLayout.LayoutParams(dp(pageContext, 94),
                ViewGroup.LayoutParams.MATCH_PARENT));

        SeekBar seekBar = new SeekBar(pageContext);
        seekBar.setMax(1000);
        seekBar.setProgress(0);
        seekBar.setPadding(dp(pageContext, 6), 0, dp(pageContext, 6), 0);
        seekBar.setContentDescription("拖动调整播放进度");
        controls.addView(seekBar, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        TextView fit = pillButton(pageContext, "适应");
        fit.setContentDescription("切换适应画面或放大画面");
        LinearLayout.LayoutParams fitParams = new LinearLayout.LayoutParams(
                dp(pageContext, 64), dp(pageContext, 36));
        fitParams.leftMargin = dp(pageContext, 8);
        controls.addView(fit, fitParams);

        ProgressBar loading = new ProgressBar(pageContext);
        loading.setIndeterminate(true);
        FrameLayout.LayoutParams loadingParams = new FrameLayout.LayoutParams(
                dp(pageContext, 54), dp(pageContext, 54), Gravity.CENTER);
        page.addView(loading, loadingParams);

        TextView playIndicator = text(pageContext, 27, PRIMARY, Typeface.BOLD);
        playIndicator.setText("▶");
        playIndicator.setGravity(Gravity.CENTER);
        playIndicator.setPadding(dp(pageContext, 4), 0, 0, 0);
        playIndicator.setBackground(circleBackground(Color.argb(205, 17, 20, 27), ACCENT));
        playIndicator.setVisibility(View.GONE);
        playIndicator.setContentDescription("播放");
        FrameLayout.LayoutParams playParams = new FrameLayout.LayoutParams(
                dp(pageContext, 74), dp(pageContext, 74), Gravity.CENTER);
        page.addView(playIndicator, playParams);

        TextView seekPreview = text(pageContext, 25, PRIMARY, Typeface.BOLD);
        seekPreview.setGravity(Gravity.CENTER);
        seekPreview.setPadding(dp(pageContext, 24), dp(pageContext, 16),
                dp(pageContext, 24), dp(pageContext, 16));
        seekPreview.setBackground(roundBackground(Color.argb(225, 17, 20, 27), 16));
        seekPreview.setVisibility(View.GONE);
        seekPreview.setContentDescription("拖动目标时间");
        seekPreview.setClickable(false);
        page.addView(seekPreview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        LinearLayout errorPanel = new LinearLayout(pageContext);
        errorPanel.setOrientation(LinearLayout.VERTICAL);
        errorPanel.setGravity(Gravity.CENTER_HORIZONTAL);
        errorPanel.setPadding(dp(pageContext, 24), dp(pageContext, 18), dp(pageContext, 24),
                dp(pageContext, 18));
        errorPanel.setBackground(roundBackground(Color.argb(220, 17, 20, 27), 18));
        errorPanel.setVisibility(View.GONE);
        FrameLayout.LayoutParams errorParams = new FrameLayout.LayoutParams(
                dp(pageContext, 292), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        page.addView(errorPanel, errorParams);

        TextView errorTitle = text(pageContext, 16, PRIMARY, Typeface.BOLD);
        errorTitle.setText("暂时无法播放");
        errorPanel.addView(errorTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView errorDetail = text(pageContext, 13, SECONDARY, Typeface.NORMAL);
        errorDetail.setGravity(Gravity.CENTER);
        errorDetail.setMaxLines(4);
        errorDetail.setPadding(0, dp(pageContext, 8), 0, dp(pageContext, 12));
        errorPanel.addView(errorDetail, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView retry = pillButton(pageContext, "重试");
        retry.setContentDescription("重试加载视频");
        errorPanel.addView(retry, new LinearLayout.LayoutParams(
                dp(pageContext, 112), dp(pageContext, 44)));

        TextView speedIndicator = pillButton(pageContext, "2.0x");
        speedIndicator.setTextSize(15);
        speedIndicator.setVisibility(View.GONE);
        speedIndicator.setContentDescription("倍速播放中");
        FrameLayout.LayoutParams speedParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(pageContext, 40),
                Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        speedParams.topMargin = dp(pageContext, 120);
        page.addView(speedIndicator, speedParams);

        VideoViewHolder holder = new VideoViewHolder(page, playerView, poster, bottomShade,
                bottomInfo, title, subtitle, time, seekBar, fit, loading, playIndicator,
                errorPanel, errorDetail, retry, speedIndicator, episodesButton, seekPreview);
        GestureDetector pageGestures = new GestureDetector(pageContext,
                new GestureDetector.SimpleOnGestureListener() {
                    private final float swipeThreshold = Math.max(dp(pageContext, 24),
                            ViewConfiguration.get(pageContext).getScaledTouchSlop());
                    private boolean verticalDrag;
                    private boolean verticalSwipeDispatched;

                    @Override
                    public boolean onDown(MotionEvent event) {
                        verticalDrag = false;
                        verticalSwipeDispatched = false;
                        return true;
                    }

                    @Override
                    public boolean onScroll(MotionEvent begin, MotionEvent current,
                                            float distanceX, float distanceY) {
                        float totalX = current.getX() - begin.getX();
                        float totalY = current.getY() - begin.getY();
                        if (holder.isSpeedPressed() || verticalDrag) {
                            return true;
                        }
                        if (!holder.isHorizontalSeekActive()) {
                            if (Math.max(Math.abs(totalX), Math.abs(totalY)) <= swipeThreshold) {
                                return true;
                            }
                            if (Math.abs(totalY) >= Math.abs(totalX)) {
                                verticalDrag = true;
                                if (verticalSwipeEnabled && !verticalSwipeDispatched) {
                                    verticalSwipeDispatched = true;
                                    listener.onVerticalSwipe(holder, totalY);
                                }
                                return true;
                            }
                            holder.markHorizontalSeekActive(true);
                            if (holder.itemView.getParent() != null) {
                                holder.itemView.getParent().requestDisallowInterceptTouchEvent(true);
                            }
                            listener.onHorizontalSeekStart(holder);
                        }
                        listener.onHorizontalSeek(holder, totalX);
                        return true;
                    }

                    @Override
                    public boolean onSingleTapUp(MotionEvent event) {
                        holder.playerView.performClick();
                        return true;
                    }

                    @Override
                    public void onLongPress(MotionEvent event) {
                        if (holder.isHorizontalSeekActive()) {
                            return;
                        }
                        holder.setSpeedPressed(true);
                        listener.onSpeedPressStart(holder);
                    }
                });
        View.OnTouchListener pageTouch = (view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP
                    || event.getAction() == MotionEvent.ACTION_CANCEL) {
                if (holder.isHorizontalSeekActive()) {
                    holder.markHorizontalSeekActive(false);
                    listener.onHorizontalSeekEnd(holder);
                }
                if (holder.isSpeedPressed()) {
                    holder.setSpeedPressed(false);
                    listener.onSpeedPressEnd(holder);
                }
            }
            pageGestures.onTouchEvent(event);
            // Own the full stream: GestureDetector may return false for UP/CANCEL.
            // Falling through would let PlayerView dispatch an extra click.
            return true;
        };
        playerView.setOnTouchListener(pageTouch);
        page.setOnTouchListener(pageTouch);
        playerView.setOnClickListener(v -> listener.onPageTapped(holder));
        page.setOnClickListener(v -> listener.onPageTapped(holder));
        fit.setOnClickListener(v -> listener.onFitToggle(holder));
        retry.setOnClickListener(v -> listener.onRetry(holder));
        episodesButton.setOnClickListener(v -> listener.onEpisodesBrowse(holder));
        playIndicator.setOnClickListener(v -> listener.onPageTapped(holder));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    listener.onSeekChanged(holder, progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                listener.onSeekStart(holder);
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                listener.onSeekStop(holder, bar.getProgress());
            }
        });
        return holder;
    }

    private static TextView text(Context context, float sizeSp, int color, int style) {
        TextView value = new TextView(context);
        value.setTextSize(sizeSp);
        value.setTextColor(color);
        value.setTypeface(Typeface.create("sans-serif", style));
        value.setIncludeFontPadding(false);
        return value;
    }

    private static TextView pillButton(Context context, String label) {
        TextView button = text(context, 13, PRIMARY, Typeface.BOLD);
        button.setText(label);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(context, 44));
        button.setPadding(dp(context, 10), 0, dp(context, 10), 0);
        button.setBackground(roundBackground(SURFACE, 18));
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    private static GradientDrawable roundBackground(int fillColor, float radiusDp) {
        GradientDrawable value = new GradientDrawable();
        value.setColor(fillColor);
        value.setCornerRadius(radiusDp);
        value.setStroke(1, Color.argb(90, 255, 255, 255));
        return value;
    }

    private static GradientDrawable circleBackground(int fillColor, int strokeColor) {
        GradientDrawable value = new GradientDrawable();
        value.setShape(GradientDrawable.OVAL);
        value.setColor(fillColor);
        value.setStroke(dpStatic(1), strokeColor);
        return value;
    }

    private static int dpStatic(int value) {
        return value;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public final class VideoViewHolder extends RecyclerView.ViewHolder {
        public final PlayerView playerView;
        public final ImageView posterView;
        private final TextView titleView;
        private final TextView subtitleView;
        private final View bottomShade;
        private final View bottomInfo;
        private final TextView timeView;
        private final SeekBar seekBar;
        private final TextView fitButton;
        private final ProgressBar loading;
        private final TextView playIndicator;
        private final LinearLayout errorPanel;
        private final TextView errorDetail;
        private final TextView retryButton;
        private final TextView speedIndicator;
        private final TextView episodesButton;
        private final TextView seekPreview;
        private MediaRepository.Video video;
        private boolean seeking;
        private boolean showPlayIndicator;
        private boolean horizontalSeekActive;
        private boolean speedPressed;
        private int posterBindGeneration;
        private boolean holderReady;

        private VideoViewHolder(FrameLayout page, PlayerView playerView, ImageView posterView,
                                View bottomShade, View bottomInfo, TextView titleView,
                                TextView subtitleView, TextView timeView, SeekBar seekBar,
                                TextView fitButton, ProgressBar loading, TextView playIndicator,
                                LinearLayout errorPanel, TextView errorDetail,
                                TextView retryButton, TextView speedIndicator,
                                TextView episodesButton, TextView seekPreview) {
            super(page);
            this.playerView = playerView;
            this.posterView = posterView;
            this.bottomShade = bottomShade;
            this.bottomInfo = bottomInfo;
            this.titleView = titleView;
            this.subtitleView = subtitleView;
            this.timeView = timeView;
            this.seekBar = seekBar;
            this.fitButton = fitButton;
            this.loading = loading;
            this.playIndicator = playIndicator;
            this.errorPanel = errorPanel;
            this.errorDetail = errorDetail;
            this.retryButton = retryButton;
            this.speedIndicator = speedIndicator;
            this.episodesButton = episodesButton;
            this.seekPreview = seekPreview;
        }

        void bind(MediaRepository.Video value) {
            video = value;
            posterBindGeneration++;
            final int generation = posterBindGeneration;
            String posterUrl = value == null ? "" : safe(value.poster);
            posterView.setImageDrawable(null);
            posterView.setVisibility(View.GONE);
            if (!posterUrl.isEmpty() && posterLoader != null) {
                posterLoader.load(posterUrl, bitmap -> {
                    if (posterBindGeneration == generation) {
                        posterView.setImageBitmap(bitmap);
                        if (!holderReady) {
                            posterView.setVisibility(View.VISIBLE);
                        }
                    }
                });
            }
            String title = value == null ? "" : safe(value.title);
            if (title.isEmpty() && value != null) {
                title = safe(value.id);
            }
            titleView.setText(title.isEmpty() ? "未命名视频" : title);
            String subtitle = value == null ? "" : safe(value.subtitle);
            subtitleView.setText(subtitle);
            subtitleView.setVisibility(subtitle.isEmpty() ? View.GONE : View.VISIBLE);
            boolean episode = value != null && "Episode".equalsIgnoreCase(safe(value.type));
            episodesButton.setVisibility(episode && !safe(value.parentId).isEmpty()
                    ? View.VISIBLE : View.GONE);
            resetStatus();
        }

        public MediaRepository.Video getVideo() {
            return video;
        }

        public boolean isSeeking() {
            return seeking;
        }

        public void setSeeking(boolean value) {
            seeking = value;
            if (!value) {
                seekPreview.setVisibility(View.GONE);
            }
            updatePlayIndicator();
        }

        public boolean isHorizontalSeekActive() {
            return horizontalSeekActive;
        }

        public void markHorizontalSeekActive(boolean value) {
            horizontalSeekActive = value;
        }

        public boolean isSpeedPressed() {
            return speedPressed;
        }

        public void setSpeedPressed(boolean value) {
            speedPressed = value;
        }

        public void showSpeedIndicator(float speed) {
            speedIndicator.setText(java.text.DecimalFormatSymbols
                    .getInstance(java.util.Locale.US).getDecimalSeparator() == '.'
                    ? String.format(java.util.Locale.US, "%.1fx", speed)
                    : String.valueOf(speed));
            speedIndicator.setVisibility(View.VISIBLE);
        }

        public void hideSpeedIndicator() {
            speedIndicator.setVisibility(View.GONE);
        }

        public void showLoading() {
            seekPreview.setVisibility(View.GONE);
            showPlayIndicator = false;
            holderReady = false;
            loading.setVisibility(View.VISIBLE);
            errorPanel.setVisibility(View.GONE);
            playIndicator.setVisibility(View.GONE);
        }

        public void showBuffering() {
            if (errorPanel.getVisibility() != View.VISIBLE) {
                loading.setVisibility(View.VISIBLE);
            }
        }

        public void showReady() {
            holderReady = true;
            loading.setVisibility(View.GONE);
            errorPanel.setVisibility(View.GONE);
            posterView.setVisibility(View.GONE);
        }

        public void showError(String detail) {
            seekPreview.setVisibility(View.GONE);
            showPlayIndicator = false;
            holderReady = false;
            loading.setVisibility(View.GONE);
            playIndicator.setVisibility(View.GONE);
            errorDetail.setText(detail == null || detail.trim().isEmpty()
                    ? "请检查服务端媒体权限后重试。" : detail);
            errorPanel.setVisibility(View.VISIBLE);
        }

        public void setPlaying(boolean playing) {
            showPlayIndicator = !playing;
            updatePlayIndicator();
        }

        /** PlayerActivity uses this for the 2.8s immersive control timeout. */
        public void setControlsVisible(boolean visible) {
            bottomShade.setVisibility(visible ? View.VISIBLE : View.GONE);
            bottomInfo.setVisibility(visible ? View.VISIBLE : View.GONE);
            if (!visible) {
                seekPreview.setVisibility(View.GONE);
            }
        }

        public boolean areControlsVisible() {
            return bottomInfo.getVisibility() == View.VISIBLE;
        }

        private void updatePlayIndicator() {
            playIndicator.setVisibility(showPlayIndicator && !seeking
                    ? View.VISIBLE : View.GONE);
        }

        public void setFitMode(boolean zoom) {
            fitButton.setText(zoom ? "放大" : "适应");
            playerView.setResizeMode(zoom
                    ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    : AspectRatioFrameLayout.RESIZE_MODE_FIT);
        }

        public void setSeekEnabled(boolean enabled) {
            seekBar.setEnabled(enabled);
            seekBar.setAlpha(enabled ? 1f : 0.45f);
        }

        public void updateProgress(long positionMs, long durationMs) {
            if (!seeking) {
                int progress = durationMs > 0 && durationMs != androidx.media3.common.C.TIME_UNSET
                        ? (int) Math.min(1000L, Math.max(0L, positionMs * 1000L / durationMs)) : 0;
                seekBar.setProgress(progress);
            }
            timeView.setText(formatTime(positionMs) + " / " + formatTime(durationMs));
        }

        public void showSeekPreview(int progress, long durationMs) {
            long preview = durationMs > 0 && durationMs != androidx.media3.common.C.TIME_UNSET
                    ? durationMs * Math.max(0, Math.min(1000, progress)) / 1000L : 0L;
            String label = formatTime(preview) + " / " + formatTime(durationMs);
            timeView.setText(label);
            if (seeking) {
                seekPreview.setText(label);
                seekPreview.setVisibility(View.VISIBLE);
            }
        }

        private void resetStatus() {
            showPlayIndicator = false;
            holderReady = false;
            loading.setVisibility(View.GONE);
            errorPanel.setVisibility(View.GONE);
            playIndicator.setVisibility(View.GONE);
            setControlsVisible(true);
            seekBar.setProgress(0);
            timeView.setText("00:00 / --:--");
            setSeekEnabled(false);
            setSeeking(false);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String formatTime(long millis) {
        if (millis < 0 || millis == androidx.media3.common.C.TIME_UNSET) {
            return "--:--";
        }
        long totalSeconds = millis / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0) {
            return String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds);
    }
}
