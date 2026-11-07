// Oranix Player: a Windows video player built on libmpv (bundled, x64 + ARM64).
// The app owns the window (native dark title bar, icon, fullscreen, maximize, drag & drop) and embeds
// mpv's renderer in it; the controls are uosc plus Oranix's own Lua scripts from <app>\config.
// Live editing: when anything in config\ changes, the player reloads at the same second.

using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Windows.Forms;
using System.Globalization;
using System.Web.Script.Serialization;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

static class Mpv
{
    const string Dll = "libmpv-2.dll";
    [DllImport(Dll)] public static extern IntPtr mpv_create();
    [DllImport(Dll)] public static extern int mpv_initialize(IntPtr ctx);
    [DllImport(Dll)] public static extern void mpv_terminate_destroy(IntPtr ctx);
    [DllImport(Dll)] public static extern int mpv_set_option_string(IntPtr ctx, byte[] name, byte[] value);
    [DllImport(Dll)] public static extern int mpv_command(IntPtr ctx, IntPtr[] args);
    [DllImport(Dll)] public static extern IntPtr mpv_get_property_string(IntPtr ctx, byte[] name);
    [DllImport(Dll)] public static extern void mpv_free(IntPtr data);
    [DllImport(Dll)] public static extern int mpv_observe_property(IntPtr ctx, ulong id, byte[] name, int format);
    [DllImport(Dll)] public static extern IntPtr mpv_wait_event(IntPtr ctx, double timeout);

    public const int FORMAT_STRING = 1, FORMAT_FLAG = 3;
    public const int EVENT_SHUTDOWN = 1, EVENT_PROPERTY_CHANGE = 22;

    [StructLayout(LayoutKind.Sequential)] public struct Event { public int id; public int error; public ulong userdata; public IntPtr data; }
    [StructLayout(LayoutKind.Sequential)] public struct Property { public IntPtr name; public int format; public IntPtr data; }

    public static byte[] U8(string s) { return Encoding.UTF8.GetBytes(s + "\0"); }

    public static string FromU8(IntPtr p)
    {
        if (p == IntPtr.Zero) return null;
        int n = 0;
        while (Marshal.ReadByte(p, n) != 0) n++;
        var b = new byte[n];
        Marshal.Copy(p, b, 0, n);
        return Encoding.UTF8.GetString(b);
    }

    public static void Command(IntPtr ctx, params string[] args)
    {
        var ptrs = new IntPtr[args.Length + 1];
        try {
            for (int i = 0; i < args.Length; i++) {
                byte[] b = U8(args[i]);
                ptrs[i] = Marshal.AllocHGlobal(b.Length);
                Marshal.Copy(b, 0, ptrs[i], b.Length);
            }
            mpv_command(ctx, ptrs);
        } finally {
            foreach (var p in ptrs) if (p != IntPtr.Zero) Marshal.FreeHGlobal(p);
        }
    }

    public static string Get(IntPtr ctx, string name)
    {
        IntPtr p = mpv_get_property_string(ctx, U8(name));
        string s = FromU8(p);
        if (p != IntPtr.Zero) mpv_free(p);
        return s;
    }
}

