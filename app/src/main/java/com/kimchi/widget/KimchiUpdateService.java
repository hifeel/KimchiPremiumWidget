package com.kimchi.widget;

import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    // 브로드캐스트 수신 제한(약 10초) 안에 끝나도록 갱신 1회의 전체 시간을 제한한다.
    private static final long UPDATE_BUDGET_MS = 9000;
    private static final long YAHOO_BUDGET_MS = 3000;
    private static final int REQUEST_TIMEOUT_MS = 3000;
    private static final int MAX_ATTEMPTS = 5;
    private static final long RETRY_DELAY_MS = 1000;
    private static final int NO_DATA_COLOR = 0xFFFFFFFF;

    private static final ExecutorService UPDATE_EXECUTOR = Executors.newSingleThreadExecutor();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        requestUpdate(this, () -> stopSelf(startId));
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** 갱신 요청 진입점. 갱신은 전용 스레드에서 한 번에 하나씩 실행하고, 끝나면 onFinished를 호출한다. */
    static void requestUpdate(Context context, Runnable onFinished) {
        Context appContext = context.getApplicationContext();
        UPDATE_EXECUTOR.execute(() -> {
            try {
                updatePricesDirectly(appContext);
            } finally {
                onFinished.run();
            }
        });
    }

    private static void updatePricesDirectly(Context context) {
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

            long deadlineMs = SystemClock.elapsedRealtime() + UPDATE_BUDGET_MS;

            // 2. 환율 (Yahoo → open.er-api.com 폴백)
            double usdKrw = fetchExchangeRate(deadlineMs);
            Log.d(TAG, "USDKRW: " + usdKrw);

            // 3. 업비트 가격 조회
            Map<String, Double> upbitKrwMap = fetchUpbitPrices(uniqueCoins, deadlineMs);
            Log.d(TAG, "Upbit map: " + upbitKrwMap);

            // 4. 바이낸스 가격 조회 (업비트에 있는 코인만, USDT 제외)
            Map<String, Double> binanceUsdMap = fetchBinancePrices(upbitKrwMap.keySet(), deadlineMs);
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

            // 렌더링 중 예외가 나도 아이콘이 남지 않도록 마지막에 한 번 더 숨긴다.
            hideRefreshIcon(context, ids);
        } catch (Exception e) {
            Log.e(TAG, "Error in updatePricesDirectly", e);
            showErrorState(context);
        }
    }

    private static double fetchExchangeRate(long deadlineMs) throws Exception {
        try {
            long yahooDeadlineMs = Math.min(deadlineMs, SystemClock.elapsedRealtime() + YAHOO_BUDGET_MS);
            double v = fetchExchangeRateFromYahoo(yahooDeadlineMs);
            Log.d(TAG, "Exchange rate from Yahoo Finance: " + v);
            return v;
        } catch (Exception e) {
            Log.w(TAG, "Yahoo failed, trying open.er-api.com: " + e.getMessage());
        }
        double v = fetchExchangeRateFromErApi(deadlineMs);
        Log.d(TAG, "Exchange rate from open.er-api.com (fallback): " + v);
        return v;
    }

    private static double fetchExchangeRateFromYahoo(long deadlineMs) throws Exception {
        String yahooJson = fetchUrl(YAHOO_FINANCE_API, deadlineMs);
        JSONObject yahooObj = new JSONObject(yahooJson);
        JSONObject result = yahooObj.getJSONObject("chart").getJSONArray("result").getJSONObject(0);
        JSONObject meta = result.getJSONObject("meta");
        return meta.getDouble("regularMarketPrice");
    }

    private static double fetchExchangeRateFromErApi(long deadlineMs) throws Exception {
        String json = fetchUrl(ER_API_FALLBACK, deadlineMs);
        JSONObject obj = new JSONObject(json);
        if (!"success".equalsIgnoreCase(obj.optString("result"))) {
            throw new Exception("er-api result not success");
        }
        return obj.getJSONObject("rates").getDouble("KRW");
    }

    private static Map<String, Double> fetchUpbitPrices(Set<String> coins, long deadlineMs) throws Exception {
        Map<String, Double> prices = new HashMap<>();
        StringBuilder markets = new StringBuilder();
        for (String c : coins) {
            if (markets.length() > 0) markets.append(",");
            markets.append("KRW-").append(c);
        }
        try {
            parseUpbitPrices(fetchUrl(UPBIT_API_BASE + markets, deadlineMs), prices);
        } catch (HttpStatusException e) {
            if (e.code != 404) throw e;
            // 없는 마켓이 하나라도 섞이면 업비트는 요청 전체를 404로 거부하므로 코인별로 다시 조회한다.
            Log.w(TAG, "Upbit rejected batch, retrying per coin");
            for (String c : coins) {
                try {
                    parseUpbitPrices(fetchUrl(UPBIT_API_BASE + "KRW-" + c, deadlineMs), prices);
                } catch (HttpStatusException single) {
                    if (single.code != 404) throw single;
                    Log.w(TAG, "No Upbit KRW market for " + c);
                }
            }
        }
        return prices;
    }

    private static void parseUpbitPrices(String json, Map<String, Double> prices) throws Exception {
        JSONArray arr = new JSONArray(json);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.getJSONObject(i);
            String market = item.getString("market");
            if (market.startsWith("KRW-")) {
                prices.put(market.substring(4), item.getDouble("trade_price"));
            }
        }
    }

    private static Map<String, Double> fetchBinancePrices(Set<String> coins, long deadlineMs) throws Exception {
        Map<String, Double> prices = new HashMap<>();
        prices.put("USDT", 1.0);
        StringBuilder symbols = new StringBuilder("[");
        boolean first = true;
        for (String c : coins) {
            if (c.equals("USDT")) continue;
            if (!first) symbols.append(",");
            symbols.append("\"").append(c).append("USDT\"");
            first = false;
        }
        symbols.append("]");
        if (first) return prices;

        try {
            parseBinancePrices(fetchUrl(binanceUrl(symbols.toString()), deadlineMs), prices);
        } catch (HttpStatusException e) {
            if (e.code != 400) throw e;
            // 없는 심볼이 하나라도 섞이면 바이낸스는 요청 전체를 400으로 거부하므로 코인별로 다시 조회한다.
            Log.w(TAG, "Binance rejected batch, retrying per coin");
            for (String c : coins) {
                if (c.equals("USDT")) continue;
                try {
                    parseBinancePrices(fetchUrl(binanceUrl("[\"" + c + "USDT\"]"), deadlineMs), prices);
                } catch (HttpStatusException single) {
                    if (single.code != 400) throw single;
                    Log.w(TAG, "No Binance USDT market for " + c);
                }
            }
        }
        return prices;
    }

    private static String binanceUrl(String symbolsJson) throws Exception {
        return BINANCE_API_BASE + URLEncoder.encode(symbolsJson, "UTF-8");
    }

    private static void parseBinancePrices(String json, Map<String, Double> prices) throws Exception {
        JSONArray arr = new JSONArray(json);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String sym = o.getString("symbol");
            if (sym.endsWith("USDT")) {
                prices.put(sym.substring(0, sym.length() - 4), o.getDouble("price"));
            }
        }
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

        Double upbitPrice = upbitMap.get(coin);
        if (upbitPrice == null) {
            views.setTextViewText(ids[0], "데이터 없음");
            views.setTextColor(ids[0], NO_DATA_COLOR);
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
                views.setTextColor(ids[0], NO_DATA_COLOR);
                views.setTextViewText(ids[1], "₩ --");
                views.setTextViewText(ids[2], "₩" + PriceFormat.krw(upbitPrice));
                views.setTextViewText(ids[3], "$ --");
                views.setTextViewText(ids[4], "바이낸스");
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
        views.setTextViewText(ids[1], "₩" + PriceFormat.krw(diff));
        views.setTextViewText(ids[2], "₩" + PriceFormat.krw(upbitPrice));
        views.setTextViewText(ids[3], refIsKrw ? ("₩" + PriceFormat.krw(refVal)) : ("$" + PriceFormat.usd(refVal)));
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
        String p = "slot" + slot + "_" + id + "_";
        Double upbitPrice = upbitMap.get(coin);
        if (upbitPrice == null) {
            // 시세가 사라진 코인의 옛 값이 새 시각과 함께 표시되지 않도록 캐시를 무효화한다.
            editor.remove(p + "coin");
            return;
        }

        double premium, diff, refVal;
        if (coin.equals("USDT")) {
            premium = (upbitPrice - usdKrw) / usdKrw * 100.0;
            diff = upbitPrice - usdKrw;
            refVal = usdKrw;
        } else {
            Double bin = binanceMap.get(coin);
            if (bin == null) {
                editor.remove(p + "coin");
                return;
            }
            double binKrw = bin * usdKrw;
            premium = (upbitPrice - binKrw) / binKrw * 100.0;
            diff = upbitPrice - binKrw;
            refVal = bin;
        }
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

    /**
     * 네트워크 오류와 5xx만 재시도한다. 4xx는 재시도해도 결과가 같으므로 바로 HttpStatusException을 던진다.
     * deadlineMs(elapsedRealtime 기준)가 지나면 새 시도를 시작하지 않는다.
     */
    private static String fetchUrl(String urlStr, long deadlineMs) throws Exception {
        IOException lastException = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            long remainingMs = deadlineMs - SystemClock.elapsedRealtime();
            if (remainingMs <= 0) break;

            HttpURLConnection conn = null;
            try {
                Log.d(TAG, "Fetching URL (attempt " + attempt + "): " + urlStr);
                int timeoutMs = (int) Math.min(REQUEST_TIMEOUT_MS, remainingMs);
                conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(timeoutMs);
                conn.setReadTimeout(timeoutMs);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                conn.setRequestProperty("Accept", "application/json");

                int responseCode = conn.getResponseCode();
                Log.d(TAG, "Response code: " + responseCode);

                if (responseCode != 200) {
                    throw new HttpStatusException(responseCode);
                }

                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                }

                String result = sb.toString();
                Log.d(TAG, "Fetched successfully: " + result.substring(0, Math.min(100, result.length())));
                return result;
            } catch (HttpStatusException e) {
                if (e.code < 500) throw e;
                lastException = e;
                Log.e(TAG, "Attempt " + attempt + " failed: " + e.getMessage());
            } catch (IOException e) {
                lastException = e;
                Log.e(TAG, "Attempt " + attempt + " failed: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }

            if (attempt < MAX_ATTEMPTS) {
                Thread.sleep(RETRY_DELAY_MS);
            }
        }

        throw lastException != null ? lastException : new IOException("Update budget exceeded: " + urlStr);
    }

    private static final class HttpStatusException extends IOException {
        final int code;

        HttpStatusException(int code) {
            super("HTTP " + code);
            this.code = code;
        }
    }
}
