package com.kimchi.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import java.text.DecimalFormat;

public class KimchiWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_REFRESH = "com.kimchi.widget.ACTION_REFRESH";
    private static final String PREFS_NAME = KimchiUpdateService.PREFS_NAME;
    private static final String TAG = "KimchiWidget";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        // 캐시된 데이터로 즉시 표시
        for (int appWidgetId : appWidgetIds) {
            renderFromCache(context, appWidgetManager, appWidgetId);
        }
        // 네트워크 갱신
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
            new Thread(() -> {
                try {
                    KimchiUpdateService.updatePricesDirectly(context);
                } catch (Exception e) {
                    Log.e(TAG, "Error in onReceive", e);
                }
            }).start();
        }
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        // 위젯 제거 시 인스턴스별 설정/캐시 정리
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        for (int id : appWidgetIds) {
            editor.remove("coin1_" + id);
            editor.remove("coin2_" + id);
            for (int slot = 1; slot <= 2; slot++) {
                String p = "slot" + slot + "_" + id + "_";
                editor.remove(p + "coin");
                editor.remove(p + "premium");
                editor.remove(p + "diff");
                editor.remove(p + "upbit");
                editor.remove(p + "ref");
            }
        }
        editor.apply();
        Log.d(TAG, "Cleaned up prefs for " + appWidgetIds.length + " widget(s)");
    }

    /**
     * 캐시된 값으로 위젯 렌더링. 시간 옆에 마지막 성공 후 경과 시간(⚠️)이 자동 표시됨.
     * KimchiUpdateService.showErrorState에서도 호출 (공용).
     */
    public static void renderFromCache(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_kimchi);

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String coin1 = prefs.getString("coin1_" + appWidgetId, KimchiUpdateService.DEFAULT_COIN1);
        String coin2 = prefs.getString("coin2_" + appWidgetId, KimchiUpdateService.DEFAULT_COIN2);
        float usdKrw = prefs.getFloat(KimchiUpdateService.KEY_USD_KRW, Float.NaN);

        views.setTextViewText(R.id.tv_coin1_label, coin1);
        views.setTextViewText(R.id.tv_coin2_label, coin2);

        if (!Float.isNaN(usdKrw)) {
            DecimalFormat dfKrw = new DecimalFormat("#,##0");
            views.setTextViewText(R.id.tv_exchange_rate, "환율: ₩" + dfKrw.format(usdKrw));
        }

        renderSlotFromCache(views, prefs, appWidgetId, 1, coin1);
        renderSlotFromCache(views, prefs, appWidgetId, 2, coin2);

        // 시간 + 경과/⚠️ 표시
        views.setTextViewText(R.id.tv_update_time, KimchiUpdateService.formatUpdateTime(prefs));
        views.setViewVisibility(R.id.tv_update_time, View.VISIBLE);
        views.setViewVisibility(R.id.iv_refresh_icon, View.GONE);

        // 클릭 → 새로고침
        Intent refreshIntent = new Intent(context, KimchiWidgetProvider.class);
        refreshIntent.setAction(ACTION_REFRESH);
        PendingIntent pi = PendingIntent.getBroadcast(context, appWidgetId, refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, pi);

        appWidgetManager.updateAppWidget(appWidgetId, views);
    }

    private static void renderSlotFromCache(RemoteViews views, SharedPreferences prefs, int id, int slot, String coin) {
        DecimalFormat df2 = new DecimalFormat("+#,##0.00;-#,##0.00");
        DecimalFormat dfKrw = new DecimalFormat("#,##0");
        DecimalFormat dfUsd = new DecimalFormat("#,##0.00");

        int[] idsArr = slotIds(slot);

        String p = "slot" + slot + "_" + id + "_";
        String cachedCoin = prefs.getString(p + "coin", null);

        // 캐시 없거나 코인 바뀐 경우: 로딩 플레이스홀더
        if (cachedCoin == null || !cachedCoin.equals(coin)) {
            views.setTextViewText(idsArr[0], "로딩중");
            views.setTextViewText(idsArr[1], "₩ --");
            views.setTextViewText(idsArr[2], "₩ --");
            views.setTextViewText(idsArr[3], coin.equals("USDT") ? "₩ --" : "$ --");
            views.setTextViewText(idsArr[4], coin.equals("USDT") ? "기준가" : "바이낸스");
            return;
        }

        float premium = prefs.getFloat(p + "premium", Float.NaN);
        float diff = prefs.getFloat(p + "diff", Float.NaN);
        float upbit = prefs.getFloat(p + "upbit", Float.NaN);
        float refVal = prefs.getFloat(p + "ref", Float.NaN);

        if (Float.isNaN(premium)) {
            views.setTextViewText(idsArr[0], "로딩중");
            return;
        }
        views.setTextViewText(idsArr[0], df2.format(premium) + "%");
        views.setTextColor(idsArr[0], getPremiumColor(premium));
        views.setTextViewText(idsArr[1], "₩" + dfKrw.format(diff));
        views.setTextViewText(idsArr[2], "₩" + dfKrw.format(upbit));
        if (coin.equals("USDT")) {
            views.setTextViewText(idsArr[3], "₩" + dfKrw.format(refVal));
            views.setTextViewText(idsArr[4], "기준가");
        } else {
            views.setTextViewText(idsArr[3], "$" + dfUsd.format(refVal));
            views.setTextViewText(idsArr[4], "바이낸스");
        }
    }

    private static int[] slotIds(int slot) {
        if (slot == 1) {
            return new int[]{R.id.tv_btc_premium, R.id.tv_btc_diff, R.id.tv_btc_krw, R.id.tv_btc_usd, R.id.tv_coin1_ref_label};
        } else {
            return new int[]{R.id.tv_usdt_premium, R.id.tv_usdt_diff, R.id.tv_usdt_krw, R.id.tv_usdt_usd, R.id.tv_coin2_ref_label};
        }
    }

    private static int getPremiumColor(double premium) {
        if (premium >= 3.0) return 0xFFFF4444;
        else if (premium >= 1.0) return 0xFFFF9800;
        else if (premium >= 0.0) return 0xFF4CAF50;
        else return 0xFF2196F3;
    }
}
