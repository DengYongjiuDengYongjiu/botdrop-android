package app.botdrop.shizuku;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

import app.botdrop.DashboardActivity;
import com.termux.R;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class ShizukuBridgeService extends Service {

    private static final String LOG_TAG = "ShizukuBridgeService";
    public static final String PREFS_NAME = "shizuku_bridge";
    public static final String PREF_TOKEN = "bridge_token";
    private static final String CHANNEL_ID = "shizuku_bridge";
    private static final int NOTIFICATION_ID = 2002;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private ShizukuManager mShizukuManager;
    private ShizukuShellExecutor mShellExecutor;
    private ShizukuBridgeServer mBridgeServer;
    private String mToken;

    private final ShizukuManager.StatusListener mStatusListener = this::onShizukuStatusChanged;
    private final ShizukuShellExecutor.ConnectionListener mConnectionListener =
        new ShizukuShellExecutor.ConnectionListener() {
            @Override
            public void onServiceConnected() {
                mHandler.post(() -> {
                    Logger.logInfo(LOG_TAG, "Shell service connected, starting bridge");
                    startBridgeServerIfReady();
                });
            }

            @Override
            public void onServiceDisconnected() {
                mHandler.post(() -> {
                    Logger.logWarn(LOG_TAG, "Shell service disconnected, keeping bridge for status tracking");
                    startBridgeServerIfReady();
                });
            }
        };

    @Override
    public void onCreate() {
        super.onCreate();
        Logger.logInfo(LOG_TAG, "Created");

        createNotificationChannel();
        mShizukuManager = ShizukuManager.getInstance();
        mShizukuManager.init(this);
        mShizukuManager.addStatusListener(mStatusListener);

        mShellExecutor = new ShizukuShellExecutor(this);
        mShellExecutor.addConnectionListener(mConnectionListener);
        ensureToken();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Logger.logInfo(LOG_TAG, "Start command");
        startForeground(NOTIFICATION_ID, buildNotification("Shizuku bridge stopped"));
        ensureToken();

        ShizukuManager.Status status = mShizukuManager == null ? ShizukuManager.Status.NOT_INSTALLED : mShizukuManager.getStatus();
        Logger.logInfo(LOG_TAG, "Shizuku status onStart: " + status);
        if (mShellExecutor != null) {
            mShellExecutor.bind();
        }

        refreshBridgeState();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Logger.logInfo(LOG_TAG, "Destroyed");

        stopBridgeServer();
        if (mShellExecutor != null) {
            mShellExecutor.removeConnectionListener(mConnectionListener);
            mShellExecutor.shutdown();
            mShellExecutor = null;
        }

        if (mShizukuManager != null) {
            mShizukuManager.removeStatusListener(mStatusListener);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void onShizukuStatusChanged(ShizukuManager.Status status) {
        Logger.logInfo(LOG_TAG, "Status changed: " + status);
        if (status == ShizukuManager.Status.READY) {
            ensureToken();
            if (mShellExecutor != null) {
                mShellExecutor.bind();
            }
        } else {
            if (mShellExecutor != null && mShellExecutor.isBound()) {
                mShellExecutor.unbind();
            }
        }
        refreshBridgeState();
    }

    private void refreshBridgeState() {
        if (mShellExecutor == null) {
            stopBridgeServer();
            return;
        }

        if (!mShellExecutor.isBound() && mShellExecutor.isReady()) {
            if (!mShellExecutor.bind()) {
                Logger.logWarn(LOG_TAG, "ShellService bind failed, start bridge anyway for status reporting");
                startBridgeServerIfReady();
            }
            return;
        }

        startBridgeServerIfReady();
    }

    private void startBridgeServerIfReady() {
        if (mShellExecutor == null) {
            return;
        }
        if (mBridgeServer == null) {
            mBridgeServer = new ShizukuBridgeServer(
                "127.0.0.1",
                ShizukuBridgeServer.DEFAULT_PORT,
                mToken,
                mShellExecutor,
                new ShizukuBridgeServer.StatusProvider() {
                    @Override
                    public String getStatus() {
                        return mShizukuManager == null ? "NOT_READY" : mShizukuManager.getStatus().name();
                    }

                    @Override
                    public boolean isShellServiceBound() {
                        return mShellExecutor != null && mShellExecutor.isBound();
                    }
                }
            );
        }

        if (!mBridgeServer.isRunning()) {
            boolean ok = mBridgeServer.start();
            if (ok) {
                writeBridgeConfig();
                if (mShizukuManager != null && mShizukuManager.isReady()) {
                    updateNotification("Shizuku bridge running (Shizuku ready)");
                } else {
                    updateNotification("Shizuku bridge running (Shizuku waiting)");
                }
            } else {
                Logger.logError(LOG_TAG, "Failed to start bridge server");
                updateNotification("Shizuku bridge failed");
            }
        }
    }

    private void stopBridgeServer() {
        if (mBridgeServer != null && mBridgeServer.isRunning()) {
            mBridgeServer.stop();
        }
        mBridgeServer = null;
        deleteBridgeConfig();
        updateNotification("Shizuku bridge stopped");
    }

    private void ensureToken() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String token = prefs.getString(PREF_TOKEN, null);
        if (token == null || token.trim().isEmpty()) {
            token = UUID.randomUUID().toString().replace("-", "");
            prefs.edit().putString(PREF_TOKEN, token).apply();
        }
        mToken = token;
    }

    private void writeBridgeConfig() {
        if (mToken == null) {
            return;
        }

        try {
            File home = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
            File openclawDir = new File(home, ".openclaw");
            if (!openclawDir.exists() && !openclawDir.mkdirs()) {
                Logger.logWarn(LOG_TAG, "Unable to create openclaw dir");
                return;
            }

            File config = new File(openclawDir, "shizuku-bridge.json");
            JSONObject payload = new JSONObject();
            payload.put("host", "127.0.0.1");
            payload.put("port", ShizukuBridgeServer.DEFAULT_PORT);
            payload.put("token", mToken);

            try (FileOutputStream fos = new FileOutputStream(config)) {
                fos.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to write bridge config: " + e.getMessage());
        }
    }

    private void deleteBridgeConfig() {
        try {
            File config = new File(TermuxConstants.TERMUX_HOME_DIR_PATH + "/.openclaw/shizuku-bridge.json");
            if (config.exists()) {
                config.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            "Shizuku Bridge",
            NotificationManager.IMPORTANCE_LOW
        );
        channel.setShowBadge(false);
        channel.setDescription("Runs local bridge for Shizuku command execution.");

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent openDashboard = new Intent(this, DashboardActivity.class);
        openDashboard.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent openPending = PendingIntent.getActivity(
            this,
            1002,
            openDashboard,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BotDrop Shizuku Bridge")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentIntent(openPending)
            .setOngoing(true)
            .setShowWhen(false)
            .build();
    }

    private void updateNotification(String text) {
        startForeground(NOTIFICATION_ID, buildNotification(text));
    }
}
