// QwikPlayer: a Windows video player built on libmpv (bundled, x64 + ARM64).
// The app owns the window (native dark title bar, icon, fullscreen, maximize, drag & drop) and embeds
// mpv's renderer in it; the controls are uosc plus the app's own Lua scripts from <app>\config.
// Live editing: when anything in config\ changes, the player reloads at the same second.

using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Windows.Forms;
using System.Globalization;
using System.Web.Script.Serialization;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

// Name shown by Windows (Open with, taskbar, Task Manager).
[assembly: System.Reflection.AssemblyTitle("QwikPlayer")]
[assembly: System.Reflection.AssemblyProduct("QwikPlayer")]

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

class QwikPlayer : Form
{
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] static extern bool SetDllDirectory(string path);
    [DllImport("user32.dll")] static extern bool SetProcessDPIAware();   // fallback only: app.manifest (per-monitor v2) wins when present
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)] static extern IntPtr LoadLibrary(string path);
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

    [MethodImpl(MethodImplOptions.NoInlining)]
    static bool ProcessIsArm64() { return RuntimeInformation.ProcessArchitecture == Architecture.Arm64; }   // needs .NET 4.7.1

    static bool IsArm64()
    {
        try { return ProcessIsArm64(); } catch (Exception) { return false; }
    }

    static string CrashLog()
    {
        string dir;
        try { dir = Library.DataDir; } catch (Exception) { dir = Path.GetTempPath(); }
        return Path.Combine(dir, "crash.log");
    }

    // Writes what went wrong to crash.log in the data folder (never throws, never grows past ~256 KB).
    static void LogCrash(string where, Exception ex)
    {
        try {
            string file = CrashLog();
            Directory.CreateDirectory(Path.GetDirectoryName(file));
            if (File.Exists(file) && new FileInfo(file).Length > 256 * 1024) return;
            File.AppendAllText(file, DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss", CultureInfo.InvariantCulture) + " [" + where + "] " + (ex == null ? "(unknown error)" : ex.ToString()) + "\r\n\r\n");
        } catch (Exception) { }
    }

    static void Fatal(string message)
    {
        try { MessageBox.Show(message, "QwikPlayer", MessageBoxButtons.OK, MessageBoxIcon.Error); } catch (Exception) { }
    }

    [STAThread]
    static int Main(string[] args)
    {
        try {
            Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
            // A UI-thread error is logged and the player carries on (as the old Continue button did); startup errors below end the run.
            Application.ThreadException += (o, e) => LogCrash("UI thread", e.Exception);
            AppDomain.CurrentDomain.UnhandledException += (o, e) => {
                Exception ex = e.ExceptionObject as Exception;
                LogCrash("unhandled", ex);
                Fatal("QwikPlayer hit an unexpected error and has to close.\n\n" + (ex == null ? "" : ex.Message) + "\n\nDetails were saved to:\n" + CrashLog());
            };
            AppDomain.CurrentDomain.ProcessExit += (o, e) => Library.KillChildren();   // no ffmpeg left running after the player
            Application.EnableVisualStyles();
            if (!Environment.Is64BitProcess) {
                Fatal("QwikPlayer needs 64-bit Windows 10 or later (x64 or ARM64).");
                return 1;
            }
            // First of all: settle the data folder (moves the pre-rename one on the first run, see Library.ResolveDataDir).
            GC.KeepAlive(Library.DataDir);
            SetProcessDPIAware();
            // Command-line files become absolute while the working directory is still the caller's; after that the process
            // lives in the app folder, so nothing it starts can be hijacked from a folder full of videos.
            for (int i = 0; i < args.Length; i++)
                if (args[i].IndexOf("://", StringComparison.Ordinal) < 0) args[i] = FullPath(args[i]);
            try { Environment.CurrentDirectory = AppDir; } catch (Exception) { }
            // One build for x64 and Windows on ARM: load the libmpv that matches this process.
            string mpvDir = Path.Combine(AppDir, "libmpv", IsArm64() ? "arm64" : "x64");
            SetDllDirectory(mpvDir);
            string mpvDll = Path.Combine(mpvDir, "libmpv-2.dll");
            if (!File.Exists(mpvDll)) {
                Fatal("QwikPlayer can't find its video engine:\n" + mpvDll + "\n\nReinstall QwikPlayer, or copy the whole QwikPlayer folder, not just the .exe.");
                return 1;
            }
            if (LoadLibrary(mpvDll) == IntPtr.Zero) {
                Fatal("QwikPlayer's video engine (libmpv-2.dll) could not be loaded (Windows error " + Marshal.GetLastWin32Error() + ").\n\nThe file may be damaged or built for a different processor. Reinstall QwikPlayer.");
                return 1;
            }
            Application.Run(new QwikPlayer(args));
            return 0;
        } catch (Exception ex) {
            LogCrash("startup", ex);
            Fatal("QwikPlayer could not start.\n\n" + ex.Message + "\n\nDetails were saved to:\n" + CrashLog());
            return 1;
        }
    }

    static string FullPath(string p)
    {
        try { return Path.GetFullPath(p); } catch (Exception) { return p; }   // too long, invalid characters: keep what mpv was given
    }

    QwikPlayer(string[] files)
    {
        Text = "QwikPlayer";
        string ico = Path.Combine(AppDir, "qwikplayer.ico");
        if (File.Exists(ico)) Icon = new Icon(ico);
        BackColor = Color.Black;
        AutoScaleMode = AutoScaleMode.None;   // the window is DPI aware (manifest): sizes below are real pixels, nothing is scaled twice
        StartPosition = FormStartPosition.CenterScreen;
        var area = Screen.PrimaryScreen.WorkingArea;
        Size = new Size(area.Width * 8 / 10, area.Height * 8 / 10);
        AllowDrop = true;
        Controls.Add(screen);

        DragEnter += (o, e) => e.Effect = e.Data.GetDataPresent(DataFormats.FileDrop) ? DragDropEffects.Copy : DragDropEffects.None;
        DragDrop += (o, e) => {
            var dropped = e.Data.GetData(DataFormats.FileDrop) as string[];
            if (mpv == IntPtr.Zero || dropped == null) return;   // shutting down or reloading
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
        // The path is passed along so the script only re-reads when the episode that is playing changed.
        Intros.Updated += changed => { try { if (IsHandleCreated && !closing) BeginInvoke((Action)(() => { if (mpv != IntPtr.Zero) Mpv.Command(mpv, "script-message", "intros-updated", changed ?? ""); })); } catch (InvalidOperationException) { } };
        Library.ThumbReady += key => { try { if (IsHandleCreated) BeginInvoke((Action)(() => SendThumb(key))); } catch (InvalidOperationException) { } };
        historyTimer.Tick += (o, e) => RecordProgress();
        Activated += (o, e) => { if (!lib.Visible) FocusVideo(); };   // coming back with Alt+Tab
        // WebView2 takes keyboard focus when its page loads, even while hidden behind a playing video.
        lib.GotFocus += (o, e) => { if (!lib.Visible) BeginInvoke((Action)FocusVideo); };
        historyTimer.Start();
        // With no file the library is the first thing on screen, so its WebView2 starts at once (as before). When a file is
        // opened directly the WebView2 is only created when the library is first shown (ShowLibrary).
        Shown += (o, e) => { if (files.Length == 0) StartLibrary(); Start(files.ToList(), null, false); Rescan(); };
        FormClosing += (o, e) => {
            try { RecordProgress(); Library.SaveHistory(); } catch { }
            Library.KillChildren();   // thumbnail / intro ffmpeg must not outlive the window
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

    // Dark title bar in QwikPlayer charcoal (Windows 11).
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
        IntPtr created;
        try { created = Mpv.mpv_create(); }
        catch (Exception ex) { LogCrash("mpv_create", ex); StartFailed("QwikPlayer's video engine (libmpv) could not be started: " + ex.Message); return; }
        if (created == IntPtr.Zero) { StartFailed("QwikPlayer's video engine (libmpv) could not be created."); return; }
        mpv = created;
        Action<string, string> opt = (k, v) => Mpv.mpv_set_option_string(mpv, Mpv.U8(k), Mpv.U8(v));
        opt("config-dir", ConfigDir);
        opt("config", "yes");
        // Where the Lua scripts read and write their data. %n% is mpv's length-prefixed quoting (n bytes follow),
        // so a path with spaces, commas or '=' arrives intact.
        opt("script-opts", "qwik-data_dir=%" + Encoding.UTF8.GetByteCount(Library.DataDir) + "%" + Library.DataDir);
        opt("wid", screen.Handle.ToInt64().ToString());
        opt("input-default-bindings", "yes");
        opt("input-vo-keyboard", "yes");
        opt("force-window", "yes");
        opt("idle", "yes");              // stay open with the QwikPlayer start screen when nothing plays
        opt("border", "yes");            // the app's own title bar replaces uosc's window buttons
        opt("terminal", "no");
        int rc = Mpv.mpv_initialize(mpv);
        if (rc < 0) {
            mpv = IntPtr.Zero;
            try { Mpv.mpv_terminate_destroy(created); } catch (Exception) { }
            StartFailed("QwikPlayer's video engine (libmpv) failed to initialise (error " + rc + ").\n\nIf this keeps happening, reinstall QwikPlayer or update your graphics driver.");
            return;
        }
        Mpv.mpv_observe_property(mpv, 1, Mpv.U8("media-title"), Mpv.FORMAT_STRING);
        Mpv.mpv_observe_property(mpv, 2, Mpv.U8("fullscreen"), Mpv.FORMAT_FLAG);
        Mpv.mpv_observe_property(mpv, 3, Mpv.U8("window-maximized"), Mpv.FORMAT_FLAG);
        Mpv.mpv_observe_property(mpv, 4, Mpv.U8("window-minimized"), Mpv.FORMAT_FLAG);
        Mpv.mpv_observe_property(mpv, 5, Mpv.U8("idle-active"), Mpv.FORMAT_FLAG);
        // A live reload resumes through per-file options on the first file, so later files don't inherit the start time or pause.
        string first = (startAt != null ? "start=" + startAt : "") + (startAt != null && paused ? "," : "") + (paused ? "pause=yes" : "");
        for (int i = 0; i < files.Count; i++) {
            if (i == 0 && first.Length > 0) Mpv.Command(mpv, "loadfile", files[i], "replace", "-1", first);
            else Mpv.Command(mpv, "loadfile", files[i], i == 0 ? "replace" : "append");
        }
        if (startAt != null) ThreadPool.QueueUserWorkItem(_ => { Thread.Sleep(1200); var h = mpv; if (h != IntPtr.Zero) Mpv.Command(h, "show-text", "✓ QwikPlayer updated", "2000"); });

        IntPtr ctx = mpv;
        new Thread(() => EventLoop(ctx)) { IsBackground = true, Name = "mpv events" }.Start();
        FocusVideo();
    }

    void StartFailed(string message)
    {
        Fatal(message);
        closing = true;
        Close();
    }

    void EventLoop(IntPtr ctx)
    {
        while (true) {
            var ev = (Mpv.Event)Marshal.PtrToStructure(Mpv.mpv_wait_event(ctx, -1), typeof(Mpv.Event));
            if (ev.id == Mpv.EVENT_SHUTDOWN) {
                // Stop every other caller (timers, key and mouse forwarding) before the handle is destroyed.
                try { Invoke((Action)(() => { if (mpv == ctx) mpv = IntPtr.Zero; })); } catch (InvalidOperationException) { }
                Mpv.mpv_terminate_destroy(ctx);
                try {
                    BeginInvoke((Action)(() => {
                        // A close during a live reload wins over the reload.
                        if (closing || !restarting) { closing = true; Close(); }
                        else { restarting = false; Start(resumeFile == null ? new List<string>() : new List<string> { resumeFile }, resumeAt, resumePaused); }
                    }));
                } catch (InvalidOperationException) { }
                return;
            }
            if (ev.id != Mpv.EVENT_PROPERTY_CHANGE || ev.data == IntPtr.Zero) continue;
            var prop = (Mpv.Property)Marshal.PtrToStructure(ev.data, typeof(Mpv.Property));
            if (prop.data == IntPtr.Zero) continue;
            string name = Mpv.FromU8(prop.name);
            string str = prop.format == Mpv.FORMAT_STRING ? Mpv.FromU8(Marshal.ReadIntPtr(prop.data)) : null;
            bool flag = prop.format == Mpv.FORMAT_FLAG && Marshal.ReadInt32(prop.data) != 0;
            try { BeginInvoke((Action)(() => OnProperty(name, str, flag))); } catch (InvalidOperationException) { return; }   // window already gone
        }
    }

    // uosc's buttons and keys change mpv properties; the app turns them into real window actions.
    void OnProperty(string name, string str, bool flag)
    {
        switch (name) {
            case "media-title":
                Text = string.IsNullOrEmpty(str) ? "QwikPlayer" : str + " - QwikPlayer";
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

    bool libStarted;

    // Creates the WebView2 library once, the first time it is needed.
    void StartLibrary()
    {
        if (libStarted) return;
        libStarted = true;
        InitLibrary();
    }

    async void InitLibrary()
    {
        string page = Path.Combine(AppDir, "library");
        if (!Directory.Exists(page)) return;
        try {
            string arch = IsArm64() ? "win-arm64" : "win-x64";
            string loader = Path.Combine(AppDir, "runtimes", arch, "native");
            if (File.Exists(Path.Combine(loader, "WebView2Loader.dll"))) CoreWebView2Environment.SetLoaderDllFolderPath(loader);
            var env = await CoreWebView2Environment.CreateAsync(null, Path.Combine(Library.DataDir, "webview"));
            await lib.EnsureCoreWebView2Async(env);
        } catch (Exception) { return; }   // no WebView2 runtime: the player still works, just without the library
        CoreWebView2 cw = lib.CoreWebView2;
        if (cw == null) return;
        cw.Settings.AreDefaultContextMenusEnabled = false;
        cw.Settings.AreDevToolsEnabled = false;
        cw.Settings.IsZoomControlEnabled = false;
        cw.Settings.IsStatusBarEnabled = false;
        cw.SetVirtualHostNameToFolderMapping("qwik.library", page, CoreWebView2HostResourceAccessKind.Allow);
        cw.SetVirtualHostNameToFolderMapping("qwik.thumbs", Library.ThumbDir, CoreWebView2HostResourceAccessKind.Allow);
        cw.SetVirtualHostNameToFolderMapping("qwik.fonts", Path.Combine(ConfigDir, "fonts"), CoreWebView2HostResourceAccessKind.Allow);
        cw.WebMessageReceived += OnLibraryMessage;
        cw.Navigate("https://qwik.library/index.html");
        // Live editing of the page: reload when library\ changes.
        var w = new FileSystemWatcher(page) { EnableRaisingEvents = true, SynchronizingObject = this, NotifyFilter = NotifyFilters.LastWrite | NotifyFilters.FileName };
        w.Changed += (o, e) => cw.Reload();
    }

    void ShowLibrary(bool show)
    {
        Library.Paused = !show;   // thumbnails are only made while the library is on screen
        if (show) { Text = "QwikPlayer"; ShowCursor(); cursorTimer.Stop(); StartLibrary(); }
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
            int got = ToUnicode((uint)key, 0, state, sb, sb.Capacity, 0);
            bool letter = key >= Keys.A && key <= Keys.Z, digit = key >= Keys.D0 && key <= Keys.D9;
            if ((letter || digit) && (got != 1 || sb[0] > 0x7F)) {
                // Non-Latin layout (Russian, Greek, Hindi, Arabic...): shortcuts follow the key's US-layout letter or digit.
                name = letter ? ((char)((shift ? 'A' : 'a') + (key - Keys.A))).ToString()
                              : (shift ? ")!@#$%^&*(" : "0123456789")[key - Keys.D0].ToString();
                if (name == "#") name = "SHARP";
            } else {
                if (got != 1) return null;
                name = sb.ToString();
                if (name == "#") name = "SHARP";
            }
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
            try {
                BeginInvoke((Action)(() => { scanning = false; SendState(); EnsureSeriesPlaylist(); if (lib.Visible) Library.StartThumbnails(); }));
            } catch (InvalidOperationException) { }   // window already gone
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
        Library.TryGetDuration(key, out d);
        Post(new Dictionary<string, object> { { "type", "thumb" }, { "key", key }, { "dur", d } });
    }

    void Toast(string text) { Post(new Dictionary<string, object> { { "type", "toast" }, { "text", text } }); }

    void OnLibraryMessage(object sender, CoreWebView2WebMessageReceivedEventArgs e)
    {
        Dictionary<string, object> m;
        try { m = json.Deserialize<Dictionary<string, object>>(e.WebMessageAsJson); } catch { return; }
        if (m == null) return;
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
                using (var dlg = new OpenFileDialog { Title = "Open a video", RestoreDirectory = true, Filter = "Videos|*.mkv;*.mp4;*.avi;*.webm;*.mov;*.m4v;*.wmv;*.ts;*.flv;*.mpg|All files|*.*" }) {
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
        var eps = Library.ShowEpisodes(item.showId, path);
        int at = eps.FindIndex(x => string.Equals(x.path, path, StringComparison.OrdinalIgnoreCase));
        if (at < 0 || eps.Count < 2) return false;
        // Find intros/credits for Skip Intro: the playing season only (the rest is analysed when it is played).
        Intros.Analyse(eps.Where(x => x.season == item.season).Select(x => x.path).ToList(), path);
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
        AddSeriesAround(FullPath(path));
    }

    // History: sample the playing file's position every 2 s.
    void RecordProgress()
    {
        if (mpv == IntPtr.Zero || lib.Visible) return;
        string path = Mpv.Get(mpv, "path");
        double pos, dur;
        if (path == null || !double.TryParse(Mpv.Get(mpv, "time-pos") ?? "", NumberStyles.Float, CultureInfo.InvariantCulture, out pos)
            || !double.TryParse(Mpv.Get(mpv, "duration") ?? "", NumberStyles.Float, CultureInfo.InvariantCulture, out dur)) return;
        if (!path.Contains("://")) path = FullPath(path);
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