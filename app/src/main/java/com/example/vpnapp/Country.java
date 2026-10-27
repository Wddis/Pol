package com.example.vpnapp;

import android.content.Context;
import android.content.res.Resources;

import java.util.ArrayList;
import java.util.List;

/** Страна выхода: двухбуквенный код и название. */
public final class Country {

    /** Регион «Авто»: страну выхода выбирает Tor. */
    public static final String ANY = "ANY";

    public final String code;
    public final String name;

    private Country(String code, String name) {
        this.code = code;
        this.name = name;
    }

    /** Все страны из ресурсов, в порядке списка. */
    public static List<Country> all(Context context) {
        Resources res = context.getResources();
        String[] codes = res.getStringArray(R.array.country_codes);
        String[] names = res.getStringArray(R.array.country_names);
        List<Country> list = new ArrayList<>(codes.length);
        for (int i = 0; i < codes.length; i++) {
            list.add(new Country(codes[i], names[i]));
        }
        return list;
    }

    public static boolean isAny(String code) {
        return ANY.equals(code);
    }

    /** Код для экрана: у «Авто» вместо кода — значок. */
    public String shortLabel() {
        return isAny(code) ? "★" : code;
    }

    /** Флаг страны (эмодзи из двух букв кода), «» — не двухбуквенный код. */
    public static String flag(String code) {
        if (code == null || code.length() != 2) return "";
        String c = code.toUpperCase(java.util.Locale.ROOT);
        if (c.charAt(0) < 'A' || c.charAt(0) > 'Z' || c.charAt(1) < 'A' || c.charAt(1) > 'Z') return "";
        return new String(Character.toChars(0x1F1E6 + c.charAt(0) - 'A'))
                + new String(Character.toChars(0x1F1E6 + c.charAt(1) - 'A'));
    }

    /** «Швеция 🇸🇪» — название с флагом (для строки под статусом). */
    public static String label(Context context, String code) {
        String f = flag(code);
        String name = nameOf(context, code);
        return f.isEmpty() ? name : name + " " + f;
    }

    /** Название страны по коду; для стран вне списка — сам код. */
    public static String nameOf(Context context, String code) {
        for (Country c : all(context)) {
            if (c.code.equals(code)) return c.name;
        }
        return code;
    }

    /** Страна по коду; если код неизвестен — первая в списке. */
    public static Country find(Context context, String code) {
        List<Country> list = all(context);
        for (Country c : list) {
            if (c.code.equals(code)) return c;
        }
        return list.get(0);
    }
}
