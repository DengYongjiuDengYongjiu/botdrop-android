package app.botdrop.shizuku;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;

import com.termux.shared.logger.Logger;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class ShizukuShellExecutor {

    private static final String LOG_TAG = "ShizukuShellExecutor";
    private static final int DEFAULT_TIMEOUT_MS = 30000;

    public interface ResultCallback {
        void onResult(Result result);
    }

    public interface ConnectionListener {
        void onServiceConnected();
        void onServiceDisconnected();
    }

    public static final class Result {
        public final boolean success;
        public final String stdout;
        public final String stderr;
        public final int exitCode;

        public Result(boolean success, String stdout, String stderr, int exitCode) {
            this.success = success;
            this.stdout = stdout;
            this.stderr = stderr;
            this.exitCode = exitCode;
        }
    }

    private final Context mContext;
    private final Handler mHandler;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final List<ConnectionListener> mListeners = new CopyOnWriteArrayList<>();
    private final Intent mShellServiceIntent;

    private volatile IShellService mService;
    private volatile boolean mBound;
    private volatile boolean mConnecting;

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mBound = service != null && service.pingBinder();
            mConnecting = false;
            if (mBound) {
                mService = IShellService.Stub.asInterface(service);
                Logger.logInfo(LOG_TAG, "ShellService bound");
                ShizukuManager.getInstance().setBridgeReady(true);
                notifyConnected();
            } else {
                Logger.logWarn(LOG_TAG, "ShellService connected but binder not valid");
                mService = null;
                ShizukuManager.getInstance().setBridgeReady(false);
                notifyDisconnected();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mBound = false;
            mConnecting = false;
            mService = null;
            Logger.logWarn(LOG_TAG, "ShellService disconnected");
            ShizukuManager.getInstance().setBridgeReady(false);
            notifyDisconnected();
        }
    };

    public ShizukuShellExecutor(Context context) {
        mContext = context == null ? null : context.getApplicationContext();
        mHandler = new Handler(Looper.getMainLooper());

        if (mContext != null) {
            mShellServiceIntent = new Intent(mContext, ShellService.class);
        } else {
            mShellServiceIntent = null;
        }
    }

    public boolean isBound() {
        return mBound;
    }

    public boolean isReady() {
        return mBound && mContext != null;
    }

    public void addConnectionListener(ConnectionListener listener) {
        if (listener == null) {
            return;
        }
        mListeners.add(listener);
        if (mBound) {
            mHandler.post(listener::onServiceConnected);
        }
    }

    public void removeConnectionListener(ConnectionListener listener) {
        mListeners.remove(listener);
    }

    public boolean bind() {
        if (mContext == null || mShellServiceIntent == null) {
            return false;
        }
        if (mBound || mConnecting) {
            return mBound;
        }

        mConnecting = true;
        try {
            return mContext.bindService(mShellServiceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);
        } catch (Throwable e) {
            mConnecting = false;
            Logger.logError(LOG_TAG, "Failed to bind ShellService: " + e.getMessage());
            return false;
        }
    }

    public void unbind() {
        if (mContext == null || mShellServiceIntent == null) {
            return;
        }
        if (!mBound && !mConnecting) {
            return;
        }

        try {
            mContext.unbindService(mServiceConnection);
        } catch (Throwable e) {
            Logger.logWarn(LOG_TAG, "unbindService failed: " + e.getMessage());
        }
        mBound = false;
        mConnecting = false;
        mService = null;
        ShizukuManager.getInstance().setBridgeReady(false);
    }

    public void shutdown() {
        unbind();
        mExecutor.shutdown();
    }

    public void execute(String command, int timeoutMs, ResultCallback callback) {
        mExecutor.execute(() -> {
            Result result = executeSync(command, timeoutMs);
            if (callback != null) {
                mHandler.post(() -> callback.onResult(result));
            }
        });
    }

    public Result executeSync(String command, int timeoutMs) {
        if (mContext == null) {
            return new Result(false, "", "ShizukuShellExecutor not initialized", -1);
        }
        if (!isBound()) {
            return new Result(false, "", "ShellService not bound", -1);
        }

        IShellService remote = mService;
        if (remote == null || !remote.asBinder().pingBinder()) {
            return new Result(false, "", "ShellService binder invalid", -1);
        }

        int timeout = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        try {
            String json = remote.executeCommand(command, timeout);
            return parseResult(json);
        } catch (RemoteException e) {
            Logger.logWarn(LOG_TAG, "executeSync remote failed: " + e.getMessage());
            return new Result(false, "", "IPC failed: " + e.getMessage(), -1);
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "executeSync failed: " + e.getMessage());
            return new Result(false, "", "Execute failed: " + e.getMessage(), -1);
        }
    }

    public boolean waitForConnection(long timeoutMs) {
        if (mBound) {
            return true;
        }
        if (!bind()) {
            return false;
        }

        CountDownLatch latch = new CountDownLatch(1);
        ConnectionListener gate = new ConnectionListener() {
            @Override
            public void onServiceConnected() {
                latch.countDown();
            }

            @Override
            public void onServiceDisconnected() {
            }
        };

        addConnectionListener(gate);
        try {
            boolean ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (!ok) {
                Logger.logWarn(LOG_TAG, "waitForConnection timeout");
            }
            return mBound;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            removeConnectionListener(gate);
        }
    }

    private Result parseResult(String json) {
        if (json == null || json.isEmpty()) {
            return new Result(false, "", "Empty response", -1);
        }

        try {
            JSONObject payload = new JSONObject(json);
            int exitCode = payload.optInt("exitCode", -1);
            String stdout = payload.optString("stdout", "");
            String stderr = payload.optString("stderr", "");
            boolean success = exitCode == 0;
            return new Result(success, stdout, stderr, exitCode);
        } catch (Exception e) {
            return new Result(false, "", "Invalid response: " + e.getMessage(), -1);
        }
    }

    private void notifyConnected() {
        mHandler.post(() -> {
            for (ConnectionListener listener : mListeners) {
                try {
                    listener.onServiceConnected();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private void notifyDisconnected() {
        mHandler.post(() -> {
            for (ConnectionListener listener : mListeners) {
                try {
                    listener.onServiceDisconnected();
                } catch (Throwable ignored) {
                }
            }
        });
    }

}
