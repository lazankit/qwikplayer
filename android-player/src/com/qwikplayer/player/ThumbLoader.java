package com.qwikplayer.player;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.widget.ImageView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads video thumbnails off the UI thread with a small memory cache. */
final class ThumbLoader {
    private final ContentResolver cr;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LruCache<Long, Bitmap> cache = new LruCache<Long, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(Long k, Bitmap b) { return b.getByteCount(); }
    };
    private volatile boolean closed;

    ThumbLoader(Context c) { cr = c.getApplicationContext().getContentResolver(); }

    void load(final Video v, final ImageView target) {
        target.setTag(v.id);
        Bitmap hit = cache.get(v.id);
        if (hit != null) { target.setImageBitmap(hit); return; }
        target.setImageDrawable(null);
        pool.execute(new Runnable() {
            @Override public void run() {
                if (closed) return;
                final Bitmap b = fetch(v);
                if (b == null) return;
                cache.put(v.id, b);
                ui.post(new Runnable() {
                    @Override public void run() {
                        // the row may have been recycled for another video meanwhile
                        if (!closed && Long.valueOf(v.id).equals(target.getTag())) target.setImageBitmap(b);
                    }
                });
            }
        });
    }

    @SuppressWarnings("deprecation")
    private Bitmap fetch(Video v) {
        try {
            if (Build.VERSION.SDK_INT >= 29) return cr.loadThumbnail(v.uri, new Size(320, 180), null);
            return MediaStore.Video.Thumbnails.getThumbnail(cr, v.id, MediaStore.Video.Thumbnails.MINI_KIND, null);
        } catch (Exception | OutOfMemoryError e) {
            return null;   // unreadable or corrupt file: the row keeps its placeholder
        }
    }

    void close() {
        closed = true;
        pool.shutdownNow();
        cache.evictAll();
    }
}
