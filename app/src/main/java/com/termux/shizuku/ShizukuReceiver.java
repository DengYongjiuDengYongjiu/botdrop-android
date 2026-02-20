package com.termux.shizuku;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.Parcel;

import com.termux.shared.logger.Logger;

import rikka.shizuku.Shizuku;

public class ShizukuReceiver extends BroadcastReceiver {

    private static final String ACTION_REQUEST_BINDER = "rikka.shizuku.intent.action.REQUEST_BINDER";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_REQUEST_BINDER.equals(intent == null ? null : intent.getAction())) {
            return;
        }

        IBinder requestBinder = null;
        try {
            if (intent.getExtras() != null) {
                requestBinder = intent.getExtras().getBinder("binder");
            }

            if (requestBinder == null) {
                requestBinder = intent.getBundleExtra("data") != null
                        ? intent.getBundleExtra("data").getBinder("binder")
                        : null;
            }

            IBinder shizukuBinder = Shizuku.getBinder();
            if (requestBinder == null || shizukuBinder == null) {
                Logger.logWarn("ShizukuReceiver", "shizuku binder is not ready yet");
                return;
            }

            Parcel parcel = Parcel.obtain();
            try {
                parcel.writeStrongBinder(shizukuBinder);
                parcel.writeString(context == null ? "" : context.getApplicationInfo().sourceDir);
                requestBinder.transact(1, parcel, null, IBinder.FLAG_ONEWAY);
            } finally {
                parcel.recycle();
            }
        } catch (Throwable tr) {
            Logger.logWarn("ShizukuReceiver", "failed to answer binder request: " + tr.getMessage());
        } finally {
            if (requestBinder != null) {
                Logger.logInfo("ShizukuReceiver", "binder request handled");
            }
        }
    }
}
