package com.qwikplayer.player;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A small, forgiving SubRip (.srt) parser. Text is decoded as UTF-8 (BOM allowed) or, if that is invalid, Windows-1252. */
final class SrtParser {
    static final class Cue {
        final long start, end;
        final String text;
        Cue(long start, long end, String text) { this.start = start; this.end = end; this.text = text; }
    }

    private static final Pattern TIME = Pattern.compile(
            "(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})");

    private SrtParser() {}

    static List<Cue> parse(byte[] data) {
        String s = decode(data).replace("\r\n", "\n").replace('\r', '\n');
        if (!s.isEmpty() && s.charAt(0) == 0xFEFF) s = s.substring(1);
        List<Cue> cues = new ArrayList<>();
        for (String block : s.split("\n\\s*\n")) {
            String[] lines = block.trim().split("\n");
            int ti = -1;
            for (int i = 0; i < lines.length && i < 3; i++) if (TIME.matcher(lines[i]).find()) { ti = i; break; }
            if (ti < 0) continue;
            Matcher m = TIME.matcher(lines[ti]);
            m.find();
            long a = ms(m.group(1), m.group(2), m.group(3), m.group(4));
            long b = ms(m.group(5), m.group(6), m.group(7), m.group(8));
            StringBuilder sb = new StringBuilder();
            for (int i = ti + 1; i < lines.length; i++) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(lines[i]);
            }
            String text = clean(sb.toString());
            if (!text.isEmpty() && b >= a) cues.add(new Cue(a, b, text));
        }
        Collections.sort(cues, new Comparator<Cue>() {
            @Override public int compare(Cue x, Cue y) { return Long.compare(x.start, y.start); }
        });
        return cues;
    }

    /** The cue showing at {@code posMs}, or null. Cues are sorted by start; overlapping cues are rare, the latest start wins. */
    static Cue at(List<Cue> cues, long posMs) {
        int lo = 0, hi = cues.size() - 1, best = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (cues.get(mid).start <= posMs) { best = mid; lo = mid + 1; } else hi = mid - 1;
        }
        if (best < 0) return null;
        // step back over a few cues in case an earlier, longer cue is still showing
        for (int i = best; i >= 0 && i > best - 3; i--) if (posMs < cues.get(i).end) return cues.get(i);
        return null;
    }

    private static long ms(String h, String m, String s, String frac) {
        while (frac.length() < 3) frac += "0";
        return Long.parseLong(h) * 3600000L + Long.parseLong(m) * 60000L + Long.parseLong(s) * 1000L + Long.parseLong(frac);
    }

    /** Drops {\an8}-style override blocks and HTML-ish tags; keeps the text. */
    private static String clean(String t) {
        t = t.replaceAll("\\{\\\\[^}]*\\}", "").replaceAll("</?[a-zA-Z][^>]*>", "");
        t = t.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
        return t.trim();
    }

    private static String decode(byte[] data) {
        try {
            return Charset.forName("UTF-8").newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException e) {
            return new String(data, Charset.forName("windows-1252"));
        }
    }
}
