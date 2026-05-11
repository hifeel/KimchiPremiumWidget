package com.kimchi.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import android.widget.RemoteViews;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class KimchiWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_REFRESH = "com.kimchi.widget.ACTION_REFRESH";
    private static final String PREFS_NAME = "KimchiWidgetPrefs";
    private static final String TAG = "KimchiWidget";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId);
        }
        // 즉시 데이터 갱신
        new Thread(() -> {
            try {
                Thread.sleep(500);
                KimchiUpdateService.updatePricesDirectly(context);
            } catch (Exception e) {
                Log.e(TAG, "Error in onUpdate", e);
            }
        }).start();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (ACTION_REFRESH.equals(intent.getAction())) {
            Log.d(TAG, "Refresh action received");
            // 즉시 데이터 갱신
            new Thread(() -> {
                try {
                    KimchiUpdateService.updatePricesDirectly(context);
                } catch (Exception e) {
                    Log.e(TAG, "Error in onReceive", e);
                }
            }).start();
        }
    }

    private static void updateAppWidget(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_kimchi);
        
        // 캐시된 데이터가 있으면 먼저 표시
        loadAndDisplayCachedData(context, views);
        
        // 위젯 전체를 클릭 가능하게 설정 (새로고침 기능)
        Intent refreshIntent = new Intent(context, KimchiWidgetProvider.class);
        refreshIntent.setAction(ACTION_REFRESH);
        PendingIntent refreshPendingIntent = PendingIntent.getBroadcast(
                context, appWidgetId, refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        
        // 루트 레이아웃에 클릭 리스너 설정
        views.setOnClickPendingIntent(R.id.widget_root, refreshPendingIntent);
        
        appWidgetManager.updateAppWidget(appWidgetId, views);
    }

    private static void loadAndDisplayCachedData(Context context, RemoteViews views) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            
            float btcPremium = prefs.getFloat("btc_premium", Float.NaN);
            float upbitBtc = prefs.getFloat("upbit_btc", Float.NaN);
            float binanceBtc = prefs.getFloat("binance_btc", Float.NaN);
            float usdtPremium = prefs.getFloat("usdt_premium", Float.NaN);
            float upbitUsdt = prefs.getFloat("upbit_usdt", Float.NaN);
            float usdKrw = prefs.getFloat("usd_krw", Float.NaN);
            
            if (!Float.isNaN(btcPremium) && !Float.isNaN(upbitBtc) && !Float.isNaN(binanceBtc) &&
                !Float.isNaN(usdtPremium) && !Float.isNaN(upbitUsdt) && !Float.isNaN(usdKrw)) {
                
                DecimalFormat df2 = new DecimalFormat("+#,##0.00;-#,##0.00");
                DecimalFormat dfKrw = new DecimalFormat("#,##0");
                DecimalFormat dfUsd = new DecimalFormat("#,##0.00");
                
                // 환율
                views.setTextViewText(R.id.tv_exchange_rate, "환율: ₩" + dfKrw.format(usdKrw));
                
                // BTC
                views.setTextViewText(R.id.tv_btc_premium, df2.format(btcPremium) + "%");
                views.setTextColor(R.id.tv_btc_premium, getPremiumColor(btcPremium));
                
                double btcDiff = upbitBtc - (binanceBtc * usdKrw);
                views.setTextViewText(R.id.tv_btc_diff, "₩" + dfKrw.format(btcDiff));
                
                views.setTextViewText(R.id.tv_btc_krw, "₩" + dfKrw.format(upbitBtc));
                views.setTextViewText(R.id.tv_btc_usd, "$" + dfUsd.format(binanceBtc));
                
                // USDT
                views.setTextViewText(R.id.tv_usdt_premium, df2.format(usdtPremium) + "%");
                views.setTextColor(R.id.tv_usdt_premium, getPremiumColor(usdtPremium));
                
                double usdtDiff = upbitUsdt - usdKrw;
                views.setTextViewText(R.id.tv_usdt_diff, "₩" + String.format(Locale.KOREA, "%.0f", usdtDiff));
                
                views.setTextViewText(R.id.tv_usdt_krw, "₩" + dfKrw.format(upbitUsdt));
                views.setTextViewText(R.id.tv_usdt_usd, "₩" + dfKrw.format(usdKrw));
                
                // 시간
                String time = new SimpleDateFormat("HH:mm", Locale.KOREA).format(new Date());
                views.setTextViewText(R.id.tv_update_time, time);
                
                Log.d(TAG, "Cached data displayed");
            } else {
                Log.d(TAG, "No cached data available");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading cached data", e);
        }
    }

    private static int getPremiumColor(double premium) {
        if (premium >= 3.0) return 0xFFFF4444;
        else if (premium >= 1.0) return 0xFFFF9800;
        else if (premium >= 0.0) return 0xFF4CAF50;
        else return 0xFF2196F3;
    }

    public static void updateAllWidgets(Context context, RemoteViews views) {
        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
        ComponentName thisWidget = new ComponentName(context, KimchiWidgetProvider.class);
        int[] appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget);
        appWidgetManager.updateAppWidget(appWidgetIds, views);
    }
}
