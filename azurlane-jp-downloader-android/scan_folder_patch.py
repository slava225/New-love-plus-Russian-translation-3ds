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
    'toolbar.setSubtitle("Resource Downloader • v0.3.1 Turbo");',
    'toolbar.setSubtitle("Resource Downloader • v0.3.2 Turbo");',
    'toolbar version',
)

repl(
    'c.setRequestProperty("User-Agent", "AzurLaneJPDownloader-Android/0.3.1");',
    'c.setRequestProperty("User-Agent", "AzurLaneJPDownloader-Android/0.3.2");',
    'user agent version',
)

# Keep diagnostics useful without filling the log with the same line on every resume.
repl(
    '    private volatile boolean downloadRunning = false;\n',
    '    private volatile boolean downloadRunning = false;\n'
    '    private volatile String lastDiagnosticSignature = "";\n',
    'diagnostic signature field',
)

repl(
'''                uiLog("Проверка: игра=" + yesNo(gameInstalled)
                        + ", files=" + yesNo(gameFilesFound)
                        + ", AssetBundles=" + yesNo(targetFound)
                        + ", запись=" + yesNo(writeReady));''',
'''                String diagnosticSignature = yesNo(gameInstalled) + "/" + yesNo(gameFilesFound)
                        + "/" + yesNo(targetFound) + "/" + yesNo(writeReady);
                if (!diagnosticSignature.equals(lastDiagnosticSignature)) {
                    lastDiagnosticSignature = diagnosticSignature;
                    uiLog("Проверка: игра=" + yesNo(gameInstalled)
                            + ", files=" + yesNo(gameFilesFound)
                            + ", AssetBundles=" + yesNo(targetFound)
                            + ", запись=" + yesNo(writeReady));
                }''',
    'dedupe diagnostics log',
)

# Android 11+ deliberately blocks generic ACTION_VIEW access to Android/data.
# The app already has Shizuku access, so open the exact directory inside our own browser.
repl(
'''    private void openFolderExternal() {
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
    }''',
'''    private void openFolderExternal() {
        if (!targetFound) {
            folderHeroStatus.setText("Папка ещё не найдена");
            return;
        }

        // Scoped Storage blocks normal apps from ACTION_VIEW into Android/data.
        // We already have privileged Shizuku access, so the reliable option is to
        // open the exact AssetBundles directory in the built-in browser.
        folderHeroStatus.setText("AssetBundles открыта внутри приложения ✓");
        folderListValue.setText("Читаю точную папку AssetBundles…");
        refreshFolderContents();
        uiLog("Открыта через Shizuku: " + TARGET_ROOT);
    }''',
    'internal folder opening',
)

repl(
    'openFolderButton = materialButton("Открыть папку");',
    'openFolderButton = materialButton("Открыть AssetBundles здесь");',
    'folder button text',
)

# Make it obvious where the app is after the ten hash-list lines.
repl(
'''                Map<String, Long> existing = listExistingFiles();
                List<Asset> need = new ArrayList<>();''',
'''                uiLog("Манифесты получены. Сканирую существующие файлы AssetBundles…");
                long scanStarted = System.currentTimeMillis();
                Map<String, Long> existing = listExistingFiles();
                long scanMs = System.currentTimeMillis() - scanStarted;
                uiLog(String.format(Locale.US, "Сканирование готово: %d файлов за %.1f с",
                        existing.size(), scanMs / 1000.0));
                List<Asset> need = new ArrayList<>();''',
    'scan progress log',
)

# Old version spawned one `stat` process per file, which is painfully slow when
# AssetBundles has many thousands of files. Modern Android toybox has find -printf;
# use that single-process path and fall back to batched stat for older devices.
repl(
'''    private Map<String, Long> listExistingFiles() throws Exception {
        String cmd = "if [ -d " + sh(TARGET_ROOT) + " ]; then "
                + "cd " + sh(TARGET_ROOT)
                + " && find . -type f ! -name '*.part' -exec stat -c '%n\\\\t%s' {} \\\\; 2>/dev/null; fi";

        Process p = shizukuProcess(new String[]{"sh", "-c", cmd});''',
'''    private Map<String, Long> listExistingFiles() throws Exception {
        String cmd = "if [ -d " + sh(TARGET_ROOT) + " ]; then "
                + "cd " + sh(TARGET_ROOT) + " && "
                + "if find . -maxdepth 0 -printf '' >/dev/null 2>&1; then "
                + "find . -type f ! -name '*.part' -printf '%P\\\\t%s\\\\n' 2>/dev/null; "
                + "else find . -type f ! -name '*.part' -exec stat -c '%n\\\\t%s' {} + 2>/dev/null; fi; fi";

        Process p = shizukuProcess(new String[]{"sh", "-c", cmd});''',
    'fast existing-file scan',
)

p.write_text(s, encoding="utf-8")
print("Scan/folder patch applied successfully")
