package com.qwikplayer.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.MotionEvent;
import android.view.View;

/** Seek bar whose played part is the brand gradient (Volt at 0:00 -> Ember at the playhead). */
final class GradientSeekBar extends View {
    interface Listener {
        void onStart();
        void onChange(long ms, boolean fromUser);
        void onStop(long ms);
    }

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final GradientDrawable played;
    private final RectF r = new RectF();
    private final float barH, thumbR, thumbDragR;
    private long max = 1, value;
    private boolean dragging;
    private Listener listener;

    GradientSeekBar(Context c) {
        super(c);
        barH = Ui.dp(c, 4);
        thumbR = Ui.dp(c, 7);
        thumbDragR = Ui.dp(c, 10);
        track.setColor(0x55FFFFFF);
        thumb.setColor(Ui.SPARK);
        played = Ui.seekGradient(barH / 2);
        setMinimumHeight(Ui.dp(c, 40));
    }

    void setListener(Listener l) { listener = l; }

    void setMax(long m) { max = Math.max(1, m); invalidate(); }

    void setValue(long v) {
        if (dragging) return;   // the finger wins over the ticker
        value = Math.max(0, Math.min(max, v));
        invalidate();
    }

    long getMax() { return max; }

    @Override protected void onDraw(Canvas c) {
        float pad = thumbDragR, w = getWidth() - 2 * pad, cy = getHeight() / 2f;
        r.set(pad, cy - barH / 2, pad + w, cy + barH / 2);
        c.drawRoundRect(r, barH / 2, barH / 2, track);
        float x = pad + w * value / max;
        played.setBounds(Math.round(pad), Math.round(cy - barH / 2), Math.max(Math.round(pad) + 1, Math.round(x)), Math.round(cy + barH / 2));
        played.draw(c);
        c.drawCircle(x, cy, dragging ? thumbDragR : thumbR, thumb);
    }

    private long toValue(float x) {
        float pad = thumbDragR, w = Math.max(1, getWidth() - 2 * pad);
        return Math.round(max * Math.max(0f, Math.min(1f, (x - pad) / w)));
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                dragging = true;
                value = toValue(e.getX());
                if (listener != null) { listener.onStart(); listener.onChange(value, true); }
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) return false;
                value = toValue(e.getX());
                if (listener != null) listener.onChange(value, true);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) return false;
                dragging = false;
                if (listener != null) listener.onStop(value);
                invalidate();
                return true;
            default:
                return true;
        }
    }
}
