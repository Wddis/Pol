package com.example.vpnapp;

/** Режим подключения к сети Tor, выбирается в листе «Мосты». */
enum BridgeMode {
    AUTO(R.string.mode_auto, R.string.mode_auto_hint),
    SNOWFLAKE(R.string.mode_snowflake, R.string.mode_snowflake_hint),
    OBFS4(R.string.mode_obfs4, R.string.mode_obfs4_hint),
    MEEK(R.string.mode_meek, R.string.mode_meek_hint),
    CUSTOM(R.string.mode_custom, R.string.mode_custom_hint),
    DIRECT(R.string.mode_direct, R.string.mode_direct_hint);

    final int title;
    final int hint;

    BridgeMode(int title, int hint) {
        this.title = title;
        this.hint = hint;
    }

    static BridgeMode from(String name) {
        for (BridgeMode m : values()) {
            if (m.name().equals(name)) return m;
        }
        return AUTO;
    }
}
