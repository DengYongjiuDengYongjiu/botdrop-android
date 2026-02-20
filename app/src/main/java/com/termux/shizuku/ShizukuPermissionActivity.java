package com.termux.shizuku;

import android.os.Bundle;
import android.os.Parcelable;
import android.content.DialogInterface;
import android.content.pm.ApplicationInfo;
import android.text.TextUtils;
import android.view.WindowManager;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuApiConstants;

public class ShizukuPermissionActivity extends AppCompatActivity {

    private int requestUid = -1;
    private int requestPid = -1;
    private int requestCode = -1;
    private String targetPackage = "unknown";

    private AlertDialog dialog;

    private void finishWithResult(boolean allowed, boolean onetime) {
        Bundle data = new Bundle();
        data.putBoolean(ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED, allowed);
        data.putBoolean(ShizukuApiConstants.REQUEST_PERMISSION_REPLY_IS_ONETIME, onetime);
        try {
            Shizuku.dispatchPermissionConfirmationResult(requestUid, requestPid, requestCode, data);
        } finally {
            finish();
        }
    }

    private void showPermissionPrompt() {
        String message = "Allow " + targetPackage + " to use Shizuku?";
        if (TextUtils.isEmpty(targetPackage)) {
            targetPackage = "this app";
        }
        message = "Allow " + targetPackage + " to use Shizuku?";

        dialog = new AlertDialog.Builder(this)
                .setTitle("Shizuku Permission")
                .setMessage(message)
                .setPositiveButton("Always allow", (DialogInterface d, int which) -> {
                    finishWithResult(true, false);
                })
                .setNeutralButton("Allow once", (DialogInterface d, int which) -> {
                    finishWithResult(true, true);
                })
                .setNegativeButton(android.R.string.cancel, (DialogInterface d, int which) -> {
                    finishWithResult(false, true);
                })
                .setOnCancelListener((DialogInterface d) -> finishWithResult(false, true))
                .setCancelable(true)
                .create();
        dialog.show();
    }

    private void closePromptAndFinishIfNeeded() {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    private boolean parseRequestIntent() {
        requestUid = getIntent().getIntExtra("uid", -1);
        requestPid = getIntent().getIntExtra("pid", -1);
        requestCode = getIntent().getIntExtra("requestCode", -1);
        Parcelable appInfoExtra = getIntent().getParcelableExtra("applicationInfo");
        if (appInfoExtra instanceof ApplicationInfo) {
            targetPackage = ((ApplicationInfo) appInfoExtra).packageName;
        } else {
            targetPackage = getIntent().getStringExtra("packageName");
            if (TextUtils.isEmpty(targetPackage)) {
                return false;
            }
        }
        return requestUid >= 0 && requestPid >= 0 && requestCode >= 0;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (!parseRequestIntent() || !Shizuku.pingBinder()) {
            finish();
            return;
        }

        showPermissionPrompt();
    }

    @Override
    protected void onDestroy() {
        closePromptAndFinishIfNeeded();
        super.onDestroy();
    }
}
