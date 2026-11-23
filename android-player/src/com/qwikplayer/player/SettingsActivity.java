package com.qwikplayer.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Defaults for the player: resume, double-tap seconds, subtitles, orientation, accent colour. */
public class SettingsActivity extends Activity {
    private Prefs prefs;
    private LinearLayout list;

    private static final String[] RESUME = {"Ask each time", "Always resume", "Always start over"};
    private static final String[] ORIENT = {"Follow the video", "Landscape", "Portrait", "Auto-rotate (system setting)"};
    private static final String[] ACCENT = {"Volt (violet)", "Ember (orange)", "Pulse (pink)"};
    private static final int[] TAP = {5, 10, 15, 20, 30};

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = Prefs.get(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 16), 0);
        ImageButton back = Ui.iconButton(this, R.drawable.ic_back, 48, Ui.TEXT);
        back.setContentDescription("Back");
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 56)));
        TextView t = Ui.text(this, "Settings", 20, Ui.TEXT, true);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.leftMargin = Ui.dp(this, 8);
        header.addView(t, tl);
        root.addView(header, new LinearLayout.LayoutParams(-1, Ui.dp(this, 56)));
        View line = new View(this);
        line.setBackground(Ui.gradient(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, 0));
        root.addView(line, new LinearLayout.LayoutParams(-1, Ui.dp(this, 2)));

        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 24));
        sv.addView(list, new ScrollView.LayoutParams(-1, -2));
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        render();
    }

    private void render() {
        list.removeAllViews();
        section("Playback");
        choice("Resume playback", RESUME[prefs.resumeMode()], v -> pick("Resume playback", RESUME, prefs.resumeMode(), i -> prefs.setResumeMode(i)));
        choice("Double-tap to skip", prefs.doubleTapSeconds() + " seconds", v -> {
            String[] names = new String[TAP.length];
            int cur = 0;
            for (int i = 0; i < TAP.length; i++) { names[i] = TAP[i] + " seconds"; if (TAP[i] == prefs.doubleTapSeconds()) cur = i; }
            pick("Double-tap to skip", names, cur, i -> prefs.setDoubleTapSeconds(TAP[i]));
        });
        toggle("Play next video automatically", "Continue with the next file in the folder", prefs.autoNext(), on -> prefs.setAutoNext(on));
        choice("Default orientation", ORIENT[prefs.orientation()], v -> pick("Default orientation", ORIENT, prefs.orientation(), i -> prefs.setOrientation(i)));
        if (Build.VERSION.SDK_INT >= 26) {
            toggle("Picture-in-picture on Home", "Keep playing in a small window when you leave the app", prefs.pipOnHome(), on -> prefs.setPipOnHome(on));
        }

        section("Subtitles");
        choice("Subtitle size", prefs.subtitleSize() + " sp", v -> showSizeDialog());
        toggle("Load .srt automatically", "Use a file with the same name next to the video", prefs.subtitleAuto(), on -> prefs.setSubtitleAuto(on));

        section("Appearance");
        choice("Accent colour", ACCENT[prefs.accentIndex()], v -> pick("Accent colour", ACCENT, prefs.accentIndex(), i -> { prefs.setAccentIndex(i); recreate(); }));

        section("Data");
        choice("Clear watch history", "Forget positions and \"watched\" marks", v ->
                new AlertDialog.Builder(this).setTitle("Clear watch history?")
                        .setMessage("Every video will show as new again and no longer resume. Your files are not touched.")
                        .setPositiveButton("Clear", (d, w) -> { prefs.clearProgress(); Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show(); })
                        .setNegativeButton("Cancel", null).show());

        section("About");
        choice("QwikPlayer", "Version 1.0.0 · plays what your phone's decoders support", null);
    }

    private interface IntCallback { void run(int i); }
    private interface BoolCallback { void run(boolean b); }

    private void pick(String title, String[] names, int current, final IntCallback cb) {
        new AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(names, current, (d, which) -> { cb.run(which); d.dismiss(); render(); })
                .setNegativeButton("Cancel", null).show();
    }

    private void showSizeDialog() {
        final android.widget.SeekBar sb = new android.widget.SeekBar(this);
        sb.setMax(40 - 12);
        sb.setProgress(prefs.subtitleSize() - 12);
        final TextView sample = Ui.text(this, "Subtitle preview", prefs.subtitleSize(), Color.WHITE, false);
        sample.setGravity(Gravity.CENTER);
        sample.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        sample.setBackground(Ui.rect(0xFF000000, Ui.dp(this, 8)));
        sb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar s, int p, boolean u) { sample.setTextSize(p + 12); }
            @Override public void onStartTrackingTouch(android.widget.SeekBar s) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar s) {}
        });
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 24), Ui.dp(this, 16), Ui.dp(this, 24), 0);
        box.addView(sample, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = Ui.dp(this, 16);
        box.addView(sb, sl);
        new AlertDialog.Builder(this).setTitle("Subtitle size").setView(box)
                .setPositiveButton("OK", (d, w) -> { prefs.setSubtitleSize(sb.getProgress() + 12); render(); })
                .setNegativeButton("Cancel", null).show();
    }

    // ----- row builders -----
    private void section(String s) {
        TextView t = Ui.text(this, s.toUpperCase(), 12, Ui.accentText(this), true);
        t.setLetterSpacing(0.08f);
        t.setPadding(Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 6));
        list.addView(t);
    }

    private LinearLayout row(String title, String sub) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(Ui.dp(this, 64));
        r.setPadding(Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 20), Ui.dp(this, 10));
        r.setBackground(Ui.ripple(null, false));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(Ui.text(this, title, 16, Ui.TEXT, false));
        TextView s = Ui.text(this, sub, 13, Ui.MUTED, false);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
        sl.topMargin = Ui.dp(this, 4);
        col.addView(s, sl);
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        list.addView(r, new LinearLayout.LayoutParams(-1, -2));
        return r;
    }

    private void choice(String title, String value, View.OnClickListener click) {
        LinearLayout r = row(title, value);
        if (click != null) r.setOnClickListener(click);
    }

    private void toggle(String title, String sub, boolean on, final BoolCallback cb) {
        LinearLayout r = row(title, sub);
        final Switch sw = new Switch(this);
        sw.setChecked(on);
        sw.setClickable(false);
        r.addView(sw);
        r.setOnClickListener(v -> { sw.toggle(); cb.run(sw.isChecked()); });
    }
}
