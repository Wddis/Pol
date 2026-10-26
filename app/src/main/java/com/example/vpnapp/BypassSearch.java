package com.example.vpnapp;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Перебор стратегий обхода DPI и выбор лучшей. Без Android: логика проверяется тестами на компьютере.
 * <p>
 * Стратегии проверяются пачками по {@link #PARALLEL} одновременно (у каждой свой процесс ByeDPI
 * и свой порт). Первая пачка — запомненная для этой сети и мягкие стратегии; дальше — безопасные
 * цепочки «мягкая → сильная» ({@link BypassProfile#chain}).
 * <p>
 * Стратегия «полная» (FULL), только если открыла заблокированный сайт И обычные сайты
 * (зарубежные и российские) через неё работают не хуже, чем напрямую и чем у лучшей из проверенных.
 * Пробивает, но ломает обычные (BREAKS) — не берём никогда: так в 0.8.4 «oob SNI» прошла проверку
 * соцсети, но сломала браузер. Полной нет — честно берём лучшую по обычным сайтам (BASIC).
 */
final class BypassSearch {

    /** Сколько стратегий проверяется одновременно. */
    static final int PARALLEL = 3;

    /** Результат проверки стратегии (или прямого соединения): сколько сайтов открылось. */
    static final class Score {
        final int blocked;       // заблокированные в РФ сайты (соцсети и т.п.)
        final int blockedTotal;
        final int foreign;       // обычные зарубежные
        final int local;         // обычные российские
        final int normalTotal;
        final String detail;     // по сайтам, для журнала

        Score(int blocked, int blockedTotal, int foreign, int local, int normalTotal, String detail) {
            this.blocked = blocked;
            this.blockedTotal = blockedTotal;
            this.foreign = foreign;
            this.local = local;
            this.normalTotal = normalTotal;
            this.detail = detail;
        }

        int normal() {
            return foreign + local;
        }

        /** Коротко для журнала: «заблокированные 1/4, обычные 5/6 (российские 3)». */
        String summary() {
            return "заблокированные " + blocked + "/" + blockedTotal + ", обычные " + normal()
                    + "/" + normalTotal + " (российские " + local + ")";
        }
    }

    enum Verdict {
        /** Пробивает заблокированные и не ломает обычные. */
        FULL,
        /** Пробивает заблокированные, но ломает обычные сайты. */
        BREAKS,
        /** Только обычные сайты. */
        BASIC,
        /** Ничего не открылось. */
        NONE
    }

    /** Что делать дальше. Ровно одно: проверить пачку, подключиться или сдаться. */
    static final class Decision {
        /** Проверить эти стратегии (одновременно). */
        @Nullable final List<BypassProfile> test;
        /** Подключиться через эту стратегию: её процесс уже запущен в текущей пачке. */
        @Nullable final BypassProfile claim;
        /** Стратегия пробила заблокированные сайты (иначе — только обычный интернет). */
        final boolean full;
        /** Взята запомненная «только обычный интернет» без перебора: не перезапоминать (не продлевать срок). */
        final boolean fromMemory;
        /** Строки для журнала: почему так решено. */
        final List<String> notes;

        private Decision(@Nullable List<BypassProfile> test, @Nullable BypassProfile claim,
                         boolean full, boolean fromMemory, List<String> notes) {
            this.test = test;
            this.claim = claim;
            this.full = full;
            this.fromMemory = fromMemory;
            this.notes = notes;
        }

        boolean giveUp() {
            return test == null && claim == null;
        }
    }

    private enum Stage { CUSTOM, QUICK, FIRST, CHAINS, VERIFY }

    private static final class Entry {
        final BypassProfile profile;
        final int order;
        @Nullable Score score;

        Entry(BypassProfile profile, int order) {
            this.profile = profile;
            this.order = order;
        }
    }

    @Nullable private BypassProfile remembered;
    private final boolean rememberedFull;
    private final List<Entry> tested = new ArrayList<>();
    private List<Entry> batch = new ArrayList<>();
    private final List<BypassProfile> chains = new ArrayList<>();
    private final List<Entry> verifyQueue = new ArrayList<>();
    @Nullable private Score baseline;
    private boolean baselineDone;
    private Stage stage;
    private int order;

    /**
     * @param custom         своя строка ByeDPI из «СПОСОБ»: тогда проверяется только она
     * @param remembered     стратегия, выбранная в прошлый раз в этой сети (или null)
     * @param rememberedFull она тогда пробила заблокированные сайты
     * @param quickBasic     запомненная давала только обычный интернет, и полный перебор был недавно:
     *                       сначала проверить её одну и сразу подключиться (режим «Обход DPI»)
     */
    BypassSearch(@Nullable BypassProfile custom, @Nullable BypassProfile remembered,
                 boolean rememberedFull, boolean quickBasic) {
        this.remembered = custom == null ? remembered : null;
        this.rememberedFull = rememberedFull;
        if (custom != null) {
            stage = Stage.CUSTOM;
            batch = entries(Collections.singletonList(custom));
        } else if (remembered != null && !rememberedFull && quickBasic) {
            stage = Stage.QUICK;
            batch = entries(Collections.singletonList(remembered));
        } else {
            stage = Stage.FIRST;
            batch = entries(firstBatch());
        }
    }

    /** Стратегии, которые нужно запустить сейчас. */
    List<BypassProfile> currentBatch() {
        List<BypassProfile> result = new ArrayList<>();
        for (Entry e : batch) result.add(e.profile);
        return result;
    }

    /** Результат проверки стратегии из текущей пачки. */
    void report(BypassProfile profile, Score score) {
        for (Entry e : batch) {
            if (e.profile == profile && e.score == null) {
                e.score = score;
                return;
            }
        }
    }

    /** Результат прямой проверки (без VPN): эталон «обычного интернета». null — проверка не удалась. */
    void setBaseline(@Nullable Score score) {
        baseline = score;
        baselineDone = true;
    }

    /** Можно решать: вся пачка проверена и известен результат прямой проверки. */
    boolean ready() {
        if (!baselineDone && stage != Stage.CUSTOM) return false;
        for (Entry e : batch) {
            if (e.score == null) return false;
        }
        return true;
    }

    /** Решение после проверки пачки (вызывать, когда {@link #ready()}). */
    Decision decide() {
        List<String> notes = new ArrayList<>();
        List<Entry> done = batch;
        tested.addAll(done);
        for (Entry e : done) notes.add(describe(e));
        switch (stage) {
            case CUSTOM: {
                // Свою строку пользователь выбрал сам: берём, если через неё хоть что-то открылось
                Entry e = done.get(0);
                Score s = e.score;
                if (s != null && s.blocked + s.normal() > 0) {
                    if (verdict(s) == Verdict.BREAKS) {
                        notes.add("Свои настройки ByeDPI ломают обычные сайты, но выбраны вами — подключаюсь");
                    }
                    return claim(e, s.blocked > 0, notes);
                }
                return giveUp(notes);
            }
            case QUICK: {
                Entry e = done.get(0);
                Verdict v = verdict(e.score);
                if (v == Verdict.FULL || v == Verdict.BASIC) {
                    notes.add("Соцсети в этой сети не пробивались при недавнем полном переборе: "
                            + "включаю обычный интернет без нового перебора");
                    return new Decision(null, e.profile, v == Verdict.FULL, true, notes);
                }
                notes.add("Запомненная стратегия больше не работает: полный перебор");
                remembered = null;
                stage = Stage.FIRST;
                return test(firstBatch(), notes);
            }
            case FIRST: {
                Entry win = bestQualifying(done);
                if (win != null) return claim(win, true, notes);
                BypassProfile base = bestMild();
                if (base != null) {
                    notes.add("Мягкая стратегия для обычных сайтов: «" + base.label
                            + "»; сильные пробую только для сайтов, которые с ней не открылись");
                } else {
                    notes.add("Мягкие стратегии не открыли обычные сайты: сильные — поверх прямого соединения");
                }
                for (BypassProfile strong : BypassProfile.STRONG) {
                    BypassProfile c = BypassProfile.chain(base, strong);
                    if (remembered == null || !c.sameArgs(remembered)) chains.add(c);
                }
                stage = Stage.CHAINS;
                return nextChains(notes);
            }
            case CHAINS: {
                Entry win = bestQualifying(done);
                if (win != null) return claim(win, true, notes);
                return nextChains(notes);
            }
            case VERIFY:
            default: {
                Entry e = done.get(0);
                Verdict v = verdict(e.score);
                if (v == Verdict.FULL || v == Verdict.BASIC) {
                    return claim(e, v == Verdict.FULL, notes);
                }
                notes.add("Повторная проверка «" + e.profile.label + "» не прошла");
                return verifyNext(notes);
            }
        }
    }

    /**
     * Выбранная стратегия уже не запущена (её процесс успел завершиться): перезапустить её
     * и проверить ещё раз. Не прошла — следующая из итогового списка или отказ.
     */
    List<BypassProfile> recheck(BypassProfile profile) {
        stage = Stage.VERIFY;
        batch = entries(Collections.singletonList(profile));
        return currentBatch();
    }

    /** Итог для журнала и экрана: что дала стратегия. */
    Verdict verdict(@Nullable Score s) {
        if (s == null) return Verdict.NONE;
        if (s.blocked == 0) return s.normal() > 0 ? Verdict.BASIC : Verdict.NONE;
        return keepsNormal(s) ? Verdict.FULL : Verdict.BREAKS;
    }

    /**
     * Обычные сайты через стратегию работают не хуже эталона: прямого соединения и лучшей
     * из проверенных стратегий (допуск — один сайт: мобильная сеть иногда теряет запрос).
     * Российские считаются отдельно: через обход DPI идёт весь трафик, и они важны не меньше.
     */
    boolean keepsNormal(Score s) {
        int refNormal = baseline != null ? baseline.normal() : 0;
        int refLocal = baseline != null ? baseline.local : 0;
        for (Entry e : tested) {
            if (e.score == null) continue;
            refNormal = Math.max(refNormal, e.score.normal());
            refLocal = Math.max(refLocal, e.score.local);
        }
        for (Entry e : batch) {
            if (e.score == null) continue;
            refNormal = Math.max(refNormal, e.score.normal());
            refLocal = Math.max(refLocal, e.score.local);
        }
        return s.normal() >= Math.max(1, refNormal - 1) && s.local >= refLocal - 1;
    }

    private List<BypassProfile> firstBatch() {
        List<BypassProfile> first = new ArrayList<>();
        if (remembered != null) first.add(remembered);
        for (BypassProfile p : BypassProfile.MILD) {
            if (first.size() < PARALLEL && (remembered == null || !p.sameArgs(remembered))) first.add(p);
        }
        return first;
    }

    private Decision nextChains(List<String> notes) {
        if (!chains.isEmpty()) {
            List<BypassProfile> next = new ArrayList<>();
            while (!chains.isEmpty() && next.size() < PARALLEL) next.add(chains.remove(0));
            return test(next, notes);
        }
        // Перебор окончен: лучшая полная, если есть (хоть и ниже порога), иначе лучшая для обычных
        verifyQueue.clear();
        Entry full = best(Verdict.FULL, tested);
        if (full != null) verifyQueue.add(full);
        Entry basic = bestBasic();
        if (basic != null) verifyQueue.add(basic);
        if (full == null) {
            notes.add("Ни одна стратегия не пробила заблокированные сайты, не сломав обычные. "
                    + "Скорее всего, соцсети здесь закрыты не по DPI, а по IP");
        }
        return verifyNext(notes);
    }

    /** Взять следующую стратегию из итогового списка: если её процесс ещё работает — сразу, иначе перепроверить. */
    private Decision verifyNext(List<String> notes) {
        while (!verifyQueue.isEmpty()) {
            Entry e = verifyQueue.remove(0);
            if (batch.contains(e) && stage != Stage.VERIFY) {
                Verdict v = verdict(e.score);
                return claim(e, v == Verdict.FULL, notes);
            }
            notes.add("Перезапускаю «" + e.profile.label + "» и проверяю ещё раз");
            stage = Stage.VERIFY;
            return test(Collections.singletonList(e.profile), notes);
        }
        return giveUp(notes);
    }

    /**
     * Лучшая полная стратегия пачки, если её можно брать сразу: пробила почти все заблокированные
     * (все, кроме одного) или это запомненная — она уже была лучшей в этой сети.
     */
    @Nullable
    private Entry bestQualifying(List<Entry> entries) {
        List<Entry> ok = new ArrayList<>();
        for (Entry e : entries) {
            Score s = e.score;
            if (s == null || verdict(s) != Verdict.FULL) continue;
            boolean isRemembered = remembered != null && e.profile.sameArgs(remembered) && rememberedFull;
            if (isRemembered || s.blocked >= Math.max(1, s.blockedTotal - 1)) ok.add(e);
        }
        return best(Verdict.FULL, ok);
    }

    /** Лучшая с этим итогом: больше заблокированных, потом больше обычных, потом раньше в плане. */
    @Nullable
    private Entry best(Verdict v, List<Entry> entries) {
        Entry best = null;
        for (Entry e : entries) {
            Score s = e.score;
            if (s == null || verdict(s) != v) continue;
            if (best == null || better(s, e.order, best.score, best.order)) best = e;
        }
        return best;
    }

    private static boolean better(Score a, int orderA, @Nullable Score b, int orderB) {
        if (b == null) return true;
        if (a.blocked != b.blocked) return a.blocked > b.blocked;
        if (a.normal() != b.normal()) return a.normal() > b.normal();
        return orderA < orderB;
    }

    /** Лучшая для обычного интернета: больше обычных сайтов, при равенстве — раньше в плане (проще). */
    @Nullable
    private Entry bestBasic() {
        Entry best = null;
        for (Entry e : tested) {
            Score s = e.score;
            if (s == null || verdict(s) != Verdict.BASIC) continue;
            if (best == null || s.normal() > best.score.normal()
                    || (s.normal() == best.score.normal() && e.order < best.order)) {
                best = e;
            }
        }
        return best;
    }

    /**
     * Мягкая стратегия с лучшими обычными сайтами — основа для цепочек. Если все мягкие
     * открывают обычные сайты хуже эталона, основа — прямое соединение (null).
     */
    @Nullable
    private BypassProfile bestMild() {
        Entry best = null;
        for (Entry e : tested) {
            if (!isMild(e.profile) || e.score == null || e.score.normal() == 0) continue;
            if (!keepsNormal(e.score)) continue;
            if (best == null || e.score.normal() > best.score.normal()
                    || (e.score.normal() == best.score.normal() && e.order < best.order)) {
                best = e;
            }
        }
        return best != null ? best.profile : null;
    }

    private static boolean isMild(BypassProfile p) {
        for (BypassProfile m : BypassProfile.MILD) {
            if (m.sameArgs(p)) return true;
        }
        return false;
    }

    private String describe(Entry e) {
        Score s = e.score;
        if (s == null) return "Обход DPI (" + e.profile.label + "): не проверена";
        String what;
        switch (verdict(s)) {
            case FULL:
                what = "пробивает заблокированные, обычные сайты работают";
                break;
            case BREAKS:
                what = "пробивает заблокированные, но ЛОМАЕТ обычные сайты — не беру";
                break;
            case BASIC:
                what = "обычные сайты есть, заблокированные — нет";
                break;
            case NONE:
            default:
                what = "ничего не открылось";
                break;
        }
        return "Обход DPI (" + e.profile.label + "): " + what + "; " + s.summary()
                + (baseline != null ? ", без VPN обычные " + baseline.normal() + "/" + baseline.normalTotal : "")
                + " · " + s.detail;
    }

    private List<Entry> entries(List<BypassProfile> profiles) {
        List<Entry> result = new ArrayList<>();
        for (BypassProfile p : profiles) result.add(new Entry(p, order++));
        return result;
    }

    private Decision test(List<BypassProfile> profiles, List<String> notes) {
        batch = entries(profiles);
        return new Decision(currentBatch(), null, false, false, notes);
    }

    private Decision claim(Entry e, boolean full, List<String> notes) {
        return new Decision(null, e.profile, full, false, notes);
    }

    private Decision giveUp(List<String> notes) {
        batch = new ArrayList<>();
        return new Decision(null, null, false, false, notes);
    }
}
