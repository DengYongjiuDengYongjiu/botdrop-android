package com.termux.shizuku;

import android.content.Context;
import android.content.SharedPreferences;

import com.termux.shared.logger.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

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
    private static final int MAX_RETRY_INTERVAL_MILLIS = 8_000;
    private static final int BINDER_POLL_COUNT = 16;
    private static final int BINDER_POLL_INTERVAL_MILLIS = 250;
    static final String PERMISSION_API = "app.botdrop.permission.API_V23";

    private static volatile boolean starting;

    private ShizukuBootstrap() {
    }

    public static void bootstrap(Context context) {
        ShizukuProvider.enableMultiProcessSupport(false);
        requestBinderForCurrentProcess(context);

        if (Shizuku.pingBinder()) {
            return;
        }

        ensureServerStarted(context);
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
        int lastMode = prefs.getInt(PREF_KEY_LAST_START_MODE, START_MODE_UNKNOWN);
        long lastAttempt = prefs.getLong(PREF_KEY_LAST_START_ATTEMPT, 0);

        if (System.currentTimeMillis() - lastAttempt < MAX_RETRY_INTERVAL_MILLIS) {
            return;
        }

        final Context appContext = context.getApplicationContext();
        starting = true;
        prefs.edit()
                .putLong(PREF_KEY_LAST_START_ATTEMPT, System.currentTimeMillis())
                .apply();

        new Thread(() -> {
            try {
                if (!hasRootPermission()) {
                    Logger.logWarn(LOG_TAG, "Root mode not available, skip Shizuku server startup");
                    prefs.edit().putInt(PREF_KEY_LAST_START_MODE, START_MODE_UNKNOWN).apply();
                    prefs.edit().putString(PREF_KEY_LAST_START_ERROR, "Root permission is not available");
                    return;
                }

                String token = "botdrop-" + UUID.randomUUID();
                String command = ServiceStarter.commandForUserService(
                        "/system/bin/app_process",
                        appContext.getApplicationInfo().sourceDir,
                        token,
                        appContext.getPackageName(),
                        "moe.shizuku.server.ShizukuService",
                        "shizuku",
                        android.os.Process.myUid(),
                        false
                );

                CommandResult result = executeShellAsRoot(command);
                Logger.logInfo(LOG_TAG, String.format(Locale.US,
                        "Shizuku root startup command finished, exit=%d, output=%s",
                        result.exitCode, result.output));

                if (result.exitCode == 0) {
                    for (int retry = 0; retry < BINDER_POLL_COUNT; retry++) {
                        if (Shizuku.pingBinder()) {
                            Logger.logInfo(LOG_TAG, "Shizuku binder ready after root startup");
                            prefs.edit().putInt(PREF_KEY_LAST_START_MODE, START_MODE_ROOT).apply();
                            prefs.edit().putString(PREF_KEY_LAST_START_ERROR, "no-error").apply();
                            return;
                        }

                        requestBinderForCurrentProcess(appContext);
                        sleepQuietly(BINDER_POLL_INTERVAL_MILLIS);
                    }
                }

                Logger.logWarn(LOG_TAG, "Shizuku root startup did not expose binder in time");
                prefs.edit().putString(PREF_KEY_LAST_START_ERROR, "Binder not available after startup");
            } catch (Throwable tr) {
                Logger.logWarn(LOG_TAG, "Shizuku bootstrap failed: " + tr.getMessage());
                prefs.edit().putInt(PREF_KEY_LAST_START_MODE, START_MODE_UNKNOWN).apply();
                prefs.edit().putString(PREF_KEY_LAST_START_ERROR, tr.getMessage());
            } finally {
                starting = false;
            }
        }, "ShizukuBootstrap").start();
    }

    private static boolean hasRootPermission() {
        CommandResult result = executeShellAsRoot("id -u");
        return result.exitCode == 0 && result.output.trim().startsWith("0");
    }

    private static CommandResult executeShellAsRoot(String command) {
        try {
            String[] shell = new String[]{"su", "-c", command};
            Process process = new ProcessBuilder(shell).start();
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
