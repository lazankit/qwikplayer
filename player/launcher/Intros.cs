// Skip Intro / Skip Credits: finds the theme song and the end credits of a series by comparing the
// audio of neighbouring episodes (the part that sounds the same in both is the intro / credits).
// Results are cached in %LOCALAPPDATA%\Oranix\Player\intros.json and read by scripts\skip-intro.lua.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text.RegularExpressions;
using System.Threading;
using System.Web.Script.Serialization;

public class IntroInfo
{
    public double introStart = -1, introEnd = -1, creditsStart = -1, dur;
    public int v = Intros.Version;
}

static class Intros
{
    public const int Version = 1;
    const int Rate = 8000, Frame = 2048, Hop = 1024;              // 0.128 s per fingerprint frame
    const double Step = (double)Hop / Rate;
    const double HeadSeconds = 600, TailSeconds = 420;            // where intros and credits are looked for
    public static string Ffmpeg;
    public static event Action<string> Updated;                    // path whose info changed
    static readonly string File_ = Path.Combine(Library.DataDir, "intros.json");
    static readonly JavaScriptSerializer Json = new JavaScriptSerializer { MaxJsonLength = int.MaxValue };
    static Dictionary<string, IntroInfo> cache;
    static readonly object Lock = new object();
    static readonly Queue<List<string>> jobs = new Queue<List<string>>();
    static Thread worker;

    static void LoadCache()
    {
        if (cache != null) return;
        try { cache = Json.Deserialize<Dictionary<string, IntroInfo>>(File.ReadAllText(File_)); } catch { }
        cache = new Dictionary<string, IntroInfo>(cache ?? new Dictionary<string, IntroInfo>(), StringComparer.OrdinalIgnoreCase);
    }

    static void Save()
    {
        string t; lock (Lock) t = Json.Serialize(cache);
        try { File.WriteAllText(File_, t); } catch { }
    }

    static bool Known(string p) { IntroInfo i; lock (Lock) { LoadCache(); return cache.TryGetValue(p, out i) && i.v == Version; } }

    // Analyse a show's episodes (in order), starting around `current` so the playing one is ready first.
    public static void Analyse(List<string> episodes, string current)
    {
        if (episodes.Count < 2 || string.IsNullOrEmpty(Ffmpeg)) return;
        lock (Lock) {
            LoadCache();
            jobs.Enqueue(new List<string>(episodes) { current ?? "" });
            if (worker == null || !worker.IsAlive) {
                worker = new Thread(Loop) { IsBackground = true, Priority = ThreadPriority.BelowNormal };
                worker.Start();
            }
        }
    }

    static void Loop()
    {
        while (true) {
            List<string> job;
            lock (Lock) { if (jobs.Count == 0) { worker = null; return; } job = jobs.Dequeue(); }
            string current = job[job.Count - 1]; job.RemoveAt(job.Count - 1);
            int at = Math.Max(0, job.FindIndex(p => string.Equals(p, current, StringComparison.OrdinalIgnoreCase)));
            // current first, then the ones after it, then the ones before
            var order = Enumerable.Range(at, job.Count - at).Concat(Enumerable.Range(0, at)).ToList();
            var prints = new Dictionary<int, Prints>();
            Func<int, Prints> get = i => { Prints p; if (!prints.TryGetValue(i, out p)) { p = Fingerprint(job[i]); prints[i] = p; } return p; };
            foreach (int i in order) {
                if (Known(job[i])) continue;
                var me = get(i);
                if (me == null) continue;
                var info = new IntroInfo { dur = me.dur };
                // compare with the next episode (or the previous one for the last), then the other side if needed
                foreach (int j in new[] { i + 1, i - 1, i + 2, i - 2 }) {
                    if (j < 0 || j >= job.Count) continue;
                    var other = get(j);
                    if (other == null) continue;
                    if (info.introStart < 0) {
                        var m = Match(me.head, other.head, 15, 130);
                        if (m != null) { info.introStart = m.Item1 * Step; info.introEnd = m.Item2 * Step; }
                    }
                    if (info.creditsStart < 0 && me.tail.Length > 0) {
                        var m = Match(me.tail, other.tail, 15, 400);
                        // credits run to (near) the end: accept a match that starts in the tail and ends late
                        if (m != null && (me.tail.Length - m.Item2) * Step < 90) info.creditsStart = me.tailStart + m.Item1 * Step;
                    }
                    if (info.introStart >= 0 && info.creditsStart >= 0) break;
                }
                lock (Lock) cache[job[i]] = info;
                Save();
                var h = Updated; if (h != null) h(job[i]);
                // keep memory small: forget prints we won't need again
                foreach (var k in prints.Keys.Where(k => Math.Abs(k - i) > 2 && order.IndexOf(k) < order.IndexOf(i)).ToList()) prints.Remove(k);
            }
        }
    }

    public static string Test(string x, string y)
    {
        var sw = Stopwatch.StartNew();
        var a = Fingerprint(x); var b = Fingerprint(y);
        long t1 = sw.ElapsedMilliseconds;
        var h = Match(a.head, b.head, 1, 9999); var t = Match(a.tail, b.tail, 1, 9999);
        return string.Format("decode+fp {0} ms, match {1} ms | dur {2:0} | intro {3} | credits {4}", t1, sw.ElapsedMilliseconds - t1, a.dur,
            h == null ? "-" : (h.Item1 * Step).ToString("0.0") + "-" + (h.Item2 * Step).ToString("0.0"),
            t == null ? "-" : (a.tailStart + t.Item1 * Step).ToString("0.0") + "-" + (a.tailStart + t.Item2 * Step).ToString("0.0"));
    }

    class Prints { public uint[] head, tail; public double tailStart, dur; }

