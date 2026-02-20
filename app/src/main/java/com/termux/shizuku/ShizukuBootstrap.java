package com.termux.shizuku;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

import androidx.lifecycle.Observer;

import com.termux.shared.logger.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import moe.shizuku.manager.ShizukuSettings;
import moe.shizuku.manager.adb.AdbClient;
import moe.shizuku.manager.adb.AdbKey;
import moe.shizuku.manager.adb.AdbMdns;
import moe.shizuku.manager.adb.PreferenceAdbKeyStore;
import moe.shizuku.manager.starter.Starter;
import moe.shizuku.starter.ServiceStarter;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuProvider;

public final class ShizukuBootstrap {

    private static final String LOG_TAG = "ShizukuBootstrap";
    private static final String PREFS_NAME = "shizuku_single_app_prefs";
    private static final String PREF_KEY_LAST_START_MODE = "last_start_mode";
    private static final String PREF_KEY_LAST_START_ATTEMPT = "last_start_attempt";
    private static final String PREF_KEY_LAST_START_ERROR = "last_start_error";

    public static final int START_MODE_UNKNOWN = 0;
    public static final int START_MODE_ROOT = 1;
    public static final int START_MODE_ADB = 2;
    private static final int MAX_RETRY_INTERVAL_MILLIS = 8_000;
    private static final int BINDER_POLL_COUNT = 16;
    private static final int BINDER_POLL_INTERVAL_MILLIS = 250;
    private static final int ADB_DISCOVERY_TIMEOUT_MILLIS = 3_000;
    private static final String ADB_WIFI_ENABLED_FLAG = "adb_wifi_enabled";
    static final String PERMISSION_API = "app.botdrop.permission.API_V23";

    private static volatile boolean starting;
    private static volatile Context appContext;

    private ShizukuBootstrap() {
    }

    public static void bootstrap(Context context) {
        appContext = context == null ? null : context.getApplicationContext();
        if (appContext == null) {
            return;
        }

        ShizukuProvider.enableMultiProcessSupport(false);
        requestBinderForCurrentProcess(appContext);

        if (Shizuku.pingBinder()) {
            return;
        }

        ensureServerStarted(appContext);
    }

    private static void requestBinderForCurrentProcess(Context context) {
        try {
            ShizukuProvider.requestBinderForNonProviderProcess(context);
        } catch (Throwable tr) {
            Logger.logWarn(LOG_TAG, "Failed to request binder from ShizukuProvider: " + tr.getMessage());
        }
    }

