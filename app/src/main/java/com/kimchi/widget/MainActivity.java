package com.kimchi.widget;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import android.widget.TextView;
import android.view.Gravity;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        TextView tv = new TextView(this);
        tv.setText("🌶 김치 프리미엄 위젯 앱이 설치되었습니다.\n\n홈 화면에서 위젯을 추가해 주세요!");
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(18);
        setContentView(tv);
    }
}