    static readonly Regex DurRx = new Regex(@"Duration: (\d+):(\d+):(\d+(\.\d+)?)");

    static Prints Fingerprint(string path)
    {
        try {
            double dur;
            var head = Decode(path, null, HeadSeconds, out dur);
            if (head == null || dur <= 0) return null;
            var p = new Prints { dur = dur, head = Bits(head) };
            double headLen = Math.Min(HeadSeconds, dur * 0.4);
            if (p.head.Length > headLen / Step) p.head = p.head.Take((int)(headLen / Step)).ToArray();
            p.tailStart = Math.Max(dur * 0.6, dur - TailSeconds);
            double d2;
            var tail = Decode(path, p.tailStart, dur - p.tailStart, out d2);
            p.tail = tail == null ? new uint[0] : Bits(tail);
            return p;
        } catch { return null; }
    }

    static short[] Decode(string path, double? start, double length, out double dur)
    {
        dur = 0;
        string ss = start.HasValue ? string.Format(CultureInfo.InvariantCulture, "-ss {0:0.###} ", start.Value) : "";
        var psi = new ProcessStartInfo(Ffmpeg, string.Format(CultureInfo.InvariantCulture,
            "-hide_banner -nostdin {0}-i \"{1}\" -t {2:0.###} -vn -sn -ac 1 -ar {3} -f s16le -", ss, path, length, Rate)) {
            UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
        using (var p = Process.Start(psi)) {
            string err = null;
            var errThread = new Thread(() => { err = p.StandardError.ReadToEnd(); }) { IsBackground = true };
            errThread.Start();
            var ms = new MemoryStream();
            p.StandardOutput.BaseStream.CopyTo(ms);
            p.WaitForExit(); errThread.Join(5000);
            var m = DurRx.Match(err ?? "");
            if (m.Success) dur = int.Parse(m.Groups[1].Value) * 3600 + int.Parse(m.Groups[2].Value) * 60 + double.Parse(m.Groups[3].Value, CultureInfo.InvariantCulture);
            var bytes = ms.ToArray();
            var s = new short[bytes.Length / 2];
            Buffer.BlockCopy(bytes, 0, s, 0, s.Length * 2);
            return s.Length > Frame ? s : null;
        }
    }

    // Haitsma–Kalker style fingerprint: 33 log-spaced bands between 300 and 2000 Hz, one 32-bit word per frame;
    // bit b = sign of the change (over time) of the energy difference between neighbouring bands.
    static uint[] Bits(short[] pcm)
    {
        int frames = (pcm.Length - Frame) / Hop;
        if (frames < 2) return new uint[0];
        var edges = new int[34];
        for (int b = 0; b <= 33; b++) edges[b] = (int)Math.Round(300 * Math.Pow(2000.0 / 300, b / 33.0) * Frame / Rate);
        var win = new double[Frame];
        for (int i = 0; i < Frame; i++) win[i] = 0.5 - 0.5 * Math.Cos(2 * Math.PI * i / (Frame - 1));
        var re = new double[Frame]; var im = new double[Frame];
        var prev = new double[33]; var cur = new double[33];
        var res = new uint[frames];
        for (int f = 0; f < frames; f++) {
            int o = f * Hop;
            for (int i = 0; i < Frame; i++) { re[i] = pcm[o + i] * win[i]; im[i] = 0; }
            Fft(re, im);
            for (int b = 0; b < 33; b++) {
                double e = 0;
                for (int k = edges[b]; k < edges[b + 1]; k++) e += re[k] * re[k] + im[k] * im[k];
                cur[b] = e;
            }
            uint word = 0;
            if (f > 0)
                for (int b = 0; b < 32; b++)
                    if ((cur[b] - cur[b + 1]) - (prev[b] - prev[b + 1]) > 0) word |= 1u << b;
            res[f] = word;
            var t = prev; prev = cur; cur = t;
        }
        return res;
    }

    static void Fft(double[] re, double[] im)
    {
        int n = re.Length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { double t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len, wr = Math.Cos(ang), wi = Math.Sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = a + len / 2;
                    double xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi; re[a] += xr; im[a] += xi;
                    double t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t;
                }
            }
        }
    }

    static int Pop(uint x) { x -= (x >> 1) & 0x55555555; x = (x & 0x33333333) + ((x >> 2) & 0x33333333); return (int)((((x + (x >> 4)) & 0x0F0F0F0F) * 0x01010101) >> 24); }

    // Longest stretch of `a` that also appears (at any offset) in `b`; returns frame range in `a`.
    public static Tuple<int, int> Match(uint[] a, uint[] b, double minSec, double maxSec)
    {
        const int MaxBits = 10, Gap = 12;   // frame matches if ≤10 of 32 bits differ; tolerate 1.5 s of noise
        int bestLen = 0, bestS = 0, bestE = 0;
        for (int d = -(b.Length - 1); d < a.Length; d++) {          // a[i] ~ b[i - d]
            int i0 = Math.Max(0, d), i1 = Math.Min(a.Length, b.Length + d);
            if (i1 - i0 < bestLen) continue;
            int runS = -1, last = -1, hits = 0;
            for (int i = i0; i < i1; i++) {
                if (a[i] != 0 && Pop(a[i] ^ b[i - d]) <= MaxBits) {
                    if (runS < 0 || i - last > Gap) { runS = i; hits = 0; }
                    last = i; hits++;
                    int len = last - runS;
                    if (len > bestLen && hits > len * 0.45) { bestLen = len; bestS = runS; bestE = last; }
                }
            }
        }
        double sec = bestLen * Step;
        if (sec < minSec || sec > maxSec) return null;
        return Tuple.Create(bestS, bestE);
    }
}
