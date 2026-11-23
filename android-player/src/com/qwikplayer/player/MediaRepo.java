package com.qwikplayer.player;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reads the device's videos from MediaStore and groups them by folder (bucket). Call from a background thread. */
final class MediaRepo {
    private MediaRepo() {}

    @SuppressWarnings("deprecation")
    static List<Video> loadAll(Context c) {
        List<Video> out = new ArrayList<>();
        Uri base = Build.VERSION.SDK_INT >= 29
                ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] cols = {
                MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE, MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT,
                MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.BUCKET_ID,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME, MediaStore.Video.Media.DATA};
        ContentResolver cr = c.getContentResolver();
        Cursor cur = null;
        try {
            cur = cr.query(base, cols, null, null, MediaStore.Video.Media.DATE_ADDED + " DESC");
            if (cur == null) return out;
            while (cur.moveToNext()) {
                Video v = new Video();
                v.id = cur.getLong(0);
                v.uri = ContentUris.withAppendedId(base, v.id);
                v.name = nz(cur.getString(1));
                v.duration = cur.getLong(2);
                v.size = cur.getLong(3);
                v.width = cur.getInt(4);
                v.height = cur.getInt(5);
                v.added = cur.getLong(6);
                v.bucketId = nz(cur.getString(7));
                v.bucketName = nz(cur.getString(8));
                v.path = cur.getString(9);
                if (v.name.isEmpty() && v.path != null) v.name = new java.io.File(v.path).getName();
                if (v.bucketName.isEmpty()) v.bucketName = "Videos";
                out.add(v);
            }
        } catch (RuntimeException e) {
            // permission revoked while querying, or a vendor MediaStore quirk: show what we have (maybe nothing)
        } finally {
            if (cur != null) cur.close();
        }
        return out;
    }

    static List<Video.Folder> group(List<Video> all) {
        Map<String, Video.Folder> map = new LinkedHashMap<>();
        for (Video v : all) {
            String k = v.bucketId.isEmpty() ? v.bucketName : v.bucketId;
            Video.Folder f = map.get(k);
            if (f == null) {
                f = new Video.Folder();
                f.id = k;
                f.name = v.bucketName;
                map.put(k, f);
            }
            f.videos.add(v);
            f.totalSize += v.size;
            f.latest = Math.max(f.latest, v.added);
        }
        return new ArrayList<>(map.values());
    }

    static void sortVideos(List<Video> list, int key, boolean desc) {
        final int dir = desc ? -1 : 1;
        Collections.sort(list, new Comparator<Video>() {
            @Override public int compare(Video a, Video b) {
                int r;
                if (key == Prefs.SORT_DATE) r = Long.compare(a.added, b.added);
                else if (key == Prefs.SORT_SIZE) r = Long.compare(a.size, b.size);
                else r = naturalCompare(a.name, b.name);
                if (r == 0) r = naturalCompare(a.name, b.name);
                return r * dir;
            }
        });
    }

    static void sortFolders(List<Video.Folder> list, int key, boolean desc) {
        final int dir = desc ? -1 : 1;
        Collections.sort(list, new Comparator<Video.Folder>() {
            @Override public int compare(Video.Folder a, Video.Folder b) {
                int r;
                if (key == Prefs.SORT_DATE) r = Long.compare(a.latest, b.latest);
                else if (key == Prefs.SORT_SIZE) r = Long.compare(a.totalSize, b.totalSize);
                else r = naturalCompare(a.name, b.name);
                if (r == 0) r = naturalCompare(a.name, b.name);
                return r * dir;
            }
        });
    }

    /** Case-insensitive compare where digit runs compare as numbers ("Ep 2" before "Ep 10"). */
    static int naturalCompare(String a, String b) {
        String x = a.toLowerCase(Locale.ROOT), y = b.toLowerCase(Locale.ROOT);
        int i = 0, j = 0;
        while (i < x.length() && j < y.length()) {
            char p = x.charAt(i), q = y.charAt(j);
            if (Character.isDigit(p) && Character.isDigit(q)) {
                int si = i, sj = j;
                while (i < x.length() && Character.isDigit(x.charAt(i))) i++;
                while (j < y.length() && Character.isDigit(y.charAt(j))) j++;
                String na = x.substring(si, i).replaceFirst("^0+(?=.)", ""), nb = y.substring(sj, j).replaceFirst("^0+(?=.)", "");
                if (na.length() != nb.length()) return na.length() - nb.length();
                int r = na.compareTo(nb);
                if (r != 0) return r;
            } else {
                if (p != q) return p - q;
                i++;
                j++;
            }
        }
        return (x.length() - i) - (y.length() - j);
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
