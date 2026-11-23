package com.qwikplayer.player;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Folder browser: folders that contain videos, then the videos in a folder. */
public class LibraryActivity extends Activity {
    private static final int REQ_PERM = 1, REQ_PICK = 2;
    private static final long STALE_MS = 30000;

    private enum State { LOADING, NO_PERMISSION, EMPTY, NO_MATCH, LIST }

    private Prefs prefs;
    private ThumbLoader thumbs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private int loadGen;
    private long loadedAt;
    private boolean loadedOnce;

    private List<Video> all = new ArrayList<>();
    private List<Video.Folder> folders = new ArrayList<>();
    private String currentFolderId;           // null = folder level
    private String query = "";
    private boolean searching;
    private Map<String, Prefs.Progress> progress = new HashMap<>();
    private final List<Object> items = new ArrayList<>();
    private final List<Video> shownVideos = new ArrayList<>();

    private TextView title;
    private EditText searchField;
    private ImageButton backBtn, searchBtn, sortBtn, settingsBtn;
    private ListView list;
    private PullRefreshLayout pull;
    private LinearLayout stateView;
    private ImageView stateIcon;
    private TextView stateTitle, stateText;
    private Button stateButton, stateButton2;
    private ProgressBar loading;
    private ItemAdapter adapter;
    private State state = State.LOADING;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = Prefs.get(this);
        thumbs = new ThumbLoader(this);
        buildUi();
        updateHeader();
    }

    @Override protected void onStart() {
        super.onStart();
        if (hasPermission()) {
            if (!loadedOnce || System.currentTimeMillis() - loadedAt > STALE_MS) reload(false);
        } else {
            showState(State.NO_PERMISSION);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        progress = prefs.allProgress();     // new / watched marks change after playing
        adapter.notifyDataSetChanged();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        loadGen++;
        io.shutdownNow();
        thumbs.close();
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        int accentText = Ui.accentText(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(Ui.BG);
        header.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);

        backBtn = Ui.iconButton(this, R.drawable.ic_back, 48, Ui.TEXT);
        backBtn.setContentDescription("Back");
        backBtn.setOnClickListener(v -> goBack());
        header.addView(backBtn, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 56)));

        title = Ui.text(this, "QwikPlayer", 22, Ui.TEXT, true);
        title.setSingleLine();
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tl.leftMargin = Ui.dp(this, 8);
        header.addView(title, tl);

        searchField = new EditText(this);
        searchField.setHint("Search videos");
        searchField.setHintTextColor(Ui.MUTED);
        searchField.setTextColor(Ui.TEXT);
        searchField.setTextSize(16);
        searchField.setSingleLine();
        searchField.setInputType(InputType.TYPE_CLASS_TEXT);
        searchField.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchField.setBackground(null);
        searchField.setVisibility(View.GONE);
        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                query = s.toString().trim();
                rebuildItems();
            }
        });
        searchField.setOnEditorActionListener((v, a, e) -> { hideKeyboard(); return true; });
        header.addView(searchField, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));

        searchBtn = Ui.iconButton(this, R.drawable.ic_search, 48, Ui.TEXT);
        searchBtn.setContentDescription("Search");
        searchBtn.setOnClickListener(v -> setSearching(!searching));
        header.addView(searchBtn, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 56)));

        sortBtn = Ui.iconButton(this, R.drawable.ic_sort, 48, Ui.TEXT);
        sortBtn.setContentDescription("Sort");
        sortBtn.setOnClickListener(v -> showSortMenu());
        header.addView(sortBtn, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 56)));

        settingsBtn = Ui.iconButton(this, R.drawable.ic_settings, 48, Ui.TEXT);
        settingsBtn.setContentDescription("Settings");
        settingsBtn.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        header.addView(settingsBtn, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 56)));
        root.addView(header, new LinearLayout.LayoutParams(-1, Ui.dp(this, 56)));

        // thin brand gradient under the header
        View line = new View(this);
        line.setBackground(Ui.gradient(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, 0));
        root.addView(line, new LinearLayout.LayoutParams(-1, Ui.dp(this, 2)));

        FrameLayout content = new FrameLayout(this);
        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setClipToPadding(false);
        list.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 16));
        adapter = new ItemAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> onItemClick(pos));
        pull = new PullRefreshLayout(this, list);
        pull.setListener(() -> reload(true));
        content.addView(pull, new FrameLayout.LayoutParams(-1, -1));

        stateView = new LinearLayout(this);
        stateView.setOrientation(LinearLayout.VERTICAL);
        stateView.setGravity(Gravity.CENTER);
        stateView.setPadding(Ui.dp(this, 32), 0, Ui.dp(this, 32), Ui.dp(this, 48));
        stateIcon = new ImageView(this);
        stateIcon.setImageResource(R.drawable.ic_play);
        stateIcon.setColorFilter(Color.WHITE);
        stateIcon.setPadding(Ui.dp(this, 22), Ui.dp(this, 22), Ui.dp(this, 22), Ui.dp(this, 22));
        stateIcon.setBackground(ovalGradient());
        stateView.addView(stateIcon, new LinearLayout.LayoutParams(Ui.dp(this, 88), Ui.dp(this, 88)));
        stateTitle = Ui.text(this, "", 20, Ui.TEXT, true);
        stateTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams stl = new LinearLayout.LayoutParams(-2, -2);
        stl.topMargin = Ui.dp(this, 20);
        stateView.addView(stateTitle, stl);
        stateText = Ui.text(this, "", 14, Ui.MUTED, false);
        stateText.setGravity(Gravity.CENTER);
        stateText.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams sxl = new LinearLayout.LayoutParams(-2, -2);
        sxl.topMargin = Ui.dp(this, 8);
        stateView.addView(stateText, sxl);
        stateButton = primaryButton("");
        LinearLayout.LayoutParams sbl = new LinearLayout.LayoutParams(-2, Ui.dp(this, 44));
        sbl.topMargin = Ui.dp(this, 20);
        stateView.addView(stateButton, sbl);
        stateButton2 = ghostButton("");
        LinearLayout.LayoutParams sb2 = new LinearLayout.LayoutParams(-2, Ui.dp(this, 44));
        sb2.topMargin = Ui.dp(this, 8);
        stateView.addView(stateButton2, sb2);
        content.addView(stateView, new FrameLayout.LayoutParams(-1, -1));

        loading = new ProgressBar(this);
        loading.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(accentText));
        content.addView(loading, new FrameLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40), Gravity.CENTER));

        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private android.graphics.drawable.GradientDrawable ovalGradient() {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR, Ui.GRADIENT);
        d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        return d;
    }

    private Button primaryButton(String s) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(s);
        b.setTextColor(Ui.onAccent(this));
        b.setTextSize(15);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setStateListAnimator(null);
        b.setBackground(Ui.ripple(Ui.rect(Ui.accent(this), Ui.dp(this, 22)), false));
        b.setPadding(Ui.dp(this, 24), 0, Ui.dp(this, 24), 0);
        return b;
    }

    private Button ghostButton(String s) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(s);
        b.setTextColor(Ui.accentText(this));
        b.setTextSize(15);
        b.setStateListAnimator(null);
        b.setBackground(Ui.ripple(null, true));
        return b;
    }

    // ---------------------------------------------------------------- state

    private void showState(State s) {
        state = s;
        boolean showList = s == State.LIST;
        pull.setVisibility(showList || s == State.EMPTY || s == State.NO_MATCH ? View.VISIBLE : View.GONE);
        loading.setVisibility(s == State.LOADING && !pull.isRefreshing() ? View.VISIBLE : View.GONE);
        stateView.setVisibility(showList || s == State.LOADING ? View.GONE : View.VISIBLE);
        if (s == State.NO_PERMISSION) {
            pull.setVisibility(View.GONE);
            stateTitle.setText("Allow access to your videos");
            stateText.setText("QwikPlayer plays videos stored on this device. It only reads your video library, nothing is uploaded.");
            stateButton.setText("Allow access");
            stateButton.setVisibility(View.VISIBLE);
            stateButton.setOnClickListener(v -> requestAccess());
            stateButton2.setText("Open a file instead");
            stateButton2.setVisibility(View.VISIBLE);
            stateButton2.setOnClickListener(v -> pickFile());
        } else if (s == State.EMPTY) {
            stateTitle.setText("No videos found");
            stateText.setText("Videos saved on this device will show up here.\nPull down to refresh.");
            stateButton.setText("Refresh");
            stateButton.setVisibility(View.VISIBLE);
            stateButton.setOnClickListener(v -> reload(false));
            stateButton2.setText("Open a file");
            stateButton2.setVisibility(View.VISIBLE);
            stateButton2.setOnClickListener(v -> pickFile());
        } else if (s == State.NO_MATCH) {
            stateTitle.setText(query.isEmpty() ? "This folder is empty" : "Nothing matches");
            stateText.setText(query.isEmpty() ? "No videos left in this folder." : "No videos match \"" + query + "\".");
            stateButton.setVisibility(View.GONE);
            stateButton2.setVisibility(View.GONE);
        }
    }

    private boolean hasPermission() {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED;
    }

    private static String permission() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_VIDEO : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private void requestAccess() {
        if (Build.VERSION.SDK_INT < 23) { reload(false); return; }
        // Once the system stops showing its dialog ("don't ask again") the only way is the app's settings page.
        if (prefs.permissionAsked() && !shouldShowRequestPermissionRationale(permission())) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null)));
            return;
        }
        prefs.setPermissionAsked();
        requestPermissions(new String[]{permission()}, REQ_PERM);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_PERM) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) reload(false);
        else showState(State.NO_PERMISSION);
    }

    // ---------------------------------------------------------------- loading

    private void reload(final boolean fromPull) {
        if (!hasPermission()) {
            pull.setRefreshing(false);
            showState(State.NO_PERMISSION);
            return;
        }
        final int gen = ++loadGen;
        if (!loadedOnce && !fromPull) showState(State.LOADING);
        else if (!fromPull) pull.setRefreshing(false);
        final android.content.Context app = getApplicationContext();
        try {
            io.execute(() -> {
                final List<Video> result = MediaRepo.loadAll(app);
                ui.post(() -> {
                    if (gen != loadGen || isDestroyed()) return;
                    all = result;
                    folders = MediaRepo.group(result);
                    loadedOnce = true;
                    loadedAt = System.currentTimeMillis();
                    pull.setRefreshing(false);
                    if (currentFolderId != null && findFolder(currentFolderId) == null) currentFolderId = null;
                    progress = prefs.allProgress();
                    updateHeader();
                    rebuildItems();
                });
            });
        } catch (RuntimeException rejected) {
            pull.setRefreshing(false);   // executor already shut down: the activity is going away
        }
    }

    private Video.Folder findFolder(String id) {
        for (Video.Folder f : folders) if (f.id.equals(id)) return f;
        return null;
    }

    // ---------------------------------------------------------------- list model

    private void rebuildItems() {
        items.clear();
        shownVideos.clear();
        int key = prefs.sortKey();
        boolean desc = prefs.sortDesc();
        String q = query.toLowerCase(Locale.ROOT);
        if (currentFolderId == null && q.isEmpty()) {
            List<Video.Folder> fs = new ArrayList<>(folders);
            MediaRepo.sortFolders(fs, key, desc);
            items.addAll(fs);
        } else {
            List<Video> src = currentFolderId == null ? all : (findFolder(currentFolderId) == null ? new ArrayList<Video>() : findFolder(currentFolderId).videos);
            List<Video> vs = new ArrayList<>();
            for (Video v : src) if (q.isEmpty() || v.name.toLowerCase(Locale.ROOT).contains(q)) vs.add(v);
            MediaRepo.sortVideos(vs, key, desc);
            items.addAll(vs);
            shownVideos.addAll(vs);
        }
        adapter.notifyDataSetChanged();
        if (all.isEmpty()) showState(State.EMPTY);
        else if (items.isEmpty()) showState(State.NO_MATCH);
        else showState(State.LIST);
    }

    private void updateHeader() {
        Video.Folder f = currentFolderId == null ? null : findFolder(currentFolderId);
        boolean inFolder = f != null;
        backBtn.setVisibility(inFolder || searching ? View.VISIBLE : View.GONE);
        title.setVisibility(searching ? View.GONE : View.VISIBLE);
        searchField.setVisibility(searching ? View.VISIBLE : View.GONE);
        title.setText(inFolder ? f.name : "QwikPlayer");
        android.text.TextPaint p = title.getPaint();
        if (!inFolder) {
            float w = p.measureText("QwikPlayer");
            p.setShader(new LinearGradient(0, 0, w, 0, new int[]{Ui.EMBER, Ui.PULSE, Ui.VOLT_LIGHT}, null, Shader.TileMode.CLAMP));
        } else {
            p.setShader(null);
        }
        title.invalidate();
        searchBtn.setColorFilter(searching ? Ui.accentText(this) : Ui.TEXT);
    }

    private void setSearching(boolean on) {
        searching = on;
        if (on) {
            searchField.setText("");
            updateHeader();
            searchField.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT);
        } else {
            hideKeyboard();
            searchField.setText("");
            query = "";
            updateHeader();
            rebuildItems();
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(searchField.getWindowToken(), 0);
    }

    private void goBack() {
        if (searching) setSearching(false);
        else if (currentFolderId != null) { currentFolderId = null; updateHeader(); rebuildItems(); list.setSelection(0); }
    }

    @Override public void onBackPressed() {
        if (searching || currentFolderId != null) goBack();
        else super.onBackPressed();
    }

    private void showSortMenu() {
        PopupMenu m = new PopupMenu(this, sortBtn);
        m.getMenu().add(1, 0, 0, "Name");
        m.getMenu().add(1, 1, 1, "Date added");
        m.getMenu().add(1, 2, 2, "Size");
        m.getMenu().setGroupCheckable(1, true, true);
        m.getMenu().getItem(prefs.sortKey()).setChecked(true);
        m.getMenu().add(2, 3, 3, "Reverse order").setCheckable(true).setChecked(prefs.sortDesc() != (prefs.sortKey() != Prefs.SORT_NAME));
        m.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            int key = prefs.sortKey();
            boolean desc = prefs.sortDesc();
            if (id == 3) {
                desc = !desc;
            } else if (id != key) {
                key = id;
                desc = id != Prefs.SORT_NAME;   // newest / biggest first, names A-Z
            }
            prefs.setSort(key, desc);
            rebuildItems();
            return true;
        });
        m.show();
    }

    private void onItemClick(int pos) {
        if (pos < 0 || pos >= items.size()) return;
        Object o = items.get(pos);
        if (o instanceof Video.Folder) {
            currentFolderId = ((Video.Folder) o).id;
            updateHeader();
            rebuildItems();
            list.setSelection(0);
        } else {
            Video.Playlist.items = new ArrayList<>(shownVideos);
            Intent i = new Intent(this, PlayerActivity.class);
            i.setData(((Video) o).uri);
            startActivity(i);
        }
    }

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("video/*");
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (RuntimeException e) {
            android.widget.Toast.makeText(this, "No file picker available", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_PICK && res == RESULT_OK && data != null && data.getData() != null) {
            Video.Playlist.items = new ArrayList<>();
            Intent i = new Intent(this, PlayerActivity.class);
            i.setData(data.getData());
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        }
    }

    // ---------------------------------------------------------------- adapter

    private final class ItemAdapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int i) { return items.get(i) instanceof Video.Folder ? 0 : 1; }

        @Override public View getView(int pos, View convert, ViewGroup parent) {
            Object o = items.get(pos);
            if (o instanceof Video.Folder) {
                FolderRow r = convert == null ? new FolderRow() : (FolderRow) convert.getTag();
                r.bind((Video.Folder) o);
                return r.root;
            }
            VideoRow r = convert == null ? new VideoRow() : (VideoRow) convert.getTag();
            r.bind((Video) o);
            return r.root;
        }
    }

    private final class FolderRow {
        final LinearLayout root = new LinearLayout(LibraryActivity.this);
        final TextView name, sub;
        final TextView badge;

        FolderRow() {
            LibraryActivity c = LibraryActivity.this;
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(Ui.dp(c, 16), Ui.dp(c, 8), Ui.dp(c, 16), Ui.dp(c, 8));
            root.setBackground(Ui.ripple(null, false));
            ImageView icon = new ImageView(c);
            icon.setImageResource(R.drawable.ic_folder);
            icon.setColorFilter(Color.WHITE);
            icon.setPadding(Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12));
            icon.setBackground(Ui.gradient(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, Ui.dp(c, 14)));
            root.addView(icon, new LinearLayout.LayoutParams(Ui.dp(c, 52), Ui.dp(c, 52)));
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            name = Ui.text(c, "", 16, Ui.TEXT, true);
            name.setSingleLine();
            name.setEllipsize(TextUtils.TruncateAt.END);
            sub = Ui.text(c, "", 13, Ui.MUTED, false);
            col.addView(name);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
            sl.topMargin = Ui.dp(c, 4);
            col.addView(sub, sl);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1);
            cl.leftMargin = Ui.dp(c, 14);
            root.addView(col, cl);
            badge = Ui.pill(c, "", Ui.SPARK, 0xFF1A1400, 11);
            root.addView(badge, new LinearLayout.LayoutParams(-2, -2));
            root.setTag(this);
        }

        void bind(Video.Folder f) {
            int fresh = 0;
            for (Video v : f.videos) if (!progress.containsKey(v.key())) fresh++;
            name.setText(f.name);
            sub.setText(f.videos.size() + (f.videos.size() == 1 ? " video" : " videos") + "  ·  " + Ui.size(f.totalSize));
            badge.setVisibility(fresh > 0 ? View.VISIBLE : View.GONE);
            badge.setText(fresh + " new");
        }
    }

    private final class VideoRow {
        final LinearLayout root = new LinearLayout(LibraryActivity.this);
        final ImageView thumb;
        final TextView duration, name, meta;
        final View bar, dot;
        final FrameLayout thumbBox;
        final int thumbW;

        VideoRow() {
            final LibraryActivity c = LibraryActivity.this;
            thumbW = Ui.dp(c, 124);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(Ui.dp(c, 16), Ui.dp(c, 8), Ui.dp(c, 16), Ui.dp(c, 8));
            root.setBackground(Ui.ripple(null, false));
            thumbBox = new FrameLayout(c);
            thumbBox.setBackground(Ui.rect(Ui.RAISED, Ui.dp(c, 10)));
            thumbBox.setClipToOutline(true);
            thumbBox.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.dp(c, 10)); }
            });
            thumb = new ImageView(c);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbBox.addView(thumb, new FrameLayout.LayoutParams(-1, -1));
            duration = Ui.text(c, "", 11, Color.WHITE, true);
            duration.setPadding(Ui.dp(c, 5), Ui.dp(c, 2), Ui.dp(c, 5), Ui.dp(c, 2));
            duration.setBackground(Ui.rect(0xB3000000, Ui.dp(c, 6)));
            FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
            dl.setMargins(0, 0, Ui.dp(c, 5), Ui.dp(c, 7));
            thumbBox.addView(duration, dl);
            bar = new View(c);
            bar.setBackground(Ui.seekGradient(0));
            thumbBox.addView(bar, new FrameLayout.LayoutParams(0, Ui.dp(c, 3), Gravity.BOTTOM | Gravity.START));
            root.addView(thumbBox, new LinearLayout.LayoutParams(thumbW, Ui.dp(c, 70)));

            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout titleRow = new LinearLayout(c);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);
            dot = Ui.dot(c, Ui.SPARK, 8);
            LinearLayout.LayoutParams dotLp = (LinearLayout.LayoutParams) dot.getLayoutParams();
            dotLp.rightMargin = Ui.dp(c, 8);
            titleRow.addView(dot);
            name = Ui.text(c, "", 15, Ui.TEXT, true);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            titleRow.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            col.addView(titleRow);
            meta = Ui.text(c, "", 12, Ui.MUTED, false);
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-2, -2);
            ml.topMargin = Ui.dp(c, 6);
            col.addView(meta, ml);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1);
            cl.leftMargin = Ui.dp(c, 12);
            root.addView(col, cl);
            root.setTag(this);
        }

        void bind(Video v) {
            Prefs.Progress p = progress.get(v.key());
            name.setText(v.title());
            duration.setText(Ui.time(v.duration));
            duration.setVisibility(v.duration > 0 ? View.VISIBLE : View.GONE);
            StringBuilder sb = new StringBuilder();
            if (v.width > 0 && v.height > 0) sb.append(v.width).append('×').append(v.height).append("  ·  ");
            sb.append(Ui.size(v.size));
            if (p != null && p.watched) sb.append("  ·  Watched");
            meta.setText(sb);
            dot.setVisibility(p == null ? View.VISIBLE : View.GONE);   // "new" = never opened
            name.setTextColor(p != null && p.watched ? Ui.MUTED : Ui.TEXT);
            float f = p == null ? 0f : p.fraction();
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bar.getLayoutParams();
            lp.width = Math.round(thumbW * f);
            bar.setLayoutParams(lp);
            bar.setVisibility(f > 0 ? View.VISIBLE : View.GONE);
            thumbs.load(v, thumb);
        }
    }
}
