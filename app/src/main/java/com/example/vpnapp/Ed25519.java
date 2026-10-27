package com.example.vpnapp;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Проверка подписи Ed25519 (RFC 8032) — только проверка, на BigInteger, без библиотек: в Android до 13
 * Ed25519 в системе нет. Подписывает сервер ключей; здесь — лицензия ключа ({@link License}).
 * Скорость не важна (проверка — при запуске и раз в несколько часов), важна точность: сверено с подписями
 * сервера в scripts/test-license.sh.
 */
final class Ed25519 {

    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger L = BigInteger.ONE.shiftLeft(252)
            .add(new BigInteger("27742317777372353535851937790883648493"));
    private static final BigInteger D = BigInteger.valueOf(-121665)
            .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    private static final BigInteger D2 = D.add(D).mod(P);
    private static final BigInteger SQRT_M1 = BigInteger.valueOf(2)
            .modPow(P.subtract(BigInteger.ONE).shiftRight(2), P);
    private static final BigInteger TWO = BigInteger.valueOf(2);

    /** Точка в расширенных координатах (X:Y:Z:T), x = X/Z, y = Y/Z, xy = T/Z. */
    private static final class Point {
        final BigInteger x, y, z, t;

        Point(BigInteger x, BigInteger y, BigInteger z, BigInteger t) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.t = t;
        }
    }

    private static final Point ZERO = new Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO);
    private static final Point BASE;

    static {
        BigInteger by = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P);
        BigInteger bx = recoverX(by, 0);
        BASE = new Point(bx, by, BigInteger.ONE, bx.multiply(by).mod(P));
    }

    private Ed25519() {
    }

    /** true — signature (64 байта) — подпись message ключом publicKey (32 байта). */
    static boolean verify(byte[] publicKey, byte[] message, byte[] signature) {
        if (publicKey == null || signature == null || publicKey.length != 32 || signature.length != 64) return false;
        Point a = decode(publicKey);
        Point r = decode(Arrays.copyOfRange(signature, 0, 32));
        if (a == null || r == null) return false;
        BigInteger s = littleEndian(Arrays.copyOfRange(signature, 32, 64));
        if (s.compareTo(L) >= 0) return false;
        byte[] h;
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-512");
            sha.update(signature, 0, 32);
            sha.update(publicKey);
            sha.update(message);
            h = sha.digest();
        } catch (NoSuchAlgorithmException e) {
            return false;
        }
        BigInteger k = littleEndian(h).mod(L);
        Point left = multiply(BASE, s);
        Point right = add(r, multiply(a, k));
        return Arrays.equals(encode(left), encode(right));
    }

    private static Point add(Point p, Point q) {
        BigInteger a = p.y.subtract(p.x).multiply(q.y.subtract(q.x)).mod(P);
        BigInteger b = p.y.add(p.x).multiply(q.y.add(q.x)).mod(P);
        BigInteger c = p.t.multiply(D2).multiply(q.t).mod(P);
        BigInteger d = p.z.multiply(TWO).multiply(q.z).mod(P);
        BigInteger e = b.subtract(a), f = d.subtract(c), g = d.add(c), hh = b.add(a);
        return new Point(e.multiply(f).mod(P), g.multiply(hh).mod(P), f.multiply(g).mod(P), e.multiply(hh).mod(P));
    }

    private static Point multiply(Point p, BigInteger n) {
        Point q = ZERO;
        for (int i = n.bitLength() - 1; i >= 0; i--) {
            q = add(q, q);
            if (n.testBit(i)) q = add(q, p);
        }
        return q;
    }

    private static byte[] encode(Point p) {
        BigInteger zi = p.z.modInverse(P);
        BigInteger x = p.x.multiply(zi).mod(P);
        BigInteger y = p.y.multiply(zi).mod(P);
        byte[] out = toLittleEndian(y);
        if (x.testBit(0)) out[31] |= (byte) 0x80;
        return out;
    }

    private static Point decode(byte[] s) {
        byte[] b = s.clone();
        int sign = (b[31] >> 7) & 1;
        b[31] &= 0x7f;
        BigInteger y = littleEndian(b);
        if (y.compareTo(P) >= 0) return null;
        BigInteger x = recoverX(y, sign);
        if (x == null) return null;
        return new Point(x, y, BigInteger.ONE, x.multiply(y).mod(P));
    }

    private static BigInteger recoverX(BigInteger y, int sign) {
        BigInteger y2 = y.multiply(y).mod(P);
        BigInteger x2 = y2.subtract(BigInteger.ONE)
                .multiply(D.multiply(y2).add(BigInteger.ONE).modInverse(P)).mod(P);
        if (x2.signum() == 0) return sign == 0 ? BigInteger.ZERO : null;
        BigInteger x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P);
        if (!x.multiply(x).subtract(x2).mod(P).equals(BigInteger.ZERO)) x = x.multiply(SQRT_M1).mod(P);
        if (!x.multiply(x).subtract(x2).mod(P).equals(BigInteger.ZERO)) return null;
        if ((x.testBit(0) ? 1 : 0) != sign) x = P.subtract(x);
        return x;
    }

    private static BigInteger littleEndian(byte[] b) {
        byte[] be = new byte[b.length + 1];
        for (int i = 0; i < b.length; i++) be[b.length - i] = b[i];
        return new BigInteger(be);
    }

    private static byte[] toLittleEndian(BigInteger n) {
        byte[] be = n.toByteArray();
        byte[] out = new byte[32];
        for (int i = 0; i < be.length && i < 32; i++) out[i] = be[be.length - 1 - i];
        return out;
    }
}
