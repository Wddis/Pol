package com.example.vpnapp;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Проверка License и Ed25519 на компьютере: векторы RFC 8032 и лицензии от кода сервера ключей. */
public class LicenseTest {
    static int failed = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  ✓ " : "  ✗ ") + what);
        if (!ok) failed++;
    }

    static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    public static void main(String[] a) {
        Map<String, String> in = License.flatJson(a[0]);
        // RFC 8032, 7.1: TEST 1 (пустое сообщение) и TEST 2 (0x72)
        byte[] pub1 = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
        byte[] sig1 = hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b");
        check(Ed25519.verify(pub1, new byte[0], sig1), "RFC 8032 TEST 1");
        byte[] pub2 = hex("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c");
        byte[] sig2 = hex("92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00");
        check(Ed25519.verify(pub2, new byte[]{0x72}, sig2), "RFC 8032 TEST 2");
        check(!Ed25519.verify(pub2, new byte[]{0x73}, sig2), "другое сообщение — подпись не подходит");
        sig2[63] ^= 1;
        check(!Ed25519.verify(pub2, new byte[]{0x72}, sig2), "испорченная подпись");

        String pub = in.get("pub");
        long t0 = System.nanoTime();
        License.Data d = License.parse(in.get("good"), pub);
        System.out.println("    проверка подписи: " + (System.nanoTime() - t0) / 1_000_000 + " мс");
        check(d != null, "лицензия от сервера ключей принимается");
        check(d != null && "ODA-ACDE-FGHJ-KMNP".equals(d.key) && "devAbc123_-xyz".equals(d.device)
                && d.devices == 2 && d.expires == 1800000000L && d.issued == 1790000000L && "custom".equals(d.tariff), "поля лицензии");
        check(License.parse(in.get("foreign"), pub) == null, "лицензия с чужим ключом подписи — нет");
        check(License.parse(in.get("forged"), pub) == null, "подделанный срок — нет");
        check(License.parse("abc", pub) == null && License.parse("", pub) == null && License.parse(null, pub) == null
                && License.parse("a.b.c", pub) == null, "мусор — нет");
        check(License.parse(in.get("forever"), pub) != null, "бессрочная лицензия");

        String k = "ODA-ACDE-FGHJ-KMNP", dev = "devAbc123_-xyz";
        long now = 1795000000L;
        check(License.evaluate(d, k, dev, now, now, now - 3600) == License.Verdict.OK, "действует");
        check(License.evaluate(d, k, "other-device", now, now, now) == License.Verdict.NONE, "лицензия с другого телефона — нет");
        check(License.evaluate(d, "ODA-QRTU-VWXY-Z234", dev, now, now, now) == License.Verdict.NONE, "лицензия от другого ключа — нет");
        check(License.evaluate(d, k, dev, 1800000000L, 1800000000L, now) == License.Verdict.EXPIRED, "срок вышел");
        check(License.evaluate(d, k, dev, 1700000000L, 1800000500L, now) == License.Verdict.EXPIRED,
                "часы переведены назад — срок всё равно вышел");
        check(License.evaluate(d, k, dev, now - 100, now, now) == License.Verdict.OK, "небольшое расхождение часов — не страшно");
        check(License.evaluate(d, k, dev, now, now, now - 8 * 86400L) == License.Verdict.NEED_CHECK, "8 дней без связи — нужна проверка");
        check(License.evaluate(null, k, dev, now, now, now) == License.Verdict.NONE, "без лицензии — нужен ключ");

        byte[] raw = "проверка base64url".getBytes(StandardCharsets.UTF_8);
        check(new String(License.unb64(License.b64(raw)), StandardCharsets.UTF_8).equals("проверка base64url"), "base64url туда и обратно");
        check(License.flatJson("{\"a\":\"x\\\"y\",\"b\":12,\"c\":null}").get("a").equals("x\"y"), "JSON: строки с кавычкой");
        check(License.flatJson("{\"a\":{\"b\":1}}") == null && License.flatJson("[1]") == null, "JSON: вложенное — отказ");

        System.out.println(failed == 0 ? "\nЛицензии: всё сходится" : "\nЛицензии: ошибок " + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
