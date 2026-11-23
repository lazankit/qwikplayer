package com.qwikplayer.player;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

/** Minimal pull-to-refresh for a single scrolling child (the framework has no SwipeRefreshLayout). */
final class PullRefreshLayout extends FrameLayout {
    interface Listener { void onRefresh(); }

    private final View target;
    private final ProgressBar spinner;
    private final int slop, threshold, hold;
    private Listener listener;
    private float startY, pull;
    private boolean dragging, refreshing;

    PullRefreshLayout(Context c, View target) {
        super(c);
        this.target = target;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        threshold = Ui.dp(c, 72);
        hold = Ui.dp(c, 56);
        addView(target, new LayoutParams(-1, -1));
        spinner = new ProgressBar(c);
        spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(Ui.accentText(c)));
        LayoutParams lp = new LayoutParams(Ui.dp(c, 32), Ui.dp(c, 32), android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
        lp.topMargin = Ui.dp(c, 8);
        spinner.setVisibility(INVISIBLE);
        addView(spinner, lp);
    }

    void setListener(Listener l) { listener = l; }

    void setRefreshing(boolean r) {
        if (r == refreshing) return;
        refreshing = r;
        if (r) {
            spinner.setVisibility(VISIBLE);
            spinner.setAlpha(1f);
            settle(hold);
        } else {
            settle(0);
        }
    }

    boolean isRefreshing() { return refreshing; }

    private boolean canPull() {
        return !target.canScrollVertically(-1);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startY = e.getY();
                dragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!dragging && !refreshing && e.getY() - startY > slop && canPull()) {
                    dragging = true;
                    startY = e.getY();
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                break;
            default:
                break;
        }
        return dragging;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                if (!dragging) return false;
                pull = Math.max(0, Math.min(threshold * 1.6f, (e.getY() - startY) * 0.5f));
                target.setTranslationY(pull);
                spinner.setVisibility(VISIBLE);
                spinner.setAlpha(Math.min(1f, pull / threshold));
                spinner.setTranslationY(pull * 0.5f);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) return false;
                dragging = false;
                if (e.getActionMasked() == MotionEvent.ACTION_UP && pull >= threshold && listener != null) {
                    refreshing = true;
                    settle(hold);
                    listener.onRefresh();
                } else {
                    settle(0);
                }
                return true;
            default:
                return dragging;
        }
    }

    private void settle(final float to) {
        pull = to;
        target.animate().translationY(to).setDuration(180).start();
        spinner.animate().translationY(to * 0.5f).alpha(to > 0 ? 1f : 0f).setDuration(180)
                .withEndAction(new Runnable() {
                    @Override public void run() { if (!refreshing) spinner.setVisibility(INVISIBLE); }
                }).start();
    }
}
