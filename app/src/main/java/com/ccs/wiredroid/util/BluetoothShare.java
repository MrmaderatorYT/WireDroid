package com.ccs.wiredroid.util;

import com.ccs.wiredroid.R;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * Sends a file to another device over Bluetooth.
 *
 * <p>This deliberately does not talk to {@code BluetoothAdapter} or implement OPP.
 * Every Android build ships a Bluetooth share activity that already handles device
 * picking, pairing, the transfer itself and its notifications — and it holds the
 * permissions for all of that. Handing it a {@code content://} URI is both far less
 * code and the only route that keeps working across OEM Bluetooth stacks.</p>
 *
 * <p>The consequence worth knowing: the transfer is owned by that app once started,
 * so WireDroid cannot report its progress or cancel it.</p>
 */
public final class BluetoothShare {

    /**
     * Package name fragments used by the stock Bluetooth share activity. AOSP and
     * most OEMs use {@code com.android.bluetooth}; Samsung ships
     * {@code com.samsung.android.app.bluetoothshare} on some builds.
     */
    private static final String[] BLUETOOTH_PACKAGE_HINTS = {
            "com.android.bluetooth",
            "com.samsung.android.app.bluetoothshare",
            "bluetooth"
    };

    private BluetoothShare() {
    }

    /**
     * Sends {@code file} over Bluetooth.
     *
     * <p>Resolves the system Bluetooth share activity and targets it directly, so
     * the user lands on the device picker rather than on a generic share sheet. If
     * no such activity exists, falls back to an ordinary chooser so the file can
     * still leave the device by some other route.</p>
     */
    public static void send(Context context, File file) {
        if (file == null || !file.isFile()) {
            toast(context, context.getString(R.string.bt_share_missing_file));
            return;
        }

        Uri uri;
        try {
            uri = FileProvider.getUriForFile(
                    context, context.getPackageName() + ".fileprovider", file);
        } catch (IllegalArgumentException e) {
            // Thrown when the file sits outside every <paths> entry of provider_paths.xml.
            toast(context, context.getString(R.string.bt_share_failed, e.getMessage()));
            return;
        }

        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(mimeTypeOf(file));
        send.putExtra(Intent.EXTRA_STREAM, uri);
        // ClipData carries the grant on the devices that ignore the flag alone.
        send.setClipData(ClipData.newRawUri(file.getName(), uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        ResolveInfo bluetooth = findBluetoothTarget(context, send);
        if (bluetooth != null) {
            send.setClassName(
                    bluetooth.activityInfo.packageName, bluetooth.activityInfo.name);
            try {
                context.startActivity(send);
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                // Resolved a moment ago but refused to start — fall through to the
                // chooser rather than leaving the user with nothing.
                send.setComponent(null);
            }
        }

        try {
            context.startActivity(Intent.createChooser(
                    send, context.getString(R.string.bt_share_chooser)));
            if (bluetooth == null) {
                toast(context, context.getString(R.string.bt_share_no_target_fallback));
            }
        } catch (ActivityNotFoundException e) {
            toast(context, context.getString(R.string.bt_share_no_target));
        }
    }

    /** True when a Bluetooth share activity exists, so callers can hide the option. */
    public static boolean isAvailable(Context context) {
        Intent probe = new Intent(Intent.ACTION_SEND).setType("*/*");
        return findBluetoothTarget(context, probe) != null;
    }

    private static ResolveInfo findBluetoothTarget(Context context, Intent intent) {
        PackageManager pm = context.getPackageManager();
        List<ResolveInfo> candidates = pm.queryIntentActivities(intent, 0);
        if (candidates == null) return null;

        // Prefer an exact package match over the loose "contains bluetooth" hint, so
        // a third-party app merely having "bluetooth" in its name cannot win against
        // the real system one.
        for (int hint = 0; hint < BLUETOOTH_PACKAGE_HINTS.length; hint++) {
            String needle = BLUETOOTH_PACKAGE_HINTS[hint];
            boolean exact = hint < BLUETOOTH_PACKAGE_HINTS.length - 1;
            for (ResolveInfo info : candidates) {
                if (info.activityInfo == null) continue;
                String pkg = info.activityInfo.packageName.toLowerCase(Locale.ROOT);
                if (exact ? pkg.equals(needle) : pkg.contains(needle)) {
                    return info;
                }
            }
        }
        return null;
    }

    private static String mimeTypeOf(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (type != null) return type;
        }
        // Bluetooth OPP refuses some exotic types; octet-stream always goes through.
        return "application/octet-stream";
    }

    private static void toast(Context context, String message) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }
}
