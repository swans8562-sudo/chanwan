package com.chanwan.shiguang;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ServiceWorkerController;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final String APP_URL = "https://swans8562-sudo.github.io/chanwan/";
    private static final int APK_BUILD_VER = 65;
    private static final int FILE_CHOOSER_REQ = 1001;
    private static final int PERM_REQ = 1002;
    private static final int MIC_PERM_REQ = 1003;

    public static volatile boolean isAppInForeground = false;
    private static volatile MainActivity activeInstance = null;

    private WebView webView;
    private FrameLayout splashOverlay;
    private ValueCallback<Uri[]> filePathCallback;
    private PermissionRequest pendingWebPermReq;
    private boolean waitingSettingsMicReturn = false;
    private boolean openDialogAfterPermGrant = false;

    private MediaRecorder nativeRecorder = null;
    private File nativeRecFile = null;
    private long nativeDialogStartMs = 0L;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable dialogTimerRunnable = null;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        activeInstance = this;
        updateForegroundState(true);

        FrameLayout root = new FrameLayout(this);
        webView = new WebView(this);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        }
        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        splashOverlay = new FrameLayout(this);
        splashOverlay.setBackgroundColor(Color.parseColor("#120d1f"));
        TextView splashText = new TextView(this);
        splashText.setText("✨ 灿完时光 ✨");
        splashText.setTextColor(Color.parseColor("#f5e6ff"));
        splashText.setTextSize(22f);
        splashText.setGravity(Gravity.CENTER);
        splashOverlay.addView(splashText, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        root.addView(splashOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);

        CookieManager.getInstance().setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                ServiceWorkerController.getInstance().getServiceWorkerWebSettings().setAllowContentAccess(true);
                ServiceWorkerController.getInstance().getServiceWorkerWebSettings().setAllowFileAccess(true);
            } catch (Exception ignored) {}
        }

        // One-time HTTP disk cache purge on APK version upgrade (keeps 100% of localStorage & IndexedDB intact!)
        SharedPreferences initPrefs = getSharedPreferences("cw_prefs", MODE_PRIVATE);
        if (initPrefs.getInt("last_apk_ver", 0) != APK_BUILD_VER) {
            try { webView.clearCache(true); } catch (Exception ignored) {}
            initPrefs.edit().putInt("last_apk_ver", APK_BUILD_VER).apply();
        }

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setTextZoom(100);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            if (url != null && url.contains(".apk")) {
                downloadAndInstallApkInApp(url);
            } else {
                openUrlExternally(url);
            }
        });

        webView.addJavascriptInterface(new NativeBridge(), "ChanwanNative");
        webView.setWebViewClient(new WebViewClient() {
            private volatile boolean useBundledAssets = false;

            private String guessMime(String name) {
                if (name.endsWith(".html")) return "text/html";
                if (name.endsWith(".js")) return "application/javascript";
                if (name.endsWith(".json")) return "application/json";
                if (name.endsWith(".png")) return "image/png";
                return "application/octet-stream";
            }

            private byte[] readAssetBytes(String assetName) {
                InputStream is = null;
                try {
                    is = getAssets().open("www/" + assetName);
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
                    return bos.toByteArray();
                } catch (Exception e) {
                    return null;
                } finally {
                    if (is != null) { try { is.close(); } catch (Exception ignored) {} }
                }
            }

            private byte[] fetchHtmlBytes(String urlStr, int connTimeout, int readTimeout) {
                HttpURLConnection conn = null;
                try {
                    URL netUrl = new URL(urlStr);
                    conn = (HttpURLConnection) netUrl.openConnection();
                    conn.setConnectTimeout(connTimeout);
                    conn.setReadTimeout(readTimeout);
                    conn.setRequestProperty("Cache-Control", "no-cache, no-store");
                    if (conn.getResponseCode() == 200) {
                        InputStream in = conn.getInputStream();
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                        byte[] bytes = bos.toByteArray();
                        String checkEnd = new String(bytes, Math.max(0, bytes.length - 128), Math.min(128, bytes.length), "UTF-8");
                        if (checkEnd.contains("</html>")) return bytes;
                    }
                } catch (Exception ignored) {
                } finally {
                    if (conn != null) { try { conn.disconnect(); } catch (Exception ignored) {} }
                }
                return null;
            }

            private int extractPrimeVer(byte[] bytes) {
                if (bytes == null || bytes.length == 0) return 0;
                try {
                    String s = new String(bytes, "UTF-8");
                    String marker = "Prime 4 · v";
                    int idx = s.indexOf(marker);
                    if (idx < 0) return 0;
                    int p = idx + marker.length();
                    int num = 0;
                    boolean found = false;
                    while (p < s.length()) {
                        char c = s.charAt(p);
                        if (c >= '0' && c <= '9') {
                            num = num * 10 + (c - '0');
                            found = true;
                            p++;
                        } else {
                            break;
                        }
                    }
                    return found ? num : 0;
                } catch (Exception e) {
                    return 0;
                }
            }

            private WebResourceResponse openAssetResp(String assetName) {
                try {
                    InputStream is = getAssets().open("www/" + assetName);
                    String mime = guessMime(assetName);
                    String enc = (mime.startsWith("text/") || mime.contains("javascript") || mime.contains("json")) ? "UTF-8" : null;
                    return new WebResourceResponse(mime, enc, is);
                } catch (Exception e) {
                    return null;
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request != null && request.getUrl() != null) {
                    String uStr = request.getUrl().toString();
                    if (uStr.contains(".apk")) {
                        downloadAndInstallApkInApp(uStr);
                        return true;
                    }
                }
                return false;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request == null || !"GET".equalsIgnoreCase(request.getMethod())) return null;
                Uri u = request.getUrl();
                if (u == null || !"swans8562-sudo.github.io".equalsIgnoreCase(u.getHost())) return null;
                String path = u.getPath();
                if (path == null) path = "/chanwan/";
                if (path.endsWith(".apk")) return null;
                String rel = path.startsWith("/chanwan/") ? path.substring("/chanwan/".length()) : (path.equals("/chanwan") ? "" : null);
                if (rel == null) return null;
                if (rel.isEmpty() || rel.equals("/")) rel = "index.html";

                if ("sw.js".equals(rel)) {
                    // Inside Native APK, prevent ServiceWorker CacheStorage from ever trapping WebView on an older HTML version!
                    String unregSw = "self.addEventListener('install',function(){self.skipWaiting();});"
                            + "self.addEventListener('activate',function(e){e.waitUntil(caches.keys().then(function(ks){return Promise.all(ks.map(function(k){return caches.delete(k);}));}).then(function(){return self.registration.unregister();}));});";
                    try {
                        return new WebResourceResponse("application/javascript", "UTF-8", new ByteArrayInputStream(unregSw.getBytes("UTF-8")));
                    } catch (Exception ignored) {}
                }

                boolean isIndex = "index.html".equals(rel);
                boolean isBundleAsset = isIndex || rel.startsWith("cw-app-") || rel.equals("manifest_clean.json") || rel.endsWith(".png");
                if (!isBundleAsset) return null;

                if (!isIndex && useBundledAssets) {
                    WebResourceResponse local = openAssetResp(rel);
                    if (local != null) return local;
                }

                if (isIndex) {
                    byte[] bestBytes = readAssetBytes("index.html");
                    int bestVer = extractPrimeVer(bestBytes);
                    boolean usedNet = false;

                    // 1) Try instant GitHub main branch raw first (0s delay after Actions build; bypasses 10-min CDN cache!)
                    String rawUrl = "https://raw.githubusercontent.com/swans8562-sudo/chanwan/main/index.html?_t=" + System.currentTimeMillis();
                    byte[] rawBytes = fetchHtmlBytes(rawUrl, 2400, 3000);
                    int rawVer = extractPrimeVer(rawBytes);
                    if (rawBytes != null && rawVer >= bestVer) {
                        bestBytes = rawBytes;
                        bestVer = rawVer;
                        usedNet = true;
                    } else {
                        // 2) Fallback to GitHub Pages (works in Mainland China too!)
                        byte[] pagesBytes = fetchHtmlBytes(u.toString(), 2600, 3200);
                        int pagesVer = extractPrimeVer(pagesBytes);
                        if (pagesBytes != null && pagesVer >= bestVer) {
                            bestBytes = pagesBytes;
                            bestVer = pagesVer;
                            usedNet = true;
                        }
                    }

                    if (bestBytes != null) {
                        useBundledAssets = !usedNet;
                        return new WebResourceResponse("text/html", "UTF-8", new ByteArrayInputStream(bestBytes));
                    }
                    useBundledAssets = true;
                    WebResourceResponse fallback = openAssetResp("index.html");
                    if (fallback != null) return fallback;
                }
                return null;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (splashOverlay != null && splashOverlay.getVisibility() == View.VISIBLE) {
                    splashOverlay.animate().alpha(0f).setDuration(160).withEndAction(() -> splashOverlay.setVisibility(View.GONE)).start();
                }
                String inject = "(function(){try{var s=JSON.parse(localStorage.getItem('chanwan_shiguang_v6_core')||localStorage.getItem('chanwan_shiguang_core_v1')||'{}');var r=(localStorage.getItem('cw_my_role')||'').trim()||s.myRole||'suwan';var rm=(s.settings&&s.settings.cloudSyncRoom)||'chanwan-suwan-jingchan-live2026';if(window.ChanwanNative){if(r)window.ChanwanNative.setRole(r);if(rm&&window.ChanwanNative.setRoom)window.ChanwanNative.setRoom(rm);}}catch(e){}})();";
                view.evaluateJavascript(inject, null);
                handleNotificationOpenIntent(getIntent());
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                String[] accepts = (params != null && params.getAcceptTypes() != null) ? params.getAcceptTypes() : new String[0];
                String joined = "";
                for (String a : accepts) { if (a != null) joined += a.toLowerCase() + ","; }

                // NEVER open Samsung's empty '최근/항목 없음' audio file picker! Launch built-in Native Voice Recorder Dialog instead!
                if (joined.contains("audio")) {
                    if (callback != null) callback.onReceiveValue(null);
                    runOnUiThread(() -> launchBuiltInVoiceRecorderDialog());
                    return true;
                }

                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                try {
                    boolean allowMulti = params != null && params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE;
                    Intent getIntent = new Intent(Intent.ACTION_GET_CONTENT);
                    getIntent.addCategory(Intent.CATEGORY_OPENABLE);
                    if (joined.contains("image") && joined.contains("video")) {
                        getIntent.setType("*/*");
                        getIntent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
                    } else if (joined.contains("image")) {
                        getIntent.setType("image/*");
                    } else if (joined.contains("video")) {
                        getIntent.setType("video/*");
                    } else if (joined.contains("json")) {
                        getIntent.setType("application/json");
                    } else {
                        getIntent.setType("*/*");
                    }
                    if (allowMulti) {
                        getIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    }
                    startActivityForResult(Intent.createChooser(getIntent, "사진 · 동영상 선택"), FILE_CHOOSER_REQ);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean needsMic = false;
                    for (String res : request.getResources()) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) {
                            needsMic = true;
                            break;
                        }
                    }
                    if (needsMic && Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        pendingWebPermReq = request;
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_PERM_REQ);
                    } else {
                        try { request.grant(request.getResources()); } catch (Exception ignored) {}
                    }
                });
            }
        });

        requestRuntimePermissions();
        startPushService();

        Intent launchIntent = getIntent();
        boolean launchedFromNotif = launchIntent != null && launchIntent.getBooleanExtra("from_notif", false);
        long launchTs = System.currentTimeMillis();
        String startUrl = launchedFromNotif
                ? (APP_URL + "?tab=translator&v=" + launchTs)
                : (APP_URL + "?v=" + launchTs);
        java.util.Map<String, String> noCacheHeaders = new java.util.HashMap<>();
        noCacheHeaders.put("Cache-Control", "no-cache, no-store, must-revalidate");
        noCacheHeaders.put("Pragma", "no-cache");
        webView.loadUrl(startUrl, noCacheHeaders);
    }

    private void downloadAndInstallApkInApp(final String apkUrl) {
        if (apkUrl == null || apkUrl.isEmpty()) return;
        runOnUiThread(() -> {
            String role = getSharedPreferences("cw_prefs", MODE_PRIVATE).getString("role", "suwan");
            final boolean isZh = "jingchan".equals(role);
            final TextView statusTv = new TextView(MainActivity.this);
            statusTv.setText(isZh ? "📲 正在应用内下载更新... 0%" : "📲 앱 안에서 업데이트 내려받는 중... 0%");
            statusTv.setTextSize(15.5f);
            statusTv.setGravity(Gravity.CENTER);
            statusTv.setPadding(48, 56, 48, 48);

            final AlertDialog progDlg = new AlertDialog.Builder(MainActivity.this)
                    .setTitle(isZh ? "✨ 灿完时光应用内更新" : "✨ 灿完时光 인앱 업데이트")
                    .setView(statusTv)
                    .setCancelable(false)
                    .create();
            progDlg.show();

            new Thread(() -> {
                HttpURLConnection conn = null;
                try {
                    File outApk = new File(getCacheDir(), "chanwan-update.apk");
                    if (outApk.exists()) outApk.delete();
                    conn = (HttpURLConnection) new URL(apkUrl).openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    conn.connect();
                    if (conn.getResponseCode() != 200) throw new RuntimeException("HTTP " + conn.getResponseCode());
                    int total = conn.getContentLength();
                    InputStream in = conn.getInputStream();
                    FileOutputStream fos = new FileOutputStream(outApk);
                    byte[] buf = new byte[16384];
                    int read = 0;
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        fos.write(buf, 0, n);
                        read += n;
                        if (total > 0) {
                            final int pct = Math.min(99, (int) ((read * 100L) / total));
                            uiHandler.post(() -> statusTv.setText((isZh ? "📲 正在应用内下载更新... " : "📲 앱 안에서 업데이트 내려받는 중... ") + pct + "%"));
                        }
                    }
                    fos.flush();
                    fos.close();
                    in.close();

                    uiHandler.post(() -> {
                        try { progDlg.dismiss(); } catch (Exception ignored) {}
                        launchApkPackageInstaller(outApk);
                    });
                } catch (Exception e) {
                    uiHandler.post(() -> {
                        try { progDlg.dismiss(); } catch (Exception ignored) {}
                        openUrlExternally(apkUrl);
                    });
                } finally {
                    if (conn != null) { try { conn.disconnect(); } catch (Exception ignored) {} }
                }
            }, "ChanwanInAppUpdater").start();
        });
    }

    private void launchApkPackageInstaller(File apkFile) {
        if (apkFile == null || !apkFile.exists()) return;
        try {
            Uri apkUri = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                    ? Uri.parse("content://com.chanwan.shiguang.apkprovider/chanwan-update.apk")
                    : Uri.fromFile(apkFile);
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(apkUri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(install);
        } catch (Exception e) {
            Toast.makeText(this, "설치 창을 열지 못했어요. 알 수 없는 앱 설치 허용을 확인해 주세요.", Toast.LENGTH_LONG).show();
        }
    }

    private void openUrlExternally(String url) {
        if (url == null || url.isEmpty()) return;
        runOnUiThread(() -> {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception ignored) {}
        });
    }

    private void launchBuiltInVoiceRecorderDialog() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            openDialogAfterPermGrant = true;
            new NativeBridge().requestMicPermission();
            return;
        }
        String role = getSharedPreferences("cw_prefs", MODE_PRIVATE).getString("role", "suwan");
        boolean isZh = "jingchan".equals(role);

        NativeBridge nb = new NativeBridge();
        if (!nb.startNativeAudioRecord()) {
            Toast.makeText(this, isZh ? "麦克风正忙，请稍后重试" : "마이크를 시작하지 못했어요. 잠시 후 다시 눌러주세요", Toast.LENGTH_SHORT).show();
            return;
        }

        nativeDialogStartMs = System.currentTimeMillis();
        final TextView timerView = new TextView(this);
        timerView.setText(isZh ? "🎙️ 正在录音... 00:00" : "🎙️ 목소리 녹음 중... 00:00");
        timerView.setTextSize(17f);
        timerView.setGravity(Gravity.CENTER);
        timerView.setPadding(48, 56, 48, 36);

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(isZh ? "💜 发送语音消息" : "💜 음성 메시지 녹음")
                .setView(timerView)
                .setCancelable(false)
                .setPositiveButton(isZh ? "完成并预览" : "녹음 완료 · 들어보기", (d, w) -> {
                    stopDialogTimer();
                    int sec = Math.max(1, (int) ((System.currentTimeMillis() - nativeDialogStartMs) / 1000L));
                    String dataUrl = nb.stopNativeAudioRecord();
                    if (dataUrl != null && dataUrl.startsWith("data:audio/") && webView != null) {
                        final String qUrl = JSONObject.quote(dataUrl);
                        webView.post(() -> {
                            String js = "(function(){if(typeof window.__receiveNativeRecordedVoice==='function')window.__receiveNativeRecordedVoice(" + qUrl + "," + sec + ");})();";
                            try { webView.evaluateJavascript(js, null); } catch (Exception ignored) {}
                        });
                    }
                })
                .setNegativeButton(isZh ? "取消" : "취소", (d, w) -> {
                    stopDialogTimer();
                    stopAndReleaseRecorder();
                })
                .create();

        dialogTimerRunnable = new Runnable() {
            @Override
            public void run() {
                long sec = Math.max(0L, (System.currentTimeMillis() - nativeDialogStartMs) / 1000L);
                String mm = String.format("%02d:%02d", sec / 60L, sec % 60L);
                timerView.setText((isZh ? "🎙️ 正在录音... " : "🎙️ 목소리 녹음 중... ") + mm);
                if (sec >= 45L && dlg.isShowing()) {
                    dlg.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                } else {
                    uiHandler.postDelayed(this, 500L);
                }
            }
        };
        dlg.show();
        uiHandler.postDelayed(dialogTimerRunnable, 500L);
    }

    private void stopDialogTimer() {
        if (dialogTimerRunnable != null) {
            uiHandler.removeCallbacks(dialogTimerRunnable);
            dialogTimerRunnable = null;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        activeInstance = this;
        updateForegroundState(true);
        startPushService();
    }

    @Override
    protected void onResume() {
        super.onResume();
        activeInstance = this;
        if (webView != null) {
            try { webView.onResume(); webView.resumeTimers(); } catch (Exception ignored) {}
        }
        updateForegroundState(true);
        handleNotificationOpenIntent(getIntent());
        nudgeForegroundWebViewSync();
        if (waitingSettingsMicReturn) {
            waitingSettingsMicReturn = false;
            if (Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                if (openDialogAfterPermGrant) {
                    openDialogAfterPermGrant = false;
                    uiHandler.postDelayed(this::launchBuiltInVoiceRecorderDialog, 250L);
                } else if (webView != null) {
                    webView.postDelayed(() -> {
                        try { webView.evaluateJavascript("if(typeof window.__onNativeMicGranted==='function')window.__onNativeMicGranted();", null); } catch (Exception ignored) {}
                    }, 220);
                }
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        activeInstance = this;
        if (webView != null) {
            try { webView.onResume(); webView.resumeTimers(); } catch (Exception ignored) {}
        }
        updateForegroundState(true);
        handleNotificationOpenIntent(intent);
    }

    @Override
    protected void onPause() {
        updateForegroundState(false);
        super.onPause();
    }

    @Override
    protected void onStop() {
        updateForegroundState(false);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        stopDialogTimer();
        stopAndReleaseRecorder();
        if (activeInstance == this) activeInstance = null;
        updateForegroundState(false);
        super.onDestroy();
    }

    private void stopAndReleaseRecorder() {
        if (nativeRecorder != null) {
            try { nativeRecorder.stop(); } catch (Exception ignored) {}
            try { nativeRecorder.release(); } catch (Exception ignored) {}
            nativeRecorder = null;
        }
    }

    private void updateForegroundState(boolean inFg) {
        isAppInForeground = inFg;
        try {
            getSharedPreferences("cw_prefs", MODE_PRIVATE)
                    .edit()
                    .putBoolean("fg_active", inFg)
                    .putLong("fg_ts", System.currentTimeMillis())
                    .apply();
        } catch (Exception ignored) {}
        if (inFg) {
            ChanwanPushService.cancelAllMessageNotifs(this);
        } else {
            ChanwanPushService.wakeBackgroundPollNow();
        }
    }

    private void handleNotificationOpenIntent(Intent intent) {
        if (intent == null || !intent.getBooleanExtra("from_notif", false)) return;
        String nTitle = intent.getStringExtra("notif_title");
        String nBody = intent.getStringExtra("notif_body");
        long nWhen = intent.getLongExtra("notif_when", System.currentTimeMillis());
        intent.removeExtra("from_notif");
        ChanwanPushService.cancelAllMessageNotifs(this);

        final String safeTitle = JSONObject.quote(nTitle != null ? nTitle : "");
        final String safeBody = JSONObject.quote(nBody != null ? nBody : "");
        final String js = "(function(){try{"
                + "window.__pendingNotifOpen={title:" + safeTitle + ",body:" + safeBody + ",when:" + nWhen + "};"
                + "if(typeof window.__openChatFromNotification==='function')window.__openChatFromNotification(" + safeTitle + "," + safeBody + "," + nWhen + ");"
                + "else if(typeof window.switchTab==='function')window.switchTab('translator');"
                + "}catch(e){}})();";

        if (webView != null) {
            webView.post(() -> {
                try { webView.evaluateJavascript(js, null); } catch (Exception ignored) {}
            });
            webView.postDelayed(() -> {
                try {
                    ChanwanPushService.cancelAllMessageNotifs(MainActivity.this);
                    webView.evaluateJavascript("if(typeof window.__openChatFromNotification==='function'&&window.__pendingNotifOpen){var p=window.__pendingNotifOpen;window.__pendingNotifOpen=null;window.__openChatFromNotification(p.title,p.body,p.when);}", null);
                } catch (Exception ignored) {}
            }, 350);
        }
    }

    public static void nudgeForegroundWebViewSync() {
        final MainActivity act = activeInstance;
        if (act == null) return;
        act.runOnUiThread(() -> {
            try {
                if (act.webView != null) {
                    act.webView.evaluateJavascript("(function(){try{if(typeof window.__pullFastChatDeltaNow==='function')window.__pullFastChatDeltaNow(true);else if(typeof window.__pullCloudSync==='function')window.__pullCloudSync('live_push');}catch(e){}})();", null);
                }
            } catch (Exception ignored) {}
        });
    }

    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= 23) {
            List<String> need = new ArrayList<>();
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.POST_NOTIFICATIONS);
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.RECORD_AUDIO);
            }
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.CAMERA);
            }
            if (!need.isEmpty()) {
                requestPermissions(need.toArray(new String[0]), PERM_REQ);
            }
        }
    }

    private void startPushService() {
        try {
            Intent svc = new Intent(this, ChanwanPushService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } catch (Throwable ignored) {}
    }

    private void openSystemAppSettingsForMic() {
        waitingSettingsMicReturn = true;
        String role = getSharedPreferences("cw_prefs", MODE_PRIVATE).getString("role", "suwan");
        String msg = "jingchan".equals(role)
                ? "请在「权限 → 麦克风」中点允许，返回即可录音 💜"
                : "「권한 → 마이크 → 허용」을 켜고 뒤로 돌아오면 바로 녹음돼요 💜";
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.fromParts("package", getPackageName(), null));
            startActivity(intent);
        } catch (Exception ignored) {}
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MIC_PERM_REQ) {
            boolean granted = grantResults != null && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (pendingWebPermReq != null) {
                final PermissionRequest req = pendingWebPermReq;
                pendingWebPermReq = null;
                runOnUiThread(() -> {
                    try {
                        if (granted) req.grant(req.getResources());
                        else req.deny();
                    } catch (Exception ignored) {}
                });
            }
            if (granted) {
                if (openDialogAfterPermGrant) {
                    openDialogAfterPermGrant = false;
                    runOnUiThread(this::launchBuiltInVoiceRecorderDialog);
                } else if (webView != null) {
                    webView.post(() -> {
                        try { webView.evaluateJavascript("if(typeof window.__onNativeMicGranted==='function')window.__onNativeMicGranted();", null); } catch (Exception ignored) {}
                    });
                }
            } else if (Build.VERSION.SDK_INT >= 23 && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                runOnUiThread(this::openSystemAppSettingsForMic);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQ) {
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    ClipData clip = data.getClipData();
                    int cnt = clip.getItemCount();
                    results = new Uri[cnt];
                    for (int i = 0; i < cnt; i++) {
                        results[i] = clip.getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            if (results == null) {
                results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    public class NativeBridge {
        @JavascriptInterface
        public void setRole(String role) {
            if (role == null) return;
            String clean = role.trim().equals("jingchan") ? "jingchan" : "suwan";
            SharedPreferences prefs = getSharedPreferences("cw_prefs", MODE_PRIVATE);
            String prev = prefs.getString("role", "");
            if (!clean.equals(prev)) {
                prefs.edit().putString("role", clean).apply();
                startPushService();
            }
        }

        @JavascriptInterface
        public void setRoom(String room) {
            if (room == null) return;
            String clean = room.trim().replaceAll("[^a-zA-Z0-9_-]", "");
            if (clean.isEmpty()) clean = "chanwan-suwan-jingchan-live2026";
            SharedPreferences prefs = getSharedPreferences("cw_prefs", MODE_PRIVATE);
            String prev = prefs.getString("room", "");
            if (!clean.equals(prev)) {
                prefs.edit().putString("room", clean).apply();
                startPushService();
            }
        }

        @JavascriptInterface
        public void clearNotifications() {
            ChanwanPushService.cancelAllMessageNotifs(MainActivity.this);
        }

        @JavascriptInterface
        public void showOsNotification(String title, String body) {
            ChanwanPushService.postDirectFromWebView(MainActivity.this, title, body);
        }

        @JavascriptInterface
        public boolean isForeground() {
            return isAppInForeground;
        }

        @JavascriptInterface
        public boolean hasMicPermission() {
            if (Build.VERSION.SDK_INT < 23) return true;
            return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        }

        @JavascriptInterface
        public void requestMicPermission() {
            if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(() -> {
                    SharedPreferences prefs = getSharedPreferences("cw_prefs", MODE_PRIVATE);
                    boolean askedOnce = prefs.getBoolean("mic_asked_once", false);
                    boolean canShowRationale = shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO);
                    prefs.edit().putBoolean("mic_asked_once", true).apply();
                    if (askedOnce && !canShowRationale) {
                        openSystemAppSettingsForMic();
                    } else {
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_PERM_REQ);
                    }
                });
            }
        }

        @JavascriptInterface
        public synchronized boolean startNativeAudioRecord() {
            if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            stopAndReleaseRecorder();
            try {
                nativeRecFile = new File(getCacheDir(), "cw_voice_rec.m4a");
                if (nativeRecFile.exists()) nativeRecFile.delete();
                MediaRecorder mr = new MediaRecorder();
                mr.setAudioSource(MediaRecorder.AudioSource.MIC);
                mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                mr.setAudioSamplingRate(16000);
                mr.setAudioEncodingBitRate(32000);
                mr.setOutputFile(nativeRecFile.getAbsolutePath());
                mr.prepare();
                mr.start();
                nativeRecorder = mr;
                return true;
            } catch (Exception e) {
                stopAndReleaseRecorder();
                return false;
            }
        }

        @JavascriptInterface
        public synchronized String stopNativeAudioRecord() {
            if (nativeRecorder == null) return "";
            try {
                nativeRecorder.stop();
            } catch (Exception e) {
                stopAndReleaseRecorder();
                return "";
            }
            stopAndReleaseRecorder();
            if (nativeRecFile == null || !nativeRecFile.exists() || nativeRecFile.length() < 200) {
                return "";
            }
            try {
                byte[] buf = new byte[(int) nativeRecFile.length()];
                FileInputStream fis = new FileInputStream(nativeRecFile);
                int off = 0;
                while (off < buf.length) {
                    int r = fis.read(buf, off, buf.length - off);
                    if (r <= 0) break;
                    off += r;
                }
                fis.close();
                return "data:audio/mp4;base64," + Base64.encodeToString(buf, Base64.NO_WRAP);
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public int getApkVersion() {
            return APK_BUILD_VER;
        }

        @JavascriptInterface
        public void downloadAndInstallApk(String url) {
            downloadAndInstallApkInApp(url);
        }

        @JavascriptInterface
        public void openExternalUrl(String url) {
            if (url != null && url.contains(".apk")) {
                downloadAndInstallApkInApp(url);
            } else {
                openUrlExternally(url);
            }
        }

        @JavascriptInterface
        public void openAppSettings() {
            runOnUiThread(MainActivity.this::openSystemAppSettingsForMic);
        }
    }
}
