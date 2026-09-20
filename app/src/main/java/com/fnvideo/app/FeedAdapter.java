package com.fnvideo.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
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
    }

    private static final int BACKGROUND = Color.rgb(8, 10, 14);
    private static final int PRIMARY = Color.rgb(246, 247, 249);
    private static final int SECONDARY = Color.rgb(174, 181, 193);
    private static final int ACCENT = Color.rgb(255, 183, 77);
    private static final int SURFACE = Color.argb(190, 20, 23, 30);

    private final Listener listener;
    private List<MediaRepository.Video> videos = Collections.emptyList();

    public FeedAdapter(@NonNull Context context, @NonNull Listener listener) {
        this.listener = listener;
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

        VideoViewHolder holder = new VideoViewHolder(page, playerView, title, subtitle, time,
                seekBar, fit, loading, playIndicator, errorPanel, errorDetail, retry);
        playerView.setOnClickListener(v -> listener.onPageTapped(holder));
        page.setOnClickListener(v -> listener.onPageTapped(holder));
        fit.setOnClickListener(v -> listener.onFitToggle(holder));
        retry.setOnClickListener(v -> listener.onRetry(holder));
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
        private final TextView titleView;
        private final TextView subtitleView;
        private final TextView timeView;
        private final SeekBar seekBar;
        private final TextView fitButton;
        private final ProgressBar loading;
        private final TextView playIndicator;
        private final LinearLayout errorPanel;
        private final TextView errorDetail;
        private final TextView retryButton;
        private MediaRepository.Video video;
        private boolean seeking;

        private VideoViewHolder(FrameLayout page, PlayerView playerView, TextView titleView,
                                TextView subtitleView, TextView timeView, SeekBar seekBar,
                                TextView fitButton, ProgressBar loading, TextView playIndicator,
                                LinearLayout errorPanel, TextView errorDetail,
                                TextView retryButton) {
            super(page);
            this.playerView = playerView;
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
        }

        private void bind(MediaRepository.Video value) {
            video = value;
            String title = value == null ? "" : safe(value.title);
            if (title.isEmpty() && value != null) {
                title = safe(value.id);
            }
            titleView.setText(title.isEmpty() ? "未命名视频" : title);
            String subtitle = value == null ? "" : safe(value.subtitle);
            subtitleView.setText(subtitle);
            subtitleView.setVisibility(subtitle.isEmpty() ? View.GONE : View.VISIBLE);
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
        }

        public void showLoading() {
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
            loading.setVisibility(View.GONE);
            errorPanel.setVisibility(View.GONE);
        }

        public void showError(String detail) {
            loading.setVisibility(View.GONE);
            playIndicator.setVisibility(View.GONE);
            errorDetail.setText(detail == null || detail.trim().isEmpty()
                    ? "请检查服务端媒体权限后重试。" : detail);
            errorPanel.setVisibility(View.VISIBLE);
        }

        public void setPlaying(boolean playing) {
            playIndicator.setVisibility(playing ? View.GONE : View.VISIBLE);
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
                    ? durationMs * progress / 1000L : 0L;
            timeView.setText(formatTime(preview) + " / " + formatTime(durationMs));
        }

        private void resetStatus() {
            loading.setVisibility(View.GONE);
            errorPanel.setVisibility(View.GONE);
            playIndicator.setVisibility(View.GONE);
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
        if (millis <= 0 || millis == androidx.media3.common.C.TIME_UNSET) {
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
