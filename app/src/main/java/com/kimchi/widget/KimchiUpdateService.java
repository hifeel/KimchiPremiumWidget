package com.kimchi.widget;

import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
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
import java.net.URLEncoder;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class KimchiUpdateService extends Service {

    private static final String TAG = "KimchiWidget";
    private static final String UPBIT_API_BASE = "https://api.upbit.com/v1/ticker?markets=";
    private static final String BINANCE_API_BASE = "https://api.binance.com/api/v3/ticker/price?symbols=";
    private static final String YAHOO_FINANCE_API = "https://query1.finance.yahoo.com/v8/finance/chart/USDKRW=X?interval=1d&range=1d";
    private static final String ER_API_FALLBACK = "https://open.er-api.com/v6/latest/USD";

    static final String PREFS_NAME = "KimchiWidgetPrefs";
    static final String DEFAULT_COIN1 = "BTC";
    static final String DEFAULT_COIN2 = "USDT";
    static final String KEY_USD_KRW = "usd_krw";
    static final String KEY_LAST_SUCCESS = "last_success_ms";
    static final String KEY_LAST_ATTEMPT = "last_attempt_ms";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        new Thread(() -> {
            try {
                updatePricesDirectly(this);
            } catch (Exception e) {
                Log.e(TAG, "Error updating prices", e);
                showErrorState(this);
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

            AppWidgetManager awm = AppWidgetManager.getInstance(context);
            ComponentName cn = new ComponentName(context, KimchiWidgetProvider.class);
            int[] ids = awm.getAppWidgetIds(cn);

            if (ids.length == 0) {
                Log.d(TAG, "No widget instances to update");
                return;
            }

            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            // 시도 시각 기록 (ScreenOnReceiver의 쓰로틀링용)
            prefs.edit().putLong(KEY_LAST_ATTEMPT, System.currentTimeMillis()).apply();

            showRefreshIcon(context, ids);

            // 1. 모든 인스턴스에서 사용하는 코인 수집
            Set<String> uniqueCoins = new LinkedHashSet<>();
            for (int id : ids) {
                uniqueCoins.add(prefs.getString("coin1_" + id, DEFAULT_COIN1));
                uniqueCoins.add(prefs.getString("coin2_" + id, DEFAULT_COIN2));
            }
            Log.d(TAG, "Unique coins: " + uniqueCoins);

            // 2. 환율 (Yahoo → open.er-api.com 폴백)
            double usdKrw = fetchExchangeRate();
            Log.d(TAG, "USDKRW: " + usdKrw);

            // 3. 업비트 가격 일괄 조회
            StringBuilder upbitMarkets = new StringBuilder();
            for (String c : uniqueCoins) {
                if (upbitMarkets.length() > 0) upbitMarkets.append(",");
                upbitMarkets.append("KRW-").append(c);
            }
            String upbitJson = fetchUrl(UPBIT_API_BASE + upbitMarkets);
            Map<String, Double> upbitKrwMap = new HashMap<>();
            JSONArray upbitArr = new JSONArray(upbitJson);
            for (int i = 0; i < upbitArr.length(); i++) {
                JSONObject item = upbitArr.getJSONObject(i);
                String market = item.getString("market");
                if (market.startsWith("KRW-")) {
                    upbitKrwMap.put(market.substring(4), item.getDouble("trade_price"));
                }
            }
            Log.d(TAG, "Upbit map: " + upbitKrwMap);

            // 4. 바이낸스 가격 일괄 조회 (USDT 제외)
            Map<String, Double> binanceUsdMap = new HashMap<>();
            binanceUsdMap.put("USDT", 1.0);
            StringBuilder binSymbols = new StringBuilder("[");
            boolean firstBin = true;
            for (String c : uniqueCoins) {
                if (c.equals("USDT")) continue;
                if (!firstBin) binSymbols.append(",");
                binSymbols.append("\"").append(c).append("USDT\"");
                firstBin = false;
            }
            binSymbols.append("]");
            if (!firstBin) {
                String binUrl = BINANCE_API_BASE + URLEncoder.encode(binSymbols.toString(), "UTF-8");
                String binJson = fetchUrl(binUrl);
                JSONArray arr = new JSONArray(binJson);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String sym = o.getString("symbol");
                    if (sym.endsWith("USDT")) {
                        String base = sym.substring(0, sym.length() - 4);
                        binanceUsdMap.put(base, o.getDouble("price"));
                    }
                }
            }
            Log.d(TAG, "Binance map: " + binanceUsdMap);

            // 환율 및 성공 시각 저장
            prefs.edit()
                    .putFloat(KEY_USD_KRW, (float) usdKrw)
                    .putLong(KEY_LAST_SUCCESS, System.currentTimeMillis())
                    .apply();

            // 5. 각 위젯 인스턴스 업데이트
            for (int id : ids) {
                String coin1 = prefs.getString("coin1_" + id, DEFAULT_COIN1);
                String coin2 = prefs.getString("coin2_" + id, DEFAULT_COIN2);
                renderInstance(context, id, coin1, coin2, usdKrw, upbitKrwMap, binanceUsdMap, prefs);
            }

            // 새로고침 아이콘 숨기기
            new Thread(() -> {
                try {
                    Thread.sleep(1000);
                    hideRefreshIcon(context, awm.getAppWidgetIds(cn));
                } catch (InterruptedException e) {
                    Log.e(TAG, "Interrupted", e);
                }
            }).start();
        } catch (Exception e) {
            Log.e(TAG, "Error in updatePricesDirectly", e);
            showErrorState(context);
        }
    }

    private static double fetchExchangeRate() throws Exception {
        try {
            double v = fetchExchangeRateFromYahoo();
            Log.d(TAG, "Exchange rate from Yahoo Finance: " + v);
            return v;
        } catch (Exception e) {
            Log.w(TAG, "Yahoo failed, trying open.er-api.com: " + e.getMessage());
        }
        double v = fetchExchangeRateFromErApi();
        Log.d(TAG, "Exchange rate from open.er-api.com (fallback): " + v);
        return v;
    }

    private static double fetchExchangeRateFromYahoo() throws Exception {
        String yahooJson = fetchUrl(YAHOO_FINANCE_API);
        JSONObject yahooObj = new JSONObject(yahooJson);
        JSONObject result = yahooObj.getJSONObject("chart").getJSONArray("result").getJSONObject(0);
        JSONObject meta = result.getJSONObject("meta");
        return meta.getDouble("regularMarketPrice");
    }

    private static double fetchExchangeRateFromErApi() throws Exception {
        String json = fetchUrl(ER_API_FALLBACK);
        JSONObject obj = new JSONObject(json);
        if (!"success".equalsIgnoreCase(obj.optString("result"))) {
            throw new Exception("er-api result not success");
        }
        return obj.getJSONObject("rates").getDouble("KRW");
    }

    private static void renderInstance(Context context, int id, String coin1, String coin2, double usdKrw,
                                       Map<String, Double> upbitMap, Map<String, Double> binanceMap,
                                       SharedPreferences prefs) {
        try {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_kimchi);

            DecimalFormat dfKrw = new DecimalFormat("#,##0");

            views.setTextViewText(R.id.tv_exchange_rate, "환율: ₩" + dfKrw.format(usdKrw));
            views.setTextViewText(R.id.tv_coin1_label, coin1);
            views.setTextViewText(R.id.tv_coin2_label, coin2);

            fillSlot(views, 1, coin1, usdKrw, upbitMap, binanceMap);
            fillSlot(views, 2, coin2, usdKrw, upbitMap, binanceMap);

            views.setTextViewText(R.id.tv_update_time, formatUpdateTime(prefs));
            views.setViewVisibility(R.id.tv_update_time, View.VISIBLE);
            views.setViewVisibility(R.id.iv_refresh_icon, View.GONE);

            Intent refreshIntent = new Intent(context, KimchiWidgetProvider.class);
            refreshIntent.setAction(KimchiWidgetProvider.ACTION_REFRESH);
            PendingIntent pi = PendingIntent.getBroadcast(context, id, refreshIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            views.setOnClickPendingIntent(R.id.widget_root, pi);

            // 인스턴스별 캐시 저장
            SharedPreferences.Editor editor = prefs.edit();
            saveSlotCache(editor, id, 1, coin1, usdKrw, upbitMap, binanceMap);
            saveSlotCache(editor, id, 2, coin2, usdKrw, upbitMap, binanceMap);
            editor.apply();

            AppWidgetManager.getInstance(context).updateAppWidget(id, views);
            Log.d(TAG, "Instance " + id + " updated with " + coin1 + "/" + coin2);
        } catch (Exception e) {
            Log.e(TAG, "Error rendering instance " + id, e);
        }
    }

    private static void fillSlot(RemoteViews views, int slot, String coin, double usdKrw,
                                  Map<String, Double> upbitMap, Map<String, Double> binanceMap) {
        int[] ids = slotIds(slot);
        DecimalFormat df2 = new DecimalFormat("+#,##0.00;-#,##0.00");
        DecimalFormat dfKrw = new DecimalFormat("#,##0");
        DecimalFormat dfUsd = new DecimalFormat("#,##0.00");

        Double upbitPrice = upbitMap.get(coin);
        if (upbitPrice == null) {
            views.setTextViewText(ids[0], "데이터 없음");
            views.setTextViewText(ids[1], "₩ --");
            views.setTextViewText(ids[2], "₩ --");
            views.setTextViewText(ids[3], coin.equals("USDT") ? "₩ --" : "$ --");
            views.setTextViewText(ids[4], coin.equals("USDT") ? "기준가" : "바이낸스");
            return;
        }

        double premium, diff, refVal;
        boolean refIsKrw;
        String refLabel;

        if (coin.equals("USDT")) {
            premium = (upbitPrice - usdKrw) / usdKrw * 100.0;
            diff = upbitPrice - usdKrw;
            refVal = usdKrw;
            refIsKrw = true;
            refLabel = "기준가";
        } else {
            Double binanceUsd = binanceMap.get(coin);
            if (binanceUsd == null) {
                views.setTextViewText(ids[0], "데이터 없음");
                return;
            }
            double binanceKrw = binanceUsd * usdKrw;
            premium = (upbitPrice - binanceKrw) / binanceKrw * 100.0;
            diff = upbitPrice - binanceKrw;
            refVal = binanceUsd;
            refIsKrw = false;
            refLabel = "바이낸스";
        }

        views.setTextViewText(ids[0], df2.format(premium) + "%");
        views.setTextColor(ids[0], getPremiumColor(premium));
        views.setTextViewText(ids[1], "₩" + dfKrw.format(diff));
        views.setTextViewText(ids[2], "₩" + dfKrw.format(upbitPrice));
        views.setTextViewText(ids[3], refIsKrw ? ("₩" + dfKrw.format(refVal)) : ("$" + dfUsd.format(refVal)));
        views.setTextViewText(ids[4], refLabel);
    }

    private static int[] slotIds(int slot) {
        if (slot == 1) {
            return new int[]{R.id.tv_btc_premium, R.id.tv_btc_diff, R.id.tv_btc_krw, R.id.tv_btc_usd, R.id.tv_coin1_ref_label};
        } else {
            return new int[]{R.id.tv_usdt_premium, R.id.tv_usdt_diff, R.id.tv_usdt_krw, R.id.tv_usdt_usd, R.id.tv_coin2_ref_label};
        }
    }

    private static void saveSlotCache(SharedPreferences.Editor editor, int id, int slot, String coin,
                                       double usdKrw, Map<String, Double> upbitMap, Map<String, Double> binanceMap) {
        Double upbitPrice = upbitMap.get(coin);
        if (upbitPrice == null) return;

        double premium, diff, refVal;
        if (coin.equals("USDT")) {
            premium = (upbitPrice - usdKrw) / usdKrw * 100.0;
            diff = upbitPrice - usdKrw;
            refVal = usdKrw;
        } else {
            Double bin = binanceMap.get(coin);
            if (bin == null) return;
            double binKrw = bin * usdKrw;
            premium = (upbitPrice - binKrw) / binKrw * 100.0;
            diff = upbitPrice - binKrw;
            refVal = bin;
        }
        String p = "slot" + slot + "_" + id + "_";
        editor.putString(p + "coin", coin);
        editor.putFloat(p + "premium", (float) premium);
        editor.putFloat(p + "diff", (float) diff);
        editor.putFloat(p + "upbit", upbitPrice.floatValue());
        editor.putFloat(p + "ref", (float) refVal);
    }

    /**
     * 갱신 실패 시: 캐시된 값 유지하면서 시간 옆에 ⚠️ 표시 (formatUpdateTime이 자동 처리).
     */
    private static void showErrorState(Context context) {
        try {
            AppWidgetManager awm = AppWidgetManager.getInstance(context);
            ComponentName cn = new ComponentName(context, KimchiWidgetProvider.class);
            int[] ids = awm.getAppWidgetIds(cn);
            for (int id : ids) {
                KimchiWidgetProvider.renderFromCache(context, awm, id);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error showing error state", e);
        }
    }

    /**
     * 마지막 성공 시각 기준으로 시간 + 경과 시간(⚠️) 포맷.
     * - < 2분: "HH:mm" (정상)
     * - < 1시간: "HH:mm ⚠️Xm"
     * - ≥ 1시간: "HH:mm ⚠️Xh"
     */
    static String formatUpdateTime(SharedPreferences prefs) {
        long lastSuccess = prefs.getLong(KEY_LAST_SUCCESS, 0);
        if (lastSuccess == 0) return "--:--";
        long now = System.currentTimeMillis();
        long ageMs = now - lastSuccess;
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.KOREA);
        String time = sdf.format(new Date(lastSuccess));

        if (ageMs < 2 * 60 * 1000L) {
            return time;
        } else if (ageMs < 60 * 60 * 1000L) {
            long mins = ageMs / (60 * 1000L);
            return time + " ⚠️" + mins + "m";
        } else {
            long hours = ageMs / (60 * 60 * 1000L);
            return time + " ⚠️" + hours + "h";
        }
    }

    private static void showRefreshIcon(Context context, int[] ids) {
        try {
            AppWidgetManager awm = AppWidgetManager.getInstance(context);
            for (int id : ids) {
                RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_kimchi);
                views.setViewVisibility(R.id.iv_refresh_icon, View.VISIBLE);
                views.setViewVisibility(R.id.tv_update_time, View.GONE);
                awm.partiallyUpdateAppWidget(id, views);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error showing refresh icon", e);
        }
    }

    private static void hideRefreshIcon(Context context, int[] ids) {
        try {
            AppWidgetManager awm = AppWidgetManager.getInstance(context);
            for (int id : ids) {
                RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_kimchi);
                views.setViewVisibility(R.id.iv_refresh_icon, View.GONE);
                views.setViewVisibility(R.id.tv_update_time, View.VISIBLE);
                awm.partiallyUpdateAppWidget(id, views);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error hiding refresh icon", e);
        }
    }

    private static int getPremiumColor(double premium) {
        if (premium >= 3.0) return 0xFFFF4444;
        else if (premium >= 1.0) return 0xFFFF9800;
        else if (premium >= 0.0) return 0xFF4CAF50;
        else return 0xFF2196F3;
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
