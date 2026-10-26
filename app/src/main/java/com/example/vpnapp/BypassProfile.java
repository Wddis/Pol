package com.example.vpnapp;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Стратегия обхода DPI: название для журнала и аргументы ByeDPI.
 * Без Android: набор стратегий и цепочки проверяются тестами на компьютере.
 * <p>
 * Формат аргументов — README ByeDPI: -s split, -d disorder, -o oob, -q disoob, -f fake,
 * -t TTL фейка, -r tlsrec, -A группа «после сбоя», -T таймаут. Позиции частей идут по возрастанию,
 * иначе ByeDPI отменяет разбиение («split cancel»).
 */
final class BypassProfile {

    final String label;
    final String[] args;

    BypassProfile(String label, String... args) {
        this.label = label;
        this.args = args;
    }

    /**
     * Мягкие стратегии: только режут ClientHello на TCP-части. Любой нормальный сервер
     * и мидлбокс их собирают, поэтому они запускаются сами по себе, для всего трафика.
     */
    static final List<BypassProfile> MILD = Collections.unmodifiableList(Arrays.asList(
            new BypassProfile("split SNI", "-s", "1+s"),
            new BypassProfile("disorder SNI", "-d", "1+s")));

    /**
     * Сильные стратегии: OOB, фейки с малым TTL, tlsrec, множественное разбиение. Они чаще бьют DPI,
     * но могут сломать обычные сайты: OOB-байт часть серверов и «нормализаторов» TCP (ASA, Palo Alto,
     * защита от DDoS) не вырезает, а вставляет в поток; фейк с малым TTL доходит до близких
     * (российских) серверов и портит ClientHello. В README ByeDPI прямо сказано использовать их
     * только с --auto. Поэтому сами по себе они не запускаются — только в {@link #chain}.
     * Порядок — порядок проверки: OOB-семейство первым (в сети РФ ТСПУ не вырезает OOB-байт и читает SNI с ошибкой).
     */
    static final List<BypassProfile> STRONG = Collections.unmodifiableList(Arrays.asList(
            new BypassProfile("oob SNI", "-o", "1+s"),
            new BypassProfile("disoob SNI", "-q", "1+s"),
            new BypassProfile("fake ttl 5", "-f", "-1", "-t", "5"),
            new BypassProfile("multidisorder", "-d", "1", "-s", "1+s", "-d", "3+s", "-s", "6+s",
                    "-d", "9+s", "-s", "12+s", "-d", "15+s", "-s", "20+s", "-d", "25+s",
                    "-s", "30+s", "-d", "35+s"),
            new BypassProfile("fake ttl 3", "-f", "-1", "-t", "3"),
            new BypassProfile("disorder+fake ttl 5", "-d", "1", "-f", "-1", "-t", "5"),
            new BypassProfile("fake ttl 8 rand", "-f", "-1", "-t", "8", "-Q", "r"),
            new BypassProfile("tlsrec+oob", "-r", "1+s", "-s", "1", "-o", "1+s"),
            new BypassProfile("tlsrec+disorder", "-s", "1", "-r", "1+s", "-r", "3+s", "-d", "3+s")));

    /**
     * Таймаут для --auto: если данные не подтверждены 4 с (ТСПУ молча отбросил ClientHello) —
     * сбой. В Linux это TCP_USER_TIMEOUT; «:0:0:1» выключает его после первых байт от сервера,
     * чтобы обычные долгие соединения не рвались при коротких провалах мобильной сети.
     */
    private static final String[] AUTO_TIMEOUT = {"-T", "4:0:0:1"};
    // Сбой = таймаут/сброс (t), редирект на чужой домен (r), вместо ServerHello пришло другое (s)
    private static final String[] AUTO_ON_FAIL = {"-A", "t,r,s"};

    /**
     * Безопасная цепочка: все сайты идут через мягкую стратегию base (или без обхода, если null),
     * а strong включается только для соединения, которое с base не прошло (таймаут, сброс,
     * ошибка TLS). ByeDPI сам переподключается, повторяет запрос и запоминает IP сайта,
     * следующие соединения к нему сразу идут через strong. Обычные сайты strong не трогает.
     */
    static BypassProfile chain(@Nullable BypassProfile base, BypassProfile strong) {
        List<String> a = new ArrayList<>(Arrays.asList(AUTO_TIMEOUT));
        if (base != null) a.addAll(Arrays.asList(base.args));
        a.addAll(Arrays.asList(AUTO_ON_FAIL));
        a.addAll(Arrays.asList(strong.args));
        String name = (base != null ? base.label : "без обхода") + " → " + strong.label;
        return new BypassProfile(name, a.toArray(new String[0]));
    }

    /**
     * Сколько соединений держит ByeDPI (-c, по умолчанию 512; пул событий — вдвое больше).
     * Каждая SOCKS-UDP-ассоциация (а это каждый DNS-запрос телефона) занимает в пуле 3 места:
     * со стандартным значением пул переполнялся за минуту, и ByeDPI переставал принимать
     * любые соединения. 4096 — до ~8 тыс. дескрипторов, у приложения Android их 32 тыс.
     */
    static final int MAX_CONN = 4096;

    /**
     * Аргументы процесса ByeDPI перед стратегией: только локальный адрес, свой порт, размер пула.
     * Стратегия (и своя строка пользователя) идёт после и может их переопределить.
     */
    static List<String> processArgs(int port) {
        return Arrays.asList("-i", "127.0.0.1", "-p", Integer.toString(port),
                "-c", Integer.toString(MAX_CONN));
    }

    /** Разобрать строку аргументов ByeDPI в стратегию или null, если пусто. */
    @Nullable
    static BypassProfile parse(String label, @Nullable String line) {
        if (line == null) return null;
        List<String> parts = new ArrayList<>();
        for (String p : line.trim().split("\\s+")) {
            if (!p.isEmpty()) parts.add(p);
        }
        if (parts.isEmpty()) return null;
        return new BypassProfile(label, parts.toArray(new String[0]));
    }

    /** Аргументы одной строкой (для журнала и для запоминания). */
    String argsLine() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }

    /** Те же аргументы — та же стратегия (название может отличаться). */
    boolean sameArgs(BypassProfile other) {
        return Arrays.equals(args, other.args);
    }
}
