package app.botdrop;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.termux.shared.logger.Logger;

/**
 * BroadcastReceiver that sets the system clipboard from a shell broadcast.
 *
 * Usage via Shizuku / adb shell:
 *   am broadcast -a app.botdrop.SET_CLIPBOARD --es text "你好世界"
 *
 * This is used by the shizuku-automation OpenClaw skill to enable
 * Chinese and special character text input by setting the clipboard
 * and then simulating a paste keyevent (KEYCODE_PASTE = 279).
 */
public class ClipboardReceiver extends BroadcastReceiver {

    private static final String LOG_TAG = "ClipboardReceiver";
    public static final String ACTION_SET_CLIPBOARD = "app.botdrop.SET_CLIPBOARD";
    private static final String EXTRA_TEXT = "text";
    private static final String CLIP_LABEL = "botdrop";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_SET_CLIPBOARD.equals(intent.getAction())) {
            return;
        }

        final String text = intent.getStringExtra(EXTRA_TEXT);
        if (text == null) {
            Logger.logWarn(LOG_TAG, "SET_CLIPBOARD received but 'text' extra is missing");
            return;
        }

        // ClipboardManager must be accessed on the main thread
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                ClipboardManager cm = (ClipboardManager)
                    context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null) {
                    Logger.logError(LOG_TAG, "ClipboardManager not available");
                    return;
                }
                ClipData clip = ClipData.newPlainText(CLIP_LABEL, text);
                cm.setPrimaryClip(clip);
                Logger.logDebug(LOG_TAG, "Clipboard set, length=" + text.length());
            } catch (Throwable e) {
                Logger.logError(LOG_TAG, "Failed to set clipboard: " + e.getMessage());
            }
        });
    }
}
