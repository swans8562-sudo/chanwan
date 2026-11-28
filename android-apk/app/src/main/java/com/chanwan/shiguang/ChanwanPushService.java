package com.chanwan.shiguang;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.IBinder;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Iterator;
import java.util.LinkedHashSet;

public class ChanwanPushService extends Service {
    public static final int FG_NOTIF_ID = 1;
    private static final String CH_FG = "chanwan_fg_channel";
    private static final String CH_MSG = "chanwan_msg_high_v2";
    private static volatile ChanwanPushService activeServiceInstance = null;
    private final Object sbSleepLock = new Object();
    private static final String DEFAULT_ROOM = "chanwan-suwan-jingchan-live2026";
    private static final String SUPABASE_URL = "https://zdqkyftqogfgbfcxdmfh.supabase.co";
    private static final String SUPABASE_KEY = "sb_publishable_iwXcMp6Fee8bJofcYC19bg_AvOMJewt";

    private volatile boolean running = true;
    private volatile HttpURLConnection activeConn = null;
    private volatile String activeRole = "";
    private volatile String activeRoom = "";
    private Thread worker;
    private Thread sbFallbackWorker;
    private ConnectivityManager.NetworkCallback netCallback;
    private BroadcastReceiver screenOffReceiver = null;

    private final Object dedupLock = new Object();
    private String lastFp = "";
    private long lastFpMs = 0L;
    private String lastBody = "";
    private int lastNotifId = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        activeServiceInstance = this;
        createChannels();
        promoteToForegroundSafely();
        registerNetworkSwitchWatcher();
        try {
            screenOffReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    wakeBackgroundPollNow();
                }
            };
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_OFF);
            f.addAction(Intent.ACTION_USER_PRESENT);
            registerReceiver(screenOffReceiver, f);
        } catch (Throwable ignored) {}
    }

    private void registerNetworkSwitchWatcher() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    netCallback = new ConnectivityManager.NetworkCallback() {
                        @Override
                        public void onAvailable(Network network) {
                            // Immediately break any stale half-open socket on Wi-Fi <-> LTE switch!
                            HttpURLConnection c = activeConn;
                            if (c != null) {
                                new Thread(() -> {
                                    try { c.disconnect(); } catch (Exception ignored) {}
                                }).start();
                            }
                        }
                    };
                    cm.registerDefaultNetworkCallback(netCallback);
                }
            } catch (Throwable ignored) {}
        }
    }

    private void promoteToForegroundSafely() {
        try {
            Notification n = buildForegroundNotif();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(FG_NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
            } else {
                startForeground(FG_NOTIF_ID, n);
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        promoteToForegroundSafely();
        SharedPreferences prefs = getSharedPreferences("cw_prefs", MODE_PRIVATE);
        String desiredRole = prefs.getString("role", "suwan");
        String desiredRoom = prefs.getString("room", DEFAULT_ROOM);
        if (desiredRoom == null || desiredRoom.trim().isEmpty()) desiredRoom = DEFAULT_ROOM;

        boolean forceReconnect = intent != null && intent.getBooleanExtra("force_reconnect", false);
        if (worker == null || !worker.isAlive()) {
            running = true;
            worker = new Thread(this::streamLoop, "ChanwanNtfyStream");
            worker.start();
        } else if (forceReconnect || !desiredRole.equals(activeRole) || !desiredRoom.equals(activeRoom)) {
            HttpURLConnection c = activeConn;
            if (c != null) {
                new Thread(() -> {
                    try { c.disconnect(); } catch (Exception ignored) {}
                }).start();
            }
        }

        if (sbFallbackWorker == null || !sbFallbackWorker.isAlive()) {
            running = true;
            sbFallbackWorker = new Thread(this::supabaseFallbackLoop, "ChanwanSupabaseFallback");
            sbFallbackWorker.start();
        }
        return START_STICKY;
    }

    public static void wakeBackgroundPollNow() {
        ChanwanPushService inst = activeServiceInstance;
        if (inst != null) {
            synchronized (inst.sbSleepLock) {
                inst.sbSleepLock.notifyAll();
            }
        }
    }

    public static void postDirectFromWebView(Context ctx, String title, String body) {
        ChanwanPushService inst = activeServiceInstance;
        if (inst != null && ctx != null) {
            SharedPreferences prefs = ctx.getSharedPreferences("cw_prefs", MODE_PRIVATE);
            String role = prefs.getString("role", "suwan");
            inst.dispatchIncomingPush(prefs, role, title != null ? title : "💜 灿完时光", body != null ? body : "", System.currentTimeMillis());
        }
    }

    public static void cancelAllMessageNotifs(Context ctx) {
        if (ctx == null) return;
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                StatusBarNotification[] active = nm.getActiveNotifications();
                if (active != null) {
                    for (StatusBarNotification sbn : active) {
                        if (sbn != null && sbn.getId() != FG_NOTIF_ID) {
                            String tag = sbn.getTag();
                            if (tag != null) {
                                try { nm.cancel(tag, sbn.getId()); } catch (Exception ignored) {}
                            }
                            try { nm.cancel(sbn.getId()); } catch (Exception ignored) {}
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            NotificationChannel fg = new NotificationChannel(CH_FG, "灿完时光 실시간 연결", NotificationManager.IMPORTANCE_MIN);
            fg.setShowBadge(false);
            nm.createNotificationChannel(fg);

            NotificationChannel msg = new NotificationChannel(CH_MSG, "새 메시지 · 하트 알림", NotificationManager.IMPORTANCE_HIGH);
            msg.enableVibration(true);
            msg.setShowBadge(true);
            nm.createNotificationChannel(msg);
        }
    }

    private Notification buildForegroundNotif() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int pFlags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, pFlags);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CH_FG)
                : new Notification.Builder(this);
        return b.setContentTitle("灿完时光")
                .setContentText("실시간 알림 대기 중 💜")
                .setSmallIcon(R.drawable.ic_stat_heart)
                .setContentIntent(pi)
                .setNumber(0)
                .setShowWhen(false)
                .setOngoing(true)
                .build();
    }

    private static boolean hasHangul(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '\uAC00' && c <= '\uD7A3') return true;
        }
        return false;
    }

    private static boolean hasCjkNoHangul(String s) {
        if (s == null || hasHangul(s)) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '\u4E00' && c <= '\u9FFF') return true;
        }
        return false;
    }

    private LinkedHashSet<String> loadSeenIds(SharedPreferences prefs, String key) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        String raw = prefs.getString(key, "");
        if (raw != null && !raw.isEmpty()) {
            String[] parts = raw.split(",");
            for (String p : parts) {
                String clean = p.trim();
                if (!clean.isEmpty()) set.add(clean);
            }
        }
        return set;
    }

    private void saveSeenIds(SharedPreferences prefs, String key, LinkedHashSet<String> set, String lastId, long streamSec) {
        while (set.size() > 100) {
            Iterator<String> it = set.iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            } else {
                break;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String s : set) {
            if (sb.length() > 0) sb.append(',');
            sb.append(s);
        }
        SharedPreferences.Editor ed = prefs.edit().putString(key, sb.toString());
        if (lastId != null && !lastId.isEmpty()) ed.putString("last_id", lastId);
        if (streamSec > 0L) ed.putLong("last_stream_sec", streamSec);
        ed.apply();
    }

    private void dispatchIncomingPush(SharedPreferences prefs, String role, String title, String body, long whenMs) {
        dispatchIncomingPush(prefs, role, title, body, whenMs, null);
    }

    private void dispatchIncomingPush(SharedPreferences prefs, String role, String title, String body, long whenMs, String rawPro2Json) {
        if (body == null || body.isEmpty()) return;
        if (body.startsWith("⏳ 번역 중") || body.startsWith("⏳ 正在翻译")) return;

        prefs.edit()
                .putString("last_push_title", title)
                .putString("last_push_body", body)
                .putLong("last_push_when", whenMs)
                .apply();

        if (MainActivity.isActivelyVisibleOnScreen()) {
            MainActivity.deliverIncomingFromService(title, body, rawPro2Json);
            return;
        }

        long nowMs = System.currentTimeMillis();
        int notifId;
        boolean silentInPlaceUpdate = false;
        synchronized (dedupLock) {
            String fp = title + "|" + body;
            if (fp.equals(lastFp) && (nowMs - lastFpMs) < 45000L) {
                return;
            }
            if (!lastBody.isEmpty() && lastBody.equals(body) && (nowMs - lastFpMs) < 45000L) {
                return;
            }
            if ("suwan".equals(role) && lastNotifId > FG_NOTIF_ID && (nowMs - lastFpMs) < 12000L
                    && hasCjkNoHangul(lastBody) && hasHangul(body)) {
                notifId = lastNotifId;
                silentInPlaceUpdate = true;
            } else {
                notifId = (int) ((nowMs & 0x3fffffffL) + 1000L);
                if (notifId == lastNotifId) notifId++;
            }
            lastFp = fp;
            lastFpMs = nowMs;
            lastBody = body;
            lastNotifId = notifId;
        }

        showMessageNotif(title, body, whenMs, notifId, silentInPlaceUpdate);
    }

    private void handlePro2SyncStreamPayload(SharedPreferences prefs, String role, String rawMsg, long fallbackWhenMs, LinkedHashSet<String> seenPro2Ids) {
        if (rawMsg == null || rawMsg.isEmpty() || !rawMsg.startsWith("{")) return;
        try {
            JSONObject pObj = new JSONObject(rawMsg);
            if (pObj.optInt("__e2ee", 0) == 1) {
                if (MainActivity.isActivelyVisibleOnScreen()) {
                    MainActivity.deliverIncomingFromService("", "", rawMsg);
                }
                return;
            }
            String ver = pObj.optString("v", "");
            if ("pro2_signal".equals(ver)) {
                if (MainActivity.isActivelyVisibleOnScreen()) {
                    MainActivity.deliverIncomingFromService("", "", rawMsg);
                }
                return;
            }
            if (!"pro2".equals(ver)) return;

            if (MainActivity.isActivelyVisibleOnScreen()) {
                MainActivity.deliverIncomingFromService("", "", rawMsg);
                return;
            }

            JSONObject chat = pObj.optJSONObject("chat");
            JSONArray msgs = (chat != null) ? chat.optJSONArray("messages") : null;
            if (msgs == null || msgs.length() == 0) return;

            long nowMs = System.currentTimeMillis();
            boolean pro2Seeded = prefs.getBoolean("pro2_stream_seeded_v71", false);
            if (!pro2Seeded) {
                for (int i = 0; i < msgs.length(); i++) {
                    JSONObject m = msgs.optJSONObject(i);
                    if (m == null) continue;
                    String mid = m.optString("id", "");
                    long mts = m.optLong("ts", 0L);
                    if (!mid.isEmpty() && (mts <= 0L || (nowMs - mts) > 90000L)) {
                        seenPro2Ids.add(mid);
                    }
                }
                prefs.edit().putBoolean("pro2_stream_seeded_v71", true).apply();
                saveSeenIds(prefs, "seen_pro2_msg_ids", seenPro2Ids, null, 0L);
            }

            for (int i = 0; i < msgs.length(); i++) {
                JSONObject m = msgs.optJSONObject(i);
                if (m == null) continue;
                String mid = m.optString("id", "");
                if (mid.isEmpty()) continue;

                String sender = m.optString("sender", "");
                String normSender = (sender.equals("jingchan") || sender.contains("靖灿")) ? "jingchan" : "suwan";
                if (normSender.equals(role)) {
                    seenPro2Ids.add(mid);
                    continue;
                }

                long mts = m.optLong("ts", 0L);
                if (mts > 0L && (nowMs - mts) > 1800000L) {
                    seenPro2Ids.add(mid);
                    continue;
                }

                String orig = m.optString("original", "").trim();
                String tr = m.optString("translated", "").trim();
                boolean hasReadyTrans = !tr.isEmpty() && !tr.startsWith("⏳");
                String stateKey = hasReadyTrans ? (mid + ":tr") : mid;
                if (seenPro2Ids.contains(stateKey) || (seenPro2Ids.contains(mid + ":tr"))) continue;

                seenPro2Ids.add(mid);
                if (hasReadyTrans) seenPro2Ids.add(mid + ":tr");
                saveSeenIds(prefs, "seen_pro2_msg_ids", seenPro2Ids, null, 0L);

                String dispBody = hasReadyTrans ? tr : orig;
                if (dispBody.isEmpty()) {
                    if (m.optBoolean("hasVoice", false)) dispBody = "suwan".equals(role) ? "음성 메시지를 보냈어요 🎙️" : "发送了一条语音 🎙️";
                    else if (m.optBoolean("hasVideo", false)) dispBody = "suwan".equals(role) ? "동영상을 보냈어요 🎬" : "发送了一段视频 🎬";
                    else dispBody = "suwan".equals(role) ? "새 메시지가 도착했어요" : "发来了一条新消息";
                }
                String dispTitle = "suwan".equals(role) ? "💜 징찬" : "💜 秀完";
                dispatchIncomingPush(prefs, role, dispTitle, dispBody, mts > 0L ? mts : fallbackWhenMs, rawMsg);
            }
        } catch (Exception ignored) {}
    }

    private void streamLoop() {
        SharedPreferences initPrefs = getSharedPreferences("cw_prefs", MODE_PRIVATE);
        LinkedHashSet<String> seenIds = loadSeenIds(initPrefs, "seen_ids");
        LinkedHashSet<String> seenPro2Ids = loadSeenIds(initPrefs, "seen_pro2_msg_ids");

        while (running) {
            HttpURLConnection conn = null;
            try {
                SharedPreferences prefs = getSharedPreferences("cw_prefs", MODE_PRIVATE);
                String role = prefs.getString("role", "suwan");
                String room = prefs.getString("room", DEFAULT_ROOM);
                if (room == null || room.trim().isEmpty()) room = DEFAULT_ROOM;
                activeRole = role;
                activeRoom = room;

                String lastId = prefs.getString("last_id", "");
                long lastStreamSec = prefs.getLong("last_stream_sec", 0L);
                long nowConnSec = System.currentTimeMillis() / 1000L;

                // Recover up to 25 minutes of missed Doze / elevator / LTE-switch notifications without dropping!
                String sinceParam;
                if (!lastId.isEmpty() && lastStreamSec > 0L && (nowConnSec - lastStreamSec) <= 1800L) {
                    sinceParam = "?since=" + URLEncoder.encode(lastId, "UTF-8");
                } else {
                    sinceParam = "?since=25m";
                }

                String cleanRoom = room.replaceAll("[^a-zA-Z0-9_-]", "");
                if (cleanRoom.length() > 48) cleanRoom = cleanRoom.substring(0, 48);
                if (cleanRoom.isEmpty()) cleanRoom = DEFAULT_ROOM;

                String pushTopic = room + "-" + role;
                String syncTopic = "chanwan_pro2_" + cleanRoom;
                String combinedTopics = pushTopic + "," + syncTopic;
                URL url = new URL("https://ntfy.sh/" + combinedTopics + "/json" + sinceParam);
                conn = (HttpURLConnection) url.openConnection();
                activeConn = conn;
                conn.setReadTimeout(75000);
                conn.setConnectTimeout(12000);
                conn.connect();
                int code = conn.getResponseCode();
                if (code == 429) {
                    try { conn.disconnect(); } catch (Exception ignored) {}
                    activeConn = null;
                    try { Thread.sleep(28000L); } catch (InterruptedException ie) { break; }
                    continue;
                }

                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                String line;
                while (running && (line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    long recvSec = System.currentTimeMillis() / 1000L;
                    JSONObject obj = new JSONObject(line);
                    String ev = obj.optString("event", "");
                    String id = obj.optString("id", "");

                    if ("open".equals(ev) || "keepalive".equals(ev)) {
                        prefs.edit().putLong("last_stream_sec", recvSec).apply();
                        continue;
                    }
                    if (!"message".equals(ev)) continue;

                    if (!id.isEmpty()) {
                        if (seenIds.contains(id)) {
                            prefs.edit().putLong("last_stream_sec", recvSec).apply();
                            continue;
                        }
                        seenIds.add(id);
                        saveSeenIds(prefs, "seen_ids", seenIds, id, recvSec);
                    }

                    long msgSec = obj.optLong("time", 0L);
                    if (msgSec > 0L && (recvSec - msgSec) > 1800L) {
                        continue;
                    }

                    String msgTopic = obj.optString("topic", "");
                    String rawMessage = obj.optString("message", "").trim();
                    long whenMs = (msgSec > 0L) ? (msgSec * 1000L) : System.currentTimeMillis();

                    if (syncTopic.equals(msgTopic)) {
                        handlePro2SyncStreamPayload(prefs, role, rawMessage, whenMs, seenPro2Ids);
                        continue;
                    }

                    String title = obj.optString("title", "💜 灿完时光").trim();
                    String body = rawMessage.isEmpty() ? "새 메시지가 도착했어요" : rawMessage;
                    dispatchIncomingPush(prefs, role, title, body, whenMs, null);
                }
            } catch (Exception ignored) {
                try { Thread.sleep(3000); } catch (InterruptedException ie) { break; }
            } finally {
                activeConn = null;
                if (conn != null) {
                    try { conn.disconnect(); } catch (Exception ignored) {}
                }
            }
        }
    }

    // Primary-Grade Mainland-China & Rate-Limit Proof Poll: checks Supabase Seoul couple_sync_state every 7s in background!
    private void supabaseFallbackLoop() {
        SharedPreferences initPrefs = getSharedPreferences("cw_prefs", MODE_PRIVATE);
        LinkedHashSet<String> seenMsgIds = loadSeenIds(initPrefs, "seen_sb_msg_ids");
        boolean baselineSeeded = initPrefs.getBoolean("sb_baseline_seeded", false);
        long lastCheckedUpdatedAt = initPrefs.getLong("sb_last_updated_at", 0L);
        int lastHeartCount = initPrefs.getInt("sb_last_heart_cnt", -1);

        while (running) {
            HttpURLConnection conn = null;
            try {
                synchronized (sbSleepLock) {
                    sbSleepLock.wait(MainActivity.isActivelyVisibleOnScreen() ? 25000L : 3200L);
                }
                if (!running || MainActivity.isActivelyVisibleOnScreen()) continue;

                SharedPreferences prefs = getSharedPreferences("cw_prefs", MODE_PRIVATE);
                String role = prefs.getString("role", "suwan");
                String room = prefs.getString("room", DEFAULT_ROOM);
                if (room == null || room.trim().isEmpty()) room = DEFAULT_ROOM;

                // 1-Minute Background Stream Self-Heal: if ntfy stream had zero keepalive/open for >65s, break stale socket so streamLoop reconnects immediately!
                long lastStreamAliveSec = prefs.getLong("last_stream_sec", 0L);
                long nowSecCheck = System.currentTimeMillis() / 1000L;
                if (lastStreamAliveSec > 0L && (nowSecCheck - lastStreamAliveSec) >= 65L) {
                    prefs.edit().putLong("last_stream_sec", nowSecCheck).apply();
                    HttpURLConnection staleC = activeConn;
                    if (staleC != null) {
                        new Thread(() -> {
                            try { staleC.disconnect(); } catch (Exception ignored) {}
                        }).start();
                    }
                    if (worker == null || !worker.isAlive()) {
                        worker = new Thread(this::streamLoop, "ChanwanNtfyStream");
                        worker.start();
                    }
                }

                String qUrl = SUPABASE_URL + "/rest/v1/couple_sync_state?room_id=eq."
                        + URLEncoder.encode(room, "UTF-8") + "&select=payload";
                conn = (HttpURLConnection) new URL(qUrl).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("apikey", SUPABASE_KEY);
                conn.setRequestProperty("Accept", "application/json");
                if (conn.getResponseCode() != 200) continue;

                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String ln;
                while ((ln = br.readLine()) != null) sb.append(ln);

                JSONArray arr = new JSONArray(sb.toString());
                if (arr.length() == 0) continue;
                JSONObject row = arr.getJSONObject(0);
                JSONObject payload = row.optJSONObject("payload");
                long updatedAt = (payload != null) ? payload.optLong("ts", 0L) : 0L;
                if (updatedAt > 0L && updatedAt == lastCheckedUpdatedAt && baselineSeeded) continue;
                if (payload == null || payload.optBoolean("__cw_e2ee", false) || payload.optInt("__e2ee", 0) == 1) {
                    lastCheckedUpdatedAt = updatedAt;
                    continue;
                }

                int curHeart = payload.optInt("heartCount", 0);
                String writerRole = payload.optString("myRole", "");
                if (baselineSeeded && lastHeartCount >= 0 && curHeart > lastHeartCount && !writerRole.isEmpty() && !writerRole.equals(role)) {
                    String hTitle = "suwan".equals(role) ? "💓 징찬 · 하트비트" : "💓 秀完 · 心跳";
                    String hBody = "suwan".equals(role) ? "지금 보고 싶어요 💜" : "现在好想你 💜";
                    dispatchIncomingPush(prefs, role, hTitle, hBody, System.currentTimeMillis());
                }
                lastHeartCount = curHeart;
                prefs.edit().putInt("sb_last_heart_cnt", curHeart).apply();

                JSONObject chat = payload.optJSONObject("chat");
                JSONArray msgs = (chat != null) ? chat.optJSONArray("messages") : null;
                if (msgs == null || msgs.length() == 0) {
                    lastCheckedUpdatedAt = updatedAt;
                    continue;
                }

                long nowMs = System.currentTimeMillis();
                if (!baselineSeeded) {
                    for (int i = Math.max(0, msgs.length() - 30); i < msgs.length(); i++) {
                        JSONObject m = msgs.optJSONObject(i);
                        if (m == null) continue;
                        String mid = m.optString("id", "");
                        long mts = m.optLong("ts", 0L);
                        // Seed older messages, but allow brand-new (<15s) partner messages right after minimizing to still notify!
                        if (!mid.isEmpty() && (mts <= 0L || (nowMs - mts) > 15000L)) {
                            seenMsgIds.add(mid);
                        }
                    }
                    baselineSeeded = true;
                    prefs.edit().putBoolean("sb_baseline_seeded", true).apply();
                    saveSeenIds(prefs, "seen_sb_msg_ids", seenMsgIds, null, 0L);
                }

                boolean deferredPending = false;
                for (int i = Math.max(0, msgs.length() - 6); i < msgs.length(); i++) {
                    JSONObject m = msgs.optJSONObject(i);
                    if (m == null) continue;
                    String mid = m.optString("id", "");
                    if (mid.isEmpty() || seenMsgIds.contains(mid)) continue;
                    long mtsPeek = m.optLong("ts", 0L);
                    String trPeek = m.optString("translated", "").trim();
                    if ((trPeek.startsWith("⏳ 번역 중") || trPeek.startsWith("⏳ 正在翻译")) && mtsPeek > 0L && (nowMs - mtsPeek) < 8500L) {
                        deferredPending = true;
                        continue;
                    }
                    seenMsgIds.add(mid);
                    saveSeenIds(prefs, "seen_sb_msg_ids", seenMsgIds, null, 0L);

                    String sender = m.optString("sender", "");
                    String normSender = (sender.equals("jingchan") || sender.contains("靖灿")) ? "jingchan" : "suwan";
                    if (normSender.equals(role)) continue;

                    long mts = m.optLong("ts", 0L);
                    if (mts > 0L && (nowMs - mts) > 1800000L) continue;

                    String orig = m.optString("original", "").trim();
                    String tr = m.optString("translated", "").trim();
                    String dispBody = (!tr.isEmpty() && !tr.startsWith("⏳")) ? tr : orig;
                    if (dispBody.isEmpty()) dispBody = "suwan".equals(role) ? "새 메시지가 도착했어요" : "发来了一条新消息";
                    String dispTitle = "suwan".equals(role) ? "💜 징찬" : "💜 秀完";

                    dispatchIncomingPush(prefs, role, dispTitle, dispBody, mts > 0L ? mts : nowMs);
                }
                if (!deferredPending) {
                    lastCheckedUpdatedAt = updatedAt;
                    prefs.edit().putLong("sb_last_updated_at", updatedAt).apply();
                }
            } catch (InterruptedException ie) {
                break;
            } catch (Exception ignored) {
            } finally {
                if (conn != null) {
                    try { conn.disconnect(); } catch (Exception ignored) {}
                }
            }
        }
    }

    private void showMessageNotif(String title, String body, long whenMs, int notifId, boolean onlyAlertOnce) {
        Intent open = new Intent(this, MainActivity.class);
        open.putExtra("from_notif", true);
        open.putExtra("notif_title", title);
        open.putExtra("notif_body", body);
        open.putExtra("notif_when", whenMs);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(this, notifId, open, flags);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CH_MSG)
                : new Notification.Builder(this);
        b.setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setSmallIcon(R.drawable.ic_stat_heart)
                .setNumber(1)
                .setWhen(whenMs)
                .setShowWhen(true)
                .setAutoCancel(true)
                .setOnlyAlertOnce(onlyAlertOnce)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPriority(Notification.PRIORITY_HIGH)
                .setDefaults(onlyAlertOnce ? 0 : Notification.DEFAULT_ALL)
                .setContentIntent(pi);

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(notifId, b.build());
    }

    @Override
    public void onDestroy() {
        running = false;
        if (activeServiceInstance == this) activeServiceInstance = null;
        synchronized (sbSleepLock) { sbSleepLock.notifyAll(); }
        if (screenOffReceiver != null) {
            try { unregisterReceiver(screenOffReceiver); } catch (Throwable ignored) {}
            screenOffReceiver = null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && netCallback != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) cm.unregisterNetworkCallback(netCallback);
            } catch (Throwable ignored) {}
        }
        HttpURLConnection c = activeConn;
        if (c != null) {
            try { c.disconnect(); } catch (Exception ignored) {}
        }
        if (worker != null) worker.interrupt();
        if (sbFallbackWorker != null) sbFallbackWorker.interrupt();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
