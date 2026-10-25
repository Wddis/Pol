package com.example.vpnapp;

import android.content.Context;
import android.content.res.Resources;

import java.util.ArrayList;
import java.util.List;

/** Страна выхода: двухбуквенный код и название. */
public final class Country {

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
