package com.ccs.wiredroid.ui;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.FullScreenHelper;
import com.ccs.wiredroid.util.MediaCodecProbe;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * Audio and video player built on the platform decoders.
 *
 * <p>Container and codec support is whatever the device provides, which always
 * includes the royalty-free set — VP8/VP9/AV1 video and Vorbis/Opus/FLAC audio in
 * WebM and Matroska. Before playback the file's tracks are probed with
 * {@link MediaCodecProbe} so an unsupported codec produces a specific message
 * naming the codec instead of a bare error number.</p>
 */
public class MediaPlayerActivity extends AppCompatActivity {

    private static final String EXTRA_FILE_PATH = "file_path";
    private static final int SEEK_STEP_MS = 10_000;
    private static final int SEEK_BAR_RANGE = 1000;

    /** {@code .ts} is deliberately absent — it collides with TypeScript sources. */
    private static final String[] VIDEO_EXTENSIONS = {
            ".webm", ".mkv", ".mp4", ".m4v", ".mov", ".3gp", ".avi", ".ogv", ".mpg", ".mpeg"
    };
    private static final String[] AUDIO_EXTENSIONS = {
            ".opus", ".ogg", ".oga", ".flac", ".wav", ".mp3", ".m4a", ".aac", ".mka", ".mid", ".amr"
    };

    private MediaPlayer mediaPlayer;
    /** The player behind {@link #videoView}, captured in {@code onPrepared}. */
    private MediaPlayer videoMediaPlayer;
    private VideoView videoView;
    private Handler handler;
    private AudioManager audioManager;
    private PowerManager.WakeLock wakeLock;

    private File mediaFile;
    private boolean isVideo;
    private boolean isPrepared;
    private boolean isUserSeeking;
    private boolean isLandscape;
    private float playbackSpeed = 1f;
    private MediaCodecProbe.Result probe;

    private AppTheme theme;

    // One control set is built, for whichever mode applies.
    private TextView tvTitle;
    private TextView tvStatus;
    private TextView tvCurrentTime;
    private TextView tvDuration;
    private SeekBar seekBar;
    private SeekBar volumeBar;
    private SeekBar brightnessBar;
    private TextView btnPlayPause;
    private TextView btnStop;
    private TextView btnRew;
    private TextView btnFwd;
    private TextView btnLandscape;
    private TextView btnSpeed;
    private LinearLayout controls;
    private FrameLayout videoContainer;
    private ProgressBar bufferProgress;

    public static void launch(Context context, File mediaFile) {
        // The probe in onCreate needs the device's codec table; start building it
        // now so it is ready while the activity is still starting.
        MediaCodecProbe.warmUp();
        Intent i = new Intent(context, MediaPlayerActivity.class);
        i.putExtra(EXTRA_FILE_PATH, mediaFile.getAbsolutePath());
        context.startActivity(i);
    }

    /** True when the extension names a video container this player handles. */
    public static boolean isVideoFile(String name) {
        return matchesAny(name, VIDEO_EXTENSIONS);
    }

    /** True when the extension names an audio format this player handles. */
    public static boolean isAudioFile(String name) {
        return matchesAny(name, AUDIO_EXTENSIONS);
    }

    /** True for any media file this player will attempt to open. */
    public static boolean isMediaFile(String name) {
        return isVideoFile(name) || isAudioFile(name);
    }

