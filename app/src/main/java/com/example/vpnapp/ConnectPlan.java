package com.example.vpnapp;

import android.content.Context;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Порядок способов подключения к Tor для выбранного режима (Psiphon запускается отдельно). */
final class ConnectPlan {

    private static final String PT_DNSTT = "dnstt";
    private static final String PT_SNOWFLAKE = "snowflake";
    private static final Set<String> CENSORED = new HashSet<>(Arrays.asList(
            "ru", "by", "cn", "ir", "tm", "mm"));

    private ConnectPlan() {
    }

    /** Нужен ли запрос к Moat перед подключением. */
    static boolean needsMoat(BridgeMode mode) {
        return mode == BridgeMode.AUTO;
    }

    /** Страны, где Tor без мостов заблокирован: прямое подключение там только последним. */
    static boolean isCensored(@Nullable String networkCountry) {
        return networkCountry != null
                && CENSORED.contains(networkCountry.toLowerCase(Locale.ROOT));
    }

    /**
     * @param moat           ответ Moat или null (тогда берутся мосты Moat из кэша)
     * @param networkCountry код страны сети оператора или null
     */
    static List<BridgeSet> build(Context context, Prefs prefs, @Nullable MoatClient.Result moat,
                                 @Nullable String networkCountry) {
        List<BridgeSet> plan = new ArrayList<>();
        switch (prefs.getMode()) {
            case PSIPHON:
                // Только Psiphon: Tor не нужен
                break;
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
                buildAuto(context, prefs, moat, networkCountry, plan);
        }
        return plan;
    }

    /**
     * Авто:
     * 1) последний рабочий мост (быстрый путь);
     * 2) Snowflake — в сетях с блокировками работает чаще всего;
     * 3) другие мосты: от Moat, свои, meek;
     * 4) встроенные obfs4 (почти везде заблокированы);
     * 5) DNSTT — медленный запасной путь через DNS;
     * 6) без мостов (первым, если Moat сказал, что мосты в этой стране не нужны).
     * Способы, которые в прошлый раз не прошли (без мостов, obfs4), уходят в конец.
     */
    private static void buildAuto(Context context, Prefs prefs, @Nullable MoatClient.Result moat,
                                  @Nullable String networkCountry, List<BridgeSet> plan) {
        // Если прямое подключение уже не прошло, не тратим на него время в начале
        boolean directOk = moat != null && moat.directOk && !prefs.isDirectFailed()
                && !isCensored(networkCountry);
        BridgeSet direct = BridgeSet.direct(context.getString(R.string.mode_direct));
        if (directOk) add(plan, direct);
        add(plan, prefs.getLastGood());

        List<BridgeSet> moatSets = moat != null ? moat.sets : prefs.getMoatCache();

        // Snowflake: сначала через Google AMP, затем через CDN77. Snowflake от Moat
        // свежее встроенного, но у них одинаковые адреса-заглушки, а их Tor не любит
        List<BridgeSet> snowflake = new ArrayList<>();
        snowflake.add(new BridgeSet("", BuiltInBridges.snowflakeAmp(context)));
        boolean moatSnowflake = false;
        for (BridgeSet s : moatSets) {
            BridgeSet only = s.only(PT_SNOWFLAKE);
            if (only != null) {
                snowflake.add(only);
                moatSnowflake = true;
            }
        }
        if (!moatSnowflake) {
            snowflake.add(new BridgeSet("", BuiltInBridges.get(context, BuiltInBridges.SNOWFLAKE)));
        }
        add(plan, nonEmpty(BridgeSet.combine(context.getString(R.string.mode_snowflake), snowflake)));

        // Другие мосты сразу: Tor пробует их параллельно и берёт первый ответивший
        List<BridgeSet> pool = new ArrayList<>(moatSets);
        BridgeSet own = custom(context, prefs);
        if (own != null) pool.add(own);
        pool.add(new BridgeSet("", BuiltInBridges.get(context, BuiltInBridges.MEEK)));
        List<String> builtinObfs4 = BuiltInBridges.get(context, BuiltInBridges.OBFS4);
        BridgeSet others = BridgeSet.combine(context.getString(R.string.mode_all), pool)
                .without(PT_SNOWFLAKE).without(PT_DNSTT);
        // Встроенные obfs4 — отдельной попыткой ниже
        List<String> otherLines = new ArrayList<>(others.lines);
        otherLines.removeAll(builtinObfs4);
        add(plan, lines(others.label, otherLines));

        BridgeSet obfs4 = lines(context.getString(R.string.mode_obfs4), builtinObfs4);
        boolean obfs4Failed = prefs.isObfs4Failed();
        if (!obfs4Failed) add(plan, obfs4);

        // DNSTT отдельно: если Tor выберет его вместе с быстрыми мостами, всё будет медленным
        // Свои DNSTT от Moat не смешиваем со встроенными: у них одинаковые адреса-заглушки
        List<BridgeSet> dnsttPool = new ArrayList<>();
        for (BridgeSet s : moatSets) {
            BridgeSet d = s.only(PT_DNSTT);
            if (d != null) dnsttPool.add(d);
        }
        if (dnsttPool.isEmpty()) dnsttPool.add(new BridgeSet("", BuiltInBridges.dnstt()));
        add(plan, BridgeSet.combine(context.getString(R.string.mode_dnstt), dnsttPool));

        if (obfs4Failed) add(plan, obfs4);
        if (!directOk) add(plan, direct);
    }

    /**
     * План с новыми данными Moat, пока подключение уже идёт: уже пройденные способы
     * (до текущего включительно) остаются, дальше — новый план без уже пробованных мостов.
     */
    static List<BridgeSet> merge(List<BridgeSet> current, int attempt, List<BridgeSet> fresh) {
        List<BridgeSet> result = new ArrayList<>();
        Set<String> tried = new HashSet<>();
        boolean directTried = false;
        for (int i = 0; i <= attempt && i < current.size(); i++) {
            BridgeSet s = current.get(i);
            result.add(s);
            tried.addAll(s.lines);
            directTried |= s.isDirect();
        }
        for (BridgeSet s : fresh) {
            if (s.isDirect()) {
                if (!directTried) {
                    result.add(s);
                    directTried = true;
                }
                continue;
            }
            List<String> left = new ArrayList<>();
            for (String line : s.lines) {
                if (!tried.contains(line)) left.add(line);
            }
            if (left.isEmpty()) continue;
            result.add(new BridgeSet(s.label, left));
            tried.addAll(left);
        }
        return result;
    }

    @Nullable
    private static BridgeSet snowflakeAll(Context context) {
        // Через Google AMP первым: в сетях с блокировками он проходит чаще CDN77
        List<String> lines = new ArrayList<>(BuiltInBridges.snowflakeAmp(context));
        lines.addAll(BuiltInBridges.get(context, BuiltInBridges.SNOWFLAKE));
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

    @Nullable
    private static BridgeSet nonEmpty(BridgeSet set) {
        return set.isDirect() ? null : set;
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
