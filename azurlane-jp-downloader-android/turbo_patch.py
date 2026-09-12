from pathlib import Path

p = Path("app/src/main/java/ru/slava/azljpdownloader/MainActivity.java")
s = p.read_text(encoding="utf-8")


def repl(old: str, new: str, label: str):
    global s
    count = s.count(old)
    if count != 1:
        raise SystemExit(f"patch {label}: expected 1 match, got {count}")
    s = s.replace(old, new, 1)

repl(
    "import java.util.concurrent.Future;\n",
    "import java.util.concurrent.Future;\nimport java.util.concurrent.Semaphore;\n",
    "semaphore import",
)

repl(
    '    private static final int SHIZUKU_REQ = 7301;\n',
    '    private static final int SHIZUKU_REQ = 7301;\n'
    '    private static final int DOWNLOAD_WORKERS = 10;\n'
    '    private static final long LARGE_FILE_BYTES = 10L * 1024L * 1024L;\n',
    "turbo constants",
)

repl(
    '    private final AtomicInteger completedFiles = new AtomicInteger(0);\n',
    '    private final AtomicInteger completedFiles = new AtomicInteger(0);\n'
    '    private final Semaphore largeFileSlots = new Semaphore(2, true);\n',
    "large file semaphore",
)

repl(
    '    private long lastSpeedSampleBytes = 0;\n    private long lastSpeedSampleMs = 0;\n',
    '    private long lastSpeedSampleBytes = 0;\n'
    '    private long lastSpeedSampleMs = 0;\n'
    '    private double smoothedBps = 0.0;\n',
    "smoothed speed field",
)

repl(
'''            long deltaMs = lastSpeedSampleMs == 0 ? 0 : now - lastSpeedSampleMs;
            long deltaBytes = wire - lastSpeedSampleBytes;
            long bps = deltaMs > 0 ? (deltaBytes * 1000L) / deltaMs : 0;
            lastSpeedSampleMs = now;
            lastSpeedSampleBytes = wire;
            renderProgress(bps);
            mainHandler.postDelayed(this, 250);''',
'''            long deltaMs = lastSpeedSampleMs == 0 ? 0 : now - lastSpeedSampleMs;
            long deltaBytes = Math.max(0, wire - lastSpeedSampleBytes);
            long instantBps = deltaMs > 0 ? (deltaBytes * 1000L) / deltaMs : 0;
            lastSpeedSampleMs = now;
            lastSpeedSampleBytes = wire;

            // The CDN delivers many small files in bursts. A raw 250 ms sample jumps
            // between 0 and a large value even when the real throughput is stable.
            // EWMA keeps the displayed speed readable without hiding real slowdowns.
            if (instantBps > 0) {
                smoothedBps = smoothedBps <= 0.0
                        ? instantBps
                        : smoothedBps * 0.72 + instantBps * 0.28;
            } else {
                smoothedBps *= 0.90;
                if (smoothedBps < 1024.0) smoothedBps = 0.0;
            }

            renderProgress((long) smoothedBps);
            mainHandler.postDelayed(this, 500);''',
    "speed smoothing",
)

repl(
'''        resolveColors();
        buildUi();''',
'''        // Keep Android's HTTP connection pool alive and large enough for the
        // same 10-per-host strategy used by the desktop asset downloader.
        System.setProperty("http.keepAlive", "true");
        System.setProperty("http.maxConnections", String.valueOf(DOWNLOAD_WORKERS));

        resolveColors();
        buildUi();''',
    "http pool properties",
)

repl(
    'toolbar.setSubtitle("Resource Downloader • v0.3.0");',
    'toolbar.setSubtitle("Resource Downloader • v0.3.1 Turbo");',
    "toolbar version",
)

repl(
'''        lastSpeedSampleBytes = 0;
        lastSpeedSampleMs = System.currentTimeMillis();''',
'''        lastSpeedSampleBytes = 0;
        lastSpeedSampleMs = System.currentTimeMillis();
        smoothedBps = 0.0;''',
    "speed reset",
)

repl(
'''                workers = Executors.newFixedThreadPool(6);''',
'''                workers = Executors.newFixedThreadPool(DOWNLOAD_WORKERS);
                uiLog("Turbo-сеть: " + DOWNLOAD_WORKERS + " параллельных загрузок, крупных файлов одновременно: 2");''',
    "worker count",
)

repl(
'''                        try {
                            uiCurrentFile(asset.path);
                            downloadWithRetry(asset);
                            completedFiles.incrementAndGet();
                            uiSavedFile(asset.path, asset.size);''',
'''                        boolean largePermit = false;
                        try {
                            uiCurrentFile(asset.path);
                            if (asset.size > LARGE_FILE_BYTES) {
                                largeFileSlots.acquire();
                                largePermit = true;
                            }
                            downloadWithRetry(asset);
                            completedFiles.incrementAndGet();
                            uiSavedFile(asset.path, asset.size);''',
    "large file acquire",
)

repl(
'''                        } catch (Exception e) {
                            if (!stopRequested.get()) {
                                failedFiles.incrementAndGet();
                                uiLog("Ошибка: " + asset.path + " — " + shortMsg(e));
                            }
                        }
                    }));''',
'''                        } catch (Exception e) {
                            if (!stopRequested.get()) {
                                failedFiles.incrementAndGet();
                                uiLog("Ошибка: " + asset.path + " — " + shortMsg(e));
                            }
                        } finally {
                            if (largePermit) largeFileSlots.release();
                        }
                    }));''',
    "large file release",
)

repl(
'''            c.setRequestProperty("User-Agent", "AzurLaneJPDownloader-Android/0.3");
            c.setRequestProperty("Connection", "keep-alive");
            c.setUseCaches(false);''',
'''            c.setRequestProperty("User-Agent", "AzurLaneJPDownloader-Android/0.3.1");
            // HttpURLConnection keeps pooled sockets alive by default. Do not force
            // a per-request disconnect: reusing TLS connections is much faster for
            // Azur Lane's thousands of small files.
            c.setUseCaches(false);''',
    "http headers",
)

repl(
'''            try (InputStream in = new BufferedInputStream(c.getInputStream(), 256 * 1024);
                 OutputStream out = p.getOutputStream()) {
                byte[] buf = new byte[256 * 1024];''',
'''            int ioBuffer = bufferSizeFor(a.size);
            try (InputStream in = new BufferedInputStream(c.getInputStream(), ioBuffer);
                 OutputStream out = p.getOutputStream()) {
                byte[] buf = new byte[ioBuffer];''',
    "adaptive buffer",
)

repl(
'''        } finally {
            if (c != null) c.disconnect();
            if (p != null && stopRequested.get()) {''',
'''        } finally {
            // Closing a fully consumed response returns the socket to Android's
            // keep-alive pool. Calling disconnect() here would throw that benefit away.
            if (p != null && stopRequested.get()) {''',
    "connection reuse",
)

repl(
'''    private void cleanupPart(String partPath) {''',
'''    private static int bufferSizeFor(long fileSize) {
        if (fileSize <= 16L * 1024L) return (int) Math.max(1024L, fileSize);
        if (fileSize <= 128L * 1024L) return 64 * 1024;
        if (fileSize <= 4L * 1024L * 1024L) return 256 * 1024;
        return 1024 * 1024;
    }

    private void cleanupPart(String partPath) {''',
    "adaptive buffer helper",
)

p.write_text(s, encoding="utf-8")
print("Turbo patch applied successfully")
