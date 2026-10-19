package org.techwithkaushik.termuxxwayland;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import java.lang.reflect.Method;

/**
 * Small app_process entry point for Termux control IPC.
 *
 * Android 10's /system/bin/content uses getContentProviderExternal(), which
 * requires ACCESS_CONTENT_PROVIDERS_EXTERNALLY and is denied to ordinary apps.
 * This helper uses the public ContentResolver.call() API from the Termux UID
 * instead. The exported provider still authenticates the calling UID.
 *
 * This class intentionally has no AndroidX or Kotlin dependencies so it can
 * be loaded directly from the installed host APK by app_process.
 */
public final class TermuxControlCli {
    private TermuxControlCli() {}

    public static void main(String[] args) {
        if (args.length < 2 || args.length > 3) {
            System.err.println("Usage: TermuxControlCli <content-uri> <method> [argument]");
            System.exit(64);
            return;
        }

        try {
            Uri uri = Uri.parse(args[0]);
            String method = args[1];
            String argument = args.length == 3 ? args[2] : null;
            Context context = obtainSystemContext();
            ContentResolver resolver = context.getContentResolver();
            Bundle result = resolver.call(uri, method, argument, null);
            if (result == null) {
                throw new IllegalStateException("Control provider returned no result");
            }

            String state = result.getString("state");
            if (state != null) {
                System.out.println("state=" + state);
            }
            System.out.println("protocol_version=" + result.getInt("protocol_version", 0));
        } catch (Throwable error) {
            System.err.println("Termux control IPC failed: "
                    + error.getClass().getSimpleName() + ": "
                    + String.valueOf(error.getMessage()));
            System.exit(1);
        }
    }

    private static Context obtainSystemContext() throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Method systemMain = activityThread.getDeclaredMethod("systemMain");
        systemMain.setAccessible(true);
        Object thread = systemMain.invoke(null);
        Method getSystemContext = activityThread.getDeclaredMethod("getSystemContext");
        getSystemContext.setAccessible(true);
        return (Context) getSystemContext.invoke(thread);
    }
}
