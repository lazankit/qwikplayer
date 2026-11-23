package com.qwikplayer.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The player screen. MediaPlayer + TextureView, everything drawn in code.
 * Lifecycle rules: the MediaPlayer exists only while the activity is started (it is released in onStop and
 * recreated in onStart at the saved position, paused) and only while a surface exists.
 */
public class PlayerActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final int REQ_SUBTITLE = 11;
    private static final int FIT = 0, FILL = 1, CROP = 2, ORIGINAL = 3;
    private static final String[] MODE_NAMES = {"Fit", "Fill", "Crop", "100%"};
    private static final float[] SPEEDS = {0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f};
    private static final int G_NONE = 0, G_BRIGHTNESS = 1, G_VOLUME = 2, G_SEEK = 3, G_IGNORE = 4;
    private static final long HIDE_DELAY = 4000, TICK = 250;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private AudioManager audio;
    private int accent, accentText;

    // views
    private FrameLayout root;
    private TextureView tv;
    private Surface surface;
    private ProgressBar spinner;
    private TextView subView, hud, hudLeft, hudRight, titleView, elapsedView, remainingView, speedBtn;
    private GradientSeekBar seek;
    private ImageButton playBtn, prevBtn, nextBtn, lockBtn, aspectBtn, audioBtn, subBtn, backBtn, rotateBtn, pipBtn, timerBtn, unlockBtn;
    private View controls, topBar, bottomBar;

    // playlist / playback state
    private List<Video> list = new ArrayList<>();
    private int index;
    private Video cur;
    private MediaPlayer mp;
    private boolean started;             // between onStart and onStop
    private boolean prepared, wantPlay, completed;
    private long durationMs, resumeFromMs = -1, lastPos;
    private boolean resumeChecked;
    private int videoW, videoH;
    private float speed = 1f;
    private boolean resumeOnFocusGain, noisyRegistered;
    private Object focusRequest;

    // UI state
    private int mode = FIT;
    private float zoom = 1f;
    private boolean controlsVisible, locked, rotationLocked, isPip, seeking;
    private boolean askingResume;
    private int gesture = G_NONE;
    private float downX, downY, startBright, startVolF;
    private long gestureStartPos, gestureTarget;
    private float brightness = -1;
    private int tickCount;
    private ScaleGestureDetector scaleDetector;
    private GestureDetector tapDetector;

    // subtitles
    private List<SrtParser.Cue> cues;
    private String cuesName = "";
    private boolean subOn = true;
    private int subLoadGen;

    // sleep timer
    private boolean sleepAtEnd;
    private long sleepUntil;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = Prefs.get(this);
        accent = Ui.accent(this);
        accentText = Ui.accentText(this);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        subOn = prefs.subtitleOn();
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        if (Build.VERSION.SDK_INT >= 28) Compat.useCutoutArea(getWindow());
        applyOrientationDefault();
        buildUi();
        if (!resolveIntent(getIntent())) { finish(); return; }
        showControls();
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        if (resolveIntent(i)) {
            releasePlayer();
            resumeFromMs = -1;
            resumeChecked = false;
            openCurrent();
        }
    }

    @Override protected void onStart() {
        super.onStart();
        started = true;
        openCurrent();
    }

    @Override protected void onPause() {
        super.onPause();
        saveProgress(false);
    }

    @Override protected void onStop() {
        super.onStop();
        started = false;
        if (Build.VERSION.SDK_INT >= 26 && Compat.inPip(this)) {
            // the PiP window was closed (or the screen turned off while in PiP): end the session
            releasePlayer();
            finish();
            return;
        }
        saveProgress(false);
        if (mp != null) resumeFromMs = lastPos;   // come back paused at the same spot
        releasePlayer();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        releasePlayer();
        if (surface != null) { surface.release(); surface = null; }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        applyVideoLayout();
    }

    @Override public void onBackPressed() {
        if (locked) { showUnlockHint(); return; }
        super.onBackPressed();
    }

    @Override protected void onUserLeaveHint() {
        if (Build.VERSION.SDK_INT >= 26 && prefs.pipOnHome() && isPlaying() && !locked && !isPip) enterPip();
    }

    @Override public void onPictureInPictureModeChanged(boolean inPip, Configuration c) {
        super.onPictureInPictureModeChanged(inPip, c);
        isPip = inPip;
        if (inPip) {
            hideControls();
        } else {
            showControls();
        }
        applyVideoLayout();
    }

    // ------------------------------------------------------------------ intent / playlist

    /** Builds the playlist from the intent (and the library's list, when it contains this file). */
    private boolean resolveIntent(Intent i) {
        Uri data = i == null ? null : i.getData();
        if (data == null) { Toast.makeText(this, "No video to play", Toast.LENGTH_SHORT).show(); return false; }
        list = new ArrayList<>(Video.Playlist.items);
        index = -1;
        for (int k = 0; k < list.size(); k++) if (list.get(k).uri.equals(data)) { index = k; break; }
        if (index < 0) {
            list = new ArrayList<>();
            list.add(describe(data));
            index = 0;
        }
        cur = list.get(index);
        wantPlay = true;
        openCurrentSetup();
        return true;
    }

    private Video describe(Uri uri) {
        Video v = new Video();
        v.uri = uri;
        v.id = -1;
        if ("file".equals(uri.getScheme())) {
            v.path = uri.getPath();
            v.name = new File(uri.getPath() == null ? "video" : uri.getPath()).getName();
        } else {
            Cursor c = null;
            try {
                c = getContentResolver().query(uri, null, null, null, null);
                if (c != null && c.moveToFirst()) {
                    int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (n >= 0) v.name = c.getString(n);
                    int d = c.getColumnIndex("_data");
                    if (d >= 0) v.path = c.getString(d);
                }
            } catch (RuntimeException ignored) {
            } finally {
                if (c != null) c.close();
            }
            if (v.name == null || v.name.isEmpty()) v.name = uri.getLastPathSegment() == null ? "Video" : uri.getLastPathSegment();
        }
        return v;
    }

    /** Per-video setup that does not need the player: title, subtitles, button states. */
    private void openCurrentSetup() {
        titleView.setText(cur.title());
        cues = null;
        cuesName = "";
        subView.setVisibility(View.GONE);
        completed = false;
        updateNavButtons();
        updateSubButton();
        if (prefs.subtitleAuto()) autoLoadSubtitle();
    }

    private void updateNavButtons() {
        boolean hasPrev = index > 0, hasNext = index < list.size() - 1;
        prevBtn.setEnabled(hasPrev);
        prevBtn.setAlpha(hasPrev ? 1f : 0.35f);
        nextBtn.setEnabled(hasNext);
        nextBtn.setAlpha(hasNext ? 1f : 0.35f);
    }

    private void openIndex(int i) {
        if (i < 0 || i >= list.size()) return;
        saveProgress(false);
        releasePlayer();
        index = i;
        cur = list.get(i);
        resumeFromMs = -1;
        resumeChecked = false;
        wantPlay = true;
        zoom = 1f;
        applyZoom();
        videoW = videoH = 0;
        openCurrentSetup();
        openCurrent();
    }

    // ------------------------------------------------------------------ player lifecycle

    private void openCurrent() {
        if (cur == null || mp != null || surface == null || !started) return;
        prepared = false;
        completed = false;
        spinner.setVisibility(View.VISIBLE);
        final MediaPlayer p = new MediaPlayer();
        mp = p;
        try {
            p.setSurface(surface);
            p.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
            p.setDataSource(this, cur.uri);
            p.setOnPreparedListener(m -> { if (m == mp) onPrepared(m); });
            p.setOnCompletionListener(m -> { if (m == mp) onCompleted(); });
            p.setOnErrorListener((m, what, extra) -> { if (m == mp) onPlayerError(what, extra); return true; });
            p.setOnVideoSizeChangedListener((m, w, h) -> {
                if (m != mp || w <= 0 || h <= 0) return;
                videoW = w;
                videoH = h;
                applyVideoLayout();
                applyOrientationForVideo();
                if (Build.VERSION.SDK_INT >= 26) Compat.updatePipRatio(this, w, h);
            });
            p.prepareAsync();
        } catch (IOException | RuntimeException e) {
            // file gone, permission lost, unsupported container...
            if (mp == p) {
                try { p.release(); } catch (RuntimeException ignored) { }
                mp = null;
                showError("This video can't be opened.");
            }
        }
    }

    private void onPrepared(MediaPlayer m) {
        prepared = true;
        spinner.setVisibility(View.GONE);
        durationMs = Math.max(0, m.getDuration());
        seek.setMax(durationMs);
        if (m.getVideoWidth() > 0) { videoW = m.getVideoWidth(); videoH = m.getVideoHeight(); }
        applyVideoLayout();
        applyOrientationForVideo();

        long startAt = -1;
        boolean play = wantPlay;
        if (resumeFromMs >= 0) {                       // returning from background / rotation of the task
            startAt = resumeFromMs;
            resumeFromMs = -1;
            play = false;
        } else if (!resumeChecked) {
            resumeChecked = true;
            Prefs.Progress pr = prefs.progress(cur.key());
            if (pr != null && !pr.watched && pr.pos > 5000 && durationMs > 0 && pr.pos < durationMs - 5000) {
                int mode = prefs.resumeMode();
                if (mode == Prefs.RESUME_ALWAYS) startAt = pr.pos;
                else if (mode == Prefs.RESUME_ASK) { askResume(pr.pos); return; }
            }
        }
        if (startAt > 0) m.seekTo((int) startAt);
        lastPos = Math.max(0, startAt);
        updateTime(lastPos);
        if (play) startPlayback();
        else { wantPlay = false; updatePlayButton(); showControls(); }
        scheduleTick();
    }

    private void askResume(final long pos) {
        askingResume = true;
        updatePlayButton();
        showControls();
        final boolean[] answered = {false};
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Resume playback?")
                .setMessage("You stopped at " + Ui.time(pos) + ".")
                .setPositiveButton("Resume from " + Ui.time(pos), (x, w) -> { answered[0] = true; finishResumeChoice(pos); })
                .setNegativeButton("Start over", (x, w) -> { answered[0] = true; finishResumeChoice(0); })
                .setOnCancelListener(x -> { if (!answered[0]) finishResumeChoice(pos); })
                .create();
        d.show();
    }

    private void finishResumeChoice(long pos) {
        askingResume = false;
        if (mp == null || !prepared) return;
        if (pos > 0) mp.seekTo((int) pos);
        lastPos = pos;
        updateTime(pos);
        startPlayback();
        scheduleTick();
    }

    private void onCompleted() {
        completed = true;
        saveProgress(true);
        lastPos = 0;
        stopPlaybackSideEffects();
        updatePlayButton();
        if (sleepAtEnd) { sleepAtEnd = false; updateTimerButton(); showControls(); Toast.makeText(this, "Sleep timer: stopped", Toast.LENGTH_SHORT).show(); return; }
        if (prefs.autoNext() && index < list.size() - 1) openIndex(index + 1);
        else showControls();
    }

    private void onPlayerError(int what, int extra) {
        MediaPlayer bad = mp;
        mp = null;
        prepared = false;
        stopPlaybackSideEffects();
        if (bad != null) { try { bad.release(); } catch (RuntimeException ignored) { } }
        showError(what == MediaPlayer.MEDIA_ERROR_SERVER_DIED
                ? "The media service stopped. Try opening the video again."
                : "This phone can't play this video (unsupported format or codec).");
    }

    private void showError(String msg) {
        spinner.setVisibility(View.GONE);
        updatePlayButton();
        showControls();
        if (isFinishing() || isDestroyed()) return;
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("Can't play").setMessage(msg)
                .setNegativeButton("Close", (d, w) -> finish());
        if (index < list.size() - 1) b.setNeutralButton("Next video", (d, w) -> openIndex(index + 1));
        b.setPositiveButton("Open with…", (d, w) -> openWithOtherApp());
        b.setCancelable(false);
        b.show();
    }

    private void openWithOtherApp() {
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(cur.uri, "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser = Intent.createChooser(i, "Open with");
        if (Build.VERSION.SDK_INT >= 24) {
            chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, new android.content.ComponentName[]{new android.content.ComponentName(this, PlayerActivity.class)});
        }
        try {
            startActivity(chooser);
        } catch (RuntimeException e) {
            Toast.makeText(this, "No other video player installed", Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    private void releasePlayer() {
        stopPlaybackSideEffects();
        ui.removeCallbacks(tick);
        MediaPlayer p = mp;
        mp = null;
        prepared = false;
        if (p != null) {
            try { p.setSurface(null); } catch (RuntimeException ignored) { }
            try { p.release(); } catch (RuntimeException ignored) { }
        }
        updatePlayButton();
    }

    /** Everything that should stop when playback stops: audio focus, the noisy-receiver, keep-screen-on. */
    private void stopPlaybackSideEffects() {
        unregisterNoisy();
        abandonFocus();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        resumeOnFocusGain = false;
    }

    private boolean isPlaying() {
        try {
            return mp != null && prepared && mp.isPlaying();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private void startPlayback() {
        if (mp == null || !prepared || askingResume) { wantPlay = true; return; }
        try {
            if (completed) { mp.seekTo(0); completed = false; }
            requestFocus();
            mp.start();
            applySpeed();
        } catch (RuntimeException e) {
            return;
        }
        wantPlay = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        registerNoisy();
        updatePlayButton();
        scheduleHide();
    }

    private void pausePlayback() {
        wantPlay = false;
        if (mp != null && prepared) {
            try { if (mp.isPlaying()) mp.pause(); } catch (RuntimeException ignored) { }
            lastPos = safePos();
        }
        unregisterNoisy();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        updatePlayButton();
        showControls();
        saveProgress(false);
    }

    private void togglePlay() {
        if (askingResume) return;
        if (isPlaying()) pausePlayback(); else startPlayback();
    }

    private int safePos() {
        try {
            return mp != null && prepared ? mp.getCurrentPosition() : (int) lastPos;
        } catch (IllegalStateException e) {
            return (int) lastPos;
        }
    }

    private void seekTo(long ms) {
        if (mp == null || !prepared) return;
        ms = Math.max(0, Math.min(durationMs > 0 ? durationMs : ms, ms));
        try { mp.seekTo((int) ms); } catch (RuntimeException ignored) { }
        lastPos = ms;
        completed = false;
        updateTime(ms);
        updateSubtitle(ms);
    }

    private void skip(int seconds) {
        if (!prepared) return;
        seekTo(safePos() + seconds * 1000L);
    }

    private void applySpeed() {
        if (Build.VERSION.SDK_INT < 23 || mp == null || !prepared) return;
        try {
            Compat.setSpeed(mp, speed);
        } catch (RuntimeException e) {
            // some decoders refuse a speed; fall back to normal
            speed = 1f;
            speedBtn.setText("1x");
        }
    }

    private void saveProgress(boolean finished) {
        if (cur == null || !prepared || durationMs <= 0) return;
        long pos = finished ? durationMs : safePos();
        lastPos = finished ? 0 : pos;
        prefs.savePosition(cur.key(), pos, durationMs, finished);
    }

    // ------------------------------------------------------------------ audio focus / headphones

    private final AudioManager.OnAudioFocusChangeListener focusListener = new AudioManager.OnAudioFocusChangeListener() {
        @Override public void onAudioFocusChange(int change) {
            switch (change) {
                case AudioManager.AUDIOFOCUS_LOSS:
                    resumeOnFocusGain = false;
                    if (isPlaying()) pausePlayback();
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    if (isPlaying()) { resumeOnFocusGain = true; pauseKeepingFocus(); }
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    if (mp != null) { try { mp.setVolume(0.2f, 0.2f); } catch (RuntimeException ignored) { } }
                    break;
                case AudioManager.AUDIOFOCUS_GAIN:
                    if (mp != null) { try { mp.setVolume(1f, 1f); } catch (RuntimeException ignored) { } }
                    if (resumeOnFocusGain) { resumeOnFocusGain = false; startPlayback(); }
                    break;
                default:
                    break;
            }
        }
    };

    private void pauseKeepingFocus() {
        if (mp != null && prepared) { try { mp.pause(); } catch (RuntimeException ignored) { } lastPos = safePos(); }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        updatePlayButton();
        saveProgress(false);
    }

    @SuppressWarnings("deprecation")
    private void requestFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest = Compat.requestFocus(audio, focusListener, focusRequest);
        } else {
            audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    @SuppressWarnings("deprecation")
    private void abandonFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest != null) { Compat.abandonFocus(audio, focusRequest); focusRequest = null; }
        } else {
            audio.abandonAudioFocus(focusListener);
        }
    }

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(i.getAction()) && isPlaying()) pausePlayback();   // headphones unplugged
        }
    };

    private void registerNoisy() {
        if (noisyRegistered) return;
        registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        noisyRegistered = true;
    }

    private void unregisterNoisy() {
        if (!noisyRegistered) return;
        noisyRegistered = false;
        try { unregisterReceiver(noisyReceiver); } catch (IllegalArgumentException ignored) { }
    }

    // ------------------------------------------------------------------ surface

    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        surface = new Surface(st);
        if (mp != null && prepared) {
            try { mp.setSurface(surface); } catch (RuntimeException ignored) { }
        } else {
            openCurrent();
        }
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {}

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        if (mp != null) { try { mp.setSurface(null); } catch (RuntimeException ignored) { } }
        if (surface != null) { surface.release(); surface = null; }
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}

    // ------------------------------------------------------------------ ticking: seek bar, subtitles, saving

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (mp == null || !prepared) return;
            long pos = safePos();
            if (!seeking) updateTime(pos);
            updateSubtitle(pos);
            if (isPlaying()) lastPos = pos;
            if (++tickCount % 20 == 0 && isPlaying()) saveProgress(false);
            if (sleepUntil > 0 && System.currentTimeMillis() >= sleepUntil) {
                sleepUntil = 0;
                updateTimerButton();
                if (isPlaying()) pausePlayback();
                Toast.makeText(PlayerActivity.this, "Sleep timer: paused", Toast.LENGTH_LONG).show();
            }
            ui.postDelayed(this, TICK);
        }
    };

    private void scheduleTick() {
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    private void updateTime(long pos) {
        seek.setValue(pos);
        elapsedView.setText(Ui.time(pos));
        remainingView.setText(durationMs > 0 ? "-" + Ui.time(durationMs - pos) : "--:--");
    }

    // ------------------------------------------------------------------ subtitles

    private void autoLoadSubtitle() {
        final Video v = cur;
        final int gen = ++subLoadGen;
        new Thread(() -> {
            List<File> found = findSidecars(v);
            if (found.isEmpty()) return;
            final byte[] data = readFile(found.get(0));
            final String name = found.get(0).getName();
            if (data == null) return;
            final List<SrtParser.Cue> parsed = SrtParser.parse(data);
            ui.post(() -> { if (gen == subLoadGen && !isDestroyed()) setCues(parsed, name); });
        }).start();
    }

    /** .srt files next to the video with the same base name (movie.srt, movie.en.srt ...). Needs file access, so it is best effort. */
    private List<File> findSidecars(Video v) {
        List<File> out = new ArrayList<>();
        String path = v.path;
        if (path == null && v.uri != null && "content".equals(v.uri.getScheme()) && v.id > 0) {
            Cursor c = null;
            try {
                c = getContentResolver().query(v.uri, new String[]{MediaStore.MediaColumns.DATA}, null, null, null);
                if (c != null && c.moveToFirst()) path = c.getString(0);
            } catch (RuntimeException ignored) {
            } finally {
                if (c != null) c.close();
            }
        }
        if (path == null) return out;
        try {
            File video = new File(path);
            File dir = video.getParentFile();
            String name = video.getName();
            int dot = name.lastIndexOf('.');
            final String base = (dot > 0 ? name.substring(0, dot) : name).toLowerCase(Locale.ROOT);
            File[] files = dir == null ? null : dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    String n = f.getName().toLowerCase(Locale.ROOT);
                    if (n.endsWith(".srt") && (n.equals(base + ".srt") || n.startsWith(base + "."))) out.add(f);
                }
                java.util.Collections.sort(out, (a, b) -> a.getName().length() - b.getName().length());   // exact name first
            } else {
                File exact = new File(dir, base + ".srt");
                if (exact.canRead()) out.add(exact);
            }
        } catch (SecurityException ignored) {
        }
        return out;
    }

    private static byte[] readFile(File f) {
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > 8 * 1024 * 1024) throw new IOException("subtitle file too large");
        }
        return out.toByteArray();
    }

    private void setCues(List<SrtParser.Cue> parsed, String name) {
        if (parsed.isEmpty()) {
            Toast.makeText(this, "No subtitles found in " + name, Toast.LENGTH_SHORT).show();
            return;
        }
        cues = parsed;
        cuesName = name;
        updateSubButton();
        updateSubtitle(safePos());
    }

    private void updateSubtitle(long pos) {
        if (cues == null || !subOn) { if (subView.getVisibility() != View.GONE) subView.setVisibility(View.GONE); return; }
        SrtParser.Cue c = SrtParser.at(cues, pos);
        if (c == null) {
            if (subView.getVisibility() != View.GONE) subView.setVisibility(View.GONE);
        } else {
            if (!c.text.contentEquals(subView.getText())) subView.setText(c.text);
            subView.setVisibility(View.VISIBLE);
        }
    }

    private void updateSubButton() {
        subBtn.setColorFilter(cues != null && subOn ? accentText : Color.WHITE);
    }

    private void showSubtitleDialog() {
        final List<File> found = new ArrayList<>(findSidecars(cur));
        final List<String> labels = new ArrayList<>();
        labels.add(cues == null ? "Subtitles: none loaded" : (subOn ? "Turn subtitles off" : "Turn subtitles on") + "  (" + cuesName + ")");
        for (File f : found) labels.add("Load " + f.getName());
        labels.add("Choose a .srt file…");
        labels.add("Subtitle size…");
        new AlertDialog.Builder(this).setTitle("Subtitles")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    int n = labels.size();
                    if (which == 0) {
                        if (cues != null) { subOn = !subOn; prefs.setSubtitleOn(subOn); updateSubButton(); updateSubtitle(safePos()); }
                    } else if (which == n - 2) {
                        pickSubtitleFile();
                    } else if (which == n - 1) {
                        showSubtitleSizeDialog();
                    } else {
                        final File f = found.get(which - 1);
                        new Thread(() -> {
                            final byte[] data = readFile(f);
                            ui.post(() -> {
                                if (data == null) Toast.makeText(this, "Can't read " + f.getName(), Toast.LENGTH_SHORT).show();
                                else { subOn = true; prefs.setSubtitleOn(true); setCues(SrtParser.parse(data), f.getName()); }
                            });
                        }).start();
                    }
                }).show();
    }

    private void pickSubtitleFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_SUBTITLE);
        } catch (RuntimeException e) {
            Toast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req != REQ_SUBTITLE || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri u = data.getData();
        new Thread(() -> {
            byte[] bytes = null;
            try (InputStream in = getContentResolver().openInputStream(u)) {
                if (in != null) bytes = readAll(in);
            } catch (IOException | RuntimeException ignored) {
            }
            final byte[] fb = bytes;
            final String name = describe(u).name;
            ui.post(() -> {
                if (isDestroyed()) return;
                if (fb == null) Toast.makeText(this, "Can't read that file", Toast.LENGTH_SHORT).show();
                else { subOn = true; prefs.setSubtitleOn(true); setCues(SrtParser.parse(fb), name); }
            });
        }).start();
    }

    private void showSubtitleSizeDialog() {
        final SeekBar sb = new SeekBar(this);
        sb.setMax(28);
        sb.setProgress(prefs.subtitleSize() - 12);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { prefs.setSubtitleSize(p + 12); applySubtitleStyle(); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        LinearLayout box = new LinearLayout(this);
        box.setPadding(Ui.dp(this, 24), Ui.dp(this, 24), Ui.dp(this, 24), Ui.dp(this, 8));
        box.addView(sb, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this).setTitle("Subtitle size").setView(box).setPositiveButton("Done", null).show();
    }

    private void applySubtitleStyle() {
        subView.setTextSize(prefs.subtitleSize());
    }

    // ------------------------------------------------------------------ audio tracks, speed, sleep timer

    private void showAudioDialog() {
        if (mp == null || !prepared) return;
        try {
            MediaPlayer.TrackInfo[] ti = mp.getTrackInfo();
            final List<Integer> idx = new ArrayList<>();
            List<String> names = new ArrayList<>();
            int selected = Build.VERSION.SDK_INT >= 23 ? Compat.selectedAudioTrack(mp) : -1;
            int checked = -1;
            for (int i = 0; i < ti.length; i++) {
                if (ti[i].getTrackType() != MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) continue;
                String lang = ti[i].getLanguage();
                String label = "Audio " + (idx.size() + 1);
                if (lang != null && !lang.isEmpty() && !"und".equals(lang)) label += "  ·  " + lang.toUpperCase(Locale.ROOT);
                if (i == selected) checked = idx.size();
                idx.add(i);
                names.add(label);
            }
            if (idx.size() < 2) {
                Toast.makeText(this, idx.isEmpty() ? "No selectable audio tracks" : "This video has one audio track", Toast.LENGTH_SHORT).show();
                return;
            }
            new AlertDialog.Builder(this).setTitle("Audio track")
                    .setSingleChoiceItems(names.toArray(new String[0]), checked, (d, which) -> {
                        try {
                            if (mp != null) mp.selectTrack(idx.get(which));
                        } catch (RuntimeException e) {
                            Toast.makeText(this, "Can't switch to that track", Toast.LENGTH_SHORT).show();
                        }
                        d.dismiss();
                    }).show();
        } catch (RuntimeException e) {
            Toast.makeText(this, "Audio tracks unavailable", Toast.LENGTH_SHORT).show();
        }
    }

    private void showSpeedDialog() {
        String[] names = new String[SPEEDS.length];
        int checked = 3;
        for (int i = 0; i < SPEEDS.length; i++) {
            names[i] = speedLabel(SPEEDS[i]) + (SPEEDS[i] == 1f ? "  (normal)" : "");
            if (SPEEDS[i] == speed) checked = i;
        }
        new AlertDialog.Builder(this).setTitle("Playback speed")
                .setSingleChoiceItems(names, checked, (d, which) -> {
                    speed = SPEEDS[which];
                    speedBtn.setText(speedLabel(speed));
                    speedBtn.setTextColor(speed == 1f ? Color.WHITE : accentText);
                    if (isPlaying()) applySpeed();   // a paused player takes the speed when it next starts
                    d.dismiss();
                }).show();
    }

    private static String speedLabel(float s) {
        return (s == (int) s ? String.valueOf((int) s) : String.valueOf(s)) + "x";
    }

    private void showTimerDialog() {
        final String[] names = {"Off", "10 minutes", "15 minutes", "30 minutes", "45 minutes", "60 minutes", "90 minutes", "End of this video"};
        final int[] mins = {0, 10, 15, 30, 45, 60, 90, -1};
        int checked = sleepAtEnd ? 7 : 0;
        new AlertDialog.Builder(this).setTitle("Sleep timer")
                .setSingleChoiceItems(names, checked, (d, which) -> {
                    sleepAtEnd = mins[which] < 0;
                    sleepUntil = mins[which] > 0 ? System.currentTimeMillis() + mins[which] * 60000L : 0;
                    updateTimerButton();
                    if (which > 0) Toast.makeText(this, "Sleep timer: " + names[which].toLowerCase(Locale.ROOT), Toast.LENGTH_SHORT).show();
                    d.dismiss();
                }).show();
    }

    private void updateTimerButton() {
        timerBtn.setColorFilter(sleepAtEnd || sleepUntil > 0 ? accentText : Color.WHITE);
    }

    // ------------------------------------------------------------------ picture in picture, orientation, aspect

    private void enterPip() {
        if (Build.VERSION.SDK_INT < 26) return;
        if (!getPackageManager().hasSystemFeature("android.software.picture_in_picture")) {
            Toast.makeText(this, "Picture-in-picture isn't available on this device", Toast.LENGTH_SHORT).show();
            return;
        }
        hideControls();
        if (!Compat.enterPip(this, videoW, videoH)) {
            Toast.makeText(this, "Picture-in-picture is turned off for QwikPlayer in Settings", Toast.LENGTH_LONG).show();
            showControls();
        }
    }

    private void applyOrientationDefault() {
        switch (prefs.orientation()) {
            case Prefs.ORI_PORTRAIT: setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT); break;
            case Prefs.ORI_SYSTEM: setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER); break;
            default: setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE); break;
        }
    }

    /** "Follow the video": landscape clips use the landscape sensor, portrait clips the portrait one. */
    private void applyOrientationForVideo() {
        if (rotationLocked || prefs.orientation() != Prefs.ORI_VIDEO || videoW <= 0) return;
        setRequestedOrientation(videoH > videoW ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    private void toggleRotationLock() {
        rotationLocked = !rotationLocked;
        if (rotationLocked) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);   // keep whatever the screen shows now
            Toast.makeText(this, "Rotation locked", Toast.LENGTH_SHORT).show();
        } else {
            applyOrientationDefault();
            applyOrientationForVideo();
            Toast.makeText(this, "Rotation unlocked", Toast.LENGTH_SHORT).show();
        }
        rotateBtn.setColorFilter(rotationLocked ? accentText : Color.WHITE);
        scheduleHide();
    }

    private void cycleAspect() {
        mode = (mode + 1) % 4;
        zoom = 1f;
        applyVideoLayout();
        flash(hud, MODE_NAMES[mode]);
        scheduleHide();
    }

    private void applyVideoLayout() {
        int cw = root.getWidth(), ch = root.getHeight();
        if (cw <= 0 || ch <= 0 || videoW <= 0 || videoH <= 0) return;
        float va = (float) videoW / videoH, ca = (float) cw / ch;
        int w, h;
        switch (mode) {
            case FILL: w = cw; h = ch; break;
            case CROP:
                if (va > ca) { h = ch; w = Math.round(ch * va); } else { w = cw; h = Math.round(cw / va); }
                break;
            case ORIGINAL: w = videoW; h = videoH; break;
            default:
                if (va > ca) { w = cw; h = Math.round(cw / va); } else { h = ch; w = Math.round(ch * va); }
                break;
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) tv.getLayoutParams();
        if (lp.width != w || lp.height != h) {
            lp.width = w;
            lp.height = h;
            tv.setLayoutParams(lp);
        }
        applyZoom();
    }

    private void applyZoom() {
        tv.setScaleX(zoom);
        tv.setScaleY(zoom);
    }

    // ------------------------------------------------------------------ gestures

    private boolean onRootTouch(MotionEvent e) {
        if (isPip) return false;
        if (locked) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) showUnlockHint();
            return true;
        }
        scaleDetector.onTouchEvent(e);
        tapDetector.onTouchEvent(e);
        int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                gesture = G_NONE;
                // leave the screen edges to the system's back / home gestures
                int edge = Ui.dp(this, 24);
                if (downX < edge || downX > root.getWidth() - edge || downY < edge || downY > root.getHeight() - edge) gesture = G_IGNORE;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (gesture == G_SEEK) hideHud();
                gesture = G_IGNORE;   // two fingers = pinch only
                break;
            case MotionEvent.ACTION_MOVE:
                if (gesture == G_IGNORE || e.getPointerCount() > 1 || scaleDetector.isInProgress()) break;
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (gesture == G_NONE) {
                    if (Math.hypot(dx, dy) < slop * 1.5f) break;
                    if (Math.abs(dy) > Math.abs(dx)) {
                        gesture = downX < root.getWidth() / 2f ? G_BRIGHTNESS : G_VOLUME;
                        startBright = currentBrightness();
                        startVolF = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                        downY = e.getY();   // measure from where the gesture really began
                        dy = 0;
                    } else if (prepared && durationMs > 0 && !askingResume) {
                        gesture = G_SEEK;
                        gestureStartPos = safePos();
                        seeking = true;
                        downX = e.getX();
                        dx = 0;
                    } else {
                        gesture = G_IGNORE;
                        break;
                    }
                }
                if (gesture == G_BRIGHTNESS) {
                    float b = Math.max(0.01f, Math.min(1f, startBright - dy / (root.getHeight() * 0.8f)));
                    setBrightness(b);
                    flash(hud, "Brightness  " + Math.round(b * 100) + "%");
                } else if (gesture == G_VOLUME) {
                    int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    float v = Math.max(0, Math.min(max, startVolF - dy / (root.getHeight() * 0.8f) * max));
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(v), 0);
                    flash(hud, "Volume  " + Math.round(100f * Math.round(v) / max) + "%");
                } else if (gesture == G_SEEK) {
                    long span = Math.min(durationMs, 180000);   // a full-width swipe is at most 3 minutes
                    gestureTarget = Math.max(0, Math.min(durationMs, gestureStartPos + Math.round(dx / root.getWidth() * span)));
                    long delta = gestureTarget - gestureStartPos;
                    flash(hud, Ui.time(gestureTarget) + " / " + Ui.time(durationMs) + "\n" + (delta >= 0 ? "+" : "-") + Ui.time(Math.abs(delta)), true);
                    updateTime(gestureTarget);
                }
                break;
            case MotionEvent.ACTION_UP:
                if (gesture == G_SEEK) { seeking = false; seekTo(gestureTarget); }
                gesture = G_NONE;
                seeking = false;
                break;
            case MotionEvent.ACTION_CANCEL:
                seeking = false;
                gesture = G_NONE;
                break;
            default:
                break;
        }
        return true;
    }

    private float currentBrightness() {
        if (brightness >= 0) return brightness;
        float b = getWindow().getAttributes().screenBrightness;
        if (b >= 0) return b;
        try {
            b = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS) / 255f;
        } catch (Settings.SettingNotFoundException e) {
            b = 0.5f;
        }
        return Math.max(0.01f, Math.min(1f, b));
    }

    private void setBrightness(float b) {
        brightness = b;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = b;
        getWindow().setAttributes(lp);
    }

    private final GestureDetector.SimpleOnGestureListener tapListener = new GestureDetector.SimpleOnGestureListener() {
        @Override public boolean onDown(MotionEvent e) { return true; }

        @Override public boolean onSingleTapConfirmed(MotionEvent e) {
            if (controlsVisible) hideControls(); else showControls();
            return true;
        }

        @Override public boolean onDoubleTap(MotionEvent e) {
            if (!prepared || askingResume) return true;
            int s = prefs.doubleTapSeconds();
            boolean right = e.getX() >= root.getWidth() / 2f;
            skip(right ? s : -s);
            flash(right ? hudRight : hudLeft, (right ? "+" : "-") + s + "s");
            return true;
        }
    };

    // ------------------------------------------------------------------ controls visibility / system UI

    private void showControls() {
        if (isPip) return;
        controlsVisible = true;
        controls.animate().cancel();
        controls.setVisibility(View.VISIBLE);
        controls.animate().alpha(1f).setDuration(150).setListener(null).start();
        subView.animate().translationY(-Ui.dp(this, 84)).setDuration(150).start();
        scheduleHide();
    }

    private void hideControls() {
        controlsVisible = false;
        ui.removeCallbacks(hideRunnable);
        controls.animate().cancel();
        controls.animate().alpha(0f).setDuration(150).withEndAction(() -> { if (!controlsVisible) controls.setVisibility(View.GONE); }).start();
        subView.animate().translationY(0).setDuration(150).start();
        hideSystemUi();
    }

    private final Runnable hideRunnable = new Runnable() {
        @Override public void run() { if (isPlaying() && !seeking) hideControls(); }
    };

    private void scheduleHide() {
        ui.removeCallbacks(hideRunnable);
        if (controlsVisible && isPlaying()) ui.postDelayed(hideRunnable, HIDE_DELAY);
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void setLocked(boolean on) {
        locked = on;
        if (on) {
            hideControls();
            Toast.makeText(this, "Screen locked. Tap the screen to show the unlock button.", Toast.LENGTH_SHORT).show();
        } else {
            unlockBtn.setVisibility(View.GONE);
            showControls();
        }
    }

    private final Runnable hideUnlock = new Runnable() {
        @Override public void run() { unlockBtn.setVisibility(View.GONE); }
    };

    private void showUnlockHint() {
        unlockBtn.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideUnlock);
        ui.postDelayed(hideUnlock, 2500);
    }

    private void flash(final TextView v, String text) { flash(v, text, false); }

    private void flash(final TextView v, String text, boolean sticky) {
        v.animate().cancel();
        v.setText(text);
        v.setAlpha(1f);
        v.setVisibility(View.VISIBLE);
        v.animate().alpha(0f).setStartDelay(sticky ? 900 : 650).setDuration(250)
                .withEndAction(() -> v.setVisibility(View.GONE)).start();
    }

    private void hideHud() {
        hud.animate().cancel();
        hud.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------------ keys

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                togglePlay();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                startPlayback();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                pausePlayback();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                skip(-prefs.doubleTapSeconds());
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                skip(prefs.doubleTapSeconds());
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                openIndex(index + 1);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                openIndex(index - 1);
                return true;
            default:
                return super.onKeyDown(code, e);
        }
    }

    // ------------------------------------------------------------------ building the UI

    private ImageButton btn(int res, String desc, View.OnClickListener l) {
        ImageButton b = Ui.iconButton(this, res, 44, Color.WHITE);
        b.setContentDescription(desc);
        b.setOnClickListener(l);
        return b;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setOnTouchListener((v, e) -> onRootTouch(e));
        scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                zoom = Math.max(0.5f, Math.min(5f, zoom * d.getScaleFactor()));
                applyZoom();
                flash(hud, "Zoom  " + Math.round(zoom * 100) + "%");
                return true;
            }
        });
        tapDetector = new GestureDetector(this, tapListener);
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) applyVideoLayout();
        });

        tv = new TextureView(this);
        tv.setSurfaceTextureListener(this);
        root.addView(tv, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        subView = new TextView(this);
        subView.setTextColor(Color.WHITE);
        subView.setGravity(Gravity.CENTER);
        subView.setShadowLayer(Ui.dp(this, 3), 0, 0, Color.BLACK);
        subView.setBackground(Ui.rect(0x99000000, Ui.dp(this, 6)));
        subView.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4));
        subView.setLineSpacing(0, 1.1f);
        subView.setVisibility(View.GONE);
        applySubtitleStyle();
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        sl.bottomMargin = Ui.dp(this, 28);
        sl.leftMargin = sl.rightMargin = Ui.dp(this, 48);
        root.addView(subView, sl);

        spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(accentText));
        root.addView(spinner, new FrameLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48), Gravity.CENTER));

        hud = Ui.text(this, "", 22, Color.WHITE, true);
        hud.setGravity(Gravity.CENTER);
        hud.setPadding(Ui.dp(this, 22), Ui.dp(this, 14), Ui.dp(this, 22), Ui.dp(this, 14));
        hud.setBackground(Ui.rect(0xCC000000, Ui.dp(this, 16)));
        hud.setVisibility(View.GONE);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        root.addView(hud, hl);

        hudLeft = circleHud(Gravity.CENTER_VERTICAL | Gravity.START);
        hudRight = circleHud(Gravity.CENTER_VERTICAL | Gravity.END);

        // ---- controls layer
        controls = new FrameLayout(this);
        FrameLayout cl = (FrameLayout) controls;

        topBar = new LinearLayout(this);
        LinearLayout top = (LinearLayout) topBar;
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xCC000000, 0x00000000}));
        top.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 16));
        backBtn = btn(R.drawable.ic_back, "Back", v -> onBackPressed());
        top.addView(backBtn, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        titleView = Ui.text(this, "", 16, Color.WHITE, true);
        titleView.setSingleLine();
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        titleView.setSelected(true);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1);
        tl.leftMargin = Ui.dp(this, 8);
        top.addView(titleView, tl);
        timerBtn = btn(R.drawable.ic_timer, "Sleep timer", v -> { showTimerDialog(); scheduleHide(); });
        top.addView(timerBtn, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        pipBtn = btn(R.drawable.ic_pip, "Picture in picture", v -> enterPip());
        pipBtn.setVisibility(Build.VERSION.SDK_INT >= 26 ? View.VISIBLE : View.GONE);
        top.addView(pipBtn, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        rotateBtn = btn(R.drawable.ic_rotate, "Lock rotation", v -> toggleRotationLock());
        top.addView(rotateBtn, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        cl.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        bottomBar = new LinearLayout(this);
        LinearLayout bottom = (LinearLayout) bottomBar;
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xDD000000, 0x00000000}));
        bottom.setPadding(Ui.dp(this, 12), Ui.dp(this, 24), Ui.dp(this, 12), Ui.dp(this, 8));

        LinearLayout seekRow = new LinearLayout(this);
        seekRow.setGravity(Gravity.CENTER_VERTICAL);
        elapsedView = Ui.text(this, "00:00", 13, Color.WHITE, false);
        elapsedView.setMinWidth(Ui.dp(this, 44));
        seekRow.addView(elapsedView);
        seek = new GradientSeekBar(this);
        seek.setListener(new GradientSeekBar.Listener() {
            @Override public void onStart() { seeking = true; ui.removeCallbacks(hideRunnable); }
            @Override public void onChange(long ms, boolean fromUser) {
                elapsedView.setText(Ui.time(ms));
                remainingView.setText("-" + Ui.time(durationMs - ms));
                flash(hud, Ui.time(ms) + " / " + Ui.time(durationMs), true);
            }
            @Override public void onStop(long ms) { seeking = false; seekTo(ms); hideHud(); scheduleHide(); }
        });
        seekRow.addView(seek, new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1));
        remainingView = Ui.text(this, "--:--", 13, Color.WHITE, false);
        remainingView.setMinWidth(Ui.dp(this, 48));
        remainingView.setGravity(Gravity.END);
        seekRow.addView(remainingView);
        bottom.addView(seekRow, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        lockBtn = btn(R.drawable.ic_lock, "Lock screen", v -> setLocked(true));
        aspectBtn = btn(R.drawable.ic_aspect, "Fit, fill, crop, 100%", v -> cycleAspect());
        prevBtn = btn(R.drawable.ic_prev, "Previous video", v -> openIndex(index - 1));
        playBtn = btn(R.drawable.ic_play, "Play or pause", v -> togglePlay());
        playBtn.setBackground(Ui.ripple(Ui.gradient(GradientDrawable.Orientation.TL_BR, Ui.dp(this, 26)), false));
        nextBtn = btn(R.drawable.ic_next, "Next video", v -> openIndex(index + 1));
        speedBtn = Ui.text(this, "1x", 15, Color.WHITE, true);
        speedBtn.setGravity(Gravity.CENTER);
        speedBtn.setContentDescription("Playback speed");
        speedBtn.setBackground(Ui.ripple(null, true));
        speedBtn.setOnClickListener(v -> { showSpeedDialog(); scheduleHide(); });
        speedBtn.setVisibility(Build.VERSION.SDK_INT >= 23 ? View.VISIBLE : View.GONE);
        audioBtn = btn(R.drawable.ic_audio, "Audio track", v -> { showAudioDialog(); scheduleHide(); });
        subBtn = btn(R.drawable.ic_subtitles, "Subtitles", v -> { showSubtitleDialog(); scheduleHide(); });

        LinearLayout.LayoutParams even = new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1);
        row.addView(lockBtn, even);
        row.addView(aspectBtn, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
        row.addView(prevBtn, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52));
        pl.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        row.addView(playBtn, pl);
        row.addView(nextBtn, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
        row.addView(speedBtn, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
        row.addView(audioBtn, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
        row.addView(subBtn, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
        bottom.addView(row, new LinearLayout.LayoutParams(-1, -2));
        cl.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        root.addView(controls, new FrameLayout.LayoutParams(-1, -1));

        unlockBtn = btn(R.drawable.ic_unlock, "Unlock screen", v -> setLocked(false));
        unlockBtn.setBackground(Ui.ripple(Ui.rect(0xCC000000, Ui.dp(this, 24)), false));
        unlockBtn.setVisibility(View.GONE);
        FrameLayout.LayoutParams ul = new FrameLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48), Gravity.CENTER_VERTICAL | Gravity.START);
        ul.leftMargin = Ui.dp(this, 32);
        root.addView(unlockBtn, ul);

        // keep controls clear of the camera cut-out and system bars
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int[] in = Build.VERSION.SDK_INT >= 28 ? Compat.safeInsets(insets)
                    : new int[]{insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom()};
            controls.setPadding(in[0], in[1], in[2], in[3]);
            return insets;
        });
        setContentView(root);
        hideSystemUi();
        updateNavButtons();
        updatePlayButton();
    }

    private TextView circleHud(int gravity) {
        TextView t = Ui.text(this, "", 18, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0x99000000);
        t.setBackground(g);
        t.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(this, 88), Ui.dp(this, 88), gravity);
        lp.leftMargin = lp.rightMargin = Ui.dp(this, 64);
        root.addView(t, lp);
        return t;
    }

    private void updatePlayButton() {
        boolean playing = isPlaying() || (wantPlay && !prepared && mp != null);
        playBtn.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
    }
}
