// QwikPlayer library: finds videos in the library folders, groups episodes into series and
// seasons, keeps the watch history, and makes episode thumbnails with the bundled ffmpeg.
// Data lives in %LOCALAPPDATA%\QwikPlayer\Player (history.json, library.json, thumbs\); see ResolveDataDir.

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Web.Script.Serialization;

class LibraryItem
{
    public string path, kind, show, showId, title, key;   // kind: episode | movie | other
    public int season, episode, year;
    public long size, added;
}

class HistoryEntry
{
    public double pos, dur;
    public long updated, first;
    public bool watched;
}

static class Library
{
    // Every path below hangs off DataDir, and the Lua scripts are told the same folder (script-opts
    // qwik-data_dir, set in QwikPlayer.Start), so the app and its scripts can never disagree.
    public static readonly string DataDir = ResolveDataDir();
    public static readonly string ThumbDir = Path.Combine(DataDir, "thumbs");
    static readonly string HistoryFile = Path.Combine(DataDir, "history.json");
    static readonly string SettingsFile = Path.Combine(DataDir, "library.json");
    static readonly string MetaFile = Path.Combine(DataDir, "thumbs", "durations.json");
    static readonly HashSet<string> Exts = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
        { ".mkv", ".mp4", ".avi", ".webm", ".mov", ".m4v", ".wmv", ".ts", ".m2ts", ".flv", ".mpg", ".mpeg" };
    const long MinSize = 50L * 1024 * 1024;   // skip clips and samples

    public static List<string> Folders = new List<string>();
    public static Dictionary<string, HistoryEntry> History = new Dictionary<string, HistoryEntry>(StringComparer.OrdinalIgnoreCase);
    public static Dictionary<string, double> Durations = new Dictionary<string, double>();
    public static List<LibraryItem> Items = new List<LibraryItem>();
    public static string Ffmpeg;
    public static event Action<string> ThumbReady;

    static readonly object Lock = new object();
    static readonly JavaScriptSerializer Json = new JavaScriptSerializer { MaxJsonLength = int.MaxValue };
    static readonly object SaveLock = new object();
    static readonly Stopwatch sinceSave = Stopwatch.StartNew();   // not TickCount: that overflows after 24.9 days of uptime
    static volatile bool dirty;
    static int saving;

    // The app was called Oranix before: the first run after the rename moves %LOCALAPPDATA%\Oranix\Player
    // to %LOCALAPPDATA%\QwikPlayer\Player (a rename on the same drive, never a copy). If the move fails, for
    // example because a file is in use, the old folder is used for this run and the move is tried again
    // next time. Nothing may create the new folder before this has run.
    static string ResolveDataDir()
    {
        string local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        string now = Path.Combine(local, "QwikPlayer", "Player"), old = Path.Combine(local, "Oranix", "Player");
        if (Directory.Exists(now) || !Directory.Exists(old)) return now;
        try {
            Directory.CreateDirectory(Path.Combine(local, "QwikPlayer"));
            Directory.Move(old, now);
        } catch { return Directory.Exists(now) ? now : old; }
        try { Directory.Delete(Path.Combine(local, "Oranix")); } catch { }   // not recursive: only goes when empty
        return now;
    }

    // ---------- settings and history ----------

    public static void Load()
    {
        try { Directory.CreateDirectory(ThumbDir); } catch (Exception) { }   // unwritable data folder: later writes fail quietly
        try { var s = Json.Deserialize<Dictionary<string, object>>(File.ReadAllText(SettingsFile));
              Folders = ((System.Collections.ArrayList)s["folders"]).Cast<string>().ToList(); } catch { }
        if (Folders.Count == 0) {
            string home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
            Folders.Add(Path.Combine(home, "Downloads"));
            Folders.Add(Environment.GetFolderPath(Environment.SpecialFolder.MyVideos));
        }
        try {
            var h = Json.Deserialize<Dictionary<string, HistoryEntry>>(File.ReadAllText(HistoryFile));   // an empty or "null" file gives null
            if (h != null) {
                History = new Dictionary<string, HistoryEntry>(StringComparer.OrdinalIgnoreCase);
                foreach (var kv in h) if (kv.Value != null) History[kv.Key] = kv.Value;
            }
        } catch { }
        try { var d = Json.Deserialize<Dictionary<string, double>>(File.ReadAllText(MetaFile)); if (d != null) Durations = d; } catch { }
    }