    private static boolean matchesAny(String name, String[] extensions) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : extensions) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppPreferences prefs = new AppPreferences(this);
        theme = AppTheme.byId(prefs.getThemeId(), prefs);
        setTheme(theme.dark ? R.style.Theme_WireDroid : R.style.Theme_WireDroid_Light);
        super.onCreate(savedInstanceState);

        handler = new Handler(Looper.getMainLooper());
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        String filePath = getIntent().getStringExtra(EXTRA_FILE_PATH);
        if (filePath == null) { finish(); return; }
        mediaFile = new File(filePath);
        if (!mediaFile.exists()) {
            Toast.makeText(this, R.string.media_file_not_found, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        probe = MediaCodecProbe.probe(mediaFile);
        // Trust the container over the file extension: an .mkv holding only audio
        // should open as audio, and a mislabelled file still plays.
        isVideo = probe.error == null ? probe.hasVideo() : isVideoFile(mediaFile.getName());

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        // A video keeps the screen on; audio only needs the CPU.
        wakeLock = pm.newWakeLock(
                isVideo ? PowerManager.SCREEN_BRIGHT_WAKE_LOCK : PowerManager.PARTIAL_WAKE_LOCK,
                "WireDroid:Media");

        if (isVideo) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            isLandscape = true;
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        setContentView(buildRoot());
        FullScreenHelper.enable(this);

        reportUnsupportedTracks();
        initPlayback();
    }

    // ─── UI ─────────────────────────────────────────────────────────────────

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.bg);

        Toolbar toolbar = new Toolbar(this);
        toolbar.setBackgroundColor(theme.toolbar);
        toolbar.setTitle(isVideo ? R.string.media_video_player : R.string.media_audio_player);
        toolbar.setTitleTextColor(theme.text);
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());
        setSupportActionBar(toolbar);
        root.addView(toolbar);

        tvTitle = new TextView(this);
        tvTitle.setPadding(dp(16), dp(8), dp(16), dp(2));
        tvTitle.setTextColor(theme.text);
        tvTitle.setTextSize(14);
        tvTitle.setMaxLines(1);
        tvTitle.setText(mediaFile.getName());
        root.addView(tvTitle);

        tvStatus = new TextView(this);
        tvStatus.setPadding(dp(16), 0, dp(16), dp(4));
        tvStatus.setTextColor(theme.textDim);
        tvStatus.setTextSize(11);
        tvStatus.setText(describeTracks());
        tvStatus.setOnClickListener(v -> showTrackInfo());
        root.addView(tvStatus);

        if (isVideo) {
            root.addView(buildVideoSurface(), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }

        controls = isVideo ? buildVideoControls() : buildAudioControls();
        root.addView(controls);
        return root;
    }

    private View buildVideoSurface() {
        videoContainer = new FrameLayout(this);
        videoContainer.setBackgroundColor(0xFF000000);

        videoView = new VideoView(this);
        videoContainer.addView(videoView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));

        bufferProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bufferProgress.setIndeterminate(true);
        bufferProgress.setVisibility(View.GONE);
        FrameLayout.LayoutParams bufLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        bufLp.gravity = Gravity.BOTTOM;
        videoContainer.addView(bufferProgress, bufLp);

        // Tapping the picture hides the controls for a clean full-screen view.
        videoContainer.setOnClickListener(v ->
                controls.setVisibility(controls.getVisibility() == View.VISIBLE
                        ? View.GONE : View.VISIBLE));
        return videoContainer;
    }

    private LinearLayout buildVideoControls() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0xCC000000);
        box.setPadding(dp(12), dp(6), dp(12), dp(6));

        box.addView(buildTimeRow(0xFFFFFFFF));
        box.addView(buildSeekBar());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(4), 0, 0);

        btnRew = videoButton("⏪", R.string.a11y_media_rewind);
        btnPlayPause = videoButton("▶", R.string.a11y_media_play_pause);
        btnStop = videoButton("⏹", R.string.a11y_media_stop);
        btnFwd = videoButton("⏩", R.string.a11y_media_forward);
        btnSpeed = videoButton("1.0×", R.string.a11y_media_speed);
        btnLandscape = videoButton("⛶", R.string.a11y_media_landscape);

        row.addView(btnRew);
        row.addView(btnPlayPause);
        row.addView(btnStop);
        row.addView(btnFwd);
        row.addView(btnSpeed);
        row.addView(btnLandscape);
        box.addView(row);
        return box;
    }

    private LinearLayout buildAudioControls() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(16));

        box.addView(buildTimeRow(theme.textDim));
        box.addView(buildSeekBar());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(12);
        row.setLayoutParams(rowLp);

        btnRew = audioButton("⏪", R.string.a11y_media_rewind);
        btnPlayPause = audioButton("▶", R.string.a11y_media_play_pause);
        btnStop = audioButton("⏹", R.string.a11y_media_stop);
        btnFwd = audioButton("⏩", R.string.a11y_media_forward);
        btnSpeed = audioButton("1.0×", R.string.a11y_media_speed);

        row.addView(btnRew);
        row.addView(btnPlayPause);
        row.addView(btnStop);
        row.addView(btnFwd);
        row.addView(btnSpeed);
        box.addView(row);

        box.addView(buildVolumeRow());
        box.addView(buildBrightnessRow());
        return box;
    }

    private LinearLayout buildTimeRow(int textColor) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        AppPreferences prefs = new AppPreferences(this);
        tvCurrentTime = new TextView(this);
        tvCurrentTime.setText("00:00");
        tvCurrentTime.setTextColor(textColor);
        tvCurrentTime.setTextSize(11);
        tvCurrentTime.setTypeface(prefs.resolveTypeface());

        tvDuration = new TextView(this);
        tvDuration.setText("00:00");
        tvDuration.setTextColor(textColor);
        tvDuration.setTextSize(11);
        tvDuration.setTypeface(prefs.resolveTypeface());
        tvDuration.setGravity(Gravity.END);
        tvDuration.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(tvCurrentTime);
        row.addView(tvDuration);
        return row;
    }

    private SeekBar buildSeekBar() {
        seekBar = new SeekBar(this);
        seekBar.setContentDescription(getString(R.string.a11y_media_seek));
        seekBar.setMax(SEEK_BAR_RANGE);
        tint(seekBar, theme.accent);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser || !isPrepared) return;
                int duration = durationMs();
                if (duration > 0) {
                    tvCurrentTime.setText(formatDuration((int) ((long) progress * duration / SEEK_BAR_RANGE)));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { isUserSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                isUserSeeking = false;
                if (!isPrepared) return;
                int duration = durationMs();
                if (duration > 0) {
                    seekTo((int) ((long) sb.getProgress() * duration / SEEK_BAR_RANGE));
                }
            }
        });
        return seekBar;
    }

    private LinearLayout buildVolumeRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, 0);

        TextView icon = new TextView(this);
        icon.setText("🔊");
        icon.setTextSize(14);
        row.addView(icon);

        volumeBar = new SeekBar(this);
        volumeBar.setMax(100);
        volumeBar.setContentDescription(getString(R.string.a11y_media_volume));
        volumeBar.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tint(volumeBar, theme.accent);

        final int maxVol = Math.max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        volumeBar.setProgress(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / maxVol);
        volumeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC,
                        progress * maxVol / 100, 0);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        row.addView(volumeBar);
        return row;
    }

    private LinearLayout buildBrightnessRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, 0);

        TextView icon = new TextView(this);
        icon.setText("☀");
        icon.setTextSize(14);
        row.addView(icon);

        brightnessBar = new SeekBar(this);
        brightnessBar.setMax(100);
        brightnessBar.setContentDescription(getString(R.string.a11y_media_brightness));
        brightnessBar.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tint(brightnessBar, theme.accent);
        try {
            int brightness = Settings.System.getInt(getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS);
            brightnessBar.setProgress(brightness * 100 / 255);
        } catch (Settings.SettingNotFoundException e) {
            brightnessBar.setProgress(50);
        }
        brightnessBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                // 0 would be fully dark; keep a floor so the screen stays usable.
                lp.screenBrightness = Math.max(0.02f, progress / 100f);
                getWindow().setAttributes(lp);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        row.addView(brightnessBar);
        return row;
    }

    private TextView videoButton(String label, int descriptionRes) {
        return makeButton(label, descriptionRes, 0xFFFFFFFF, 17, dp(12), dp(6));
    }

    private TextView audioButton(String label, int descriptionRes) {
        return makeButton(label, descriptionRes, theme.accent, 17, dp(14), dp(10));
    }

    private TextView makeButton(String label, int descriptionRes, int color,
                                int textSize, int padH, int padV) {
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setTextColor(color);
        btn.setTextSize(textSize);
        btn.setPadding(padH, padV, padH, padV);
        btn.setGravity(Gravity.CENTER);
        btn.setContentDescription(getString(descriptionRes));
        return btn;
    }

    private void tint(SeekBar bar, int color) {
        try {
            bar.getProgressDrawable().setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN);
            bar.getThumb().setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN);
        } catch (Exception ignored) {
        }
    }

    // ─── Codec reporting ────────────────────────────────────────────────────

    private String describeTracks() {
        if (probe == null || probe.error != null) return getString(R.string.media_status_ready);
        StringBuilder sb = new StringBuilder();
        for (MediaCodecProbe.Track t : probe.tracks) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(t.describe());
            if (!t.decodable) sb.append(" ⚠");
        }
        if (sb.length() == 0) return getString(R.string.media_status_ready);
        sb.append("   ").append(getString(R.string.media_tap_for_details));
        return sb.toString();
    }

    /** Names any codec this device cannot decode, rather than failing silently. */
    private void reportUnsupportedTracks() {
        if (probe == null) return;
        if (probe.error != null) {
            tvStatus.setText(getString(R.string.media_container_error, probe.error));
            return;
        }
        List<MediaCodecProbe.Track> missing = probe.undecodableTracks();
        if (missing.isEmpty()) return;

        StringBuilder names = new StringBuilder();
        for (MediaCodecProbe.Track t : missing) {
            if (names.length() > 0) names.append(", ");
            names.append(MediaCodecProbe.shortCodecName(t.mime));
        }
        String codecs = names.toString();
        // Inflating the dialog inside onCreate would hold up the player's first frame.
        handler.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.media_unsupported_title)
                    .setMessage(getString(R.string.media_unsupported_message, codecs))
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton(R.string.media_track_info, (d, w) -> showTrackInfo())
                    .show();
        });
    }

    private void showTrackInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append(mediaFile.getName()).append('\n');
        sb.append(getString(R.string.media_info_size, humanSize(mediaFile.length()))).append('\n');
        if (probe != null && probe.error != null) {
            sb.append('\n').append(getString(R.string.media_container_error, probe.error));
        } else if (probe != null) {
            if (probe.durationUs() > 0) {
                sb.append(getString(R.string.media_info_duration,
                        formatDuration((int) (probe.durationUs() / 1000)))).append('\n');
            }
            for (MediaCodecProbe.Track t : probe.tracks) {
                sb.append('\n')
                        .append(t.video ? getString(R.string.media_track_video)
                                : getString(R.string.media_track_audio))
                        .append(": ").append(t.describe()).append('\n')
                        .append("   ").append(t.mime).append('\n')
                        .append("   ").append(t.decodable
                                ? getString(R.string.media_codec_supported)
                                : getString(R.string.media_codec_unsupported))
                        .append(t.royaltyFree
                                ? ", " + getString(R.string.media_codec_royalty_free)
                                : ", " + getString(R.string.media_codec_device_licensed))
                        .append('\n');
            }
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.media_track_info)
                .setMessage(sb.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    // ─── Playback ───────────────────────────────────────────────────────────

    private void initPlayback() {
        Uri uri = Uri.fromFile(mediaFile);
        if (isVideo) initVideoPlayer(uri);
        else initAudioPlayer(uri);

        btnPlayPause.setOnClickListener(v -> togglePlayPause());
        btnStop.setOnClickListener(v -> stopPlayback());
        btnRew.setOnClickListener(v -> seekRelative(-SEEK_STEP_MS));
        btnFwd.setOnClickListener(v -> seekRelative(SEEK_STEP_MS));
        btnSpeed.setOnClickListener(v -> cycleSpeed());
        if (btnLandscape != null) btnLandscape.setOnClickListener(v -> toggleOrientation());
    }

    private void initVideoPlayer(Uri uri) {
        videoView.setVideoURI(uri);
        if (bufferProgress != null) bufferProgress.setVisibility(View.VISIBLE);

        videoView.setOnPreparedListener(mp -> {
            isPrepared = true;
            videoMediaPlayer = mp;
            if (bufferProgress != null) bufferProgress.setVisibility(View.GONE);
            mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT);
            tvDuration.setText(formatDuration(mp.getDuration()));
            applySpeed();
            videoView.start();
            acquireWakeLock();
            tvStatus.setText(R.string.media_status_playing);
            btnPlayPause.setText("⏸");
            startProgressUpdates();
        });

        videoView.setOnCompletionListener(mp -> {
            tvStatus.setText(R.string.media_status_finished);
            btnPlayPause.setText("▶");
            seekBar.setProgress(0);
            tvCurrentTime.setText("00:00");
            releaseWakeLock();
        });

        videoView.setOnErrorListener((mp, what, extra) -> {
            if (bufferProgress != null) bufferProgress.setVisibility(View.GONE);
            tvStatus.setText(describePlaybackError(what, extra));
            return true;
        });

        videoView.setOnInfoListener((mp, what, extra) -> {
            if (bufferProgress == null) return false;
            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                bufferProgress.setVisibility(View.VISIBLE);
            } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END
                    || what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                bufferProgress.setVisibility(View.GONE);
            }
            return false;
        });
    }

    private void initAudioPlayer(Uri uri) {
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            mediaPlayer.setDataSource(this, uri);
            mediaPlayer.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);

            mediaPlayer.setOnPreparedListener(mp -> {
                isPrepared = true;
                tvDuration.setText(formatDuration(mp.getDuration()));
                tvStatus.setText(R.string.media_status_ready_tap_play);
            });

            mediaPlayer.setOnCompletionListener(mp -> {
                tvStatus.setText(R.string.media_status_finished);
                btnPlayPause.setText("▶");
                seekBar.setProgress(0);
                tvCurrentTime.setText("00:00");
                releaseWakeLock();
            });

            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                tvStatus.setText(describePlaybackError(what, extra));
                return true;
            });

            mediaPlayer.setOnBufferingUpdateListener((mp, percent) ->
                    seekBar.setSecondaryProgress(percent * SEEK_BAR_RANGE / 100));

            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            tvStatus.setText(getString(R.string.media_open_error, e.getMessage()));
        }
    }

    /**
     * Turns a MediaPlayer error code into something actionable, preferring the
     * codec information from the probe when the failure is a format problem.
     */
    private String describePlaybackError(int what, int extra) {
        if (extra == MediaPlayer.MEDIA_ERROR_UNSUPPORTED
                || what == MediaPlayer.MEDIA_ERROR_UNKNOWN) {
            List<MediaCodecProbe.Track> missing = probe != null
                    ? probe.undecodableTracks() : java.util.Collections.emptyList();
            if (!missing.isEmpty()) {
                StringBuilder names = new StringBuilder();
                for (MediaCodecProbe.Track t : missing) {
                    if (names.length() > 0) names.append(", ");
                    names.append(MediaCodecProbe.shortCodecName(t.mime));
                }
                return getString(R.string.media_unsupported_message, names.toString());
            }
        }
        if (extra == MediaPlayer.MEDIA_ERROR_MALFORMED) {
            return getString(R.string.media_error_malformed);
        }
        if (extra == MediaPlayer.MEDIA_ERROR_IO) {
            return getString(R.string.media_error_io);
        }
        return getString(R.string.media_error_code, what, extra);
    }

    private void togglePlayPause() {
        if (!isPrepared) return;
        if (isPlaying()) {
            pause();
            btnPlayPause.setText("▶");
            tvStatus.setText(R.string.media_status_paused);
            releaseWakeLock();
        } else {
            play();
            btnPlayPause.setText("⏸");
            tvStatus.setText(R.string.media_status_playing);
            acquireWakeLock();
            startProgressUpdates();
        }
    }

    private void stopPlayback() {
        if (!isPrepared) return;
        seekTo(0);
        pause();
        seekBar.setProgress(0);
        tvCurrentTime.setText("00:00");
        btnPlayPause.setText("▶");
        tvStatus.setText(R.string.media_status_stopped);
        releaseWakeLock();
    }

    private void seekRelative(int deltaMs) {
        if (!isPrepared) return;
        int duration = durationMs();
        int target = Math.max(0, Math.min(duration, positionMs() + deltaMs));
        seekTo(target);
        tvCurrentTime.setText(formatDuration(target));
    }

    /** Steps through 0.5× → 1× → 1.25× → 1.5× → 2×. */
    private void cycleSpeed() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(this, R.string.media_speed_unsupported, Toast.LENGTH_SHORT).show();
            return;
        }
        float[] steps = {0.5f, 1f, 1.25f, 1.5f, 2f};
        int next = 1;
        for (int i = 0; i < steps.length; i++) {
            if (Math.abs(steps[i] - playbackSpeed) < 0.01f) {
                next = (i + 1) % steps.length;
                break;
            }
        }
        playbackSpeed = steps[next];
        btnSpeed.setText(formatSpeed(playbackSpeed));
        applySpeed();
    }

    /** {@code 0.5×}, {@code 1×}, {@code 1.25×} — no trailing zeros. */
    private static String formatSpeed(float speed) {
        if (Math.abs(speed - Math.round(speed)) < 0.001f) {
            return Math.round(speed) + "×";
        }
        return new java.math.BigDecimal(String.valueOf(speed)).stripTrailingZeros()
                .toPlainString() + "×";
    }

    /**
     * Applies the current speed to whichever player is active. The video path
     * uses the {@link MediaPlayer} handed to {@code onPrepared}, since
     * {@link VideoView} has no speed setter of its own.
     */
    private void applySpeed() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        MediaPlayer target = isVideo ? videoMediaPlayer : mediaPlayer;
        if (target == null) return;
        try {
            boolean wasPlaying = target.isPlaying();
            target.setPlaybackParams(new android.media.PlaybackParams().setSpeed(playbackSpeed));
            // Setting params starts playback on some devices; restore the state.
            if (!wasPlaying && target.isPlaying()) target.pause();
        } catch (Exception e) {
            Toast.makeText(this, R.string.media_speed_unsupported, Toast.LENGTH_SHORT).show();
            playbackSpeed = 1f;
            btnSpeed.setText(formatSpeed(1f));
        }
    }

    private void toggleOrientation() {
        isLandscape = !isLandscape;
        setRequestedOrientation(isLandscape
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
    }

    // ─── Player abstraction over VideoView / MediaPlayer ─────────────────────

    private boolean isPlaying() {
        try {
            return isVideo ? videoView.isPlaying() : mediaPlayer != null && mediaPlayer.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    private void play() {
        if (isVideo) videoView.start();
        else if (mediaPlayer != null) mediaPlayer.start();
    }

    private void pause() {
        if (isVideo) videoView.pause();
        else if (mediaPlayer != null) mediaPlayer.pause();
    }

    private void seekTo(int ms) {
        try {
            if (isVideo) videoView.seekTo(ms);
            else if (mediaPlayer != null) mediaPlayer.seekTo(ms);
        } catch (Exception ignored) {
        }
    }

    private int positionMs() {
        try {
            return isVideo ? videoView.getCurrentPosition()
                    : mediaPlayer != null ? mediaPlayer.getCurrentPosition() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private int durationMs() {
        try {
            int duration = isVideo ? videoView.getDuration()
                    : mediaPlayer != null ? mediaPlayer.getDuration() : 0;
            return Math.max(0, duration);
        } catch (Exception e) {
            return 0;
        }
    }

    private void startProgressUpdates() {
        handler.removeCallbacksAndMessages(null);
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!isPrepared) return;
                if (!isUserSeeking) {
                    int duration = durationMs();
                    int position = positionMs();
                    if (duration > 0) {
                        seekBar.setProgress((int) ((long) position * SEEK_BAR_RANGE / duration));
                    }
                    tvCurrentTime.setText(formatDuration(position));
                }
                handler.postDelayed(this, isPlaying() ? 250 : 600);
            }
        });
    }

    // ─── Lifecycle ──────────────────────────────────────────────────────────

    private void acquireWakeLock() {
        try {
            if (wakeLock != null && !wakeLock.isHeld()) {
                // Bounded so a forgotten player cannot hold the screen forever.
                wakeLock.acquire(4 * 60 * 60 * 1000L);
            }
        } catch (Exception ignored) {
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isPlaying()) {
            pause();
            btnPlayPause.setText("▶");
            tvStatus.setText(R.string.media_status_paused);
        }
        releaseWakeLock();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (volumeBar != null) {
            int maxVol = Math.max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            volumeBar.setProgress(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / maxVol);
        }
    }

    @Override
    protected void onDestroy() {
        releaseWakeLock();
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                mediaPlayer.release();
            } catch (Exception ignored) {
            }
            mediaPlayer = null;
        }
        if (videoView != null) {
            try {
                videoView.stopPlayback();
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    // ─── Formatting ─────────────────────────────────────────────────────────

    private String formatDuration(int ms) {
        int totalSec = Math.max(0, ms) / 1000;
        int hours = totalSec / 3600;
        int min = (totalSec % 3600) / 60;
        int sec = totalSec % 60;
        if (hours > 0) return String.format(Locale.US, "%d:%02d:%02d", hours, min, sec);
        return String.format(Locale.US, "%02d:%02d", min, sec);
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
