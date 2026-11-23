package com.qwikplayer.player;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

/** Settings and per-file playback state, both in SharedPreferences (no database needed). */
final class Prefs {
    static final int RESUME_ASK = 0, RESUME_ALWAYS = 1, RESUME_NEVER = 2;
    static final int ORI_VIDEO = 0, ORI_LANDSCAPE = 1, ORI_PORTRAIT = 2, ORI_SYSTEM = 3;
    static final int SORT_NAME = 0, SORT_DATE = 1, SORT_SIZE = 2;

    /** Remembered state of one video: position (ms), duration (ms) and whether it was finished. */
    static final class Progress {
        final long pos, dur;
        final boolean watched;
        Progress(long pos, long dur, boolean watched) { this.pos = pos; this.dur = dur; this.watched = watched; }
        float fraction() { return dur > 0 && pos > 0 && !watched ? Math.min(1f, (float) pos / dur) : 0f; }
    }

    private final SharedPreferences sp;     // settings
    private final SharedPreferences state;  // key -> "pos:dur:watched"

    private Prefs(Context c) {
        Context app = c.getApplicationContext();
        sp = app.getSharedPreferences("settings", Context.MODE_PRIVATE);
        state = app.getSharedPreferences("playback", Context.MODE_PRIVATE);
    }

    static Prefs get(Context c) { return new Prefs(c); }

    // ----- settings -----
    int resumeMode() { return sp.getInt("resume", RESUME_ASK); }
    void setResumeMode(int v) { sp.edit().putInt("resume", v).apply(); }
    int doubleTapSeconds() { return sp.getInt("double_tap", 10); }
    void setDoubleTapSeconds(int v) { sp.edit().putInt("double_tap", v).apply(); }
    int subtitleSize() { return sp.getInt("sub_size", 20); }
    void setSubtitleSize(int v) { sp.edit().putInt("sub_size", Math.max(12, Math.min(40, v))).apply(); }
    boolean subtitleAuto() { return sp.getBoolean("sub_auto", true); }
    void setSubtitleAuto(boolean v) { sp.edit().putBoolean("sub_auto", v).apply(); }
    boolean subtitleOn() { return sp.getBoolean("sub_on", true); }
    void setSubtitleOn(boolean v) { sp.edit().putBoolean("sub_on", v).apply(); }
    int orientation() { return sp.getInt("orientation", ORI_VIDEO); }
    void setOrientation(int v) { sp.edit().putInt("orientation", v).apply(); }
    int accentIndex() { return Math.max(0, Math.min(2, sp.getInt("accent", 0))); }
    void setAccentIndex(int v) { sp.edit().putInt("accent", v).apply(); }
    boolean autoNext() { return sp.getBoolean("auto_next", true); }
    void setAutoNext(boolean v) { sp.edit().putBoolean("auto_next", v).apply(); }
    boolean pipOnHome() { return sp.getBoolean("pip_home", false); }
    void setPipOnHome(boolean v) { sp.edit().putBoolean("pip_home", v).apply(); }
    int sortKey() { return sp.getInt("sort", SORT_DATE); }
    boolean sortDesc() { return sp.getBoolean("sort_desc", true); }
    void setSort(int key, boolean desc) { sp.edit().putInt("sort", key).putBoolean("sort_desc", desc).apply(); }
    boolean permissionAsked() { return sp.getBoolean("perm_asked", false); }
    void setPermissionAsked() { sp.edit().putBoolean("perm_asked", true).apply(); }

    // ----- per-file playback state -----
    /** Saves the position. Within the last 5% (or on completion) the file counts as watched and restarts at 0. */
    void savePosition(String key, long pos, long dur, boolean completed) {
        boolean watched = completed || (dur > 0 && pos >= dur * 0.95);
        state.edit().putString(key, (watched ? 0 : Math.max(0, pos)) + ":" + Math.max(0, dur) + ":" + (watched ? 1 : 0)).apply();
    }

    Progress progress(String key) { return parse(state.getString(key, null)); }

    /** Snapshot for list screens: one read of the whole file instead of one lookup per row. */
    Map<String, Progress> allProgress() {
        Map<String, Progress> out = new HashMap<>();
        for (Map.Entry<String, ?> e : state.getAll().entrySet()) {
            Progress p = e.getValue() instanceof String ? parse((String) e.getValue()) : null;
            if (p != null) out.put(e.getKey(), p);
        }
        return out;
    }

    void clearProgress() { state.edit().clear().apply(); }

    private static Progress parse(String s) {
        if (s == null) return null;
        String[] p = s.split(":");
        if (p.length < 3) return null;
        try {
            return new Progress(Long.parseLong(p[0]), Long.parseLong(p[1]), "1".equals(p[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