    // Writes through a per-process temp file and swaps it in, so a crash never leaves a half-written file and
    // two players writing at once don't collide. A failed write (file locked, disk full, ...) is never fatal.
    public static bool WriteAtomic(string file, string text)
    {
        string tmp = file + "." + Process.GetCurrentProcess().Id + ".tmp";
        try {
            File.WriteAllText(tmp, text);
            if (File.Exists(file)) File.Replace(tmp, file, null); else File.Move(tmp, file);
            return true;
        } catch (Exception) {
            try { File.Delete(tmp); } catch { }
            return false;
        }
    }

    public static void SaveSettings()
    {
        string text;
        lock (Lock) text = Json.Serialize(new Dictionary<string, object> { { "folders", Folders } });
        WriteAtomic(SettingsFile, text);
    }

    // Saving is serialised (the UI thread and the thread pool both call it) and never throws.
    public static void SaveHistory()
    {
        lock (SaveLock) {
            try {
                dirty = false;
                MergeNewerFromDisk();   // another player instance may have saved a newer position for a file
                string text;
                lock (Lock) text = Json.Serialize(History);
                if (!WriteAtomic(HistoryFile, text)) dirty = true;   // try again at the next sample
            } catch (Exception) { dirty = true; }
            sinceSave.Restart();
        }
    }

    static void MergeNewerFromDisk()
    {
        Dictionary<string, HistoryEntry> disk;
        try { disk = Json.Deserialize<Dictionary<string, HistoryEntry>>(File.ReadAllText(HistoryFile)); } catch { return; }
        if (disk == null) return;
        lock (Lock) {
            foreach (var kv in disk) {
                HistoryEntry mine;
                if (kv.Value != null && History.TryGetValue(kv.Key, out mine) && kv.Value.updated > mine.updated) History[kv.Key] = kv.Value;
            }
        }
    }

    static long Now() { return (long)(DateTime.UtcNow - new DateTime(1970, 1, 1)).TotalMilliseconds; }

    // Called every couple of seconds while something plays.
    public static void Record(string path, double pos, double dur)
    {
        if (string.IsNullOrEmpty(path) || dur <= 0 || pos < 0) return;
        double credits = Intros.CreditsStart(path);   // looked up before taking Lock (Intros has its own lock)
        lock (Lock) {
            HistoryEntry h;
            if (!History.TryGetValue(path, out h)) {
                if (pos < 5) return;   // opening a file isn't watching it
                h = new HistoryEntry { first = Now() };
                History[path] = h;
            }
            if (Math.Abs(pos - h.pos) < 0.01 && h.dur == dur) return;   // paused or stalled: nothing changed, nothing to write
            h.pos = pos; h.dur = dur; h.updated = Now();
            if (pos >= dur * 0.93 || dur - pos < 60 || (credits > 0 && pos >= credits)) h.watched = true;
            else if (h.watched && pos > 30 && pos < dur * 0.9 && dur - pos > 120) h.watched = false;   // watching it again
            dirty = true;
        }
        // Periodic save: off the UI thread, and only when something changed.
        if (dirty && sinceSave.ElapsedMilliseconds > 10000 && Interlocked.CompareExchange(ref saving, 1, 0) == 0) {
            sinceSave.Restart();
            ThreadPool.QueueUserWorkItem(_ => { try { SaveHistory(); } finally { Interlocked.Exchange(ref saving, 0); } });
        }
    }

    public static void SetWatched(string path, bool watched)
    {
        lock (Lock) {
            HistoryEntry h;
            if (!History.TryGetValue(path, out h)) { h = new HistoryEntry { first = Now() }; History[path] = h; }
            h.watched = watched; h.updated = Now();
            if (!watched) h.pos = 0;
            else if (h.dur > 0) h.pos = h.dur;
        }
        SaveHistory();
    }

    public static void Forget(string path) { lock (Lock) History.Remove(path); SaveHistory(); }
    public static void ClearHistory() { lock (Lock) History.Clear(); SaveHistory(); }

    // Where to start a file: resume part-way through, otherwise from the beginning.
    public static double ResumeAt(string path)
    {
        lock (Lock) {
            HistoryEntry h;
            if (History.TryGetValue(path, out h) && !h.watched && h.pos > 30) return Math.Max(0, h.pos - 3);
        }
        return 0;
    }

    // ---------- scanning ----------

