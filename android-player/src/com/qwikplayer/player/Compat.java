package com.qwikplayer.player;

import android.app.Activity;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.app.PictureInPictureParams;
import android.util.Rational;
import android.view.DisplayCutout;
import android.view.WindowInsets;

/**
 * Calls that need a newer Android than the app's minSdk (21). Every caller checks Build.VERSION.SDK_INT first, so
 * these methods only run where the classes exist; keeping them in one place keeps PlayerActivity readable.
 */
final class Compat {
    private Compat() {}

    // ---- playback speed (API 23) ----
    static void setSpeed(MediaPlayer mp, float speed) {
        PlaybackParams p = mp.getPlaybackParams();
        p.setSpeed(speed);
        mp.setPlaybackParams(p);
    }

    static int selectedAudioTrack(MediaPlayer mp) {
        return mp.getSelectedTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO);
    }

    // ---- picture in picture (API 26) ----
    private static Rational ratio(int w, int h) {
        if (w <= 0 || h <= 0) return new Rational(16, 9);
        float r = (float) w / h;
        if (r > 2.39f) return new Rational(239, 100);   // the system rejects ratios outside 1:2.39 .. 2.39:1
        if (r < 0.42f) return new Rational(42, 100);
        return new Rational(w, h);
    }

    static boolean enterPip(Activity a, int w, int h) {
        try {
            return a.enterPictureInPictureMode(new PictureInPictureParams.Builder().setAspectRatio(ratio(w, h)).build());
        } catch (RuntimeException e) {
            return false;   // PiP disabled by the user / not supported on this device
        }
    }

    static void updatePipRatio(Activity a, int w, int h) {
        try {
            a.setPictureInPictureParams(new PictureInPictureParams.Builder().setAspectRatio(ratio(w, h)).build());
        } catch (RuntimeException ignored) {
        }
    }

    static boolean inPip(Activity a) { return a.isInPictureInPictureMode(); }

    // ---- audio focus (AudioFocusRequest from API 26, the old call below it) ----
    /** Re-uses {@code existing} (the request returned by an earlier call) so one request is requested and abandoned. */
    static Object requestFocus(AudioManager am, AudioManager.OnAudioFocusChangeListener l, Object existing) {
        AudioFocusRequest r = existing != null ? (AudioFocusRequest) existing
                : new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setOnAudioFocusChangeListener(l).build();
        am.requestAudioFocus(r);
        return r;
    }

    static void abandonFocus(AudioManager am, Object request) {
        am.abandonAudioFocusRequest((AudioFocusRequest) request);
    }

    // ---- display cutout (API 28) ----
    /** {left, top, right, bottom} the app should keep clear of: the larger of system bars and the camera cut-out. */
    static int[] safeInsets(WindowInsets i) {
        int l = i.getSystemWindowInsetLeft(), t = i.getSystemWindowInsetTop(), r = i.getSystemWindowInsetRight(), b = i.getSystemWindowInsetBottom();
        DisplayCutout c = i.getDisplayCutout();
        if (c != null) {
            l = Math.max(l, c.getSafeInsetLeft());
            t = Math.max(t, c.getSafeInsetTop());
            r = Math.max(r, c.getSafeInsetRight());
            b = Math.max(b, c.getSafeInsetBottom());
        }
        return new int[]{l, t, r, b};
    }

    static void useCutoutArea(android.view.Window w) {
        android.view.WindowManager.LayoutParams lp = w.getAttributes();
        lp.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        w.setAttributes(lp);
    }
}
