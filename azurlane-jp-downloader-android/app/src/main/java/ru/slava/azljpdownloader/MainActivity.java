package ru.slava.azljpdownloader;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.PowerManager;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;

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

    private final int bg;
    private final int surface;
    private final int surface2;
    private final int textPrimary;
    private final int textSecondary;
    private final int accent;
    private final int accentSoft;
    private final int good;
    private final int warn;
    private final int bad;
    private final int border;

    private TextView heroStatus;
    private TextView shizukuValue;
    private TextView gameValue;
    private TextView folderValue;
    private TextView assetFolderValue;
    private TextView writeValue;
    private TextView destinationValue;
    private TextView cdnValue;
    private TextView filesValue;
    private TextView sizeValue;
    private TextView downloadedValue;
    private TextView speedValue;
    private TextView currentFileValue;
    private TextView percentValue;
    private TextView details;
    private ProgressBar progress;
    private Button grantButton;
    private Button checkButton;
    private Button startButton;
    private Button stopButton;
    private Button openGameButton;

    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final AtomicBoolean diagnosticRunning = new AtomicBoolean(false);
    private final AtomicLong downloadedBytes = new AtomicLong(0);
    private volatile long totalBytes = 0;
    private volatile long startedAt = 0;
    private volatile boolean diagnosticsDone = false;
    private volatile boolean gameInstalled = false;
    private volatile boolean gameFilesFound = false;
    private volatile boolean targetFound = false;
    private volatile boolean writeReady = false;
    private volatile boolean downloadRunning = false;
    private ExecutorService workers;
    private PowerManager.WakeLock wakeLock;

    private final Shizuku.OnRequestPermissionResultListener permissionListener = (requestCode, grantResult) -> {
        if (requestCode == SHIZUKU_REQ) {
            diagnosticsDone = false;
            runOnUiThread(this::refreshShizukuState);
        }
    };

    public MainActivity() {
        boolean dark = false;
        // Values are initialized to safe defaults here; real palette is resolved in onCreate.
        bg = Color.TRANSPARENT;
        surface = Color.TRANSPARENT;
        surface2 = Color.TRANSPARENT;
        textPrimary = Color.TRANSPARENT;
        textSecondary = Color.TRANSPARENT;
        accent = Color.TRANSPARENT;
        accentSoft = Color.TRANSPARENT;
        good = Color.TRANSPARENT;
        warn = Color.TRANSPARENT;
        bad = Color.TRANSPARENT;
        border = Color.TRANSPARENT;
    }

    private int pBg, pSurface, pSurface2, pTextPrimary, pTextSecondary, pAccent, pAccentSoft, pGood, pWarn, pBad, pBorder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        resolvePalette();
        Window w = getWindow();
        w.setStatusBarColor(pBg);
        w.setNavigationBarColor(pBg);
        buildUi();
        Shizuku.addRequestPermissionResultListener(permissionListener);
        refreshShizukuState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (heroStatus != null) getWindow().getDecorView().postDelayed(this::refreshShizukuState, 250);
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener);
        requestStop();
        releaseWakeLock();
        super.onDestroy();
    }

    private void resolvePalette() {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        pBg = dark ? Color.rgb(14, 17, 23) : Color.rgb(246, 248, 252);
        pSurface = dark ? Color.rgb(25, 29, 38) : Color.WHITE;
        pSurface2 = dark ? Color.rgb(31, 36, 47) : Color.rgb(241, 244, 249);
        pTextPrimary = dark ? Color.rgb(240, 243, 250) : Color.rgb(25, 31, 43);
        pTextSecondary = dark ? Color.rgb(170, 179, 194) : Color.rgb(101, 112, 130);
        pAccent = resolveThemeColor(android.R.attr.colorAccent, dark ? Color.rgb(126, 163, 255) : Color.rgb(65, 105, 225));
        pAccentSoft = blend(pAccent, pBg, dark ? 0.76f : 0.88f);
        pGood = dark ? Color.rgb(84, 214, 139) : Color.rgb(18, 143, 83);
        pWarn = dark ? Color.rgb(255, 196, 87) : Color.rgb(184, 116, 0);
        pBad = dark ? Color.rgb(255, 119, 119) : Color.rgb(202, 52, 52);
        pBorder = dark ? Color.rgb(48, 55, 68) : Color.rgb(222, 228, 238);
    }

    private int resolveThemeColor(int attr, int fallback) {
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(attr, tv, true)) {
            if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) return tv.data;
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
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(pBg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        LinearLayout hero = card(pAccentSoft, pAccentSoft, 20);
        hero.setPadding(dp(18), dp(18), dp(18), dp(18));
        TextView eyebrow = text("AZUR LANE • JP", 12, pAccent, Typeface.BOLD);
        hero.addView(eyebrow);
        TextView title = text("Resource Downloader", 27, pTextPrimary, Typeface.BOLD);
        title.setPadding(0, dp(3), 0, 0);
        hero.addView(title);
        TextView subtitle = text("Быстрая загрузка ресурсов напрямую с CDN игры", 14, pTextSecondary, Typeface.NORMAL);
        subtitle.setPadding(0, dp(6), 0, dp(14));
        hero.addView(subtitle);
        heroStatus = text("Проверяю Shizuku…", 16, pTextPrimary, Typeface.BOLD);
        heroStatus.setBackground(roundRect(pSurface, pSurface, 14));
        heroStatus.setPadding(dp(12), dp(10), dp(12), dp(10));
        hero.addView(heroStatus, new LinearLayout.LayoutParams(-1, -2));
        root.addView(hero, fullMargins(0, 0, 0, 18));

        root.addView(sectionTitle("ГОТОВНОСТЬ"));
        LinearLayout readiness = card(pSurface, pBorder, 18);
        shizukuValue = addStatusRow(readiness, "Shizuku", "Проверка…");
        gameValue = addStatusRow(readiness, "Azur Lane JP", "Проверка…");
        folderValue = addStatusRow(readiness, "Папка игры", "Проверка…");
        assetFolderValue = addStatusRow(readiness, "AssetBundles", "Проверка…");
        writeValue = addStatusRow(readiness, "Доступ на запись", "Проверка…");
        root.addView(readiness, fullMargins(0, 8, 0, 18));

        root.addView(sectionTitle("КУДА СКАЧИВАЕТ"));
        LinearLayout destCard = card(pSurface, pBorder, 18);
        TextView destLabel = text("Папка назначения", 13, pTextSecondary, Typeface.BOLD);
        destCard.addView(destLabel);
        destinationValue = text(TARGET_ROOT, 14, pTextPrimary, Typeface.NORMAL);
        destinationValue.setTypeface(Typeface.MONOSPACE);
        destinationValue.setTextIsSelectable(true);
        destinationValue.setPadding(0, dp(7), 0, dp(14));
        destCard.addView(destinationValue);
        TextView cdnLabel = text("Источник", 13, pTextSecondary, Typeface.BOLD);
        destCard.addView(cdnLabel);
        cdnValue = text("blhxstatic.yo-star.com • официальный CDN JP", 14, pTextPrimary, Typeface.NORMAL);
        cdnValue.setPadding(0, dp(7), 0, 0);
        destCard.addView(cdnValue);
        root.addView(destCard, fullMargins(0, 8, 0, 18));

        root.addView(sectionTitle("ЗАГРУЗКА"));
        LinearLayout progressCard = card(pSurface, pBorder, 18);
        LinearLayout progressHeader = new LinearLayout(this);
        progressHeader.setOrientation(LinearLayout.HORIZONTAL);
        TextView progressTitle = text("Ресурсы JP", 18, pTextPrimary, Typeface.BOLD);
        progressHeader.addView(progressTitle, new LinearLayout.LayoutParams(0, -2, 1));
        percentValue = text("0%", 18, pAccent, Typeface.BOLD);
        progressHeader.addView(percentValue);
        progressCard.addView(progressHeader);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgress(0);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(pAccent));
        progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(pSurface2));
        progressCard.addView(progress, fullMargins(0, 12, 0, 14));

        LinearLayout stats1 = new LinearLayout(this);
        stats1.setOrientation(LinearLayout.HORIZONTAL);
        filesValue = statTile(stats1, "Файлы", "—");
        sizeValue = statTile(stats1, "Нужно скачать", "—");
        progressCard.addView(stats1);

        LinearLayout stats2 = new LinearLayout(this);
        stats2.setOrientation(LinearLayout.HORIZONTAL);
        downloadedValue = statTile(stats2, "Скачано", "0 B");
        speedValue = statTile(stats2, "Скорость", "0 B/с");
        LinearLayout.LayoutParams sm = new LinearLayout.LayoutParams(-1, -2);
        sm.setMargins(0, dp(8), 0, 0);
        progressCard.addView(stats2, sm);

        TextView currentLabel = text("Текущий файл", 12, pTextSecondary, Typeface.BOLD);
        currentLabel.setPadding(0, dp(14), 0, dp(4));
        progressCard.addView(currentLabel);
        currentFileValue = text("Ожидание", 13, pTextPrimary, Typeface.NORMAL);
        currentFileValue.setTypeface(Typeface.MONOSPACE);
        currentFileValue.setSingleLine(true);
        currentFileValue.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        progressCard.addView(currentFileValue);
        root.addView(progressCard, fullMargins(0, 8, 0, 18));

        root.addView(sectionTitle("УПРАВЛЕНИЕ"));
        LinearLayout actions = card(pSurface, pBorder, 18);
        grantButton = actionButton("Разрешить Shizuku", false);
        grantButton.setOnClickListener(v -> requestShizukuPermission());
        actions.addView(grantButton, fullMargins(0, 0, 0, 8));

        checkButton = actionButton("Проверить игру и папки", false);
        checkButton.setOnClickListener(v -> {
            diagnosticsDone = false;
            runDiagnostics();
        });
        actions.addView(checkButton, fullMargins(0, 0, 0, 8));

        startButton = actionButton("Скачать / докачать ресурсы JP", true);
        startButton.setOnClickListener(v -> startDownload());
        actions.addView(startButton, fullMargins(0, 0, 0, 8));

        stopButton = actionButton("Остановить", false);
        stopButton.setOnClickListener(v -> requestStop());
        actions.addView(stopButton, fullMargins(0, 0, 0, 8));

        openGameButton = actionButton("Открыть Azur Lane JP", false);
        openGameButton.setOnClickListener(v -> openGame());
        actions.addView(openGameButton);
        root.addView(actions, fullMargins(0, 8, 0, 18));

        root.addView(sectionTitle("ЖУРНАЛ"));
        LinearLayout logCard = card(pSurface, pBorder, 18);
        details = text("Запуск приложения…\n", 12, pTextSecondary, Typeface.NORMAL);
        details.setTypeface(Typeface.MONOSPACE);
        details.setMovementMethod(new ScrollingMovementMethod());
        details.setMinLines(4);
        details.setMaxLines(9);
        details.setTextIsSelectable(true);
        logCard.addView(details, new LinearLayout.LayoutParams(-1, -2));
        root.addView(logCard, fullMargins(0, 8, 0, 0));

        setContentView(scroll);
        updateButtons();
    }

    private LinearLayout card(int fill, int stroke, int radiusDp) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(15), dp(16), dp(15));
        l.setBackground(roundRect(fill, stroke, radiusDp));
        return l;
    }

    private GradientDrawable roundRect(int fill, int stroke, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (stroke != fill) d.setStroke(dp(1), stroke);
        return d;
    }

    private TextView sectionTitle(String s) {
        TextView v = text(s, 12, pTextSecondary, Typeface.BOLD);
        v.setLetterSpacing(0.08f);
        v.setPadding(dp(2), 0, 0, 0);
        return v;
    }

    private TextView text(String s, int sp, int color, int style) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setTypeface(Typeface.create(Typeface.DEFAULT, style));
        return v;
    }

    private TextView addStatusRow(LinearLayout parent, String label, String initial) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(9), 0, dp(9));
        TextView left = text(label, 15, pTextPrimary, Typeface.NORMAL);
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView right = text(initial, 14, pTextSecondary, Typeface.BOLD);
        right.setGravity(Gravity.END);
        row.addView(right, new LinearLayout.LayoutParams(-2, -2));
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return right;
    }

    private TextView statTile(LinearLayout parent, String label, String initial) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(12), dp(10), dp(12), dp(10));
        tile.setBackground(roundRect(pSurface2, pSurface2, 13));
        TextView l = text(label, 11, pTextSecondary, Typeface.BOLD);
        TextView v = text(initial, 15, pTextPrimary, Typeface.BOLD);
        v.setPadding(0, dp(3), 0, 0);
        tile.addView(l);
        tile.addView(v);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        parent.addView(tile, lp);
        return v;
    }

    private Button actionButton(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        b.setMinHeight(dp(52));
        b.setGravity(Gravity.CENTER);
        b.setTextColor(primary ? Color.WHITE : pTextPrimary);
        b.setBackground(roundRect(primary ? pAccent : pSurface2, primary ? pAccent : pSurface2, 14));
        return b;
    }

    private LinearLayout.LayoutParams fullMargins(int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(l), dp(t), dp(r), dp(b));
        return lp;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void setValue(TextView view, String value, int color) {
        runOnUiThread(() -> {
            view.setText(value);
            view.setTextColor(color);
        });
    }

    private void refreshShizukuState() {
        boolean binder = false;
        int perm = PackageManager.PERMISSION_DENIED;
        try {
            binder = Shizuku.pingBinder();
            if (binder) perm = Shizuku.checkSelfPermission();
        } catch (Throwable ignored) {}

        if (!binder) {
            setValue(shizukuValue, "Не запущен", pBad);
            if (!downloadRunning) heroStatus.setText("Shizuku не подключен");
            diagnosticsDone = false;
        } else if (perm != PackageManager.PERMISSION_GRANTED) {
            setValue(shizukuValue, "Нужно разрешение", pWarn);
            if (!downloadRunning) heroStatus.setText("Разреши доступ Shizuku");
            diagnosticsDone = false;
        } else {
            setValue(shizukuValue, "Готов ✓", pGood);
            if (!downloadRunning && !diagnosticsDone) heroStatus.setText("Проверяю Azur Lane JP…");
            if (!diagnosticsDone) runDiagnostics();
        }
        updateButtons();
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
        checkButton.setEnabled(false);
        if (!downloadRunning) heroStatus.setText("Проверяю игру и папки…");

        new Thread(() -> {
            try {
                gameInstalled = isPackageInstalled(GAME_PACKAGE);
                setValue(gameValue, gameInstalled ? "Установлена ✓" : "Не найдена", gameInstalled ? pGood : pBad);
                runOnUiThread(() -> openGameButton.setEnabled(gameInstalled));

                if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    setValue(folderValue, "Нет Shizuku", pBad);
                    setValue(assetFolderValue, "Нет Shizuku", pBad);
                    setValue(writeValue, "Нет Shizuku", pBad);
                    return;
                }

                String cmd =
                        "if [ -d " + sh(GAME_FILES) + " ]; then echo FILES=1; else echo FILES=0; fi; " +
                        "if [ -d " + sh(TARGET_ROOT) + " ]; then echo TARGET=1; else echo TARGET=0; fi; " +
                        "if [ -d " + sh(GAME_FILES) + " ]; then " +
                        "  if ( : > " + sh(GAME_FILES + "/.azljp_write_test") + " ) 2>/dev/null; then rm -f " + sh(GAME_FILES + "/.azljp_write_test") + "; echo WRITE=1; else echo WRITE=0; fi; " +
                        "else echo WRITE=0; fi";
                Process p = shizukuProcess(new String[]{"sh", "-c", cmd});
                String out = readAll(p.getInputStream());
                p.waitFor();
                gameFilesFound = out.contains("FILES=1");
                targetFound = out.contains("TARGET=1");
                writeReady = out.contains("WRITE=1");

                setValue(folderValue,
                        gameFilesFound ? "Найдена ✓" : (gameInstalled ? "Запусти игру 1 раз" : "Нет игры"),
                        gameFilesFound ? pGood : pWarn);
                setValue(assetFolderValue,
                        targetFound ? "Найдена ✓" : (gameFilesFound ? "Будет создана" : "Не найдена"),
                        targetFound ? pGood : (gameFilesFound ? pWarn : pBad));
                setValue(writeValue,
                        writeReady ? "Есть ✓" : "Нет доступа",
                        writeReady ? pGood : pBad);

                diagnosticsDone = true;
                if (!downloadRunning) {
                    runOnUiThread(() -> {
                        if (!gameInstalled) heroStatus.setText("Azur Lane JP не установлена");
                        else if (!gameFilesFound) heroStatus.setText("Запусти Azur Lane JP один раз");
                        else if (!writeReady) heroStatus.setText("Нет доступа к папке игры");
                        else heroStatus.setText("Готов к загрузке ✓");
                    });
                }
                uiLog("Проверка: игра=" + (gameInstalled ? "да" : "нет") +
                        ", папка=" + (gameFilesFound ? "да" : "нет") +
                        ", AssetBundles=" + (targetFound ? "да" : "будет создана") +
                        ", запись=" + (writeReady ? "да" : "нет"));
            } catch (Throwable e) {
                diagnosticsDone = false;
                uiLog("Диагностика: " + shortMsg(e));
                if (!downloadRunning) runOnUiThread(() -> heroStatus.setText("Ошибка проверки папки"));
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
        boolean binder = false;
        boolean granted = false;
        try {
            binder = Shizuku.pingBinder();
            granted = binder && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {}
        grantButton.setEnabled(binder && !granted && !downloadRunning);
        checkButton.setEnabled(granted && !diagnosticRunning.get() && !downloadRunning);
        startButton.setEnabled(granted && diagnosticsDone && gameInstalled && gameFilesFound && writeReady && !downloadRunning);
        stopButton.setEnabled(downloadRunning);
        openGameButton.setEnabled(gameInstalled && !downloadRunning);
        startButton.setAlpha(startButton.isEnabled() ? 1f : 0.45f);
        stopButton.setAlpha(stopButton.isEnabled() ? 1f : 0.45f);
        grantButton.setAlpha(grantButton.isEnabled() ? 1f : 0.45f);
        checkButton.setAlpha(checkButton.isEnabled() ? 1f : 0.45f);
        openGameButton.setAlpha(openGameButton.isEnabled() ? 1f : 0.45f);
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
        progress.setProgress(0);
        percentValue.setText("0%");
        details.setText("");
        downloadedValue.setText("0 B");
        speedValue.setText("0 B/с");
        filesValue.setText("…");
        sizeValue.setText("…");
        currentFileValue.setText("Получаю список ресурсов…");
        downloadedBytes.set(0);
        totalBytes = 0;
        startedAt = System.currentTimeMillis();
        heroStatus.setText("Получаю список актуальных ресурсов JP…");
        updateButtons();
        acquireWakeLock();

        new Thread(() -> {
            try {
                List<String> versions = requestVersionStrings();
                if (versions.isEmpty()) throw new Exception("Сервер версии не вернул список ресурсов");

                LinkedHashMap<String, Asset> all = new LinkedHashMap<>();
                int groups = 0;
                for (String raw : versions) {
                    if (stopRequested.get()) return;
                    String type = versionType(raw);
                    if (!isSupportedType(type)) continue;
                    groups++;
                    uiLog("Список: " + type);
                    for (Asset a : loadHashList(raw)) all.put(a.path, a);
                }
                if (all.isEmpty()) throw new Exception("Не удалось получить hashes*.csv");

                final int totalFiles = all.size();
                final int groupCount = groups;
                runOnUiThread(() -> {
                    filesValue.setText(totalFiles + " файлов");
                    currentFileValue.setText("Проверяю локальные файлы…");
                    heroStatus.setText("Проверяю уже скачанные ресурсы…");
                });
                uiLog("Получено групп: " + groupCount + ", файлов: " + totalFiles);

                Map<String, Long> existing = listExistingFiles();
                List<Asset> need = new ArrayList<>();
                long bytes = 0;
                for (Asset a : all.values()) {
                    Long sz = existing.get(a.path);
                    if (sz == null || sz != a.size) {
                        need.add(a);
                        bytes += a.size;
                    }
                }
                totalBytes = bytes;
                final long bytesFinal = bytes;
                final int needFiles = need.size();
                final int existingCount = totalFiles - needFiles;
                runOnUiThread(() -> {
                    filesValue.setText(existingCount + " готово • " + needFiles + " нужно");
                    sizeValue.setText(human(bytesFinal));
                });

                if (need.isEmpty()) {
                    uiProgress(1000);
                    runOnUiThread(() -> {
                        downloadedValue.setText("Уже всё есть");
                        currentFileValue.setText("Все файлы актуальны");
                        heroStatus.setText("Все ресурсы уже скачаны ✓");
                    });
                    return;
                }

                uiLog(String.format(Locale.US, "Нужно: %d файлов, %s", need.size(), human(bytes)));
                runOnUiThread(() -> heroStatus.setText("Скачивание ресурсов…"));

                workers = Executors.newFixedThreadPool(6);
                List<Future<?>> futures = new ArrayList<>();
                AtomicInteger doneFiles = new AtomicInteger();
                AtomicInteger failedFiles = new AtomicInteger();
                for (Asset asset : need) {
                    futures.add(workers.submit(() -> {
                        if (stopRequested.get()) return;
                        try {
                            uiCurrentFile(asset.path);
                            downloadOne(asset);
                            int d = doneFiles.incrementAndGet();
                            if (d % 20 == 0 || d == need.size()) {
                                uiLog("Готово файлов: " + d + "/" + need.size());
                            }
                        } catch (Exception e) {
                            failedFiles.incrementAndGet();
                            uiLog("Ошибка: " + asset.path + " — " + shortMsg(e));
                        }
                    }));
                }
                for (Future<?> f : futures) {
                    if (stopRequested.get()) break;
                    try { f.get(); } catch (Exception ignored) {}
                }

                if (stopRequested.get()) {
                    runOnUiThread(() -> heroStatus.setText("Остановлено • можно продолжить позже"));
                } else if (failedFiles.get() == 0) {
                    uiProgress(1000);
                    runOnUiThread(() -> {
                        currentFileValue.setText("Готово");
                        heroStatus.setText("Готово ✓ Можно запускать Azur Lane JP");
                    });
                } else {
                    int failed = failedFiles.get();
                    runOnUiThread(() -> heroStatus.setText("Есть ошибки: " + failed + " • нажми докачать ещё раз"));
                }
            } catch (Exception e) {
                runOnUiThread(() -> heroStatus.setText("Ошибка: " + shortMsg(e)));
                uiLog(stackSummary(e));
            } finally {
                if (workers != null) workers.shutdownNow();
                workers = null;
                downloadRunning = false;
                releaseWakeLock();
                runOnUiThread(this::updateButtons);
            }
        }, "azl-main").start();
    }

    private void requestStop() {
        if (!downloadRunning) return;
        stopRequested.set(true);
        if (workers != null) workers.shutdownNow();
        if (heroStatus != null) uiHero("Останавливаю…");
    }

    private void downloadOne(Asset a) throws Exception {
        if (stopRequested.get()) return;
        URL url = new URL(CDN + "/resource/" + a.md5);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "AzurLaneJPDownloader-Android/0.2");
        c.setUseCaches(false);
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("HTTP " + code);
        long len = c.getContentLengthLong();
        if (len > 0 && len != a.size) throw new Exception("неверный размер " + len + "/" + a.size);

        String finalPath = TARGET_ROOT + "/" + a.path;
        String partPath = finalPath + ".part";
        String parent = finalPath.substring(0, finalPath.lastIndexOf('/'));
        String cmd = "mkdir -p " + sh(parent) + " && cat > " + sh(partPath) + " && mv -f " + sh(partPath) + " " + sh(finalPath);
        Process p = shizukuProcess(new String[]{"sh", "-c", cmd});

        long count = 0;
        try (InputStream in = new BufferedInputStream(c.getInputStream(), 256 * 1024);
             OutputStream out = p.getOutputStream()) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while (!stopRequested.get() && (n = in.read(buf)) >= 0) {
                if (n == 0) continue;
                out.write(buf, 0, n);
                count += n;
                long now = downloadedBytes.addAndGet(n);
                updateProgress(now);
            }
        } finally {
            c.disconnect();
        }
        int exit = p.waitFor();
        if (stopRequested.get()) {
            try { p.destroy(); } catch (Throwable ignored) {}
            return;
        }
        if (count != a.size) throw new Exception("неполный файл " + count + "/" + a.size);
        if (exit != 0) throw new Exception("ошибка записи, код " + exit + ": " + readAll(p.getErrorStream()));
    }

    private void updateProgress(long now) {
        if (totalBytes <= 0) return;
        int value = (int)Math.min(1000, (now * 1000L) / totalBytes);
        long elapsed = Math.max(1, System.currentTimeMillis() - startedAt);
        double bps = now * 1000.0 / elapsed;
        runOnUiThread(() -> {
            progress.setProgress(value);
            percentValue.setText(String.format(Locale.US, "%.1f%%", value / 10.0));
            downloadedValue.setText(human(now) + " / " + human(totalBytes));
            speedValue.setText(human((long)bps) + "/с");
        });
    }

    private List<String> requestVersionStrings() throws Exception {
        try (Socket socket = new Socket(GATE_HOST, GATE_PORT)) {
            socket.setSoTimeout(15000);
            byte[] payload = new byte[]{0x08, 0x15, 0x12, 0x01, 0x30};
            int total = payload.length + 5;
            byte[] header = new byte[7];
            header[0] = (byte)((total >> 8) & 0xff);
            header[1] = (byte)(total & 0xff);
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
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "AzurLaneJPDownloader-Android/0.2");
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("hash HTTP " + code + " для " + versionType(rawVersion));
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
        String cmd = "if [ -d " + sh(TARGET_ROOT) + " ]; then cd " + sh(TARGET_ROOT) + " && find . -type f ! -name '*.part' -exec stat -c '%n\\t%s' {} \\; 2>/dev/null; fi";
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
        return (Process)m.invoke(null, cmd, null, null);
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
            int field = (int)(key >> 3);
            int wire = (int)(key & 7);
            if (wire == 0) {
                readVarint(data, pos);
            } else if (wire == 1) {
                pos[0] += 8;
            } else if (wire == 2) {
                int len = (int)readVarint(data, pos);
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
            result |= (long)(b & 0x7f) << shift;
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

    private static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double v = bytes;
        String[] u = {"KB", "MB", "GB", "TB"};
        int i = -1;
        do { v /= 1024.0; i++; } while (v >= 1024 && i < u.length - 1);
        return String.format(Locale.US, v >= 100 ? "%.0f %s" : v >= 10 ? "%.1f %s" : "%.2f %s", v, u[i]);
    }

    private void uiHero(String s) {
        runOnUiThread(() -> heroStatus.setText(s));
    }

    private void uiProgress(int p) {
        runOnUiThread(() -> {
            progress.setProgress(p);
            percentValue.setText(String.format(Locale.US, "%.1f%%", p / 10.0));
        });
    }

    private void uiCurrentFile(String s) {
        runOnUiThread(() -> currentFileValue.setText(s));
    }

    private void uiLog(String s) {
        runOnUiThread(() -> {
            details.append(s + "\n");
            if (details.getLineCount() > 120) {
                String text = details.getText().toString();
                int cut = text.indexOf('\n', Math.max(0, text.length() / 3));
                if (cut > 0) details.setText(text.substring(cut + 1));
            }
        });
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
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
