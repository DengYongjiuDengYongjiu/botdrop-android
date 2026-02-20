package app.botdrop.shizuku;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Embedded Binder shell service entry point.
 * Executes commands and returns JSON results for the local bridge path.
 */
public class ShellService extends Service {

    private static final String LOG_TAG = "ShizukuShellService";
    private static final int DEFAULT_TIMEOUT_MS = 30000;
    private static final int ROOT_CHECK_TIMEOUT_MS = 1500;

    private final ExecutorService mStreamExecutor = Executors.newCachedThreadPool();
    private final Object mRootCheckLock = new Object();
    private volatile Boolean mHasRoot;

    private final IShellService.Stub mBinder = new IShellService.Stub() {
        @Override
        public String executeCommand(String command, int timeoutMs) {
            return ShellService.this.executeCommandInternal(command, timeoutMs);
        }

        @Override
        public void destroy() {
            Logger.logInfo(LOG_TAG, "Destroy requested by binder client");
            stopSelf();
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mHasRoot = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mStreamExecutor.shutdownNow();
    }

    private String executeCommandInternal(String command, int timeoutMs) {
        String safeCommand = command == null ? "" : command;
        int effectiveTimeout = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;

        JSONObject result = new JSONObject();
        int exitCode = -1;
        String stdout = "";
        String stderr = "";

        Process process = null;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(selectCommand(safeCommand));
            processBuilder.environment().put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            processBuilder.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
            processBuilder.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":" + System.getenv("PATH"));
            processBuilder.environment().put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
            processBuilder.environment().put("SSL_CERT_FILE", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/etc/tls/cert.pem");
            processBuilder.environment().put("NODE_OPTIONS", "--dns-result-order=ipv4first");
            process = processBuilder.start();

            Future<String> stdoutFuture = mStreamExecutor.submit(readProcessOutput(process.getInputStream()));
            Future<String> stderrFuture = mStreamExecutor.submit(readProcessOutput(process.getErrorStream()));

            if (!process.waitFor(effectiveTimeout, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                exitCode = -1;
                stderr = "Command timeout after " + effectiveTimeout + " ms";
            } else {
                exitCode = process.exitValue();
            }

            long streamTimeoutMs = Math.max(effectiveTimeout + 2000L, 2000L);
            stdout = getFutureResult(stdoutFuture, streamTimeoutMs);
            if (stderr == null || stderr.isEmpty()) {
                stderr = getFutureResult(stderrFuture, streamTimeoutMs);
            } else {
                String tail = getFutureResult(stderrFuture, streamTimeoutMs);
                if (!tail.isEmpty()) {
                    stderr = stderr + tail;
                }
            }
        } catch (IOException | InterruptedException e) {
            Logger.logError(LOG_TAG, "executeCommand failed: " + e.getMessage());
            stderr = (stderr == null ? "" : stderr) + "\n" + e.getMessage();
            if (process != null) {
                process.destroyForcibly();
            }
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        } catch (TimeoutException e) {
            stderr = "Failed to collect command output: " + e.getMessage();
            if (process != null) {
                process.destroyForcibly();
            }
            Logger.logWarn(LOG_TAG, "Stream read timed out");
        } catch (ExecutionException e) {
            stderr = "Failed to collect command output: " + e.getMessage();
            Logger.logWarn(LOG_TAG, stderr);
        }

        try {
            result.put("exitCode", exitCode);
            result.put("stdout", stdout == null ? "" : stdout);
            result.put("stderr", stderr == null ? "" : stderr);
            result.put("success", exitCode == 0);
        } catch (JSONException e) {
            Logger.logWarn(LOG_TAG, "Failed to build result JSON: " + e.getMessage());
        }

        return result.toString();
    }

    private String[] selectCommand(String command) {
        String payload = ensureTermuxEnvironment(command);
        if (hasRootAccess()) {
            return new String[]{"su", "-c", payload};
        }
        return new String[]{"sh", "-lc", payload};
    }

    private String ensureTermuxEnvironment(String command) {
        String safeCommand = command == null ? "" : command;
        return "export PREFIX=" + TermuxConstants.TERMUX_PREFIX_DIR_PATH + "; " +
            "export HOME=" + TermuxConstants.TERMUX_HOME_DIR_PATH + "; " +
            "export PATH=" + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":$PATH; " +
            "export TMPDIR=" + TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH + "; " +
            "export SSL_CERT_FILE=" + TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/etc/tls/cert.pem; " +
            "export NODE_OPTIONS=--dns-result-order=ipv4first; " +
            safeCommand;
    }

    private boolean hasRootAccess() {
        Boolean cached = mHasRoot;
        if (cached != null) {
            return cached;
        }

        synchronized (mRootCheckLock) {
            if (mHasRoot != null) {
                return mHasRoot;
            }

            Process process = null;
            try {
                process = new ProcessBuilder("su", "-c", "id -u").start();
                boolean finished = process.waitFor(ROOT_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    mHasRoot = false;
                    return false;
                }
                mHasRoot = process.exitValue() == 0;
                return mHasRoot;
            } catch (Exception e) {
                Logger.logWarn(LOG_TAG, "Root check failed: " + e.getMessage());
                mHasRoot = false;
                return false;
            } finally {
                if (process != null) {
                    process.destroy();
                }
            }
        }
    }

    private Callable<String> readProcessOutput(InputStream inputStream) {
        return () -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                char[] buffer = new char[4096];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    sb.append(buffer, 0, read);
                }
                return sb.toString();
            } catch (IOException e) {
                Logger.logWarn(LOG_TAG, "Failed reading stream: " + e.getMessage());
                return "";
            }
        };
    }

    private String getFutureResult(Future<String> future, long timeoutMs)
        throws ExecutionException, InterruptedException, TimeoutException {
        return future.get(timeoutMs, TimeUnit.MILLISECONDS);
    }
}