    static readonly Regex SeriesRx = new Regex(@"^(?<t>.*?)[\s._\-\[\(]*\b[Ss](?<s>\d{1,2})[\s._-]?[Ee](?<e>\d{1,3})(?<rest>.*)$");
    static readonly Regex AltSeriesRx = new Regex(@"^(?<t>.*?)[\s._-]+(?<s>\d{1,2})x(?<e>\d{2})\b(?<rest>.*)$");
    static readonly Regex MovieRx = new Regex(@"^(?<t>.+?)[\s(\[]+(?<y>(19|20)\d{2})(?![0-9p])");
    static readonly Regex Junk = new Regex(@"(?i)(\b(19|20)\d{2}\b.*|\b\d{3,4}p\b.*|\b(web|webrip|web-dl|hdtv|bluray|brrip|dvdrip|x26[45]|h 26[45]|h26[45]|hevc|amzn|dsnp|nf|hmax|atvp|hindi|dual|10bit|ddp?\d?|aac|ac3|proper|repack|complete|season)\b.*|[\[\(].*)$");

    static string Spaced(string s) { return Regex.Replace(s.Replace('.', ' ').Replace('_', ' '), @"\s+", " ").Trim(); }

    static string Clean(string s)
    {
        s = Junk.Replace(Spaced(s), "");
        return Regex.Replace(s, @"\s+", " ").Trim(' ', '-', '.', ',', ':');
    }

    static string Pretty(string s)
    {
        if (s == s.ToLowerInvariant()) s = CultureInfo.InvariantCulture.TextInfo.ToTitleCase(s);
        return s;
    }

    static string KeyOf(string path, long size)
    {
        using (var sha = SHA1.Create()) {
            byte[] h = sha.ComputeHash(Encoding.UTF8.GetBytes(path.ToLowerInvariant() + "|" + size));
            return BitConverter.ToString(h, 0, 8).Replace("-", "").ToLowerInvariant();
        }
    }

    static LibraryItem Classify(FileInfo f)
    {
        var it = new LibraryItem { path = f.FullName, size = f.Length, key = KeyOf(f.FullName, f.Length),
            added = (long)(f.LastWriteTimeUtc - new DateTime(1970, 1, 1)).TotalMilliseconds };
        string name = Path.GetFileNameWithoutExtension(f.Name);
        string sp = Spaced(name);   // "Show_S01E02" has no word boundary before the S until the underscores are spaces
        Match m = SeriesRx.Match(sp);
        if (!m.Success) m = AltSeriesRx.Match(sp);
        if (m.Success) {
            string show = Clean(m.Groups["t"].Value);
            if (show.Length == 0) show = Clean(f.Directory.Name);   // "S01E05.mkv" inside "The Americans Season 1"
            if (show.Length == 0) show = "Unknown series";
            it.kind = "episode";
            it.show = Pretty(show);
            it.showId = Regex.Replace(show.ToLowerInvariant(), @"[^\p{L}\p{M}\p{Nd}]", "");   // letters of any script
            if (it.showId.Length == 0) it.showId = show.ToLowerInvariant();
            it.season = int.Parse(m.Groups["s"].Value);
            it.episode = int.Parse(m.Groups["e"].Value);
            it.title = Clean(Regex.Replace(m.Groups["rest"].Value, @"^(?:[\s\-]*[Ee]\d{1,3}\b)+", ""));   // S01E01E02 / S01E01-E02
            return it;
        }
        string spaced = sp;
        m = MovieRx.Match(spaced);
        if (m.Success && f.Length > 300L * 1024 * 1024) {
            it.kind = "movie";
            it.title = Pretty(Regex.Replace(m.Groups["t"].Value, @"\s+", " ").Trim(' ', '-', '.'));
            it.year = int.Parse(m.Groups["y"].Value);
            return it;
        }
        it.kind = "other";
        it.title = spaced;
        return it;
    }

    public static void Scan()
    {
        var found = new List<LibraryItem>();
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (string root in Folders.ToList()) Walk(root, 0, found, seen);
        found = found
            .OrderBy(i => i.kind == "episode" ? 0 : i.kind == "movie" ? 1 : 2)
            .ThenBy(i => i.show ?? i.title, StringComparer.OrdinalIgnoreCase)
            .ThenBy(i => i.season).ThenBy(i => i.episode).ToList();
        lock (Lock) Items = found;
    }

