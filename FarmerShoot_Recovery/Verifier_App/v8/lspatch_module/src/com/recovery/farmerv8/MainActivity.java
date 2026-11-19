package com.recovery.farmerv8;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

/** Пустышка-лаунчер: сам модуль работает ВНУТРИ игры через LSPatch. */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        TextView tv = new TextView(this);
        tv.setPadding(32, 48, 32, 32);
        tv.setTextColor(Color.WHITE);
        tv.setText(
            "FarmerShoot v8 — модуль LSPatch/Xposed\n\n" +
            "Это НЕ запускается напрямую. Используйте LSPatch:\n" +
            "1) LSPatch → New Patch → выберите вашу игру.\n" +
            "2) Add module → выберите этот apk (FarmerShoot v8).\n" +
            "3) Start patch → установите пропатченную игру.\n" +
            "4) Запустите игру — сверху появится тост и панель v8.\n\n" +
            "Патроны/прыжок работают только с вшитым нативным\n" +
            "движком libfarmerv8.so (см. README). Без него панель\n" +
            "всё равно появится — это доказательство инъекции.\n\n" +
            "Честно: Xposed хукает Java, а логика Unity — нативный\n" +
            "IL2CPP, поэтому значения читает/пишет нативная часть.");
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.rgb(16, 20, 16));
        sv.addView(tv);
        setContentView(sv);
    }
}
