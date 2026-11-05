package com.kimchi.widget;

import android.app.Application;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

/**
 * 앱 프로세스 시작 시 ACTION_USER_PRESENT 동적 리시버 등록.
 * Android 8+ 백그라운드 제한으로 매니페스트 정적 등록은 불가.
 */
public class KimchiApp extends Application {
    private static final String TAG = "KimchiWidget";

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            IntentFilter filter = new IntentFilter(Intent.ACTION_USER_PRESENT);
            registerReceiver(new ScreenOnReceiver(), filter);
            Log.d(TAG, "ScreenOnReceiver registered (USER_PRESENT)");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register ScreenOnReceiver", e);
        }
    }
}
