package com.kimchi.widget;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class KimchiUpdateService extends Service {

    private static final String TAG = "KimchiWidget";
    private static final String UPBIT_API = "https://api.upbit.com/v1/ticker?markets=KRW-BTC,KRW-USDT";
    private static final String BINANCE_BTC_API = "https://api.binance.com/api/v3/ticker/price?symbol=BTCUSDT";
    private static final String YAHOO_FINANCE_API = "https://query1.finance.yahoo.com/v8/finance/chart/USDKRW=X?interval=1d&range=1d";
    private static final String PREFS_NAME = "KimchiWidgetPrefs";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        new Thread(() -> {
            try {
                updatePricesDirectly(this);
            } catch (Exception e) {
                Log.e(TAG, "Error updating prices", e);
                showError(this);
            } finally {
                stopSelf();
            }
        }).start();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static void updatePricesDirectly(Context context) {
        try {
            Log.d(TAG, "Starting price update");
            showRefreshIcon(context);

            // 환율 조회 (야후 파이낸스)
            double usdKrw = fetchExchangeRateFromYahoo();
            Log.d(TAG, "Exchange rate from Yahoo Finance: " + usdKrw);

            // 업비트 가격 조회
            String upbitJson = fetchUrl(UPBIT_API);
            JSONArray upbitArr = new JSONArray(upbitJson);

            double upbitBtcKrw = 0, upbitUsdtKrw = 0;
            for (int i = 0; i < upbitArr.length(); i++) {
                JSONObject item = upbitArr.getJSONObject(i);
                String market = item.getString("market");
                double price = item.getDouble("trade_price");
                if ("KRW-BTC".equals(market)) upbitBtcKrw = price;
                else if ("KRW-USDT".equals(market)) upbitUsdtKrw = price;
            }
            Log.d(TAG, "Upbit BTC: " + upbitBtcKrw + ", USDT: " + upbitUsdtKrw);

            // 바이낸스 BTC 가격 조회
            String binanceJson = fetchUrl(BINANCE_BTC_API);
            JSONObject binanceObj = new JSONObject(binanceJson);
            double binanceBtcUsd = binanceObj.getDouble("price");
            Log.d(TAG, "Binance BTC: " + binanceBtcUsd);

            // 김치 프리미엄 계산
            double binanceBtcKrw = binanceBtcUsd * usdKrw;
            double btcPremium = (upbitBtcKrw - binanceBtcKrw) / binanceBtcKrw * 100.0;
            double usdtPremium = (upbitUsdtKrw - usdKrw) / usdKrw * 100.0;

            Log.d(TAG, "BTC Premium: " + btcPremium + "%, USDT Premium: " + usdtPremium + "%");

            // 데이터 저장 (캐시)
            saveData(context, btcPremium, upbitBtcKrw, binanceBtcUsd, usdtPremium, upbitUsdtKrw, usdKrw);

            // UI 업데이트
            updateUI(context, btcPremium, upbitBtcKrw, binanceBtcUsd, 
                     usdtPremium, upbitUsdtKrw, usdKrw);

            // 새로고침 아이콘 숨기기
            new Thread(() -> {
                try {
                    Thread.sleep(1000);
                    hideRefreshIcon(context);
                } catch (InterruptedException e) {
                    Log.e(TAG, "Interrupted", e);
                }
            }).start();
        } catch (Exception e) {
            Log.e(TAG, "Error in updatePricesDirectly", e);
            showError(context);
            hideRefreshIcon(context);
        }
    }

    private static double fetchExchangeRateFromYahoo() throws Exception {
        String yahooJson = fetchUrl(YAHOO_FINANCE_API);
        JSONObject yahooObj = new JSONObject(yahooJson);
        JSONObject result = yahooObj.getJSONObject("chart").getJSONArray("result").getJSONObject(0);
        JSONObject meta = result.getJSONObject("meta");
        return meta.getDouble("regularMarketPrice");
    }

    private static void saveData(Context context, double btcPremium, double upbitBtcKrw, double binanceBtcUsd,
                                 double usdtPremium, double upbitUsdtKrw, double usdKrw) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putFloat("btc_premium", (float) btcPremium);
            editor.putFloat("upbit_btc", (float) upbitBtcKrw);
            editor.putFloat("binance_btc", (float) binanceBtcUsd);
            editor.putFloat("usdt_premium", (float) usdtPremium);
            editor.putFloat("upbit_usdt", (float) upbitUsdtKrw);
            editor.putFloat("usd_krw", (float) usdKrw);
            editor.apply();
            Log.d(TAG, "Data saved to SharedPreferences");
        } catch (Exception e) {
            Log.e(TAG, "Error saving data", e);
        }
    }

    private static void showRefreshIcon(Context context) {
        try {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_kimchi);
            views.setViewVisibility(R.id.iv_refresh_icon, View.VISIBLE);
            views.setViewVisibility(R.id.tv_update_time, View.GONE);
            KimchiWidgetProvider.updateAllWidgets(context, views);
        } catch (Exception e) {
            Log.e(TAG, "Error showing refresh icon", e);
        }
    }

    private static void hideRefreshIcon(Context context) {
        try {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_kimchi);
            views.setViewVisibility(R.id.iv_refresh_icon, View.GONE);
            views.setViewVisibility(R.id.tv_update_time, View.VISIBLE);
            KimchiWidgetProvider.updateAllWidgets(context, views);
        } catch (Exception e) {
            Log.e(TAG, "Error hiding refresh icon", e);
        }
    }

    private static void updateUI(Context context, double btcPremium, double upbitBtcKrw, double binanceBtcUsd,
                          double usdtPremium, double upbitUsdtKrw, double usdKrw) {
        try {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_kimchi);

            DecimalFormat df2 = new DecimalFormat("+#,##0.00;-#,##0.00");
            DecimalFormat dfKrw = new DecimalFormat("#,##0");
            DecimalFormat dfUsd = new DecimalFormat("#,##0.00");

            // 환율 표시
            views.setTextViewText(R.id.tv_exchange_rate, "환율: ₩" + dfKrw.format(usdKrw));

            // BTC 업데이트
            String btcPremStr = df2.format(btcPremium) + "%";
            views.setTextViewText(R.id.tv_btc_premium, btcPremStr);
            views.setTextColor(R.id.tv_btc_premium, getPremiumColor(btcPremium));
            
            double btcDiff = upbitBtcKrw - (binanceBtcUsd * usdKrw);
            views.setTextViewText(R.id.tv_btc_diff, "₩" + dfKrw.format(btcDiff));
            
            views.setTextViewText(R.id.tv_btc_krw, "₩" + dfKrw.format(upbitBtcKrw));
            views.setTextViewText(R.id.tv_btc_usd, "$" + dfUsd.format(binanceBtcUsd));

            // USDT 업데이트
            String usdtPremStr = df2.format(usdtPremium) + "%";
            views.setTextViewText(R.id.tv_usdt_premium, usdtPremStr);
            views.setTextColor(R.id.tv_usdt_premium, getPremiumColor(usdtPremium));
            
            double usdtDiff = upbitUsdtKrw - usdKrw;
            views.setTextViewText(R.id.tv_usdt_diff, "₩" + String.format(Locale.KOREA, "%.0f", usdtDiff));
            
            views.setTextViewText(R.id.tv_usdt_krw, "₩" + dfKrw.format(upbitUsdtKrw));
            views.setTextViewText(R.id.tv_usdt_usd, "₩" + dfKrw.format(usdKrw));

            // 업데이트 시간
            String time = new SimpleDateFormat("HH:mm", Locale.KOREA).format(new Date());
            views.setTextViewText(R.id.tv_update_time, time);

            // 위젯 전체 클릭 리스너 재설정
            Intent refreshIntent = new Intent(context, KimchiWidgetProvider.class);
            refreshIntent.setAction(KimchiWidgetProvider.ACTION_REFRESH);
            android.app.PendingIntent refreshPendingIntent = android.app.PendingIntent.getBroadcast(
                    context, 0, refreshIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            views.setOnClickPendingIntent(R.id.widget_root, refreshPendingIntent);

            KimchiWidgetProvider.updateAllWidgets(context, views);
            Log.d(TAG, "UI updated successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error updating UI", e);
        }
    }

    private static int getPremiumColor(double premium) {
        if (premium >= 3.0) return 0xFFFF4444;
        else if (premium >= 1.0) return 0xFFFF9800;
        else if (premium >= 0.0) return 0xFF4CAF50;
        else return 0xFF2196F3;
    }

    private static void showError(Context context) {
        try {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_kimchi);
            views.setTextViewText(R.id.tv_btc_premium, "오류");
            views.setTextViewText(R.id.tv_usdt_premium, "오류");
            views.setTextViewText(R.id.tv_btc_krw, "네트워크 확인");
            views.setTextViewText(R.id.tv_usdt_krw, "터치로 재시도");
            String time = new SimpleDateFormat("HH:mm", Locale.KOREA).format(new Date());
            views.setTextViewText(R.id.tv_update_time, time);
            
            // 위젯 전체 클릭 리스너 설정
            Intent refreshIntent = new Intent(context, KimchiWidgetProvider.class);
            refreshIntent.setAction(KimchiWidgetProvider.ACTION_REFRESH);
            android.app.PendingIntent refreshPendingIntent = android.app.PendingIntent.getBroadcast(
                    context, 0, refreshIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            views.setOnClickPendingIntent(R.id.widget_root, refreshPendingIntent);
            
            KimchiWidgetProvider.updateAllWidgets(context, views);
        } catch (Exception e) {
            Log.e(TAG, "Error showing error message", e);
        }
    }

    private static String fetchUrl(String urlStr) throws Exception {
        int maxRetries = 5;
        Exception lastException = null;
        
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                Log.d(TAG, "Fetching URL (attempt " + (attempt + 1) + "): " + urlStr);
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                conn.setRequestProperty("Accept", "application/json");

                int responseCode = conn.getResponseCode();
                Log.d(TAG, "Response code: " + responseCode);
                
                if (responseCode != 200) {
                    throw new Exception("HTTP " + responseCode);
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                conn.disconnect();
                
                String result = sb.toString();
                Log.d(TAG, "Fetched successfully: " + result.substring(0, Math.min(100, result.length())));
                return result;
            } catch (Exception e) {
                lastException = e;
                Log.e(TAG, "Attempt " + (attempt + 1) + " failed: " + e.getMessage());
                if (attempt < maxRetries - 1) {
                    Thread.sleep(1000);
                }
            }
        }
        
        throw lastException != null ? lastException : new Exception("Failed after " + maxRetries + " attempts");
    }
}
