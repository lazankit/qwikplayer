package com.qwikplayer.player;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.Locale;

/** Brand A ("Afterburn") palette and small view helpers shared by every screen (the UI is built in code). */
final class Ui {
    static final int BG = 0xFF0D0B14, SURFACE = 0xFF181522, RAISED = 0xFF242033, BORDER = 0xFF352F4A;
    static final int TEXT = 0xFFF5F2FB, MUTED = 0xFFA8A1BC;
    static final int VOLT = 0xFF7438FF, VOLT_LIGHT = 0xFFB9A0FF, EMBER = 0xFFFF8A2B, PULSE = 0xFFFF2D87, SPARK = 0xFFFFD84A;
    static final int SUCCESS = 0xFF2FD6A1, ERROR = 0xFFFF4D5E;
    /** Ember -> Pulse -> Volt, the brand gradient. */
    static final int[] GRADIENT = {EMBER, PULSE, VOLT};

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    // ----- theme accent (Settings): 0 Volt, 1 Ember, 2 Pulse -----
    static int accent(Context c) {
        switch (Prefs.get(c).accentIndex()) {
            case 1: return EMBER;
            case 2: return PULSE;
            default: return VOLT;
        }
    }

    /** The accent as a text / icon colour on the dark background. */
    static int accentText(Context c) {
        return Prefs.get(c).accentIndex() == 0 ? VOLT_LIGHT : accent(c);
    }

    /** Text colour that sits on a filled accent surface (white on Volt and Pulse, near-black on Ember). */
    static int onAccent(Context c) {
        return Prefs.get(c).accentIndex() == 1 ? 0xFF1A1206 : Color.WHITE;
    }

    static GradientDrawable rect(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    static GradientDrawable gradient(GradientDrawable.Orientation o, float radiusPx) {
        GradientDrawable d = new GradientDrawable(o, GRADIENT);
        d.setCornerRadius(radiusPx);
        return d;
    }

    /** Seek bar / progress gradient: Volt at the start, Ember at the playhead. */
    static GradientDrawable seekGradient(float radiusPx) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{VOLT, PULSE, EMBER});
        d.setCornerRadius(radiusPx);
        return d;
    }

    static Drawable ripple(Drawable content, boolean borderless) {
        Drawable mask = null;
        if (!borderless) {
            // the ripple is clipped to the content's own shape (rounded buttons stay round)
            mask = content != null && content.getConstantState() != null
                    ? content.getConstantState().newDrawable() : new android.graphics.drawable.ColorDrawable(Color.WHITE);
        }
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, mask);
    }

    static ImageButton iconButton(Context c, int res, int sizeDp, int tint) {
        ImageButton b = new ImageButton(c);
        b.setImageResource(res);
        b.setColorFilter(tint);
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = dp(c, 10);
        b.setPadding(pad, pad, pad, pad);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, new android.graphics.drawable.ColorDrawable(Color.WHITE)));
        b.setMinimumWidth(dp(c, sizeDp));
        b.setMinimumHeight(dp(c, sizeDp));
        return b;
    }

    static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setIncludeFontPadding(false);
        return t;
    }

    static TextView pill(Context c, CharSequence s, int bg, int fg, float sp) {
        TextView t = text(c, s, sp, fg, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 8), dp(c, 3), dp(c, 8), dp(c, 3));
        t.setBackground(rect(bg, dp(c, 10)));
        return t;
    }

    static View dot(Context c, int color, int sizeDp) {
        View v = new View(c);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        v.setBackground(d);
        v.setLayoutParams(new android.widget.LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return v;
    }

    // ----- formatting -----
    static String time(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000, h = s / 3600, m = (s / 60) % 60;
        s %= 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s) : String.format(Locale.US, "%02d:%02d", m, s);
    }

    static String size(long b) {
        if (b < 1024) return b + " B";
        double v = b / 1024.0;
        String[] u = {"KB", "MB", "GB", "TB"};
        int i = 0;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        return String.format(Locale.US, v >= 100 || i == 0 ? "%.0f %s" : "%.1f %s", v, u[i]);
    }
}
