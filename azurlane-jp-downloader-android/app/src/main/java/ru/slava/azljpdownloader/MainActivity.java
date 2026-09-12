package ru.slava.azljpdownloader;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
    private static final String TARGET_ROOT = "/sdcard/Android/data/" + GAME_PACKAGE + "/files/AssetBundles";
    private static final int SHIZUKU_REQ = 7301;

    private TextView status;
    private TextView details;
    private ProgressBar progress;
    private Button grantButton;
    private Button startButton;
    private Button stopButton;

    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final AtomicLong downloadedBytes = new AtomicLong(0);
    private volatile long totalBytes = 0;
    private volatile long startedAt = 0;
    private ExecutorService workers;
    private PowerManager.WakeLock wakeLock;

    private final Shizuku.OnRequestPermissionResultListener permissionListener = (requestCode, grantResult) -> {
        if (requestCode == SHIZUKU_REQ) runOnUiThread(this::refreshShizukuState);
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        Shizuku.addRequestPermissionResultListener(permissionListener);
        refreshShizukuState();
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener);
        requestStop();
        releaseWakeLock();
        super.onDestroy();
    }

    private void buildUi() {
        int pad = dp(18);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Azur Lane JP Downloader");
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitle = new TextView(this);
        subtitle.setText("Скачивает ресурсы японской версии напрямую с CDN и записывает их в папку игры через Shizuku.");
        subtitle.setTextSize(15);
        subtitle.setPadding(0, dp(8), 0, dp(14));
        root.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(this);
        status.setTextSize(17);
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgress(0);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, dp(28));
        pp.setMargins(0, dp(12), 0, dp(8));
        root.addView(progress, pp);

        details = new TextView(this);
        details.setTextSize(14);
        details.setMovementMethod(new ScrollingMovementMethod());
        LinearLayout.LayoutParams dpv = new LinearLayout.LayoutParams(-1, 0, 1f);
        dpv.setMargins(0, dp(8), 0, dp(10));
        root.addView(details, dpv);

        grantButton = new Button(this);
        grantButton.setText("Разрешить Shizuku");
        grantButton.setOnClickListener(v -> requestShizukuPermission());
        root.addView(grantButton, new LinearLayout.LayoutParams(-1, -2));

        startButton = new Button(this);
        startButton.setText("Скачать / докачать ресурсы JP");
        startButton.setOnClickListener(v -> startDownload());
        root.addView(startButton, new LinearLayout.LayoutParams(-1, -2));

        stopButton = new Button(this);
        stopButton.setText("Остановить");
        stopButton.setEnabled(false);
        stopButton.setOnClickListener(v -> requestStop());
        root.addView(stopButton, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void refreshShizukuState() {
        boolean binder = Shizuku.pingBinder();
        int perm = binder ? Shizuku.checkSelfPermission() : PackageManager.PERMISSION_DENIED;
        if (!binder) {
            status.setText("Shizuku: не запущен");
            grantButton.setEnabled(false);
            startButton.setEnabled(false);
        } else if (perm != PackageManager.PERMISSION_GRANTED) {
            status.setText("Shizuku: нужно разрешение");
            grantButton.setEnabled(true);
            startButton.setEnabled(false);
        } else {
            status.setText("Shizuku: готов ✓");
            grantButton.setEnabled(false);
            startButton.setEnabled(true);
        }
    }

    private void requestShizukuPermission() {
        if (!Shizuku.pingBinder()) {
            status.setText("Сначала запусти Shizuku");
            return;
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            refreshShizukuState();
            return;
        }
        Shizuku.requestPermission(SHIZUKU_REQ);
    }

    private void startDownload() {
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            refreshShizukuState();
            return;
        }
        stopRequested.set(false);
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        progress.setProgress(0);
        details.setText("");
        downloadedBytes.set(0);
        totalBytes = 0;
        startedAt = System.currentTimeMillis();
        acquireWakeLock();

        new Thread(() -> {
            try {
                uiStatus("Получаю список актуальных ресурсов JP…");
                List<String> versions = requestVersionStrings();
                if (versions.isEmpty()) throw new Exception("Сервер версии не вернул список ресурсов");

                LinkedHashMap<String, Asset> all = new LinkedHashMap<>();
                for (String raw : versions) {
                    if (stopRequested.get()) return;
                    String type = versionType(raw);
                    if (!isSupportedType(type)) continue;
                    uiLog("Список: " + type);
                    for (Asset a : loadHashList(raw)) all.put(a.path, a);
                }
                if (all.isEmpty()) throw new Exception("Не удалось получить hashes*.csv");

                uiStatus("Проверяю уже скачанные файлы…");
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

                if (need.isEmpty()) {
                    uiStatus("Все ресурсы уже скачаны ✓");
                    uiProgress(1000);
                    return;
                }

                uiLog(String.format(Locale.US, "Нужно: %d файлов, %s", need.size(), human(bytes)));
                uiStatus("Скачивание…");

                workers = Executors.newFixedThreadPool(6);
                List<Future<?>> futures = new ArrayList<>();
                AtomicInteger doneFiles = new AtomicInteger();
                AtomicInteger failedFiles = new AtomicInteger();
                for (Asset asset : need) {
                    futures.add(workers.submit(() -> {
                        if (stopRequested.get()) return;
                        try {
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
                    uiStatus("Остановлено. При следующем запуске продолжит с недостающих файлов.");
                } else if (failedFiles.get() == 0) {
                    uiProgress(1000);
                    uiStatus("Готово ✓ Можно запускать Azur Lane JP");
                } else {
                    uiStatus("Завершено с ошибками: " + failedFiles.get() + ". Нажми скачать ещё раз — докачает.");
                }
            } catch (Exception e) {
                uiStatus("Ошибка: " + shortMsg(e));
                uiLog(stackSummary(e));
            } finally {
                if (workers != null) workers.shutdownNow();
                workers = null;
                releaseWakeLock();
                runOnUiThread(() -> {
                    stopButton.setEnabled(false);
                    refreshShizukuState();
                });
            }
        }, "azl-main").start();
    }

    private void requestStop() {
        stopRequested.set(true);
        if (workers != null) workers.shutdownNow();
        uiStatus("Останавливаю…");
    }

    private void downloadOne(Asset a) throws Exception {
        if (stopRequested.get()) return;
        URL url = new URL(CDN + "/resource/" + a.md5);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "AzurLaneJPDownloader-Android/0.1");
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
            status.setText(String.format(Locale.US, "Скачивание: %.1f%% • %s/с", value / 10.0, human((long)bps)));
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
        c.setRequestProperty("User-Agent", "AzurLaneJPDownloader-Android/0.1");
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
        String cmd = "if [ -d " + sh(TARGET_ROOT) + " ]; then cd " + sh(TARGET_ROOT) + " && find . -type f ! -name '*.part' -printf '%P\\t%s\\n'; fi";
        Process p = shizukuProcess(new String[]{"sh", "-c", cmd});
        Map<String, Long> map = new ConcurrentHashMap<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                int tab = line.lastIndexOf('\t');
                if (tab <= 0) continue;
                try { map.put(line.substring(0, tab), Long.parseLong(line.substring(tab + 1))); } catch (Exception ignored) {}
            }
        }
        int exit = p.waitFor();
        if (exit != 0) {
            String err = readAll(p.getErrorStream());
            if (!err.trim().isEmpty()) uiLog("Проверка папки: " + err.trim());
        }
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

    private void uiStatus(String s) {
        runOnUiThread(() -> status.setText(s));
    }

    private void uiProgress(int p) {
        runOnUiThread(() -> progress.setProgress(p));
    }

    private void uiLog(String s) {
        runOnUiThread(() -> {
            details.append(s + "\n");
            if (details.getLayout() != null) {
                int scroll = details.getLayout().getLineTop(details.getLineCount()) - details.getHeight();
                if (scroll > 0) details.scrollTo(0, scroll);
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
