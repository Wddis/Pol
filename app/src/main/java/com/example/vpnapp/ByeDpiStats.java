package com.example.vpnapp;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Сводка по подробному выводу ByeDPI (-x 1): куда на самом деле ходят приложения телефона
 * и что не открывается. Без Android: проверяется на стенде scripts/test-tun.sh по выводу
 * настоящего ByeDPI.
 * <p>
 * Строки ByeDPI, которые разбираются:
 * <ul>
 * <li>«new conn: fd=F, pair=P, addr=IP:PORT» — новое соединение к IP;</li>
 * <li>«desync TCP: group=G, round=1, fd=F» и следом «host: имя (N)» — через какую группу
 * пошло соединение и имя сайта из ClientHello (так IP связывается с именем);</li>
 * <li>«save: ip=IP, id=G» — с прошлой группой не вышло, соединение повторено через G;</li>
 * <li>«unreach ip: IP» — не вышло ни через одну группу;</li>
 * <li>«udp addr: fd=F, addr=IP:443» — UDP на порт 443, то есть QUIC (HTTP/3): приложения Meta
 * и YouTube ходят им в первую очередь; считается по владельцу адреса.</li>
 * </ul>
 * Остальные отладочные строки отбрасываются; строки ошибок (recv: …, split cancel …) идут в журнал.
 * Вызовы — из разных потоков (чтение вывода и сторож), поэтому всё под одной блокировкой.
 */
final class ByeDpiStats {

    // Отладочные строки ByeDPI (LOG_S/LOG_L, все форматы из исходников подмодуля; полноту проверяет
    // scripts/test-bypass.sh), которые в журнал не идут
    static final String[] DEBUG_PREFIXES = {
            "accept:", "new conn:", "desync TCP:", "desync UDP:", "host:", "bytes:", "split:",
            "close:", "udp associate:", "udp addr:", "udp drop:", "save:", "unreach ip:", "tlsrec:",
            "modify HTTP", "sock_has_notsent", "pollout", "timeout (", "alloc new buffer",
            "buffers count", "resolve:", "not resolved:", "swop:", "time=", "hosts count", "ip count",
            "cache ip count", "group:", "listen address", "TFO supported", "exit", "send: pos=",
            "new event:", "temp file:"};
    private static final int MAX_FDS = 8192;
    private static final int MAX_HOSTS = 2048;
    private static final int MAX_IPS = 512;
    // Сколько адресов показывать в строке журнала
    private static final int SHOW = 4;