    static void Walk(string dir, int depth, List<LibraryItem> found, HashSet<string> seen)
    {
        if (depth > 5 || !Directory.Exists(dir)) return;
        try {
            foreach (string file in Directory.EnumerateFiles(dir)) {
                if (!Exts.Contains(Path.GetExtension(file)) || !seen.Add(file)) continue;
                var f = new FileInfo(file);
                if (f.Length < MinSize || f.Name.ToLowerInvariant().Contains("sample")) continue;
                found.Add(Classify(f));
            }
            foreach (string sub in Directory.EnumerateDirectories(dir)) {
                var attr = File.GetAttributes(sub);
                string n = Path.GetFileName(sub);
                if ((attr & (FileAttributes.Hidden | FileAttributes.System)) != 0 || n.StartsWith(".") ||
                    n.Equals("node_modules", StringComparison.OrdinalIgnoreCase)) continue;
                Walk(sub, depth + 1, found, seen);
            }
        } catch (UnauthorizedAccessException) { } catch (IOException) { }
    }

    public static List<LibraryItem> Snapshot() { lock (Lock) return Items.ToList(); }

    public static LibraryItem Find(string path)
    {
        lock (Lock) return Items.FirstOrDefault(i => string.Equals(i.path, path, StringComparison.OrdinalIgnoreCase));
    }

    // Every episode of the same series, in order.
    // The same season and episode in two files (a sample, two releases) counts once: `keepPath` if it is one of them, else the largest.
    public static List<LibraryItem> ShowEpisodes(string showId, string keepPath = null)
    {
        lock (Lock)
            return Items.Where(i => i.kind == "episode" && i.showId == showId)
                .GroupBy(i => i.season * 10000 + i.episode)
                .Select(g => g.FirstOrDefault(i => keepPath != null && string.Equals(i.path, keepPath, StringComparison.OrdinalIgnoreCase)) ?? g.OrderByDescending(i => i.size).First())
                .OrderBy(i => i.season).ThenBy(i => i.episode).ToList();
    }

    public static bool TryGetDuration(string key, out double dur)
    {
        lock (Lock) return Durations.TryGetValue(key, out dur);
    }

    // ---------- state for the page ----------

    public static string StateJson()
    {
        var items = Snapshot();
        var series = items.Where(i => i.kind == "episode").GroupBy(i => i.showId).Select(g => {
            var eps = g.OrderBy(i => i.season).ThenBy(i => i.episode).ToList();
            var mid = eps[eps.Count / 2];
            return new Dictionary<string, object> {
                { "id", g.Key }, { "name", eps[0].show }, { "backdrop", "bd-" + mid.key },
                { "seasons", eps.GroupBy(e => e.season).Select(sg => new Dictionary<string, object> {
                    { "n", sg.Key },
                    { "eps", sg.Select(e => new Dictionary<string, object> {
                        { "path", e.path }, { "key", e.key }, { "s", e.season }, { "e", e.episode },
                        { "title", e.title }, { "size", e.size }, { "added", e.added } }).ToList() } }).ToList() } };
        }).ToList();
        Func<LibraryItem, Dictionary<string, object>> flat = i => new Dictionary<string, object> {
            { "path", i.path }, { "key", i.key }, { "title", i.title }, { "year", i.year }, { "size", i.size }, { "added", i.added } };
        var thumbs = Directory.Exists(ThumbDir)
            ? Directory.EnumerateFiles(ThumbDir, "*.jpg").Select(Path.GetFileNameWithoutExtension).ToList() : new List<string>();
        Dictionary<string, HistoryEntry> hist;
        Dictionary<string, double> durs;
        lock (Lock) { hist = new Dictionary<string, HistoryEntry>(History); durs = new Dictionary<string, double>(Durations); }
        return Json.Serialize(new Dictionary<string, object> {
            { "type", "state" }, { "series", series },
            { "movies", items.Where(i => i.kind == "movie").Select(flat).ToList() },
            { "other", items.Where(i => i.kind == "other").Select(flat).ToList() },
            { "history", hist }, { "durations", durs }, { "thumbs", thumbs }, { "folders", Folders } });
    }

    // ---------- thumbnails ----------

    static Thread worker;
    static volatile bool rerun;
    // Thumbnails are only made while the library is on screen: set true while a video plays.
    public static volatile bool Paused = true;
    // One ffmpeg at a time (thumbnails and intro analysis share this), at idle priority, and every child is
    // killed when the player exits so a hung or long ffmpeg never outlives it.
    public static readonly object FfmpegGate = new object();
    static readonly List<Process> children = new List<Process>();
    static bool exiting;