class OranixPlayer : Form
{
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] static extern bool SetDllDirectory(string path);
    [DllImport("user32.dll")] static extern bool SetProcessDPIAware();
    [DllImport("dwmapi.dll")] static extern int DwmSetWindowAttribute(IntPtr h, int attr, ref int value, int size);

    static readonly string AppDir = AppDomain.CurrentDomain.BaseDirectory;
    static readonly string ConfigDir = Path.Combine(AppDir, "config");

    readonly Panel screen = new Panel { Dock = DockStyle.Fill, BackColor = Color.Black };
    IntPtr mpv;
    bool restarting, closing;
    string resumeFile, resumeAt;
    bool resumePaused;
    FormWindowState beforeFullscreen;
    readonly System.Windows.Forms.Timer liveDebounce = new System.Windows.Forms.Timer { Interval = 700 };

    // Library home screen (library\index.html in a WebView2 over the video), shown when nothing plays.
    readonly WebView2 lib = new WebView2 { Dock = DockStyle.Fill, Visible = false, Enabled = false, DefaultBackgroundColor = Color.FromArgb(20, 20, 20) };
    readonly System.Windows.Forms.Timer historyTimer = new System.Windows.Forms.Timer { Interval = 2000 };
    readonly JavaScriptSerializer json = new JavaScriptSerializer { MaxJsonLength = int.MaxValue };
    bool libReady, scanning;
    System.Threading.Timer closeWatchdog;
    // Hide the pointer over the video after 2 s without movement (mpv's own autohide can't see our forwarded mouse).
    readonly System.Windows.Forms.Timer cursorTimer = new System.Windows.Forms.Timer { Interval = 2000 };
    bool cursorHidden; Point lastMouse;
    void ShowCursor() { cursorTimer.Stop(); cursorTimer.Start(); if (cursorHidden) { cursorHidden = false; Cursor.Show(); } }

    [STAThread]
    static void Main(string[] args)
    {
        SetProcessDPIAware();
        // One build for x64 and Windows on ARM: load the libmpv that matches this process.
        SetDllDirectory(Path.Combine(AppDir, "libmpv", RuntimeInformation.ProcessArchitecture == Architecture.Arm64 ? "arm64" : "x64"));
        Application.EnableVisualStyles();
        Application.Run(new OranixPlayer(args));
    }

    OranixPlayer(string[] files)
    {
        Text = "Oranix Player";
        string ico = Path.Combine(AppDir, "oranix-player.ico");
        if (File.Exists(ico)) Icon = new Icon(ico);
        BackColor = Color.Black;
        StartPosition = FormStartPosition.CenterScreen;
        var area = Screen.PrimaryScreen.WorkingArea;
        Size = new Size(area.Width * 8 / 10, area.Height * 8 / 10);
        AllowDrop = true;
        Controls.Add(screen);

        DragEnter += (o, e) => e.Effect = e.Data.GetDataPresent(DataFormats.FileDrop) ? DragDropEffects.Copy : DragDropEffects.None;
        DragDrop += (o, e) => {
            var dropped = (string[])e.Data.GetData(DataFormats.FileDrop);
            for (int i = 0; i < dropped.Length; i++) Mpv.Command(mpv, "loadfile", dropped[i], i == 0 ? "replace" : "append");
        };
        Controls.Add(lib);
        lib.BringToFront();
        // mpv draws into `screen` but doesn't receive its mouse input, so pass movement and clicks on to it
        // (the control bar, seek bar, popups and double-click all depend on this).
        screen.MouseMove += (o, e) => {
            if (e.Location == lastMouse) return;   // Windows repeats WM_MOUSEMOVE without movement
            lastMouse = e.Location; ShowCursor();
            if (mpv != IntPtr.Zero) Mpv.Command(mpv, "mouse", e.X.ToString(), e.Y.ToString());
        };
        cursorTimer.Tick += (o, e) => {
            cursorTimer.Stop();
            if (!cursorHidden && !lib.Visible && ContainsFocus && screen.ClientRectangle.Contains(screen.PointToClient(Control.MousePosition))) { cursorHidden = true; Cursor.Hide(); }
        };
        screen.MouseDown += (o, e) => { ShowCursor(); string b = MouseName(e.Button); if (b != null && mpv != IntPtr.Zero) { Mpv.Command(mpv, "mouse", e.X.ToString(), e.Y.ToString()); Mpv.Command(mpv, "keydown", b); } };
        screen.MouseUp += (o, e) => { string b = MouseName(e.Button); if (b != null && mpv != IntPtr.Zero) Mpv.Command(mpv, "keyup", b); };
        screen.MouseLeave += (o, e) => { ShowCursor(); cursorTimer.Stop(); if (mpv != IntPtr.Zero) Mpv.Command(mpv, "mouse", "-1", "-1"); };
        Library.Load();
        Library.Ffmpeg = Path.Combine(AppDir, "alass", "bin", "ffmpeg.exe");
        Intros.Ffmpeg = Library.Ffmpeg;
        // Skip Intro data arrives in the background; tell skip-intro.lua to re-read it.
        Intros.Updated += changed => { if (IsHandleCreated && !closing) BeginInvoke((Action)(() => { if (mpv != IntPtr.Zero) Mpv.Command(mpv, "script-message", "intros-updated"); })); };
        Library.ThumbReady += key => { if (IsHandleCreated) BeginInvoke((Action)(() => SendThumb(key))); };
        historyTimer.Tick += (o, e) => RecordProgress();
        Activated += (o, e) => { if (!lib.Visible) FocusVideo(); };   // coming back with Alt+Tab
        // WebView2 takes keyboard focus when its page loads, even while hidden behind a playing video.
        lib.GotFocus += (o, e) => { if (!lib.Visible) BeginInvoke((Action)FocusVideo); };
        historyTimer.Start();
        Shown += (o, e) => { InitLibrary(); Start(files.ToList(), null, false); };
        FormClosing += (o, e) => {
            try { RecordProgress(); Library.SaveHistory(); } catch { }
            if (mpv == IntPtr.Zero || closing) return;
            e.Cancel = true;   // let mpv save the position and shut down first; the event loop closes us
            closing = true;
            historyTimer.Stop();
            Hide();            // feels instant; the rest happens out of sight
            Mpv.Command(mpv, "quit");
            // Safety net: never leave a frozen window or a stuck process behind.
            closeWatchdog = new System.Threading.Timer(_ => Environment.Exit(0), null, 4000, Timeout.Infinite);
        };
        WatchConfig();
    }

    // Dark title bar in Oranix charcoal (Windows 11).
    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        int dark = 1, caption = 0x001F1F1F, text = 0x00FFFFFF;
        DwmSetWindowAttribute(Handle, 20, ref dark, 4);
        DwmSetWindowAttribute(Handle, 35, ref caption, 4);
        DwmSetWindowAttribute(Handle, 36, ref text, 4);
    }

    void Start(List<string> files, string startAt, bool paused)
    {
        mpv = Mpv.mpv_create();
        Action<string, string> opt = (k, v) => Mpv.mpv_set_option_string(mpv, Mpv.U8(k), Mpv.U8(v));
        opt("config-dir", ConfigDir);
        opt("config", "yes");
        opt("wid", screen.Handle.ToInt64().ToString());
        opt("input-default-bindings", "yes");
        opt("input-vo-keyboard", "yes");
        opt("force-window", "yes");
        opt("idle", "yes");              // stay open with the Oranix start screen when nothing plays
        opt("border", "yes");            // the app's own title bar replaces uosc's window buttons
        opt("terminal", "no");
        if (startAt != null) opt("start", startAt);
        if (paused) opt("pause", "yes");
        Mpv.mpv_initialize(mpv);
        Mpv.mpv_observe_property(mpv, 1, Mpv.U8("media-title"), Mpv.FORMAT_STRING);
        Mpv.mpv_observe_property(mpv, 2, Mpv.U8("fullscreen"), Mpv.FORMAT_FLAG);
        Mpv.mpv_observe_property(mpv, 3, Mpv.U8("window-maximized"), Mpv.FORMAT_FLAG);
        Mpv.mpv_observe_property(mpv, 4, Mpv.U8("window-minimized"), Mpv.FORMAT_FLAG);
        Mpv.mpv_observe_property(mpv, 5, Mpv.U8("idle-active"), Mpv.FORMAT_FLAG);
        for (int i = 0; i < files.Count; i++) Mpv.Command(mpv, "loadfile", files[i], i == 0 ? "replace" : "append");
        if (startAt != null) ThreadPool.QueueUserWorkItem(_ => { Thread.Sleep(1200); var h = mpv; if (h != IntPtr.Zero) Mpv.Command(h, "show-text", "✓ Oranix Player updated", "2000"); });

        IntPtr ctx = mpv;
        new Thread(() => EventLoop(ctx)) { IsBackground = true, Name = "mpv events" }.Start();
        FocusVideo();
    }

    void EventLoop(IntPtr ctx)
    {
        while (true) {
            var ev = (Mpv.Event)Marshal.PtrToStructure(Mpv.mpv_wait_event(ctx, -1), typeof(Mpv.Event));
            if (ev.id == Mpv.EVENT_SHUTDOWN) {
                // Stop every other caller (timers, key and mouse forwarding) before the handle is destroyed.
                Invoke((Action)(() => { if (mpv == ctx) mpv = IntPtr.Zero; }));
                Mpv.mpv_terminate_destroy(ctx);
                BeginInvoke((Action)(() => {
                    if (restarting) { restarting = false; Start(resumeFile == null ? new List<string>() : new List<string> { resumeFile }, resumeAt, resumePaused); }
                    else { closing = true; Close(); }
                }));
                return;
            }
            if (ev.id != Mpv.EVENT_PROPERTY_CHANGE || ev.data == IntPtr.Zero) continue;
            var prop = (Mpv.Property)Marshal.PtrToStructure(ev.data, typeof(Mpv.Property));
            if (prop.data == IntPtr.Zero) continue;
            string name = Mpv.FromU8(prop.name);
            string str = prop.format == Mpv.FORMAT_STRING ? Mpv.FromU8(Marshal.ReadIntPtr(prop.data)) : null;
            bool flag = prop.format == Mpv.FORMAT_FLAG && Marshal.ReadInt32(prop.data) != 0;
            BeginInvoke((Action)(() => OnProperty(name, str, flag)));
        }
    }

    // uosc's buttons and keys change mpv properties; the app turns them into real window actions.
    void OnProperty(string name, string str, bool flag)
    {
        switch (name) {
            case "media-title":
                Text = string.IsNullOrEmpty(str) ? "Oranix Player" : str + " - Oranix Player";
                if (!string.IsNullOrEmpty(str)) EnsureSeriesPlaylist();
                break;
            case "idle-active": ShowLibrary(flag); break;
            case "window-minimized": if (flag) WindowState = FormWindowState.Minimized; break;
            case "window-maximized":
                if (FormBorderStyle != FormBorderStyle.None) WindowState = flag ? FormWindowState.Maximized : FormWindowState.Normal;
                break;
            case "fullscreen":
                if (flag && FormBorderStyle != FormBorderStyle.None) {
                    beforeFullscreen = WindowState;
                    FormBorderStyle = FormBorderStyle.None;
                    WindowState = FormWindowState.Normal;
                    WindowState = FormWindowState.Maximized;
                } else if (!flag && FormBorderStyle == FormBorderStyle.None) {
                    FormBorderStyle = FormBorderStyle.Sizable;
                    WindowState = beforeFullscreen;
                }
                break;
        }
    }

    // ---------- library ----------

    async void InitLibrary()
    {
        string page = Path.Combine(AppDir, "library");
        if (!Directory.Exists(page)) return;
        try {
            string arch = RuntimeInformation.ProcessArchitecture == Architecture.Arm64 ? "win-arm64" : "win-x64";
            string loader = Path.Combine(AppDir, "runtimes", arch, "native");
            if (File.Exists(Path.Combine(loader, "WebView2Loader.dll"))) CoreWebView2Environment.SetLoaderDllFolderPath(loader);
            var env = await CoreWebView2Environment.CreateAsync(null, Path.Combine(Library.DataDir, "webview"));
            await lib.EnsureCoreWebView2Async(env);
        } catch (Exception) { return; }   // no WebView2 runtime: the player still works, just without the library
        var cw = lib.CoreWebView2;
        cw.Settings.AreDefaultContextMenusEnabled = false;
        cw.Settings.AreDevToolsEnabled = false;
        cw.Settings.IsZoomControlEnabled = false;
        cw.Settings.IsStatusBarEnabled = false;
        cw.SetVirtualHostNameToFolderMapping("oranix.library", page, CoreWebView2HostResourceAccessKind.Allow);
        cw.SetVirtualHostNameToFolderMapping("oranix.thumbs", Library.ThumbDir, CoreWebView2HostResourceAccessKind.Allow);
        cw.SetVirtualHostNameToFolderMapping("oranix.fonts", Path.Combine(ConfigDir, "fonts"), CoreWebView2HostResourceAccessKind.Allow);
        cw.WebMessageReceived += OnLibraryMessage;
        cw.Navigate("https://oranix.library/index.html");
        // Live editing of the page: reload when library\ changes.
        var w = new FileSystemWatcher(page) { EnableRaisingEvents = true, SynchronizingObject = this, NotifyFilter = NotifyFilters.LastWrite | NotifyFilters.FileName };
        w.Changed += (o, e) => cw.Reload();
        Rescan();
    }

    void ShowLibrary(bool show)
    {
        if (show) { Text = "Oranix Player"; ShowCursor(); cursorTimer.Stop(); }
        if (!libReady && show) { lib.Visible = lib.Enabled = lib.CoreWebView2 != null; return; }
        lib.Visible = show;
        lib.Enabled = show;   // a disabled window can't hold keyboard focus, so keys reach the video
        if (show) { lib.BringToFront(); SendState(); lib.Focus(); Rescan(); }
        else FocusVideo();
    }

    [DllImport("user32.dll")] static extern IntPtr SetFocus(IntPtr h);
    [DllImport("user32.dll")] static extern int ToUnicode(uint vk, uint scan, byte[] state, StringBuilder buf, int size, uint flags);

    // While a video plays the window itself holds keyboard focus and passes every key to mpv
    // (mpv's embedded video window can't take focus, and the hidden library must not keep it).
    void FocusVideo()
    {
        ActiveControl = null;
        SetFocus(Handle);
    }

    static readonly Dictionary<Keys, string> SpecialKeys = new Dictionary<Keys, string> {
        { Keys.Space, "SPACE" }, { Keys.Return, "ENTER" }, { Keys.Escape, "ESC" }, { Keys.Left, "LEFT" }, { Keys.Right, "RIGHT" },
        { Keys.Up, "UP" }, { Keys.Down, "DOWN" }, { Keys.Back, "BS" }, { Keys.Tab, "TAB" }, { Keys.Delete, "DEL" }, { Keys.Insert, "INS" },
        { Keys.Home, "HOME" }, { Keys.End, "END" }, { Keys.PageUp, "PGUP" }, { Keys.PageDown, "PGDWN" },
        { Keys.MediaPlayPause, "PLAYPAUSE" }, { Keys.MediaNextTrack, "NEXT" }, { Keys.MediaPreviousTrack, "PREV" }, { Keys.MediaStop, "STOP" },
        { Keys.Add, "KP_ADD" }, { Keys.Subtract, "KP_SUBTRACT" }, { Keys.Multiply, "KP_MULTIPLY" }, { Keys.Divide, "KP_DIVIDE" }, { Keys.Decimal, "KP_DEC" },
    };

    static string MpvKeyName(Keys key, bool shift, bool ctrl, bool alt)
    {
        string name;
        bool isChar = false;
        if (SpecialKeys.TryGetValue(key, out name)) { }
        else if (key >= Keys.F1 && key <= Keys.F24) name = "F" + (key - Keys.F1 + 1);
        else if (key >= Keys.NumPad0 && key <= Keys.NumPad9) name = "KP" + (key - Keys.NumPad0);
        else if (key == Keys.ShiftKey || key == Keys.ControlKey || key == Keys.Menu || key == Keys.LWin || key == Keys.RWin) return null;
        else {
            // The character this key types with Shift only (with Ctrl or Alt it would be a control code).
            var state = new byte[256];
            if (shift) state[0x10] = 0x80;
            var sb = new StringBuilder(8);
            if (ToUnicode((uint)key, 0, state, sb, sb.Capacity, 0) != 1) return null;
            name = sb.ToString();
            if (name == "#") name = "SHARP";
            isChar = true;
        }
        return (ctrl ? "Ctrl+" : "") + (alt ? "Alt+" : "") + (shift && !isChar ? "Shift+" : "") + name;
    }

    static string MouseName(MouseButtons b)
    {
        switch (b) {
            case MouseButtons.Left: return "MBTN_LEFT";
            case MouseButtons.Right: return "MBTN_RIGHT";
            case MouseButtons.Middle: return "MBTN_MID";
            case MouseButtons.XButton1: return "MBTN_BACK";
            case MouseButtons.XButton2: return "MBTN_FORWARD";
        }
        return null;
    }

    readonly Dictionary<Keys, string> keysDown = new Dictionary<Keys, string>();

    protected override void WndProc(ref Message m)
    {
        const int WM_KEYDOWN = 0x100, WM_KEYUP = 0x101, WM_SYSKEYDOWN = 0x104, WM_SYSKEYUP = 0x105;
        const int WM_MOUSEWHEEL = 0x20A, WM_MOUSEHWHEEL = 0x20E;
        // The wheel goes to the focused window (this one), not mpv's video window under the cursor.
        if ((m.Msg == WM_MOUSEWHEEL || m.Msg == WM_MOUSEHWHEEL) && mpv != IntPtr.Zero && !lib.Visible) {
            int delta = (short)(((long)m.WParam >> 16) & 0xFFFF);
            string wheel = m.Msg == WM_MOUSEWHEEL ? (delta > 0 ? "WHEEL_UP" : "WHEEL_DOWN") : (delta > 0 ? "WHEEL_RIGHT" : "WHEEL_LEFT");
            string mods = ((ModifierKeys & Keys.Control) != 0 ? "Ctrl+" : "") + ((ModifierKeys & Keys.Shift) != 0 ? "Shift+" : "");
            Mpv.Command(mpv, "keypress", mods + wheel);
            m.Result = IntPtr.Zero;
            return;
        }
        bool isKey = m.Msg == WM_KEYDOWN || m.Msg == WM_KEYUP || m.Msg == WM_SYSKEYDOWN || m.Msg == WM_SYSKEYUP;
        if (isKey && mpv != IntPtr.Zero && !lib.Visible) {
            Keys key = (Keys)(int)m.WParam & Keys.KeyCode;
            if (m.Msg == WM_KEYDOWN || m.Msg == WM_SYSKEYDOWN) {
                if (key == Keys.F4 && (ModifierKeys & Keys.Alt) != 0) { base.WndProc(ref m); return; }   // Alt+F4 still closes
                if (((long)m.LParam & 0x40000000) != 0 && keysDown.ContainsKey(key)) return;   // auto-repeat (bit 30 = already down): mpv repeats held keys itself
                string name = MpvKeyName(key, (ModifierKeys & Keys.Shift) != 0, (ModifierKeys & Keys.Control) != 0, (ModifierKeys & Keys.Alt) != 0);
                if (name == null) { base.WndProc(ref m); return; }
                keysDown[key] = name;
                Mpv.Command(mpv, "keydown", name);
            } else {
                string name;
                if (keysDown.TryGetValue(key, out name)) { Mpv.Command(mpv, "keyup", name); keysDown.Remove(key); }
            }
            return;
        }
        base.WndProc(ref m);
    }

    protected override void OnDeactivate(EventArgs e)
    {
        // Don't leave keys "held" (e.g. Space at 2x) when switching away mid-press.
        if (mpv != IntPtr.Zero) foreach (var k in keysDown.Values) Mpv.Command(mpv, "keyup", k);
        keysDown.Clear();
        base.OnDeactivate(e);
    }

    void Rescan()
    {
        if (scanning) return;
        scanning = true;
        ThreadPool.QueueUserWorkItem(_ => {
            try { Library.Scan(); } catch { }
            BeginInvoke((Action)(() => { scanning = false; SendState(); EnsureSeriesPlaylist(); Library.StartThumbnails(); }));
        });
    }

    void Post(object msg)
    {
        if (lib.CoreWebView2 == null || !libReady) return;
        lib.CoreWebView2.PostWebMessageAsJson(msg as string ?? json.Serialize(msg));
    }

    void SendState() { if (libReady) Post(Library.StateJson()); }

    void SendThumb(string key)
    {
        double d;
        Library.Durations.TryGetValue(key, out d);
        Post(new Dictionary<string, object> { { "type", "thumb" }, { "key", key }, { "dur", d } });
    }

    void Toast(string text) { Post(new Dictionary<string, object> { { "type", "toast" }, { "text", text } }); }

    void OnLibraryMessage(object sender, CoreWebView2WebMessageReceivedEventArgs e)
    {
        Dictionary<string, object> m;
        try { m = json.Deserialize<Dictionary<string, object>>(e.WebMessageAsJson); } catch { return; }
        string cmd = m.ContainsKey("cmd") ? m["cmd"] as string : null;
        string path = m.ContainsKey("path") ? m["path"] as string : null;
        switch (cmd) {
            case "ready":
                libReady = true;
                if (mpv != IntPtr.Zero && Mpv.Get(mpv, "idle-active") == "yes") ShowLibrary(true);
                else { SendState(); FocusVideo(); }
                break;
            case "play": if (path != null) PlayFromLibrary(path); break;
            case "rescan": Toast("Looking for new videos…"); Rescan(); break;
            case "watched": Library.SetWatched(path, m.ContainsKey("value") && (bool)m["value"]); SendState(); break;
            case "forget": Library.Forget(path); SendState(); break;
            case "clearHistory": Library.ClearHistory(); SendState(); break;
            case "removeFolder": Library.Folders.Remove(path); Library.SaveSettings(); Rescan(); break;
            case "addFolder":
                using (var dlg = new FolderBrowserDialog { Description = "Choose a folder with your series or movies", ShowNewFolderButton = false }) {
                    if (dlg.ShowDialog(this) == DialogResult.OK && !Library.Folders.Contains(dlg.SelectedPath)) {
                        Library.Folders.Add(dlg.SelectedPath); Library.SaveSettings(); Toast("Added " + dlg.SelectedPath); Rescan();
                    }
                }
                break;
            case "openFile":
                using (var dlg = new OpenFileDialog { Title = "Open a video", Filter = "Videos|*.mkv;*.mp4;*.avi;*.webm;*.mov;*.m4v;*.wmv;*.ts;*.flv;*.mpg|All files|*.*" }) {
                    if (dlg.ShowDialog(this) == DialogResult.OK) PlayFromLibrary(dlg.FileName);
                }
                break;
        }
    }

    // Plays a file, resuming where it was left. For an episode the whole series becomes the playlist,
    // so it continues into the next episode and Next/Previous work across seasons.
    void PlayFromLibrary(string path)
    {
        if (mpv == IntPtr.Zero) return;
        string start = Library.ResumeAt(path).ToString("0.###", CultureInfo.InvariantCulture);
        Mpv.Command(mpv, "loadfile", path, "replace", "-1", "start=" + start);
        AddSeriesAround(path);
        ShowLibrary(false);
    }

    // Puts the rest of the series around the playing episode, so the next one follows on its own and
    // Previous/Next work across seasons. Assumes the playlist holds only `path` (at position 0).
    bool AddSeriesAround(string path)
    {
        var item = Library.Find(path);
        if (item == null || item.kind != "episode") return false;
        var eps = Library.ShowEpisodes(item.showId);
        int at = eps.FindIndex(x => string.Equals(x.path, path, StringComparison.OrdinalIgnoreCase));
        if (at < 0 || eps.Count < 2) return false;
        Intros.Analyse(eps.Select(x => x.path).ToList(), path);   // find intros/credits for Skip Intro
        for (int i = 0; i < at; i++) Mpv.Command(mpv, "loadfile", eps[i].path, "insert-at", i.ToString(), "start=0");
        for (int i = at + 1; i < eps.Count; i++)
            Mpv.Command(mpv, "loadfile", eps[i].path, "append", "-1", "start=" + Library.ResumeAt(eps[i].path).ToString("0.###", CultureInfo.InvariantCulture));
        return true;
    }

    // An episode opened some other way (Explorer, drag & drop, Open file) gets its series playlist too.
    void EnsureSeriesPlaylist()
    {
        if (mpv == IntPtr.Zero || Mpv.Get(mpv, "playlist-count") != "1") return;
        string path = Mpv.Get(mpv, "path");
        if (string.IsNullOrEmpty(path) || path.Contains("://")) return;
        AddSeriesAround(Path.GetFullPath(path));
    }

    // History: sample the playing file's position every 2 s.
    void RecordProgress()
    {
        if (mpv == IntPtr.Zero || lib.Visible) return;
        string path = Mpv.Get(mpv, "path");
        double pos, dur;
        if (path == null || !double.TryParse(Mpv.Get(mpv, "time-pos") ?? "", NumberStyles.Float, CultureInfo.InvariantCulture, out pos)
            || !double.TryParse(Mpv.Get(mpv, "duration") ?? "", NumberStyles.Float, CultureInfo.InvariantCulture, out dur)) return;
        if (!path.Contains("://")) path = Path.GetFullPath(path);
        Library.Record(path, pos, dur);
    }

    // Live editing: a change anywhere in config\ reloads the player on the same video at the same second.
    void WatchConfig()
    {
        if (!Directory.Exists(ConfigDir)) return;
        var watcher = new FileSystemWatcher(ConfigDir) { IncludeSubdirectories = true, EnableRaisingEvents = true,
            NotifyFilter = NotifyFilters.LastWrite | NotifyFilters.FileName, SynchronizingObject = this };
        FileSystemEventHandler changed = (o, e) => { liveDebounce.Stop(); liveDebounce.Start(); };
        watcher.Changed += changed; watcher.Created += changed; watcher.Deleted += changed;
        watcher.Renamed += (o, e) => changed(o, e);
        liveDebounce.Tick += (o, e) => {
            liveDebounce.Stop();
            if (mpv == IntPtr.Zero || restarting || closing) return;
            resumeFile = Mpv.Get(mpv, "path");
            resumeAt = Mpv.Get(mpv, "time-pos");
            resumePaused = Mpv.Get(mpv, "pause") == "yes";
            restarting = true;
            Mpv.Command(mpv, "quit");
        };
    }
}