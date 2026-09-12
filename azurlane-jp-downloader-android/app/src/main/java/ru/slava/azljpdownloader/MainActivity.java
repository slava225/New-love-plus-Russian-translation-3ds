package ru.slava.azljpdownloader;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.tabs.TabLayout;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private static final String GAME_PACKAGE = "com.YoStarJP.AzurLane";
    private static final String GATE_HOST = "blhxjploginapi.azurlane.jp";
    private static final int GATE_PORT = 80;
    private static final String CDN = "https://blhxstatic.yo-star.com/android";
    private static final String GAME_FILES = "/sdcard/Android/data/" + GAME_PACKAGE + "/files";
    private static final String TARGET_ROOT = GAME_FILES + "/AssetBundles";
    private static final int SHIZUKU_REQ = 7301;

    private int cSurface;
    private int cSurfaceContainer;
    private int cSurfaceContainerHigh;
    private int cOnSurface;
    private int cOnSurfaceVariant;
    private int cPrimary;
    private int cOutline;
    private int cGood;
    private int cWarn;
    private int cBad;

    private TabLayout tabs;
    private FrameLayout pageHost;
    private View downloadPage;
    private View folderPage;
    private View logPage;

    private TextView heroStatus;
    private TextView heroHint;
    private Chip shizukuChip;
    private Chip gameChip;
    private Chip folderChip;
    private LinearProgressIndicator progress;
    private TextView percentValue;
    private TextView downloadedValue;
    private TextView remainingValue;
    private TextView speedValue;
    private TextView filesValue;
    private TextView currentFileValue;
    private TextView writeNowValue;
    private MaterialButton grantButton;
    private MaterialButton startButton;
    private MaterialButton stopButton;
    private MaterialButton checkButton;
    private MaterialButton folderButton;
    private MaterialButton openGameButton;

    private TextView folderHeroStatus;
    private TextView destinationValue;
    private TextView folderFilesValue;
    private TextView folderSizeValue;
    private TextView lastSavedValue;
    private TextView folderListValue;
    private MaterialButton openFolderButton;
    private MaterialButton refreshFolderButton;

    private TextView details;

    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final AtomicBoolean diagnosticRunning = new AtomicBoolean(false);
    private final AtomicBoolean folderRefreshRunning = new AtomicBoolean(false);
    private final AtomicLong progressBytes = new AtomicLong(0);
    private final AtomicLong wireBytes = new AtomicLong(0);
    private final AtomicInteger completedFiles = new AtomicInteger(0);

    private volatile long totalAssetBytes = 0;
    private volatile long baseVerifiedBytes = 0;
    private volatile int totalAssetFiles = 0;
    private volatile int baseVerifiedFiles = 0;
    private volatile boolean diagnosticsDone = false;
    private volatile boolean gameInstalled = false;
    private volatile boolean gameFilesFound = false;
    private volatile boolean targetFound = false;
    private volatile boolean writeReady = false;
    private volatile boolean downloadRunning = false;

    private ExecutorService workers;
    private PowerManager.WakeLock wakeLock;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long lastSpeedSampleBytes = 0;
    private long lastSpeedSampleMs = 0;

    private final Runnable progressTicker = new Runnable() {
        @Override
        public void run() {
            if (!downloadRunning) return;
            long now = System.currentTimeMillis();
            long wire = wireBytes.get();
            long deltaMs = lastSpeedSampleMs == 0 ? 0 : now - lastSpeedSampleMs;
            long deltaBytes = wire - lastSpeedSampleBytes;
            long bps = deltaMs > 0 ? (deltaBytes * 1000L) / deltaMs : 0;
            lastSpeedSampleMs = now;
            lastSpeedSampleBytes = wire;
            renderProgress(bps);
            mainHandler.postDelayed(this, 250);
        }
    };

    private final Shizuku.OnRequestPermissionResultListener permissionListener = (requestCode, grantResult) -> {
        if (requestCode == SHIZUKU_REQ) {
            diagnosticsDone = false;
            runOnUiThread(this::refreshShizukuState);
        }
    };

    private final Shizuku.OnBinderReceivedListener binderReceivedListener =
            () -> runOnUiThread(this::refreshShizukuState);

    private final Shizuku.OnBinderDeadListener binderDeadListener =
            () -> runOnUiThread(this::refreshShizukuState);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        resolveColors();
        buildUi();

        Shizuku.addRequestPermissionResultListener(permissionListener);
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);

        refreshShizukuState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mainHandler.postDelayed(this::refreshShizukuState, 200);
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        Shizuku.removeRequestPermissionResultListener(permissionListener);
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        requestStop();
        releaseWakeLock();
        super.onDestroy();
    }

    private void resolveColors() {
        cSurface = themeColor(com.google.android.material.R.attr.colorSurface, Color.rgb(250, 250, 250));
        cSurfaceContainer = themeColor(com.google.android.material.R.attr.colorSurfaceContainer, Color.rgb(245, 245, 245));
        cSurfaceContainerHigh = themeColor(com.google.android.material.R.attr.colorSurfaceContainerHigh, Color.rgb(238, 238, 238));
        cOnSurface = themeColor(com.google.android.material.R.attr.colorOnSurface, Color.rgb(30, 30, 30));
        cOnSurfaceVariant = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.rgb(90, 90, 90));
        cPrimary = themeColor(com.google.android.material.R.attr.colorPrimary, Color.rgb(65, 105, 225));
        cOutline = themeColor(com.google.android.material.R.attr.colorOutlineVariant, blend(cOnSurfaceVariant, cSurface, 0.72f));
        cGood = Color.rgb(30, 150, 90);
        cWarn = Color.rgb(194, 124, 0);
        cBad = Color.rgb(210, 64, 64);
    }

    private int themeColor(int attr, int fallback) {
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(attr, tv, true)) {
            if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return tv.data;
            }
            if (tv.resourceId != 0) {
                try { return getColor(tv.resourceId); } catch (Throwable ignored) {}
            }
        }
        return fallback;
    }

    private static int blend(int fg, int bg, float bgWeight) {
        float fw = 1f - bgWeight;
        return Color.rgb(
                Math.round(Color.red(fg) * fw + Color.red(bg) * bgWeight),
                Math.round(Color.green(fg) * fw + Color.green(bg) * bgWeight),
                Math.round(Color.blue(fg) * fw + Color.blue(bg) * bgWeight));
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(cSurface);

        MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setTitle("Azur Lane JP");
        toolbar.setSubtitle("Resource Downloader • v0.3.0");
        toolbar.setTitleTextColor(cOnSurface);
        toolbar.setSubtitleTextColor(cOnSurfaceVariant);
        toolbar.setBackgroundColor(cSurface);
        toolbar.setPadding(dp(4), 0, dp(4), 0);
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(72)));

        tabs = new TabLayout(this);
        tabs.setTabMode(TabLayout.MODE_FIXED);
        tabs.setTabGravity(TabLayout.GRAVITY_FILL);
        tabs.setBackgroundColor(cSurface);
        tabs.addTab(tabs.newTab().setText("Загрузка"));
        tabs.addTab(tabs.newTab().setText("Папка"));
        tabs.addTab(tabs.newTab().setText("Журнал"));
        root.addView(tabs, new LinearLayout.LayoutParams(-1, dp(52)));

        pageHost = new FrameLayout(this);
        root.addView(pageHost, new LinearLayout.LayoutParams(-1, 0, 1f));

        downloadPage = buildDownloadPage();
        folderPage = buildFolderPage();
        logPage = buildLogPage();

        pageHost.addView(downloadPage, new FrameLayout.LayoutParams(-1, -1));
        pageHost.addView(folderPage, new FrameLayout.LayoutParams(-1, -1));
        pageHost.addView(logPage, new FrameLayout.LayoutParams(-1, -1));

        showPage(0);

        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                showPage(tab.getPosition());
                if (tab.getPosition() == 1) refreshFolderContents();
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {
                if (tab.getPosition() == 1) refreshFolderContents();
            }
        });

        setContentView(root);
    }

    private View buildDownloadPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(12), dp(10), dp(12), dp(12));

        MaterialCardView statusCard = card();
        LinearLayout sc = cardContent(statusCard);
        heroStatus = label("Проверяю Shizuku…", 22, cOnSurface, Typeface.BOLD);
        sc.addView(heroStatus);
        heroHint = label("Игра и папка будут проверены автоматически", 13, cOnSurfaceVariant, Typeface.NORMAL);
        heroHint.setPadding(0, dp(4), 0, dp(8));
        sc.addView(heroHint);

        ChipGroup chips = new ChipGroup(this);
        chips.setSingleLine(false);
        chips.setChipSpacingHorizontal(dp(6));
        chips.setChipSpacingVertical(dp(4));
        shizukuChip = statusChip("Shizuku …");
        gameChip = statusChip("Игра …");
        folderChip = statusChip("Папка …");
        chips.addView(shizukuChip);
        chips.addView(gameChip);
        chips.addView(folderChip);
        sc.addView(chips);
        page.addView(statusCard, lpMatchWrap(0, 0, 0, 8));

        MaterialCardView progressCard = card();
        LinearLayout pc = cardContent(progressCard);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView progressTitle = label("Общий прогресс", 15, cOnSurfaceVariant, Typeface.BOLD);
        top.addView(progressTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        percentValue = label("—", 30, cPrimary, Typeface.BOLD);
        top.addView(percentValue);
        pc.addView(top);

        progress = new LinearProgressIndicator(this);
        progress.setMax(1000);
        progress.setProgress(0);
        progress.setTrackThickness(dp(10));
        progress.setIndicatorColor(cPrimary);
        progress.setTrackColor(cSurfaceContainerHigh);
        pc.addView(progress, lpMatch(dp(10), 0, 8, 0, 12));

        LinearLayout stats1 = new LinearLayout(this);
        stats1.setOrientation(LinearLayout.HORIZONTAL);
        downloadedValue = addStat(stats1, "Готово", "—");
        remainingValue = addStat(stats1, "Осталось", "—");
        pc.addView(stats1);

        LinearLayout stats2 = new LinearLayout(this);
        stats2.setOrientation(LinearLayout.HORIZONTAL);
        speedValue = addStat(stats2, "Скорость", "0 B/с");
        filesValue = addStat(stats2, "Файлы", "—");
        pc.addView(stats2, lpMatchWrap(0, 7, 0, 0));

        TextView currentLabel = label("Сейчас", 11, cOnSurfaceVariant, Typeface.BOLD);
        currentLabel.setPadding(dp(2), dp(9), 0, dp(2));
        pc.addView(currentLabel);
        currentFileValue = label("Ожидание", 12, cOnSurface, Typeface.NORMAL);
        currentFileValue.setTypeface(Typeface.MONOSPACE);
        currentFileValue.setSingleLine(true);
        currentFileValue.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        pc.addView(currentFileValue);

        writeNowValue = label("Путь записи ещё не проверен", 12, cOnSurfaceVariant, Typeface.BOLD);
        writeNowValue.setSingleLine(true);
        writeNowValue.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        writeNowValue.setPadding(0, dp(6), 0, 0);
        pc.addView(writeNowValue);

        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        progressLp.setMargins(0, 0, 0, dp(8));
        page.addView(progressCard, progressLp);

        grantButton = materialButton("Разрешить Shizuku");
        grantButton.setOnClickListener(v -> requestShizukuPermission());
        page.addView(grantButton, lpMatchWrap(0, 0, 0, 7));

        startButton = materialButton("Скачать / докачать ресурсы");
        startButton.setOnClickListener(v -> startDownload());
        page.addView(startButton, lpMatchWrap(0, 0, 0, 7));

        stopButton = materialButton("Остановить загрузку");
        stopButton.setOnClickListener(v -> requestStop());
        page.addView(stopButton, lpMatchWrap(0, 0, 0, 7));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        checkButton = smallButton("Проверить");
        checkButton.setOnClickListener(v -> {
            diagnosticsDone = false;
            runDiagnostics();
        });
        actions.addView(checkButton, weightedButtonLp());

        folderButton = smallButton("Папка");
        folderButton.setOnClickListener(v -> {
            TabLayout.Tab t = tabs.getTabAt(1);
            if (t != null) t.select();
        });
        actions.addView(folderButton, weightedButtonLp());

        openGameButton = smallButton("Открыть игру");
        openGameButton.setOnClickListener(v -> openGame());
        actions.addView(openGameButton, weightedButtonLp());

        page.addView(actions, new LinearLayout.LayoutParams(-1, -2));

        updateButtons();
        return page;
    }

    private View buildFolderPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(12), dp(10), dp(12), dp(12));

        MaterialCardView pathCard = card();
        LinearLayout c = cardContent(pathCard);
        folderHeroStatus = label("Проверяю папку…", 20, cOnSurface, Typeface.BOLD);
        c.addView(folderHeroStatus);

        TextView pathLabel = label("ТОЧНАЯ ПАПКА ЗАГРУЗКИ", 11, cOnSurfaceVariant, Typeface.BOLD);
        pathLabel.setPadding(0, dp(8), 0, dp(3));
        c.addView(pathLabel);

        destinationValue = label(TARGET_ROOT, 12, cOnSurface, Typeface.NORMAL);
        destinationValue.setTypeface(Typeface.MONOSPACE);
        destinationValue.setTextIsSelectable(true);
        c.addView(destinationValue);

        LinearLayout folderStats = new LinearLayout(this);
        folderStats.setOrientation(LinearLayout.HORIZONTAL);
        folderFilesValue = addStat(folderStats, "Файлов", "—");
        folderSizeValue = addStat(folderStats, "Размер", "—");
        c.addView(folderStats, lpMatchWrap(0, 10, 0, 0));

        TextView lastLabel = label("Последняя подтверждённая запись", 11, cOnSurfaceVariant, Typeface.BOLD);
        lastLabel.setPadding(0, dp(10), 0, dp(2));
        c.addView(lastLabel);
        lastSavedValue = label("Пока ничего", 12, cOnSurface, Typeface.NORMAL);
        lastSavedValue.setTypeface(Typeface.MONOSPACE);
        lastSavedValue.setSingleLine(true);
        lastSavedValue.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        c.addView(lastSavedValue);

        page.addView(pathCard, lpMatchWrap(0, 0, 0, 8));

        LinearLayout folderActions = new LinearLayout(this);
        folderActions.setOrientation(LinearLayout.HORIZONTAL);

        openFolderButton = materialButton("Открыть папку");
        openFolderButton.setOnClickListener(v -> openFolderExternal());
        folderActions.addView(openFolderButton, weightedButtonLp());

        refreshFolderButton = smallButton("Обновить");
        refreshFolderButton.setOnClickListener(v -> refreshFolderContents());
        folderActions.addView(refreshFolderButton, weightedButtonLp());

        page.addView(folderActions, lpMatchWrap(0, 0, 0, 8));

        MaterialCardView listCard = card();
        LinearLayout listContent = cardContent(listCard);
        TextView listTitle = label("Содержимое AssetBundles", 14, cOnSurface, Typeface.BOLD);
        listContent.addView(listTitle);
        TextView listHint = label("Это реальное содержимое папки, прочитанное через Shizuku", 11, cOnSurfaceVariant, Typeface.NORMAL);
        listHint.setPadding(0, dp(2), 0, dp(7));
        listContent.addView(listHint);

        ScrollView scroll = new ScrollView(this);
        folderListValue = label("Нажми «Обновить»", 12, cOnSurfaceVariant, Typeface.NORMAL);
        folderListValue.setTypeface(Typeface.MONOSPACE);
        folderListValue.setTextIsSelectable(true);
        scroll.addView(folderListValue, new ScrollView.LayoutParams(-1, -2));
        listContent.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        page.addView(listCard, new LinearLayout.LayoutParams(-1, 0, 1f));
        return page;
    }

    private View buildLogPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(12), dp(10), dp(12), dp(12));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("Журнал работы", 20, cOnSurface, Typeface.BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        MaterialButton clear = smallButton("Очистить");
        clear.setOnClickListener(v -> details.setText(""));
        head.addView(clear);
        page.addView(head, lpMatchWrap(0, 0, 0, 8));

        MaterialCardView logCard = card();
        LinearLayout lc = cardContent(logCard);
        ScrollView scroll = new ScrollView(this);
        details = label("Запуск приложения…\n", 12, cOnSurfaceVariant, Typeface.NORMAL);
        details.setTypeface(Typeface.MONOSPACE);
        details.setTextIsSelectable(true);
        scroll.addView(details, new ScrollView.LayoutParams(-1, -2));
        lc.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        page.addView(logCard, new LinearLayout.LayoutParams(-1, 0, 1f));
        return page;
    }

    private MaterialCardView card() {
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(24));
        card.setCardBackgroundColor(cSurfaceContainer);
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(cOutline);
        card.setUseCompatPadding(false);
        card.setPreventCornerOverlap(true);
        return card;
    }

    private LinearLayout cardContent(MaterialCardView card) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(15), dp(13), dp(15), dp(13));
        card.addView(content, new FrameLayout.LayoutParams(-1, -1));
        return content;
    }

    private TextView label(String text, int sp, int color, int style) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setTypeface(Typeface.create(Typeface.DEFAULT, style));
        return v;
    }

    private Chip statusChip(String text) {
        Chip c = new Chip(this);
        c.setText(text);
        c.setCheckable(false);
        c.setClickable(false);
        c.setTextSize(12);
        c.setChipMinHeight(dp(34));
        paintChip(c, cOnSurfaceVariant);
        return c;
    }

    private void paintChip(Chip chip, int color) {
        chip.setTextColor(color);
        chip.setChipBackgroundColor(ColorStateList.valueOf(blend(color, cSurfaceContainer, 0.88f)));
        chip.setChipStrokeColor(ColorStateList.valueOf(blend(color, cSurfaceContainer, 0.65f)));
        chip.setChipStrokeWidth(dp(1));
    }

    private MaterialButton materialButton(String text) {
        MaterialButton b = new MaterialButton(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setMinHeight(dp(50));
        b.setCornerRadius(dp(18));
        return b;
    }

    private MaterialButton smallButton(String text) {
        MaterialButton b = new MaterialButton(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setMinHeight(dp(44));
        b.setCornerRadius(dp(16));
        b.setInsetTop(0);
        b.setInsetBottom(0);
        return b;
    }

    private LinearLayout.LayoutParams weightedButtonLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        return lp;
    }

    private TextView addStat(LinearLayout row, String labelText, String initial) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(8), dp(10), dp(8));
        box.setBackground(roundRect(cSurfaceContainerHigh, 16));

        TextView l = label(labelText, 10, cOnSurfaceVariant, Typeface.BOLD);
        TextView v = label(initial, 14, cOnSurface, Typeface.BOLD);
        v.setPadding(0, dp(2), 0, 0);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(l);
        box.addView(v);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        row.addView(box, lp);
        return v;
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private LinearLayout.LayoutParams lpMatchWrap(int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(l), dp(t), dp(r), dp(b));
        return lp;
    }

    private LinearLayout.LayoutParams lpMatch(int heightDp, int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(heightDp));
        lp.setMargins(dp(l), dp(t), dp(r), dp(b));
        return lp;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void showPage(int index) {
        downloadPage.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        folderPage.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        logPage.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
    }

    private void refreshShizukuState() {
        boolean binder = false;
        int perm = PackageManager.PERMISSION_DENIED;
        try {
            binder = Shizuku.pingBinder();
            if (binder) perm = Shizuku.checkSelfPermission();
        } catch (Throwable ignored) {}

        if (!binder) {
            setChip(shizukuChip, "Shizuku: не запущен", cBad);
            heroStatus.setText("Shizuku не подключен");
            heroHint.setText("Запусти Shizuku и вернись сюда");
            diagnosticsDone = false;
        } else if (perm != PackageManager.PERMISSION_GRANTED) {
            setChip(shizukuChip, "Shizuku: нужен доступ", cWarn);
            heroStatus.setText("Нужно разрешение Shizuku");
            heroHint.setText("Нажми кнопку ниже — появится системный запрос");
            diagnosticsDone = false;
        } else {
            setChip(shizukuChip, "Shizuku: готов ✓", cGood);
            if (!downloadRunning && !diagnosticsDone) {
                heroStatus.setText("Проверяю Azur Lane JP…");
                heroHint.setText("Ищу игру и точную папку AssetBundles");
            }
            if (!diagnosticsDone) runDiagnostics();
        }
        updateButtons();
    }

    private void setChip(Chip chip, String text, int color) {
        runOnUiThread(() -> {
            chip.setText(text);
            paintChip(chip, color);
        });
    }

    private void requestShizukuPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                heroStatus.setText("Сначала запусти Shizuku");
                return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                diagnosticsDone = false;
                refreshShizukuState();
                return;
            }
            Shizuku.requestPermission(SHIZUKU_REQ);
        } catch (Throwable e) {
            uiLog("Shizuku: " + shortMsg(e));
        }
    }

    private void runDiagnostics() {
        if (diagnosticRunning.getAndSet(true)) return;
        runOnUiThread(() -> {
            heroStatus.setText("Проверяю игру и папку…");
            heroHint.setText("Тестирую запись именно в AssetBundles");
            updateButtons();
        });

        new Thread(() -> {
            try {
                gameInstalled = isPackageInstalled(GAME_PACKAGE);
                setChip(gameChip, gameInstalled ? "Игра: найдена ✓" : "Игра: не найдена", gameInstalled ? cGood : cBad);

                if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    gameFilesFound = false;
                    targetFound = false;
                    writeReady = false;
                    setChip(folderChip, "Папка: нет Shizuku", cBad);
                    return;
                }

                String testFile = TARGET_ROOT + "/.azljp_write_test";
                String cmd =
                        "if [ -d " + sh(GAME_FILES) + " ]; then " +
                        "echo FILES=1; " +
                        "mkdir -p " + sh(TARGET_ROOT) + " 2>/dev/null; " +
                        "if [ -d " + sh(TARGET_ROOT) + " ]; then echo TARGET=1; else echo TARGET=0; fi; " +
                        "if ( printf 'ok' > " + sh(testFile) + " ) 2>/dev/null; then " +
                        "  if [ \"$(cat " + sh(testFile) + " 2>/dev/null)\" = \"ok\" ]; then echo WRITE=1; else echo WRITE=0; fi; " +
                        "  rm -f " + sh(testFile) + "; " +
                        "else echo WRITE=0; fi; " +
                        "else echo FILES=0; echo TARGET=0; echo WRITE=0; fi";

                Process p = shizukuProcess(new String[]{"sh", "-c", cmd});
                String out = readAll(p.getInputStream());
                p.waitFor();

                gameFilesFound = out.contains("FILES=1");
                targetFound = out.contains("TARGET=1");
                writeReady = out.contains("WRITE=1");

                if (!gameInstalled) {
                    setChip(folderChip, "Папка: нет игры", cBad);
                } else if (!gameFilesFound) {
                    setChip(folderChip, "Папка: запусти игру", cWarn);
                } else if (!targetFound) {
                    setChip(folderChip, "Папка: не создана", cBad);
                } else if (!writeReady) {
                    setChip(folderChip, "Папка: нет записи", cBad);
                } else {
                    setChip(folderChip, "Папка: готова ✓", cGood);
                }

                diagnosticsDone = true;

                runOnUiThread(() -> {
                    if (!gameInstalled) {
                        heroStatus.setText("Azur Lane JP не установлена");
                        heroHint.setText("Нужен оригинальный JP-клиент");
                        folderHeroStatus.setText("Игра не найдена");
                        writeNowValue.setText("Нет папки игры");
                        writeNowValue.setTextColor(cBad);
                    } else if (!gameFilesFound) {
                        heroStatus.setText("Запусти Azur Lane JP один раз");
                        heroHint.setText("После первого запуска появится папка files");
                        folderHeroStatus.setText("Папка игры ещё не создана");
                        writeNowValue.setText("Сначала запусти игру");
                        writeNowValue.setTextColor(cWarn);
                    } else if (!writeReady) {
                        heroStatus.setText("Нет записи в AssetBundles");
                        heroHint.setText("Shizuku видит игру, но тест записи не прошёл");
                        folderHeroStatus.setText("Нет доступа на запись");
                        writeNowValue.setText("Тест записи: ошибка");
                        writeNowValue.setTextColor(cBad);
                    } else {
                        heroStatus.setText(downloadRunning ? "Скачивание ресурсов…" : "Готов к загрузке ✓");
                        heroHint.setText("Папка найдена и запись в неё проверена");
                        folderHeroStatus.setText("AssetBundles открыта и доступна ✓");
                        writeNowValue.setText("Запись: " + TARGET_ROOT);
                        writeNowValue.setTextColor(cGood);
                    }
                    updateButtons();
                });

                uiLog("Проверка: игра=" + yesNo(gameInstalled)
                        + ", files=" + yesNo(gameFilesFound)
                        + ", AssetBundles=" + yesNo(targetFound)
                        + ", запись=" + yesNo(writeReady));

                if (writeReady) refreshFolderContents();

            } catch (Throwable e) {
                diagnosticsDone = false;
                writeReady = false;
                setChip(folderChip, "Папка: ошибка", cBad);
                uiLog("Диагностика: " + shortMsg(e));
                runOnUiThread(() -> {
                    heroStatus.setText("Ошибка проверки папки");
                    heroHint.setText(shortMsg(e));
                });
            } finally {
                diagnosticRunning.set(false);
                runOnUiThread(this::updateButtons);
            }
        }, "azl-diagnostics").start();
    }

    private boolean isPackageInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private void updateButtons() {
        if (grantButton == null) return;
        boolean binder = false;
        boolean granted = false;
        try {
            binder = Shizuku.pingBinder();
            granted = binder && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {}

        grantButton.setVisibility(binder && !granted && !downloadRunning ? View.VISIBLE : View.GONE);
        startButton.setVisibility(downloadRunning ? View.GONE : View.VISIBLE);
        stopButton.setVisibility(downloadRunning ? View.VISIBLE : View.GONE);

        startButton.setEnabled(granted && diagnosticsDone && gameInstalled && gameFilesFound && writeReady && !downloadRunning);
        stopButton.setEnabled(downloadRunning);
        checkButton.setEnabled(granted && !diagnosticRunning.get() && !downloadRunning);
        folderButton.setEnabled(granted && targetFound);
        openGameButton.setEnabled(gameInstalled && !downloadRunning);

        if (openFolderButton != null) openFolderButton.setEnabled(granted && targetFound);
        if (refreshFolderButton != null) refreshFolderButton.setEnabled(granted && targetFound && !folderRefreshRunning.get());
    }

    private void openGame() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(GAME_PACKAGE);
            if (i == null) {
                heroStatus.setText("Azur Lane JP не найдена");
                return;
            }
            startActivity(i);
        } catch (Throwable e) {
            uiLog("Не удалось открыть игру: " + shortMsg(e));
        }
    }

    private void openFolderExternal() {
        if (!targetFound) {
            folderHeroStatus.setText("Папка ещё не найдена");
            return;
        }

        String docId = "primary:Android/data/" + GAME_PACKAGE + "/files/AssetBundles";
        Uri uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", docId);

        try {
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR);
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivity(view);
            uiLog("Открываю AssetBundles во внешнем файловом менеджере");
            return;
        } catch (ActivityNotFoundException ignored) {
        } catch (Throwable e) {
            uiLog("ACTION_VIEW папки: " + shortMsg(e));
        }

        try {
            Intent tree = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            tree.putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri);
            tree.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivity(tree);
            uiLog("Открываю системный выбор папки прямо в AssetBundles");
        } catch (Throwable e) {
            folderHeroStatus.setText("Файловый менеджер не открыл Android/data");
            uiLog("Открытие папки: " + shortMsg(e));
        }
    }

    private void refreshFolderContents() {
        if (folderRefreshRunning.getAndSet(true)) return;
        runOnUiThread(() -> {
            if (folderListValue != null) folderListValue.setText("Читаю папку…");
            if (refreshFolderButton != null) refreshFolderButton.setEnabled(false);
        });

        new Thread(() -> {
            try {
                if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    throw new Exception("нет доступа Shizuku");
                }

                String cmd =
                        "if [ -d " + sh(TARGET_ROOT) + " ]; then " +
                        "COUNT=$(find " + sh(TARGET_ROOT) + " -type f ! -name '*.part' 2>/dev/null | wc -l); " +
                        "KB=$(du -sk " + sh(TARGET_ROOT) + " 2>/dev/null | awk '{print $1}'); " +
                        "echo COUNT=$COUNT; echo KB=$KB; echo __ITEMS__; " +
                        "for x in " + sh(TARGET_ROOT) + "/*; do " +
                        "  [ -e \"$x\" ] || continue; n=$(basename \"$x\"); " +
                        "  if [ -d \"$x\" ]; then printf 'D\\t%s\\n' \"$n\"; " +
                        "  else s=$(stat -c %s \"$x\" 2>/dev/null); printf 'F\\t%s\\t%s\\n' \"$n\" \"$s\"; fi; " +
                        "done | head -80; " +
                        "else echo MISSING=1; fi";

                Process p = shizukuProcess(new String[]{"sh", "-c", cmd});
                String out = readAll(p.getInputStream());
                p.waitFor();

                if (out.contains("MISSING=1")) {
                    targetFound = false;
                    runOnUiThread(() -> {
                        folderHeroStatus.setText("AssetBundles не найдена");
                        folderFilesValue.setText("0");
                        folderSizeValue.setText("0 B");
                        folderListValue.setText("Папки пока нет.");
                    });
                    return;
                }

                int count = 0;
                long kb = 0;
                StringBuilder items = new StringBuilder();
                boolean itemMode = false;
                for (String line : out.split("\\n")) {
                    if (line.startsWith("COUNT=")) {
                        try { count = Integer.parseInt(line.substring(6).trim()); } catch (Throwable ignored) {}
                    } else if (line.startsWith("KB=")) {
                        try { kb = Long.parseLong(line.substring(3).trim()); } catch (Throwable ignored) {}
                    } else if (line.equals("__ITEMS__")) {
                        itemMode = true;
                    } else if (itemMode && !line.isEmpty()) {
                        String[] p2 = line.split("\\t", 3);
                        if (p2.length >= 2 && "D".equals(p2[0])) {
                            items.append("📁 ").append(p2[1]).append("/\n");
                        } else if (p2.length >= 3 && "F".equals(p2[0])) {
                            long sz = 0;
                            try { sz = Long.parseLong(p2[2]); } catch (Throwable ignored) {}
                            items.append("• ").append(p2[1]).append("   ").append(human(sz)).append("\n");
                        }
                    }
                }

                final int countFinal = count;
                final long sizeFinal = kb * 1024L;
                final String itemsFinal = items.length() == 0 ? "(папка пустая)" : items.toString().trim();

                runOnUiThread(() -> {
                    targetFound = true;
                    folderHeroStatus.setText("AssetBundles открыта ✓");
                    folderFilesValue.setText(String.valueOf(countFinal));
                    folderSizeValue.setText(human(sizeFinal));
                    folderListValue.setText(itemsFinal);
                    updateButtons();
                });

            } catch (Throwable e) {
                uiLog("Папка: " + shortMsg(e));
                runOnUiThread(() -> {
                    if (folderHeroStatus != null) folderHeroStatus.setText("Ошибка чтения папки");
                    if (folderListValue != null) folderListValue.setText(shortMsg(e));
                });
            } finally {
                folderRefreshRunning.set(false);
                runOnUiThread(this::updateButtons);
            }
        }, "azl-folder").start();
    }

    private void startDownload() {
        if (downloadRunning) return;
        if (!diagnosticsDone || !gameInstalled || !gameFilesFound || !writeReady) {
            runDiagnostics();
            return;
        }

        try {
            if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                refreshShizukuState();
                return;
            }
        } catch (Throwable e) {
            refreshShizukuState();
            return;
        }

        stopRequested.set(false);
        downloadRunning = true;
        progressBytes.set(0);
        wireBytes.set(0);
        completedFiles.set(0);
        totalAssetBytes = 0;
        baseVerifiedBytes = 0;
        totalAssetFiles = 0;
        baseVerifiedFiles = 0;
        lastSpeedSampleBytes = 0;
        lastSpeedSampleMs = System.currentTimeMillis();

        runOnUiThread(() -> {
            progress.setIndeterminate(true);
            percentValue.setText("…");
            downloadedValue.setText("Проверка");
            remainingValue.setText("—");
            speedValue.setText("0 B/с");
            filesValue.setText("—");
            currentFileValue.setText("Получаю список ресурсов JP…");
            writeNowValue.setText("Назначение: AssetBundles ✓");
            writeNowValue.setTextColor(cGood);
            heroStatus.setText("Получаю список ресурсов…");
            heroHint.setText("Затем проверю уже скачанные файлы");
            updateButtons();
        });

        acquireWakeLock();

        new Thread(() -> {
            try {
                List<String> versions = requestVersionStrings();
                if (versions.isEmpty()) throw new Exception("сервер версии не вернул список ресурсов");

                LinkedHashMap<String, Asset> all = new LinkedHashMap<>();
                int groupCount = 0;
                for (String raw : versions) {
                    if (stopRequested.get()) return;
                    String type = versionType(raw);
                    if (!isSupportedType(type)) continue;
                    groupCount++;
                    uiLog("Список: " + type);
                    for (Asset a : loadHashList(raw)) all.put(a.path, a);
                }

                if (all.isEmpty()) throw new Exception("не удалось получить hashes*.csv");

                long allBytes = 0;
                for (Asset a : all.values()) allBytes += a.size;
                totalAssetBytes = allBytes;
                totalAssetFiles = all.size();

                runOnUiThread(() -> {
                    heroStatus.setText("Проверяю файлы на телефоне…");
                    heroHint.setText("Сверяю размер каждого ресурса");
                    currentFileValue.setText("Сканирую AssetBundles");
                });

                Map<String, Long> existing = listExistingFiles();
                List<Asset> need = new ArrayList<>();
                long verifiedBytes = 0;
                int verifiedFiles = 0;

                for (Asset a : all.values()) {
                    Long sz = existing.get(a.path);
                    if (sz != null && sz == a.size) {
                        verifiedFiles++;
                        verifiedBytes += a.size;
                    } else {
                        need.add(a);
                    }
                }

                baseVerifiedBytes = verifiedBytes;
                baseVerifiedFiles = verifiedFiles;

                runOnUiThread(() -> {
                    progress.setIndeterminate(false);
                    progress.setMax(1000);
                    heroStatus.setText(need.isEmpty() ? "Все ресурсы уже скачаны ✓" : "Скачивание ресурсов…");
                    heroHint.setText("Запись идёт прямо в com.YoStarJP.AzurLane/files/AssetBundles");
                    renderProgress(0);
                });

                uiLog("Групп: " + groupCount + ", всего файлов: " + totalAssetFiles
                        + ", уже готово: " + baseVerifiedFiles + ", скачать: " + need.size());

                if (need.isEmpty()) {
                    runOnUiThread(() -> {
                        currentFileValue.setText("Все файлы актуальны");
                        writeNowValue.setText("AssetBundles полностью готова ✓");
                        writeNowValue.setTextColor(cGood);
                    });
                    return;
                }

                lastSpeedSampleBytes = 0;
                lastSpeedSampleMs = System.currentTimeMillis();
                mainHandler.removeCallbacks(progressTicker);
                mainHandler.post(progressTicker);

                workers = Executors.newFixedThreadPool(6);
                List<Future<?>> futures = new ArrayList<>();
                AtomicInteger failedFiles = new AtomicInteger();

                for (Asset asset : need) {
                    futures.add(workers.submit(() -> {
                        if (stopRequested.get()) return;
                        try {
                            uiCurrentFile(asset.path);
                            downloadWithRetry(asset);
                            completedFiles.incrementAndGet();
                            uiSavedFile(asset.path, asset.size);
                        } catch (Exception e) {
                            if (!stopRequested.get()) {
                                failedFiles.incrementAndGet();
                                uiLog("Ошибка: " + asset.path + " — " + shortMsg(e));
                            }
                        }
                    }));
                }

                for (Future<?> f : futures) {
                    if (stopRequested.get()) break;
                    try { f.get(); } catch (Exception ignored) {}
                }

                if (stopRequested.get()) {
                    uiHero("Остановлено • можно продолжить позже", "Готовые файлы сохранены в AssetBundles");
                } else if (failedFiles.get() == 0) {
                    baseVerifiedBytes = totalAssetBytes;
                    baseVerifiedFiles = totalAssetFiles;
                    progressBytes.set(0);
                    completedFiles.set(0);
                    runOnUiThread(() -> {
                        renderProgress(0);
                        currentFileValue.setText("Готово");
                        writeNowValue.setText("Все файлы записаны и проверены ✓");
                        writeNowValue.setTextColor(cGood);
                        heroStatus.setText("Готово ✓");
                        heroHint.setText("Можно запускать Azur Lane JP");
                    });
                    refreshFolderContents();
                } else {
                    int failed = failedFiles.get();
                    uiHero("Есть ошибки: " + failed, "Нажми «Скачать» ещё раз — докачаются только недостающие");
                }

            } catch (Exception e) {
                uiHero("Ошибка загрузки", shortMsg(e));
                uiLog(stackSummary(e));
            } finally {
                mainHandler.removeCallbacks(progressTicker);
                if (workers != null) workers.shutdownNow();
                workers = null;
                downloadRunning = false;
                releaseWakeLock();
                runOnUiThread(() -> {
                    if (progress.isIndeterminate()) progress.setIndeterminate(false);
                    renderProgress(0);
                    updateButtons();
                });
            }
        }, "azl-main").start();
    }

    private void downloadWithRetry(Asset asset) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            if (stopRequested.get()) throw new Exception("остановлено");
            try {
                downloadOne(asset);
                return;
            } catch (Exception e) {
                last = e;
                if (stopRequested.get()) throw e;
                if (attempt < 3) {
                    uiLog("Повтор " + attempt + "/2: " + asset.path + " — " + shortMsg(e));
                    try {
                        Thread.sleep(500L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new Exception("остановлено");
                    }
                }
            }
        }
        throw last != null ? last : new Exception("ошибка загрузки");
    }

    private void downloadOne(Asset a) throws Exception {
        if (stopRequested.get()) throw new Exception("остановлено");

        String finalPath = TARGET_ROOT + "/" + a.path;
        String partPath = finalPath + ".part";
        String parent = finalPath.substring(0, finalPath.lastIndexOf('/'));

        HttpURLConnection c = null;
        Process p = null;
        long count = 0;
        boolean committed = false;

        try {
            URL url = new URL(CDN + "/resource/" + a.md5);
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "AzurLaneJPDownloader-Android/0.3");
            c.setRequestProperty("Connection", "keep-alive");
            c.setUseCaches(false);

            int code = c.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);

            long len = c.getContentLengthLong();
            if (len > 0 && len != a.size) {
                throw new Exception("неверный размер " + len + "/" + a.size);
            }

            String cmd = "mkdir -p " + sh(parent)
                    + " && cat > " + sh(partPath)
                    + " && test \"$(stat -c %s " + sh(partPath) + " 2>/dev/null)\" = \"" + a.size + "\""
                    + " && mv -f " + sh(partPath) + " " + sh(finalPath)
                    + " && test \"$(stat -c %s " + sh(finalPath) + " 2>/dev/null)\" = \"" + a.size + "\"";

            p = shizukuProcess(new String[]{"sh", "-c", cmd});

            try (InputStream in = new BufferedInputStream(c.getInputStream(), 256 * 1024);
                 OutputStream out = p.getOutputStream()) {
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (stopRequested.get()) throw new Exception("остановлено");
                    if (n == 0) continue;
                    out.write(buf, 0, n);
                    count += n;
                    progressBytes.addAndGet(n);
                    wireBytes.addAndGet(n);
                }
            }

            int exit = p.waitFor();
            if (count != a.size) throw new Exception("неполный файл " + count + "/" + a.size);
            if (exit != 0) {
                throw new Exception("файл не подтвердился в AssetBundles: " + readAll(p.getErrorStream()));
            }
            committed = true;

        } catch (Exception e) {
            if (!committed && count > 0) progressBytes.addAndGet(-count);
            cleanupPart(partPath);
            throw e;
        } finally {
            if (c != null) c.disconnect();
            if (p != null && stopRequested.get()) {
                try { p.destroy(); } catch (Throwable ignored) {}
            }
        }
    }

    private void cleanupPart(String partPath) {
        try {
            Process p = shizukuProcess(new String[]{"sh", "-c", "rm -f " + sh(partPath)});
            p.waitFor();
        } catch (Throwable ignored) {}
    }

    private void renderProgress(long bps) {
        if (progress == null) return;

        long done = baseVerifiedBytes + Math.max(0, progressBytes.get());
        if (totalAssetBytes > 0) done = Math.min(totalAssetBytes, done);
        int fileDone = baseVerifiedFiles + Math.max(0, completedFiles.get());
        if (totalAssetFiles > 0) fileDone = Math.min(totalAssetFiles, fileDone);

        final long doneFinal = done;
        final long remain = totalAssetBytes > 0 ? Math.max(0, totalAssetBytes - done) : 0;
        final int fileDoneFinal = fileDone;

        runOnUiThread(() -> {
            if (totalAssetBytes > 0) {
                int value = (int) Math.min(1000, (doneFinal * 1000L) / totalAssetBytes);
                progress.setProgressCompat(value, true);
                percentValue.setText(String.format(Locale.US, "%.1f%%", value / 10.0));
                downloadedValue.setText(human(doneFinal));
                remainingValue.setText(human(remain));
            } else {
                percentValue.setText("—");
                downloadedValue.setText("—");
                remainingValue.setText("—");
            }

            speedValue.setText(human(Math.max(0, bps)) + "/с");
            filesValue.setText(totalAssetFiles > 0 ? fileDoneFinal + " / " + totalAssetFiles : "—");
        });
    }

    private void uiSavedFile(String path, long size) {
        runOnUiThread(() -> {
            String shortPath = path;
            int slash = shortPath.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < shortPath.length()) shortPath = shortPath.substring(slash + 1);
            writeNowValue.setText("✓ Записан: " + shortPath + " • " + human(size));
            writeNowValue.setTextColor(cGood);
            lastSavedValue.setText(path + " • " + human(size));
            folderHeroStatus.setText("Запись в AssetBundles подтверждена ✓");
        });
    }

    private void requestStop() {
        if (!downloadRunning) return;
        stopRequested.set(true);
        mainHandler.removeCallbacks(progressTicker);
        if (workers != null) workers.shutdownNow();
        uiHero("Останавливаю…", "Уже записанные файлы останутся в AssetBundles");
    }

    private List<String> requestVersionStrings() throws Exception {
        try (Socket socket = new Socket(GATE_HOST, GATE_PORT)) {
            socket.setSoTimeout(15000);
            byte[] payload = new byte[]{0x08, 0x15, 0x12, 0x01, 0x30};
            int total = payload.length + 5;
            byte[] header = new byte[7];
            header[0] = (byte) ((total >> 8) & 0xff);
            header[1] = (byte) (total & 0xff);
            header[2] = 0;
            header[3] = 0x2A;
            header[4] = 0x30;
            header[5] = 0;
            header[6] = 0;

            OutputStream out = socket.getOutputStream();
            out.write(header);
            out.write(payload);
            out.flush();

            byte[] rh = readExact(socket.getInputStream(), 7);
            int payloadSize = (((rh[0] & 0xff) << 8) | (rh[1] & 0xff)) - 5;
            if (payloadSize <= 0 || payloadSize > 1024 * 1024) throw new Exception("неверный ответ gate");

            byte[] body = readExact(socket.getInputStream(), payloadSize);
            return parseRepeatedStringField(body, 4);
        }
    }

    private List<Asset> loadHashList(String rawVersion) throws Exception {
        String encoded = rawVersion.replace(" ", "%20");
        URL url = new URL(CDN + "/hash/" + encoded);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "AzurLaneJPDownloader-Android/0.3");

        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new Exception("hash HTTP " + code + " для " + versionType(rawVersion));
        }

        List<Asset> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) continue;
                String[] p = line.split(",", 3);
                if (p.length != 3) continue;
                String path = p[0].replace('\\', '/');
                long size;
                try { size = Long.parseLong(p[1]); } catch (Exception e) { continue; }
                out.add(new Asset(path, size, p[2].trim()));
            }
        } finally {
            c.disconnect();
        }
        return out;
    }

    private Map<String, Long> listExistingFiles() throws Exception {
        String cmd = "if [ -d " + sh(TARGET_ROOT) + " ]; then "
                + "cd " + sh(TARGET_ROOT)
                + " && find . -type f ! -name '*.part' -exec stat -c '%n\\t%s' {} \\; 2>/dev/null; fi";

        Process p = shizukuProcess(new String[]{"sh", "-c", cmd});
        Map<String, Long> map = new ConcurrentHashMap<>();

        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                int tab = line.lastIndexOf('\t');
                if (tab <= 0) continue;
                String path = line.substring(0, tab);
                if (path.startsWith("./")) path = path.substring(2);
                try { map.put(path, Long.parseLong(line.substring(tab + 1))); } catch (Exception ignored) {}
            }
        }

        p.waitFor();
        return map;
    }

    private Process shizukuProcess(String[] cmd) throws Exception {
        Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
        m.setAccessible(true);
        return (Process) m.invoke(null, cmd, null, null);
    }

    private static byte[] readExact(InputStream in, int n) throws Exception {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) throw new Exception("соединение закрыто");
            off += r;
        }
        return b;
    }

    private static List<String> parseRepeatedStringField(byte[] data, int wantedField) throws Exception {
        List<String> out = new ArrayList<>();
        int[] pos = {0};

        while (pos[0] < data.length) {
            long key = readVarint(data, pos);
            int field = (int) (key >> 3);
            int wire = (int) (key & 7);

            if (wire == 0) {
                readVarint(data, pos);
            } else if (wire == 1) {
                pos[0] += 8;
            } else if (wire == 2) {
                int len = (int) readVarint(data, pos);
                if (len < 0 || pos[0] + len > data.length) throw new Exception("protobuf length");
                if (field == wantedField) out.add(new String(data, pos[0], len, StandardCharsets.UTF_8));
                pos[0] += len;
            } else if (wire == 5) {
                pos[0] += 4;
            } else {
                throw new Exception("protobuf wire " + wire);
            }
        }
        return out;
    }

    private static long readVarint(byte[] data, int[] pos) throws Exception {
        long result = 0;
        int shift = 0;
        while (pos[0] < data.length && shift < 64) {
            int b = data[pos[0]++] & 0xff;
            result |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) return result;
            shift += 7;
        }
        throw new Exception("bad varint");
    }

    private static String versionType(String raw) {
        String[] p = raw.split("\\$");
        return p.length > 1 ? p[1].toLowerCase(Locale.ROOT) : "unknown";
    }

    private static boolean isSupportedType(String s) {
        return s.equals("azhash") || s.equals("cvhash") || s.equals("l2dhash") || s.equals("pichash") ||
                s.equals("bgmhash") || s.equals("cipherhash") || s.equals("mangahash") || s.equals("paintinghash") ||
                s.equals("dormhash") || s.equals("maphash");
    }

    private static String sh(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static String shortMsg(Throwable e) {
        String s = e.getMessage();
        return s == null || s.isEmpty() ? e.getClass().getSimpleName() : s;
    }

    private static String stackSummary(Throwable e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.getClass().getSimpleName()).append(": ").append(shortMsg(e));
        Throwable c = e.getCause();
        if (c != null) sb.append("\nПричина: ").append(c.getClass().getSimpleName()).append(": ").append(shortMsg(c));
        return sb.toString();
    }

    private static String yesNo(boolean v) {
        return v ? "да" : "нет";
    }

    private static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double v = bytes;
        String[] u = {"KB", "MB", "GB", "TB"};
        int i = -1;
        do { v /= 1024.0; i++; } while (v >= 1024 && i < u.length - 1);
        return String.format(Locale.US, v >= 100 ? "%.0f %s" : v >= 10 ? "%.1f %s" : "%.2f %s", v, u[i]);
    }

    private void uiHero(String title, String subtitle) {
        runOnUiThread(() -> {
            heroStatus.setText(title);
            heroHint.setText(subtitle);
        });
    }

    private void uiCurrentFile(String s) {
        runOnUiThread(() -> currentFileValue.setText(s));
    }

    private void uiLog(String s) {
        runOnUiThread(() -> {
            if (details == null) return;
            details.append(s + "\n");
            if (details.length() > 24000) {
                String text = details.getText().toString();
                int cut = text.indexOf('\n', 8000);
                if (cut > 0) details.setText(text.substring(cut + 1));
            }
        });
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "azljp:download");
            wakeLock.acquire(6 * 60 * 60 * 1000L);
        } catch (Throwable ignored) {}
    }

    private void releaseWakeLock() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Throwable ignored) {}
        wakeLock = null;
    }

    private static class Asset {
        final String path;
        final long size;
        final String md5;

        Asset(String path, long size, String md5) {
            this.path = path;
            this.size = size;
            this.md5 = md5;
        }
    }
}
