package com.kimchi.widget;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;

import androidx.appcompat.app.AppCompatActivity;

public class KimchiWidgetConfigActivity extends AppCompatActivity {

    private int appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 사용자가 뒤로 나가면 위젯 추가 취소
        setResult(RESULT_CANCELED);

        setContentView(R.layout.widget_config);

        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            appWidgetId = extras.getInt(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
        }
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return;
        }

        Spinner spinner1 = findViewById(R.id.spinner_coin1);
        Spinner spinner2 = findViewById(R.id.spinner_coin2);
        Button saveBtn = findViewById(R.id.btn_save);

        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this, R.array.coin_symbols, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner1.setAdapter(adapter);
        spinner2.setAdapter(adapter);

        // 기존 설정 로드 (재구성 시)
        SharedPreferences prefs = getSharedPreferences(
                KimchiUpdateService.PREFS_NAME, Context.MODE_PRIVATE);
        String existingCoin1 = prefs.getString(
                "coin1_" + appWidgetId, KimchiUpdateService.DEFAULT_COIN1);
        String existingCoin2 = prefs.getString(
                "coin2_" + appWidgetId, KimchiUpdateService.DEFAULT_COIN2);
        int pos1 = adapter.getPosition(existingCoin1);
        int pos2 = adapter.getPosition(existingCoin2);
        if (pos1 >= 0) spinner1.setSelection(pos1);
        if (pos2 >= 0) spinner2.setSelection(pos2);

        saveBtn.setOnClickListener(v -> {
            String coin1 = (String) spinner1.getSelectedItem();
            String coin2 = (String) spinner2.getSelectedItem();

            SharedPreferences.Editor editor = prefs.edit();
            editor.putString("coin1_" + appWidgetId, coin1);
            editor.putString("coin2_" + appWidgetId, coin2);
            editor.apply();

            // 즉시 가격 갱신 트리거
            Intent refreshIntent = new Intent(this, KimchiWidgetProvider.class);
            refreshIntent.setAction(KimchiWidgetProvider.ACTION_REFRESH);
            sendBroadcast(refreshIntent);

            // 위젯 추가 완료 알림
            Intent result = new Intent();
            result.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
            setResult(RESULT_OK, result);
            finish();
        });
    }
}
