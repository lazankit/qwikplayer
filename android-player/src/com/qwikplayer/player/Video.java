package com.qwikplayer.player;

import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

/** One video from MediaStore (or a single file opened from another app). */
final class Video {
    long id;
    Uri uri;
    String name = "";
    String path;          // may be null (scoped storage / content:// from other apps)
    long duration, size, added;   // ms, bytes, seconds
    int width, height;
    String bucketId = "", bucketName = "";

    String key() { return uri.toString(); }

    /** Display title: the file name without its extension. */
    String title() {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** A folder that contains videos. */
    static final class Folder {
        String id, name;
        final List<Video> videos = new ArrayList<>();
        long totalSize, latest;
    }

    /** The list the player steps through with Previous / Next (set by the library, survives only while the process lives). */
    static final class Playlist {
        static List<Video> items = new ArrayList<>();
        private Playlist() {}
    }
}
