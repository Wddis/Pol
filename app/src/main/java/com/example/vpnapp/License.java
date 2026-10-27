package com.example.vpnapp;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Лицензия ключа от сервера ключей — без Android, чтобы проверять на компьютере (scripts/test-license.sh).
 *
 * <p>Формат: «&lt;данные&gt;.&lt;подпись&gt;», base64url без «=»; данные — JSON
 * {"dev","devices","exp","iat","key","tariff","v":1}, подпись Ed25519 закрытым ключом сервера.
 * Подделать срок, ключ или устройство без закрытого ключа нельзя; лицензию с другого телефона не принять
 * (dev — отпечаток этого телефона).
 *
 * <p>Решение «можно ли пользоваться» ({@link #evaluate}) считает время защищённо: часы телефона, отведённые
 * назад, не продлевают срок (берётся наибольшее из виденного времени), а без связи с сервером лицензия
 * действует не дольше {@link #OFFLINE_GRACE} с последней удачной проверки — так удалённый или
 * заблокированный ключ не будет работать бесконечно.
 */
final class License {

    /** Открытый ключ сервера ключей (bot/README.md, «Состояние»). Меняется только вместе с LICENSE_KEY. */
    static final String PUBLIC_KEY = "OSkRbr2ENcvsYqTI8JiusLO3M6k9vXL5m9lHKu8kcB4";
    /** Сколько можно без удачной проверки на сервере. */
    static final long OFFLINE_GRACE = 7 * 86400L;
    /** Часы телефона могут немного расходиться с сервером — это не «перевод назад». */
    static final long CLOCK_SLACK = 300;

    /** Данные проверенной лицензии. */
    static final class Data {
        final String key;
        final String device;
        final String tariff;
        final int devices;
        final long expires;
        final long issued;

        Data(String key, String device, String tariff, int devices, long expires, long issued) {
            this.key = key;
            this.device = device;
            this.tariff = tariff;
            this.devices = devices;
            this.expires = expires;
            this.issued = issued;
        }
    }

    enum Verdict {
        /** Лицензии нет, она не наша или подделана — нужен ключ. */
        NONE,
        OK,
        EXPIRED,
        /** Давно не было связи с сервером — нужна проверка. */
        NEED_CHECK
    }

    private License() {
    }

    /** Данные лицензии, если подпись верна; иначе null. */
    static Data parse(String token, String publicKeyB64) {
        if (token == null) return null;
        int dot = token.indexOf('.');
        if (dot <= 0 || dot != token.lastIndexOf('.')) return null;
        byte[] data = unb64(token.substring(0, dot));
        byte[] sig = unb64(token.substring(dot + 1));
        byte[] pub = unb64(publicKeyB64);
        if (data == null || sig == null || pub == null || !Ed25519.verify(pub, data, sig)) return null;
        Map<String, String> m = flatJson(new String(data, StandardCharsets.UTF_8));
        if (m == null || !"1".equals(m.get("v"))) return null;
        try {
            return new Data(m.get("key"), m.get("dev"), m.get("tariff"),
                    Integer.parseInt(m.get("devices")), Long.parseLong(m.get("exp")), Long.parseLong(m.get("iat")));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Наше «сейчас»: часы телефона, но не раньше уже виденного времени (защита от перевода часов назад). */
    static long trustedNow(long now, long maxSeen) {
        return Math.max(now, maxSeen - CLOCK_SLACK);
    }

    /**
     * Можно ли пользоваться ODA.
     *
     * @param lic       проверенная лицензия (null — нет или подделана)
     * @param key       ключ, введённый на этом телефоне
     * @param device    отпечаток этого телефона
     * @param now       часы телефона, секунды
     * @param maxSeen   наибольшее время, которое видели (часы телефона и сервера)
     * @param lastOk    время сервера при последней удачной проверке
     */
    static Verdict evaluate(Data lic, String key, String device, long now, long maxSeen, long lastOk) {
        if (lic == null || key == null || device == null || !key.equals(lic.key) || !device.equals(lic.device)) {
            return Verdict.NONE;
        }
        long t = trustedNow(now, maxSeen);
        if (t >= lic.expires) return Verdict.EXPIRED;
        if (t - lastOk > OFFLINE_GRACE) return Verdict.NEED_CHECK;
        return Verdict.OK;
    }

    // ---------- base64url и плоский JSON (лицензия — один уровень, строки и числа) ----------

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    static byte[] unb64(String s) {
        if (s == null) return null;
        int n = s.length();
        if (n % 4 == 1) return null;
        byte[] out = new byte[n * 3 / 4];
        int buf = 0, bits = 0, o = 0;
        for (int i = 0; i < n; i++) {
            int v = ALPHABET.indexOf(s.charAt(i));
            if (v < 0) return null;
            buf = (buf << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[o++] = (byte) (buf >> bits);
            }
        }
        if (o == out.length) return out;
        byte[] trimmed = new byte[o];
        System.arraycopy(out, 0, trimmed, 0, o);
        return trimmed;
    }

    static String b64(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buf = 0, bits = 0;
        for (byte b : data) {
            buf = (buf << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 6) {
                bits -= 6;
                sb.append(ALPHABET.charAt((buf >> bits) & 63));
            }
        }
        if (bits > 0) sb.append(ALPHABET.charAt((buf << (6 - bits)) & 63));
        return sb.toString();
    }

    /** {"a":"x","b":12,"c":null} → карта строк (null — null). Вложенность и экранирование, кроме \" и \\, не нужны. */
    static Map<String, String> flatJson(String s) {
        Map<String, String> m = new HashMap<>();
        int i = skip(s, 0);
        if (i >= s.length() || s.charAt(i) != '{') return null;
        i = skip(s, i + 1);
        if (i < s.length() && s.charAt(i) == '}') return m;
        while (i < s.length()) {
            if (s.charAt(i) != '"') return null;
            int end = stringEnd(s, i);
            if (end < 0) return null;
            String name = unescape(s.substring(i + 1, end));
            i = skip(s, end + 1);
            if (i >= s.length() || s.charAt(i) != ':') return null;
            i = skip(s, i + 1);
            if (i >= s.length()) return null;
            String value;
            if (s.charAt(i) == '"') {
                end = stringEnd(s, i);
                if (end < 0) return null;
                value = unescape(s.substring(i + 1, end));
                i = end + 1;
            } else {
                int j = i;
                while (j < s.length() && ",} \n\r\t".indexOf(s.charAt(j)) < 0) j++;
                String raw = s.substring(i, j);
                if (raw.isEmpty() || raw.startsWith("{") || raw.startsWith("[")) return null;
                value = "null".equals(raw) ? null : raw;
                i = j;
            }
            m.put(name, value);
            i = skip(s, i);
            if (i < s.length() && s.charAt(i) == ',') {
                i = skip(s, i + 1);
                continue;
            }
            if (i < s.length() && s.charAt(i) == '}') return m;
            return null;
        }
        return null;
    }

    private static int skip(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    private static int stringEnd(String s, int start) {
        for (int i = start + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') i++;
            else if (c == '"') return i;
        }
        return -1;
    }

    private static String unescape(String s) {
        return s.indexOf('\\') < 0 ? s : s.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
