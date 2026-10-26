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
            new BypassProfile("tlsrec+disorder", "-s", "1", "-r", "1+s", "-r", "3+s", "-d", "3+s"),
            // В журнале 0.8.7 OOB и disoob перестали пробивать, а tlsrec — пробивает: запас на смену ТСПУ
            new BypassProfile("tlsrec+disoob", "-r", "1+s", "-s", "1", "-q", "1+s"),
            new BypassProfile("tlsrec mid", "-r", "0+sm", "-s", "1", "-s", "0+sm")));

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
     * Домены, заблокированные в РФ (вместе с поддоменами: ByeDPI сравнивает с конца по точке).
     * Для них в цепочке сильная стратегия идёт сразу, без попытки через мягкую: приложения
     * соцсетей ходят не на www, а на десятки имён (i.instagram.com, scontent-…cdninstagram.com,
     * graph.facebook.com, …) и адресов, и с цепочкой каждый новый адрес сначала ждал 4 с,
     * пока мягкая стратегия не «упадёт», а ТСПУ при этом видел имя сайта. Только заблокированные:
     * незаблокированному сайту мягкая не мешает, а сильную лучше не трогать.
     */
    static final List<String> BLOCKED_HOSTS = Collections.unmodifiableList(Arrays.asList(
            // Instagram, Facebook, Messenger, Threads, WhatsApp (сайты и CDN; чаты WhatsApp — не TLS)
            "instagram.com", "cdninstagram.com", "ig.me", "instagr.am",
            "facebook.com", "fb.com", "fbcdn.net", "fbsbx.com", "facebook.net", "fb.me",
            "messenger.com", "m.me", "threads.net", "threads.com",
            "whatsapp.com", "whatsapp.net", "wa.me",
            // X (Twitter)
            "x.com", "twitter.com", "twimg.com", "t.co",
            // Telegram — сайты (сами чаты Telegram не TLS, см. TELEGRAM_NETS)
            "telegram.org", "t.me", "telegram.me", "telesco.pe",
            // прочее, заблокированное по имени
            "rutracker.org", "rutracker.cc", "linkedin.com", "licdn.com",
            "discord.com", "discord.gg", "discordapp.com", "discordapp.net", "discord.media",
            "signal.org", "torproject.org"));

    /**
     * Сети Telegram (core.telegram.org/resources/cidr.txt). Приложение Telegram ходит прямо
     * на адреса своих серверов по своему протоколу (MTProto, без имени сайта), поэтому
     * стратегии ByeDPI, которые режут имя сайта, его не касаются («split cancel» в журнале).
     * С 2026 года ТСПУ замедляет и режет Telegram по адресам и по виду трафика — это
     * обход DPI не берёт. Если есть запасной путь (Psiphon), Telegram сразу идёт через него.
     */
    static final List<String> TELEGRAM_NETS = Collections.unmodifiableList(Arrays.asList(
            "91.105.192.0/23", "91.108.4.0/22", "91.108.8.0/22", "91.108.12.0/22",
            "91.108.16.0/22", "91.108.20.0/22", "91.108.56.0/22", "95.161.64.0/20",
            "149.154.160.0/20", "185.76.151.0/24",
            "2001:67c:4e8::/48", "2001:b28:f23c::/48", "2001:b28:f23d::/48",
            "2001:b28:f23f::/48", "2a0a:f280::/32"));

    /**
     * Сети Meta (Instagram, Facebook, WhatsApp). Приложения Meta ходят в первую очередь по QUIC
     * (UDP 443); ByeDPI QUIC не меняет, а ТСПУ его режет или «подвешивает». В цепочке UDP 443
     * к этим сетям отбрасывается (--udp-drop, правка scripts/byedpi.patch), и приложения уходят
     * на TCP, где их открывает стратегия. В 0.8.7 приложения Meta почти не открывали TCP-соединений
     * (6 за 4 минуты) и висели.
     */
    static final List<String> META_NETS = Collections.unmodifiableList(Arrays.asList(
            "31.13.24.0/21", "31.13.64.0/18", "57.144.0.0/14", "66.220.144.0/20",
            "69.63.176.0/20", "69.171.224.0/19", "74.119.76.0/22", "102.132.96.0/20",
            "129.134.0.0/16", "157.240.0.0/16", "163.70.128.0/17", "173.252.64.0/18",
            "179.60.192.0/22", "185.60.216.0/22", "185.89.216.0/22", "204.15.20.0/22",
            "2a03:2880::/32"));

    /** Аргументы процесса ByeDPI для стратегии и названия групп (по номеру группы, для журнала). */
    static final class Route {
        final List<String> args;
        final List<String> groups;
        /** Есть группы запасного пути (Psiphon). */
        final boolean helper;

        Route(List<String> args, List<String> groups, boolean helper) {
            this.args = Collections.unmodifiableList(args);
            this.groups = Collections.unmodifiableList(groups);
            this.helper = helper;
        }

        /** Для журнала: группы по порядку, без длинных списков доменов и сетей. */
        String describe() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < groups.size(); i++) {
                if (i > 0) sb.append(" | ");
                sb.append(groups.get(i));
            }
            return sb.toString();
        }
    }

    /** Группа ByeDPI: условие (-A …; у первой группы его нет), аргументы, название для журнала. */
    private static final class Group {
        final String trigger;
        final List<String> args;
        final String name;

        Group(String trigger, List<String> args, String name) {
            this.trigger = trigger;
            this.args = args;
            this.name = name;
        }
    }

    /**
     * Маршрут ByeDPI для стратегии: как он делит трафик телефона по группам.
     * <p>
     * Цепочка «мягкая → сильная» ({@link #chain}):
     * <ol>
     * <li>QUIC (UDP 443) к сетям Meta — отбрасывается, приложения уходят на TCP;</li>
     * <li>[запасной путь] сети Telegram → Psiphon (не готов — напрямую, триггер «c»);</li>
     * <li>заблокированные домены ({@link #BLOCKED_HOSTS}, порт 443) → сразу сильная;
     * [запасной путь] не прошла — Psiphon;</li>
     * <li>остальное → мягкая; не прошла (таймаут, сброс, не TLS-ответ) — сильная;
     * [запасной путь] и она не прошла — Psiphon.</li>
     * </ol>
     * Мягкая сама по себе — с запасным путём: Telegram и то, что не прошло, — через Psiphon.
     * Своя строка пользователя — как есть.
     * <p>
     * Порядок групп важен: после сбоя ByeDPI ищет следующую группу с подходящим условием,
     * но останавливается на первой группе без условия («-A n»), которую соединение ещё не прошло.
     * Поэтому группа «… → Psiphon» стоит сразу за той, чей сбой она подхватывает.
     *
     * @param helperPort локальный порт запасного пути (HelperRelay → Psiphon), 0 — без него
     */
    static Route route(BypassProfile p, int helperPort) {
        List<Group> groups = new ArrayList<>();
        boolean helper = helperPort > 0;
        List<String> tunnel = helper
                ? Arrays.asList("-C", "socks5://127.0.0.1:" + helperPort) : Collections.emptyList();
        BypassProfile[] parts = chainParts(p);
        boolean mild = isMild(p);
        if (parts == null && !(mild && helper)) {
            // Своя строка или мягкая без запасного пути: как есть
            return new Route(new ArrayList<>(Arrays.asList(p.args)),
                    Collections.singletonList(p.label), false);
        }
        if (parts != null) {
            // Группа только для UDP (-K u): TCP её не видит
            groups.add(new Group("n", Arrays.asList("-K", "u", "-V", "443",
                    "-j", ":" + String.join(" ", META_NETS), "--udp-drop"), "QUIC Meta → отброшен"));
        }
        if (helper) {
            groups.add(new Group("n", join(Arrays.asList("-j", ":" + String.join(" ", TELEGRAM_NETS)), tunnel),
                    "Telegram → Psiphon"));
            groups.add(new Group("c", Collections.emptyList(), "Telegram напрямую"));
        }
        if (parts != null) {
            BypassProfile base = parts[0];
            BypassProfile strong = parts[1];
            // Порт 443: с запасным путём ByeDPI для групп со списком доменов отвечает приложению
            // раньше, чем соединяется с сервером (ждёт имя сайта) — протоколы, где сервер говорит
            // первым (почта, FTP), так зависли бы. На 443 первым всегда говорит клиент.
            groups.add(new Group("n", join(Arrays.asList("-V", "443", "-H", ":" + String.join(" ", BLOCKED_HOSTS)),
                    Arrays.asList(strong.args)), "список → " + strong.label));
            if (helper) groups.add(new Group("t,r,s,c", tunnel, "список → Psiphon"));
            groups.add(new Group("n", base != null ? Arrays.asList(base.args) : Collections.emptyList(),
                    base != null ? base.label : "без обхода"));
            groups.add(new Group("t,r,s", Arrays.asList(strong.args), strong.label + " после сбоя"));
        } else {
            groups.add(new Group("n", Arrays.asList(p.args), p.label));
        }
        if (helper) groups.add(new Group("t,r,s,c", tunnel, "Psiphon после сбоя"));

        List<String> args = new ArrayList<>(Arrays.asList(AUTO_TIMEOUT));
        List<String> names = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            Group g = groups.get(i);
            if (i > 0) {
                args.add("-A");
                args.add(g.trigger);
            }
            args.addAll(g.args);
            names.add(g.name);
        }
        return new Route(args, names, helper);
    }

    /**
     * Части цепочки {@link #chain}: {мягкая или null, сильная}. Не цепочка — null.
     * Разбирает и запомненные в прошлых версиях стратегии (они хранятся строкой аргументов).
     */
    @Nullable
    static BypassProfile[] chainParts(BypassProfile p) {
        String[] a = p.args;
        if (a.length < 4 || !a[0].equals(AUTO_TIMEOUT[0]) || !a[1].equals(AUTO_TIMEOUT[1])) return null;
        int on = -1;
        for (int i = 2; i < a.length - 1; i++) {
            if (a[i].equals(AUTO_ON_FAIL[0])) {
                if (on >= 0 || !a[i + 1].equals(AUTO_ON_FAIL[1])) return null;
                on = i;
            }
        }
        if (on < 0 || on + 2 >= a.length) return null;
        String[] baseArgs = Arrays.copyOfRange(a, 2, on);
        String[] strongArgs = Arrays.copyOfRange(a, on + 2, a.length);
        for (String s : strongArgs) {
            // Внутри — свои группы или фильтры: это своя строка, не наша цепочка
            if (s.equals("-A") || s.equals("-H") || s.equals("-j") || s.equals("-C")) return null;
        }
        BypassProfile base = null;
        if (baseArgs.length > 0) {
            for (BypassProfile m : MILD) {
                if (Arrays.equals(m.args, baseArgs)) base = m;
            }
            if (base == null) return null;
        }
        BypassProfile strong = null;
        for (BypassProfile s : STRONG) {
            if (Arrays.equals(s.args, strongArgs)) strong = s;
        }
        if (strong == null) return null;
        return new BypassProfile[]{base, strong};
    }

    private static boolean isMild(BypassProfile p) {
        for (BypassProfile m : MILD) {
            if (m.sameArgs(p)) return true;
        }
        return false;
    }

    private static List<String> join(List<String> a, List<String> b) {
        List<String> r = new ArrayList<>(a);
        r.addAll(b);
        return r;
    }

    /**
     * Сколько соединений держит ByeDPI (-c, по умолчанию 512; пул событий — вдвое больше).
     * Каждая SOCKS-UDP-ассоциация (а это каждый DNS-запрос телефона) занимает в пуле 3 места:
     * со стандартным значением пул переполнялся за минуту, и ByeDPI переставал принимать
     * любые соединения. 4096 — до ~8 тыс. дескрипторов, у приложения Android их 32 тыс.
     */
    static final int MAX_CONN = 4096;

    /**
     * Аргументы процесса ByeDPI перед стратегией: только локальный адрес, свой порт, размер пула
     * и подробный вывод (-x 1: по нему ByeDpiStats считает, куда ходят приложения и что
     * не открывается; в журнал идёт сводка, а не сами строки).
     * Стратегия (и своя строка пользователя) идёт после и может их переопределить.
     */
    static List<String> processArgs(int port) {
        return Arrays.asList("-i", "127.0.0.1", "-p", Integer.toString(port),
                "-c", Integer.toString(MAX_CONN), "-x", "1");
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
