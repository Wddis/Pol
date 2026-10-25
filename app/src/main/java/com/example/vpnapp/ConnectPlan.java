package com.example.vpnapp;

import android.content.Context;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Порядок способов подключения для выбранного режима. */
final class ConnectPlan {

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
                add(plan, builtIn(context, BuiltInBridges.SNOWFLAKE, R.string.mode_snowflake));
                break;
            case OBFS4:
                add(plan, builtIn(context, BuiltInBridges.OBFS4, R.string.mode_obfs4));
                break;
            case MEEK:
                add(plan, builtIn(context, BuiltInBridges.MEEK, R.string.mode_meek));
                break;
            case CUSTOM:
                List<String> lines = BridgeSet.parseUserLines(prefs.getCustomBridges());
                if (!lines.isEmpty()) {
                    plan.add(new BridgeSet(context.getString(R.string.mode_custom), lines));
                }
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
     * Авто: последний рабочий способ → рекомендации Moat (свежие мосты для этой сети)
     * → встроенные snowflake, obfs4, meek → без мостов.
     */
    private static void buildAuto(Context context, Prefs prefs, @Nullable MoatClient.Result moat,
                                  List<BridgeSet> plan) {
        add(plan, prefs.getLastGood());
        if (moat != null) {
            if (moat.directOk) add(plan, BridgeSet.direct(context.getString(R.string.mode_direct)));
            for (BridgeSet s : moat.sets) add(plan, s);
        } else {
            for (BridgeSet s : prefs.getMoatCache()) add(plan, s);
        }
        List<String> custom = BridgeSet.parseUserLines(prefs.getCustomBridges());
        if (!custom.isEmpty()) add(plan, new BridgeSet(context.getString(R.string.mode_custom), custom));
        add(plan, builtIn(context, BuiltInBridges.SNOWFLAKE, R.string.mode_snowflake));
        add(plan, builtIn(context, BuiltInBridges.OBFS4, R.string.mode_obfs4));
        add(plan, builtIn(context, BuiltInBridges.MEEK, R.string.mode_meek));
        add(plan, BridgeSet.direct(context.getString(R.string.mode_direct)));
    }

    @Nullable
    private static BridgeSet builtIn(Context context, String type, int label) {
        List<String> lines = BuiltInBridges.get(context, type);
        return lines.isEmpty() ? null : new BridgeSet(context.getString(label), lines);
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