    private static void ensureServerStarted(Context context) {
        if (starting) {
            return;
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long lastAttempt = prefs.getLong(PREF_KEY_LAST_START_ATTEMPT, 0);
        if (System.currentTimeMillis() - lastAttempt < MAX_RETRY_INTERVAL_MILLIS) {
            return;
        }

        starting = true;
        prefs.edit()
                .putLong(PREF_KEY_LAST_START_ATTEMPT, System.currentTimeMillis())
                .apply();

        new Thread(() -> {
            try {
                if (startWithRoot(context, prefs)) {
                    return;
                }

                if (startWithAdb(context, prefs)) {
                    return;
                }

                Logger.logWarn(LOG_TAG, "Shizuku startup failed in both root and adb paths");
                prefs.edit().putInt(PREF_KEY_LAST_START_MODE, START_MODE_UNKNOWN).apply();
                prefs.edit().putString(PREF_KEY_LAST_START_ERROR, "Unable to start via root or adb").apply();
            } catch (Throwable tr) {
                Logger.logWarn(LOG_TAG, "Shizuku bootstrap failed: " + tr.getMessage());
                prefs.edit().putInt(PREF_KEY_LAST_START_MODE, START_MODE_UNKNOWN).apply();
                prefs.edit().putString(PREF_KEY_LAST_START_ERROR, tr.getMessage()).apply();
            } finally {
                starting = false;
            }
        }, "ShizukuBootstrap").start();
    }

    private static boolean startWithRoot(Context context, SharedPreferences prefs) {
        if (!hasRootPermission()) {
            Logger.logWarn(LOG_TAG, "Root mode not available");
            return false;
        }

        try {
            String token = "botdrop-" + UUID.randomUUID();
            String command = ServiceStarter.commandForUserService(
                    "/system/bin/app_process",
                    context.getApplicationInfo().sourceDir,
                    token,
                    context.getPackageName(),
                    "moe.shizuku.server.ShizukuService",
                    "shizuku",
                    android.os.Process.myUid(),
                    false
            );

            CommandResult result = executeShellAsRoot(command);
            Logger.logInfo(LOG_TAG, String.format(Locale.US,
                    "Shizuku root startup command finished, exit=%d, output=%s",
                    result.exitCode, result.output));

            if (result.exitCode == 0 && waitForBinder(context)) {
                Logger.logInfo(LOG_TAG, "Shizuku binder ready after root startup");
                prefs.edit().putInt(PREF_KEY_LAST_START_MODE, START_MODE_ROOT).apply();
                prefs.edit().putString(PREF_KEY_LAST_START_ERROR, "no-error").apply();
                return true;
            }

            Logger.logWarn(LOG_TAG, "Shizuku root startup did not expose binder in time");
            prefs.edit().putString(PREF_KEY_LAST_START_ERROR, "Binder not available after root startup").apply();
            return false;
        } catch (Throwable tr) {
            Logger.logWarn(LOG_TAG, "Shizuku root startup failed: " + tr.getMessage());
            prefs.edit().putString(PREF_KEY_LAST_START_ERROR, tr.getMessage()).apply();
            return false;
        }
    }

    private static boolean startWithAdb(Context context, SharedPreferences prefs) {
        if (!isAdbStartAllowed(context)) {
            Logger.logWarn(LOG_TAG, "ADB start not allowed");
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            enableWirelessAdb(context);
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (context.getContentResolver() == null) {
                    prefs.edit().putString(PREF_KEY_LAST_START_ERROR, "Invalid content resolver").apply();
                    return false;
                }
                if (Settings.Global.getInt(context.getContentResolver(), ADB_WIFI_ENABLED_FLAG, 0) != 1) {
                    prefs.edit().putString(PREF_KEY_LAST_START_ERROR, "ADB over WiFi is not enabled").apply();
                    return false;
                }
            }
        } catch (Throwable tr) {
            Logger.logWarn(LOG_TAG, "Failed to check adb status: " + tr.getMessage());
            prefs.edit().putString(PREF_KEY_LAST_START_ERROR, "Failed to check adb status").apply();
            return false;
        }

        if (ShizukuSettings.getPreferences() == null) {
            Logger.logWarn(LOG_TAG, "ShizukuSettings not initialized, cannot start with adb");
            prefs.edit().putString(PREF_KEY_LAST_START_ERROR, "Shizuku settings not initialized").apply();
            return false;
        }

        final AtomicBoolean observed = new AtomicBoolean(false);
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean bootSuccess = new AtomicBoolean(false);

        AdbMdns adbMdns = new AdbMdns(context, AdbMdns.TLS_CONNECT, new Observer<Integer>() {
            @Override
            public void onChanged(Integer port) {
                if (port == null || port <= 0 || observed.get()) {
                    return;
                }
                if (!observed.compareAndSet(false, true)) {
                    return;
                }

                try {
                    AdbKey key = new AdbKey(new PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku");
                    AdbClient client = new AdbClient("127.0.0.1", port, key);
                    client.connect();
                    client.shellCommand(Starter.INSTANCE.internalCommand(context), null);
                    client.close();
                    bootSuccess.set(waitForBinder(context));
                } catch (Throwable tr) {
                    Logger.logWarn(LOG_TAG, "ADB start execution failed on port " + port + ": " + tr.getMessage());
                    bootSuccess.set(false);
                } finally {
                    latch.countDown();
                }
            }
        });

        try {
            adbMdns.start();
            latch.await(ADB_DISCOVERY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            adbMdns.stop();
            if (!observed.get()) {
                Logger.logWarn(LOG_TAG, "No adb TLS connect service discovered in time");
                prefs.edit().putString(PREF_KEY_LAST_START_ERROR, "ADB discovery timeout").apply();
                return false;
            }

            if (bootSuccess.get()) {
                Logger.logInfo(LOG_TAG, "Shizuku binder ready after adb startup");
                prefs.edit().putInt(PREF_KEY_LAST_START_MODE, START_MODE_ADB).apply();
                prefs.edit().putString(PREF_KEY_LAST_START_ERROR, "no-error").apply();
                return true;
            }
        } catch (Throwable tr) {
            Logger.logWarn(LOG_TAG, "Shizuku adb startup failed: " + tr.getMessage());
            prefs.edit().putString(PREF_KEY_LAST_START_ERROR, tr.getMessage()).apply();
        }

        return false;
    }

    private static boolean isAdbStartAllowed(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true;
        }
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    private static void enableWirelessAdb(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) {
            Logger.logWarn(LOG_TAG, "WRITE_SECURE_SETTINGS is not granted");
            return;
        }

        try {
            Settings.Global.putInt(context.getContentResolver(), ADB_WIFI_ENABLED_FLAG, 1);
            Settings.Global.putInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, 1);
            Settings.Global.putLong(context.getContentResolver(), "adb_allowed_connection_time", 0L);
        } catch (Throwable tr) {
            Logger.logWarn(LOG_TAG, "Unable to enable adb wireless setting: " + tr.getMessage());
        }
    }

    private static boolean waitForBinder(Context context) {
        for (int retry = 0; retry < BINDER_POLL_COUNT; retry++) {
            if (Shizuku.pingBinder()) {
                requestBinderForCurrentProcess(context);
                return true;
            }
            sleepQuietly(BINDER_POLL_INTERVAL_MILLIS);
        }
        return false;
    }

    private static boolean hasRootPermission() {
        CommandResult result = executeShellAsRoot("id -u");
        return result.exitCode == 0 && result.output.trim().startsWith("0");
    }

    private static CommandResult executeShellAsRoot(String command) {
        return executeShell(new String[]{"su", "-c", command});
    }

    private static CommandResult executeShell(String[] command) {
        try {
            Process process = new ProcessBuilder(command).start();
            String output = readAll(process.getInputStream());
            int exitCode = process.waitFor();
            return new CommandResult(exitCode, output.trim());
        } catch (Throwable tr) {
            return new CommandResult(-1, String.valueOf(tr.getMessage()));
        }
    }

    private static String readAll(InputStream inputStream) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        int count;
        while ((count = inputStream.read(buffer)) != -1) {
            byteArrayOutputStream.write(buffer, 0, count);
        }
        return new String(byteArrayOutputStream.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    public static int getLastStartMode(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(PREF_KEY_LAST_START_MODE, START_MODE_UNKNOWN);
    }

    public static long getLastStartAttempt(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(PREF_KEY_LAST_START_ATTEMPT, 0);
    }

    public static String getLastStartError(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_KEY_LAST_START_ERROR, "no-error");
    }

    public static String startModeToString(int mode) {
        switch (mode) {
            case START_MODE_ROOT:
                return "ROOT";
            case START_MODE_ADB:
                return "ADB";
            case START_MODE_UNKNOWN:
            default:
                return "UNKNOWN";
        }
    }

    private static class CommandResult {
        final int exitCode;
        final String output;

        CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }
    }
}