    public static Process StartFfmpeg(ProcessStartInfo psi)
    {
        lock (children) {
            if (exiting) return null;
            var p = Process.Start(psi);
            children.Add(p);
            try { p.PriorityClass = ProcessPriorityClass.Idle; } catch { }
            return p;
        }
    }

    public static void Release(Process p) { lock (children) children.Remove(p); }

    public static void KillChildren()
    {
        lock (children) {
            exiting = true;
            foreach (var p in children) { try { p.Kill(); } catch { } }
            children.Clear();
        }
    }

    public static void StartThumbnails()
    {
        Paused = false;
        if (Ffmpeg == null || !File.Exists(Ffmpeg)) return;
        lock (Lock) {
            if (worker != null && worker.IsAlive) { rerun = true; return; }
            worker = new Thread(ThumbLoop) { IsBackground = true, Priority = ThreadPriority.BelowNormal, Name = "thumbnails" };
            worker.Start();
        }
    }

    static void ThumbLoop()
    {
        try { ThumbLoopCore(); } catch (Exception) { }   // a worker exception must never take the player down
    }

    static void ThumbLoopCore()
    {
        do {
            rerun = false;
            var items = Snapshot();
            // Series backdrops first (big, from the middle of the show), then every episode/movie card.
            var jobs = new List<Tuple<string, string, double, int>>();
            foreach (var g in items.Where(i => i.kind == "episode").GroupBy(i => i.showId)) {
                var eps = g.OrderBy(i => i.season).ThenBy(i => i.episode).ToList();
                var mid = eps[eps.Count / 2];
                jobs.Add(Tuple.Create("bd-" + mid.key, mid.path, 900.0, 1280));
            }
            foreach (var i in items) jobs.Add(Tuple.Create(i.key, i.path, i.kind == "episode" ? 420.0 : i.kind == "movie" ? 900.0 : 15.0, 640));
            bool changed = false;
            foreach (var j in jobs) {
                if (Paused) break;
                string jpg = Path.Combine(ThumbDir, j.Item1 + ".jpg");
                if (File.Exists(jpg)) continue;
                double dur = Grab(j.Item2, j.Item3, j.Item4, jpg);
                if (!File.Exists(jpg) && dur > 0) Grab(j.Item2, dur * 0.2, j.Item4, jpg);   // shorter than the seek point
                if (dur > 0 && !j.Item1.StartsWith("bd-")) { lock (Lock) Durations[j.Item1] = dur; changed = true; }
                if (File.Exists(jpg)) { var ready = ThumbReady; if (ready != null) ready(j.Item1); }
            }
            if (changed) { string t; lock (Lock) t = Json.Serialize(Durations); WriteAtomic(MetaFile, t); }
        } while (rerun && !Paused);
    }

    static readonly Regex DurRx = new Regex(@"Duration: (\d+):(\d+):(\d+(\.\d+)?)");

    // Saves one frame; returns the file's duration (seconds) from ffmpeg's output, or 0.
    static double Grab(string video, double at, int width, string jpg)
    {
        try {
            var psi = new ProcessStartInfo(Ffmpeg, string.Format(CultureInfo.InvariantCulture,
                "-hide_banner -ss {0:0.###} -i \"{1}\" -frames:v 1 -vf scale={2}:-2 -q:v 3 -y \"{3}\"", at, video, width, jpg)) {
                UseShellExecute = false, CreateNoWindow = true, RedirectStandardError = true };
            lock (FfmpegGate) {
                var p = StartFfmpeg(psi);
                if (p == null) return 0;
                try {
                    // Read stderr asynchronously: a blocking ReadToEnd would hang with the process and the timeout would never fire.
                    var err = new StringBuilder();
                    p.ErrorDataReceived += (o, e) => { if (e.Data != null) lock (err) err.AppendLine(e.Data); };
                    p.BeginErrorReadLine();
                    if (!p.WaitForExit(30000)) { try { p.Kill(); } catch { } return 0; }
                    p.WaitForExit();   // lets the last stderr lines arrive
                    string text; lock (err) text = err.ToString();
                    Match m = DurRx.Match(text);
                    if (!m.Success) return 0;
                    return int.Parse(m.Groups[1].Value) * 3600 + int.Parse(m.Groups[2].Value) * 60 + double.Parse(m.Groups[3].Value, CultureInfo.InvariantCulture);
                } finally { Release(p); p.Dispose(); }
            }
        } catch { return 0; }
    }
}
