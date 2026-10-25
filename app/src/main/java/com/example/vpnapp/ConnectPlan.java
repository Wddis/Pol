package com.example.vpnapp;

import android.content.Context;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Порядок способов подключения для выбранного режима. */
final class ConnectPlan {

    private static final String PT_DNSTT = "dnstt";
    private static final String PT_SNOWFLAKE = "snowflake";

    private ConnectPlan() {
    }

    /** Нужен ли запрос к Moat перед подключением. */
    static boolean needsMoat(BridgeMode mode) {
        return mode == BridgeMode.AUTO;
    }

    static List<BridgeSet> build(Context context, Prefs prefs, @Nullable MoatClient.Result moat) {
        List<BridgeSet> plan = new ArrayList<>();
        switch (prefs.getMode()) {
            case SNOWFLAKE:
                add(plan, snowflakeAll(context));
                break;
            case OBFS4:
                add(plan, lines(context.getString(R.string.mode_obfs4),
                        BuiltInBridges.get(context, BuiltInBridges.OBFS4)));
                break;
            case MEEK:
                add(plan, lines(context.getString(R.string.mode_meek),
                        BuiltInBridges.get(context, BuiltInBridges.MEEK)));
                break;
            case DNSTT:
                add(plan, lines(context.getString(R.string.mode_dnstt), BuiltInBridges.dnstt()));
                break;
            case CUSTOM:
                add(plan, custom(context, prefs));
                break;
            case DIRECT:
                plan.add(BridgeSet.direct(context.getString(R.string.mode_direct)));
                break;
            case AUTO:
            default:
                buildAuto(context, prefs, moat, plan);
        }
        return plan;
    }

    /**
     * Авто:
     * 1) последний рабочий мост (быстрый путь);
     * 2) все мосты сразу — Tor пробует их параллельно и берёт первый ответивший:
     *    от Moat, свои, snowflake (CDN77 и Google AMP), meek, obfs4;
     * 3) DNSTT — медленный запасной путь через DNS;
     * 4) без мостов (первым, если Moat сказал, что мосты в этой стране не нужны).
     */
    private static void buildAuto(Context context, Prefs prefs, @Nullable MoatClient.Result moat,
                                  List<BridgeSet> plan) {
        // Если прямое подключение уже не прошло, не тратим на него время в начале
        boolean directOk = moat != null && moat.directOk && !prefs.isDirectFailed();
        BridgeSet direct = BridgeSet.direct(context.getString(R.string.mode_direct));
        if (directOk) add(plan, direct);
        add(plan, prefs.getLastGood());

        List<BridgeSet> moatSets = moat != null ? moat.sets : prefs.getMoatCache();
        List<BridgeSet> pool = new ArrayList<>(moatSets);
        BridgeSet own = custom(context, prefs);
        if (own != null) pool.add(own);
        // Snowflake от Moat свежее встроенного; одинаковые адреса-заглушки Tor не любит
        boolean moatSnowflake = false;
        for (BridgeSet s : moatSets) moatSnowflake |= s.only(PT_SNOWFLAKE) != null;
        pool.add(new BridgeSet("", BuiltInBridges.snowflakeAmp(context)));
        if (!moatSnowflake) pool.add(new BridgeSet("", BuiltInBridges.get(context, BuiltInBridges.SNOWFLAKE)));
        pool.add(new BridgeSet("", BuiltInBridges.get(context, BuiltInBridges.MEEK)));
        pool.add(new BridgeSet("", BuiltInBridges.get(context, BuiltInBridges.OBFS4)));
        BridgeSet all = BridgeSet.combine(context.getString(R.string.mode_all), pool).without(PT_DNSTT);
        if (!all.isDirect()) add(plan, all);

        // DNSTT отдельно: если Tor выберет его вместе с быстрыми мостами, всё будет медленным
        // Свои DNSTT от Moat не смешиваем со встроенными: у них одинаковые адреса-заглушки
        List<BridgeSet> dnsttPool = new ArrayList<>();
        for (BridgeSet s : moatSets) {
            BridgeSet d = s.only(PT_DNSTT);
            if (d != null) dnsttPool.add(d);
        }
        if (dnsttPool.isEmpty()) dnsttPool.add(new BridgeSet("", BuiltInBridges.dnstt()));
        add(plan, BridgeSet.combine(context.getString(R.string.mode_dnstt), dnsttPool));

        if (!directOk) add(plan, direct);
    }

    @Nullable
    private static BridgeSet snowflakeAll(Context context) {
        List<String> lines = new ArrayList<>(BuiltInBridges.get(context, BuiltInBridges.SNOWFLAKE));
        lines.addAll(BuiltInBridges.snowflakeAmp(context));
        return lines(context.getString(R.string.mode_snowflake), lines);
    }

    @Nullable
    private static BridgeSet custom(Context context, Prefs prefs) {
        return lines(context.getString(R.string.mode_custom),
                BridgeParser.parse(prefs.getCustomBridges()).valid);
    }

    @Nullable
    private static BridgeSet lines(String label, List<String> lines) {
        return lines.isEmpty() ? null : new BridgeSet(label, lines);
    }

    /** Добавляет без повторов (одинаковые строки мостов). */
    private static void add(List<BridgeSet> plan, @Nullable BridgeSet set) {
        if (set == null) return;
        for (BridgeSet s : plan) {
            if (s.sameLines(set)) return;
        }
        plan.add(set);
    }
}
