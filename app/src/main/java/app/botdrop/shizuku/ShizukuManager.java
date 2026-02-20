package app.botdrop.shizuku;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.Looper;
import android.content.pm.PackageManager;

import com.termux.shared.logger.Logger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class ShizukuManager {

    private static final String LOG_TAG = "ShizukuManager";

    public enum Status {
        NOT_INSTALLED,
        NOT_RUNNING,
        NO_PERMISSION,
        READY
    }

    public interface StatusListener {
        void onStatusChanged(Status status);
    }

    private static final ShizukuManager INSTANCE = new ShizukuManager();

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final List<StatusListener> mListeners = new CopyOnWriteArrayList<>();
    private volatile Status mStatus = Status.NOT_INSTALLED;
    private Context mContext;
    private boolean mInitialized;
    private volatile boolean mBridgeReady;

    private ShizukuManager() {
    }

    public static ShizukuManager getInstance() {
        return INSTANCE;
    }

    public Status getStatus() {
        return mStatus;
    }

    public boolean isReady() {
        return getStatus() == Status.READY;
    }

    public void init(Context context) {
        if (mInitialized) {
            updateStatus();
            return;
        }

        mContext = context == null ? null : context.getApplicationContext();
        if (mContext == null) {
            return;
        }

        mInitialized = true;
        updateStatus();
    }

    public void destroy() {
        if (!mInitialized) {
            return;
        }

        mInitialized = false;
        mContext = null;
        mBridgeReady = false;
        mStatus = Status.NOT_INSTALLED;
    }

    public void addStatusListener(StatusListener listener) {
        if (listener == null) {
            return;
        }
        mListeners.add(listener);
        listener.onStatusChanged(mStatus);
    }

    public void removeStatusListener(StatusListener listener) {
        mListeners.remove(listener);
    }

    public void setBridgeReady(boolean ready) {
        if (!mInitialized || mContext == null) {
            return;
        }

        if (mBridgeReady != ready) {
            mBridgeReady = ready;
            updateStatus();
        }
    }

    public void updateStatus() {
        if (!mInitialized || mContext == null) {
            return;
        }

        Status nextStatus = resolveStatusInternal();
        if (nextStatus != mStatus) {
            mStatus = nextStatus;
            notifyStatusChanged(nextStatus);
        }
    }

    public void requestPermission() {
        if (!mInitialized || mContext == null) {
            return;
        }
        if (!rikka.shizuku.Shizuku.pingBinder()) {
            Logger.logWarn(LOG_TAG, "Shizuku binder is not ready");
            return;
        }
        try {
            rikka.shizuku.Shizuku.requestPermission(0);
        } catch (Throwable tr) {
            Logger.logWarn(LOG_TAG, "requestPermission failed: " + tr.getMessage());
        }
    }

    public String getInstalledShizukuPackageName() {
        return mContext == null ? null : mContext.getPackageName();
    }

    public String[] getKnownShizukuPackages() {
        String packageName = getInstalledShizukuPackageName();
        return packageName == null ? new String[0] : new String[]{packageName};
    }

    private Status resolveStatusInternal() {
        if (!isShizukuInstalled()) {
            return Status.NOT_INSTALLED;
        }

        if (!rikka.shizuku.Shizuku.pingBinder()) {
            return Status.NOT_RUNNING;
        }

        int perm = PackageManager.PERMISSION_DENIED;
        try {
            perm = rikka.shizuku.Shizuku.checkSelfPermission();
        } catch (Throwable tr) {
            Logger.logWarn(LOG_TAG, "checkSelfPermission failed: " + tr.getMessage());
        }
        if (perm != PackageManager.PERMISSION_GRANTED) {
            return Status.NO_PERMISSION;
        }

        return Status.READY;
    }

    private boolean isShizukuInstalled() {
        if (mContext == null) {
            return false;
        }
        try {
            PackageInfo info = mContext.getPackageManager().getPackageInfo(mContext.getPackageName(), 0);
            return info != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void notifyStatusChanged(Status status) {
        mMainHandler.post(() -> {
            for (StatusListener listener : mListeners) {
                try {
                    listener.onStatusChanged(status);
                } catch (Throwable ignored) {
                }
            }
        });
    }
}
