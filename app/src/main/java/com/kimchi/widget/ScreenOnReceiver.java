package com.kimchi.widget;

import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * 잠금 해제 시 위젯을 강제 새로고침. 단, 마지막 시도 후 5분 이내면 스킵.
 * 매니페스트에 정적 등록 불가 (Android 8+) → KimchiApp.onCreate에서 동적 등록.
 */
public class ScreenOnReceiver extends BroadcastReceiver {
    private static final String TAG = "KimchiWidget";
    private static final long MIN_INTERVAL_MS = 5 * 60 * 1000; // 5분

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_USER_PRESENT.equals(intent.getAction())) return;

        AppWidgetManager awm = AppWidgetManager.getInstance(context);
        int[] ids = awm.getAppWidgetIds(new ComponentName(context, KimchiWidgetProvider.class));
        if (ids.length == 0) return;

        SharedPreferences prefs = context.getSharedPreferences(
                KimchiUpdateService.PREFS_NAME, Context.MODE_PRIVATE);
        long lastAttempt = prefs.getLong(KimchiUpdateService.KEY_LAST_ATTEMPT, 0);
        long now = System.currentTimeMillis();
        if (now - lastAttempt < MIN_INTERVAL_MS) {
            Log.d(TAG, "Unlock refresh skipped (cooldown): " + (now - lastAttempt) + "ms");
            return;
        }

        Log.d(TAG, "Unlock detected → triggering refresh");
        Intent refresh = new Intent(context, KimchiWidgetProvider.class);
        refresh.setAction(KimchiWidgetProvider.ACTION_REFRESH);
        context.sendBroadcast(refresh);
    }
}