    private final List<String> groups;
    private final Map<Integer, String> fdAddr = new HashMap<>();
    // IP → имя сайта (последнее увиденное); старые вытесняются
    private final Map<String, String> ipHost = new LinkedHashMap<String, String>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> e) {
            return size() > MAX_HOSTS;
        }
    };
    private int lastFd = -1;

    // За период (с прошлой сводки)
    private int[] perGroup;
    private final Map<String, Integer> fallback = new LinkedHashMap<>();   // "IP\tгруппа" → раз
    private final Map<String, Integer> unreach = new LinkedHashMap<>();    // IP → раз
    private final Map<String, Integer> quic = new LinkedHashMap<>();       // владелец или IP → потоков
    // Не открывшиеся адреса, ещё не отданные на проверку «почему» (не зависит от сводки),
    // и все, что уже отдавались
    private final List<String> pending = new ArrayList<>();
    private final Map<String, Boolean> reported = new HashMap<>();

    ByeDpiStats(List<String> groups) {
        this.groups = new ArrayList<>(groups);
        perGroup = new int[Math.max(1, groups.size())];
    }

    /**
     * Строка вывода ByeDPI. true — отладочная (разобрана, в журнал не идёт);
     * false — строка ошибки, её показать в журнале.
     */
    synchronized boolean feed(String raw) {
        String line = raw.trim();
        if (line.isEmpty()) return true;
        if (line.startsWith("send: ") && line.length() > 6 && Character.isDigit(line.charAt(6))) {
            return true;  // «send: N != M» — отладка; «send: Broken pipe» — ошибка
        }
        boolean debug = false;
        for (String p : DEBUG_PREFIXES) {
            if (line.startsWith(p)) {
                debug = true;
                break;
            }
        }
        if (!debug) return false;
        try {
            parse(line);
        } catch (RuntimeException ignored) {
            // строка другого формата (другая версия ByeDPI) — просто не учитываем
        }
        return true;
    }

    private void parse(String line) {
        if (line.startsWith("new conn:")) {
            int fd = intField(line, "fd=");
            String addr = field(line, "addr=");
            if (fd >= 0 && addr != null) {
                if (fdAddr.size() > MAX_FDS) fdAddr.clear();
                fdAddr.put(fd, stripPort(addr));
            }
        } else if (line.startsWith("desync TCP:")) {
            int g = intField(line, "group=");
            if (g >= 0) {
                if (g >= perGroup.length) {
                    int[] n = new int[g + 1];
                    System.arraycopy(perGroup, 0, n, 0, perGroup.length);
                    perGroup = n;
                }
                perGroup[g]++;
            }
            lastFd = intField(line, "fd=");
        } else if (line.startsWith("host:")) {
            String ip = lastFd >= 0 ? fdAddr.get(lastFd) : null;
            String[] w = line.split("\\s+");
            if (ip != null && w.length >= 2) ipHost.put(ip, w[1].toLowerCase(Locale.ROOT));
            lastFd = -1;
        } else if (line.startsWith("bytes:")) {
            lastFd = -1;
        } else if (line.startsWith("close:")) {
            int fd = intField(line, "fd=");
            if (fd >= 0) fdAddr.remove(fd);
        } else if (line.startsWith("save:")) {
            String ip = field(line, "ip=");
            int g = intField(line, "id=");
            if (ip != null && g >= 0 && fallback.size() < MAX_IPS) {
                String key = ip + "\t" + g;
                Integer n = fallback.get(key);
                fallback.put(key, n == null ? 1 : n + 1);
            }
        } else if (line.startsWith("unreach ip:")) {
            String ip = line.substring("unreach ip:".length()).trim();
            if (!ip.isEmpty() && unreach.size() < MAX_IPS) {
                Integer n = unreach.get(ip);
                unreach.put(ip, n == null ? 1 : n + 1);
            }
            if (!ip.isEmpty() && reported.size() < MAX_IPS && reported.put(ip, Boolean.TRUE) == null) {
                pending.add(ip);
            }
        } else if (line.startsWith("udp addr:")) {
            String addr = field(line, "addr=");
            if (addr != null && addr.endsWith(":443") && quic.size() < MAX_IPS) {
                String ip = stripPort(addr);
                String o = owner(ip);
                String key = o != null ? o : ip;
                Integer n = quic.get(key);
                quic.put(key, n == null ? 1 : n + 1);
            }
        }
    }

    /**
     * Сводка за период для журнала (и сброс счётчиков периода) или "" — если соединений не было.
     * Пример: «соединений 85: split SNI 70, список → disoob SNI 12, Psiphon после сбоя 3;
     * после сбоя: i.instagram.com 57.144.62.34 → disoob SNI ×2; не открылись ничем:
     * 149.154.167.51 (Telegram) ×4».
     */
    synchronized String drain() {
        int total = 0;
        for (int n : perGroup) total += n;
        if (total == 0 && fallback.isEmpty() && unreach.isEmpty() && quic.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("соединений ").append(total);
        String sep = ": ";
        for (int g = 0; g < perGroup.length; g++) {
            if (perGroup[g] == 0) continue;
            sb.append(sep).append(groupName(g)).append(' ').append(perGroup[g]);
            sep = ", ";
        }
        if (!fallback.isEmpty()) {
            sb.append("; после сбоя:");
            int shown = 0;
            for (Map.Entry<String, Integer> e : top(fallback)) {
                if (shown++ >= SHOW) {
                    sb.append(" … ещё ").append(fallback.size() - SHOW);
                    break;
                }
                String[] k = e.getKey().split("\t");
                sb.append(shown > 1 ? ", " : " ").append(who(k[0])).append(" → ")
                        .append(groupName(Integer.parseInt(k[1])))
                        .append(e.getValue() > 1 ? " ×" + e.getValue() : "");
            }
        }
        if (!unreach.isEmpty()) {
            sb.append("; не открылись ничем:");
            int shown = 0;
            for (Map.Entry<String, Integer> e : top(unreach)) {
                if (shown++ >= SHOW) {
                    sb.append(" … ещё ").append(unreach.size() - SHOW);
                    break;
                }
                sb.append(shown > 1 ? ", " : " ").append(who(e.getKey()))
                        .append(e.getValue() > 1 ? " ×" + e.getValue() : "");
            }
        }
        if (!quic.isEmpty()) {
            sb.append("; QUIC (UDP 443):");
            int shown = 0;
            for (Map.Entry<String, Integer> e : top(quic)) {
                if (shown++ >= SHOW) {
                    sb.append(" … ещё ").append(quic.size() - SHOW);
                    break;
                }
                sb.append(shown > 1 ? ", " : " ").append(e.getKey()).append(' ').append(e.getValue());
            }
        }
        perGroup = new int[perGroup.length];
        fallback.clear();
        unreach.clear();
        quic.clear();
        return sb.toString();
    }

    /**
     * Адреса, которые не открылись ничем и ещё не отдавались на проверку (каждый — один раз).
     * Не зависит от {@link #drain}: в 0.8.7 сводка очищала счётчики раньше, и проверка «почему»
     * не получала ни одного адреса.
     */
    synchronized List<String> takeNewUnreachable(int max) {
        List<String> r = new ArrayList<>();
        while (!pending.isEmpty() && r.size() < max) r.add(pending.remove(0));
        return r;
    }

    /** «имя IP» или «IP (владелец)», если имени не видели. */
    synchronized String who(String ip) {
        String host = ipHost.get(ip);
        if (host != null) return host + " " + ip;
        String owner = owner(ip);
        return owner != null ? ip + " (" + owner + ")" : ip;
    }

    private String groupName(int g) {
        return g < groups.size() ? groups.get(g) : "группа " + g;
    }

    private static List<Map.Entry<String, Integer>> top(Map<String, Integer> m) {
        List<Map.Entry<String, Integer>> l = new ArrayList<>(m.entrySet());
        l.sort((a, b) -> b.getValue() - a.getValue());
        return l;
    }

    // ---------- Владельцы адресов (когда имени сайта нет: Telegram, WhatsApp, QUIC) ----------

    static final String[][] OWNERS = {
            {"Cloudflare", "104.16.0.0/13", "104.24.0.0/14", "172.64.0.0/13", "162.158.0.0/15",
                    "188.114.96.0/20", "141.101.64.0/18", "108.162.192.0/18", "190.93.240.0/20",
                    "198.41.128.0/17", "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22",
                    "103.31.4.0/22", "131.0.72.0/22", "197.234.240.0/22"},
            {"Google", "142.250.0.0/15", "172.217.0.0/16", "173.194.0.0/16", "209.85.128.0/17",
                    "216.58.192.0/19", "74.125.0.0/16", "64.233.160.0/19", "108.177.0.0/17"},
    };

    /** Чей адрес: Telegram, Meta, Cloudflare, Google или null (только IPv4). */
    @Nullable
    static String owner(String ip) {
        long a = ipv4(ip);
        if (a < 0) return null;
        for (String net : BypassProfile.TELEGRAM_NETS) {
            if (inNet(a, net)) return "Telegram";
        }
        for (String net : BypassProfile.META_NETS) {
            if (net.indexOf(':') < 0 && inNet(a, net)) return "Meta";
        }
        for (String[] o : OWNERS) {
            for (int i = 1; i < o.length; i++) {
                if (inNet(a, o[i])) return o[0];
            }
        }
        return null;
    }

    private static boolean inNet(long a, String cidr) {
        int slash = cidr.indexOf('/');
        long n = ipv4(cidr.substring(0, slash));
        if (n < 0) return false;
        int bits = Integer.parseInt(cidr.substring(slash + 1));
        long mask = bits == 0 ? 0 : (0xffffffffL << (32 - bits)) & 0xffffffffL;
        return (a & mask) == (n & mask);
    }

    private static long ipv4(String s) {
        String[] p = s.split("\\.");
        if (p.length != 4) return -1;
        long r = 0;
        try {
            for (String x : p) {
                int v = Integer.parseInt(x);
                if (v < 0 || v > 255) return -1;
                r = (r << 8) | v;
            }
        } catch (NumberFormatException e) {
            return -1;
        }
        return r;
    }

    // ---------- Разбор полей «ключ=значение» ----------

    @Nullable
    private static String field(String line, String key) {
        int i = line.indexOf(key);
        if (i < 0) return null;
        int s = i + key.length();
        int e = s;
        while (e < line.length() && line.charAt(e) != ',' && line.charAt(e) != ' ') e++;
        return e > s ? line.substring(s, e) : null;
    }

    private static int intField(String line, String key) {
        String v = field(line, key);
        if (v == null) return -1;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** «1.2.3.4:443» → «1.2.3.4»; «2a03::1:443» → «2a03::1» (ByeDPI пишет IPv6 без скобок). */
    private static String stripPort(String addr) {
        int c = addr.lastIndexOf(':');
        return c > 0 ? addr.substring(0, c) : addr;
    }
}
