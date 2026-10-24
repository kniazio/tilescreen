package app.tilescreen.core;

import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The primitives of Noise_NNpsk0_25519_ChaChaPoly_SHA256 that Android 5 to 7 lack: X25519 (RFC 7748, after
 * TweetNaCl) and ChaCha20-Poly1305 (RFC 8439, Poly1305 after poly1305-donna). SHA-256 and HMAC come from the platform.
 */
public final class Crypto {
    private Crypto() {}

    // ----- X25519 -----

    private static final long[] A24 = {0xDB41, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    private static final byte[] BASE = new byte[32];

    static {
        BASE[0] = 9;
    }

    private static void car(long[] o) {
        for (int i = 0; i < 16; i++) {
            o[i] += 1L << 16;
            long c = o[i] >> 16;
            if (i < 15) o[i + 1] += c - 1; else o[0] += 38 * (c - 1);
            o[i] -= c << 16;
        }
    }

    private static void sel(long[] p, long[] q, int b) {
        long c = ~(b - 1L);
        for (int i = 0; i < 16; i++) {
            long t = c & (p[i] ^ q[i]);
            p[i] ^= t;
            q[i] ^= t;
        }
    }

    private static void pack(byte[] o, long[] n) {
        long[] m = new long[16], t = n.clone();
        car(t);
        car(t);
        car(t);
        for (int j = 0; j < 2; j++) {
            m[0] = t[0] - 0xffed;
            for (int i = 1; i < 15; i++) {
                m[i] = t[i] - 0xffff - ((m[i - 1] >> 16) & 1);
                m[i - 1] &= 0xffff;
            }
            m[15] = t[15] - 0x7fff - ((m[14] >> 16) & 1);
            int b = (int) ((m[15] >> 16) & 1);
            m[14] &= 0xffff;
            sel(t, m, 1 - b);
        }
        for (int i = 0; i < 16; i++) {
            o[2 * i] = (byte) (t[i] & 0xff);
            o[2 * i + 1] = (byte) ((t[i] >> 8) & 0xff);
        }
    }

    private static void unpack(long[] o, byte[] n) {
        for (int i = 0; i < 16; i++) o[i] = (n[2 * i] & 0xff) + ((long) (n[2 * i + 1] & 0xff) << 8);
        o[15] &= 0x7fff;
    }

    private static void add(long[] o, long[] a, long[] b) {
        for (int i = 0; i < 16; i++) o[i] = a[i] + b[i];
    }

    private static void sub(long[] o, long[] a, long[] b) {
        for (int i = 0; i < 16; i++) o[i] = a[i] - b[i];
    }

    private static void mul(long[] o, long[] a, long[] b) {
        long[] t = new long[31];
        for (int i = 0; i < 16; i++) {
            for (int j = 0; j < 16; j++) t[i + j] += a[i] * b[j];
        }
        for (int i = 0; i < 15; i++) t[i] += 38 * t[i + 16];
        System.arraycopy(t, 0, o, 0, 16);
        car(o);
        car(o);
    }

    private static void inv(long[] o, long[] i) {
        long[] c = i.clone();
        for (int a = 253; a >= 0; a--) {
            mul(c, c, c);
            if (a != 2 && a != 4) mul(c, c, i);
        }
        System.arraycopy(c, 0, o, 0, 16);
    }

    /** X25519(scalar, point). */
    public static byte[] x25519(byte[] scalar, byte[] point) {
        byte[] z = scalar.clone();
        z[31] = (byte) ((z[31] & 127) | 64);
        z[0] &= (byte) 248;
        long[] x = new long[16], a = new long[16], b = new long[16], c = new long[16], d = new long[16],
                e = new long[16], f = new long[16];
        unpack(x, point);
        System.arraycopy(x, 0, b, 0, 16);
        a[0] = 1;
        d[0] = 1;
        for (int i = 254; i >= 0; i--) {
            int r = (z[i >>> 3] >>> (i & 7)) & 1;
            sel(a, b, r);
            sel(c, d, r);
            add(e, a, c);
            sub(a, a, c);
            add(c, b, d);
            sub(b, b, d);
            mul(d, e, e);
            mul(f, a, a);
            mul(a, c, a);
            mul(c, b, e);
            add(e, a, c);
            sub(a, a, c);
            mul(b, a, a);
            sub(c, d, f);
            mul(a, c, A24);
            add(a, a, d);
            mul(c, c, a);
            mul(a, d, f);
            mul(d, b, x);
            mul(b, e, e);
            sel(a, b, r);
            sel(c, d, r);
        }
        inv(c, c);
        mul(a, a, c);
        byte[] out = new byte[32];
        pack(out, a);
        return out;
    }

    public static byte[] x25519Public(byte[] secret) { return x25519(secret, BASE); }

    public static byte[] random(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    // ----- ChaCha20 -----

    private static int rotl(int v, int c) { return (v << c) | (v >>> (32 - c)); }

    private static int le32(byte[] b, int at) {
        return (b[at] & 0xff) | (b[at + 1] & 0xff) << 8 | (b[at + 2] & 0xff) << 16 | (b[at + 3] & 0xff) << 24;
    }

    private static void block(int[] state, byte[] out) {
        int[] x = state.clone();
        for (int i = 0; i < 10; i++) {
            quarter(x, 0, 4, 8, 12);
            quarter(x, 1, 5, 9, 13);
            quarter(x, 2, 6, 10, 14);
            quarter(x, 3, 7, 11, 15);
            quarter(x, 0, 5, 10, 15);
            quarter(x, 1, 6, 11, 12);
            quarter(x, 2, 7, 8, 13);
            quarter(x, 3, 4, 9, 14);
        }
        for (int i = 0; i < 16; i++) {
            int v = x[i] + state[i];
            out[4 * i] = (byte) v;
            out[4 * i + 1] = (byte) (v >>> 8);
            out[4 * i + 2] = (byte) (v >>> 16);
            out[4 * i + 3] = (byte) (v >>> 24);
        }
    }

    private static void quarter(int[] x, int a, int b, int c, int d) {
        x[a] += x[b]; x[d] = rotl(x[d] ^ x[a], 16);
        x[c] += x[d]; x[b] = rotl(x[b] ^ x[c], 12);
        x[a] += x[b]; x[d] = rotl(x[d] ^ x[a], 8);
        x[c] += x[d]; x[b] = rotl(x[b] ^ x[c], 7);
    }

    private static int[] state(byte[] key, int counter, byte[] nonce) {
        int[] s = new int[16];
        s[0] = 0x61707865;
        s[1] = 0x3320646e;
        s[2] = 0x79622d32;
        s[3] = 0x6b206574;
        for (int i = 0; i < 8; i++) s[4 + i] = le32(key, 4 * i);
        s[12] = counter;
        for (int i = 0; i < 3; i++) s[13 + i] = le32(nonce, 4 * i);
        return s;
    }

    /** XORs data with the ChaCha20 stream starting at block `counter`. */
    public static byte[] chacha20(byte[] key, int counter, byte[] nonce, byte[] data, int off, int len) {
        byte[] out = new byte[len];
        int[] s = state(key, counter, nonce);
        byte[] ks = new byte[64];
        for (int i = 0; i < len; i += 64) {
            block(s, ks);
            s[12]++;
            int n = Math.min(64, len - i);
            for (int j = 0; j < n; j++) out[i + j] = (byte) (data[off + i + j] ^ ks[j]);
        }
        return out;
    }

    // ----- Poly1305 -----

    public static byte[] poly1305(byte[] key, byte[] m) {
        long r0 = le32(key, 0) & 0x3ffffffL, r1 = (le32(key, 3) >>> 2) & 0x3ffff03L, r2 = (le32(key, 6) >>> 4) & 0x3ffc0ffL,
                r3 = (le32(key, 9) >>> 6) & 0x3f03fffL, r4 = (le32(key, 12) >>> 8) & 0x00fffffL;
        long s1 = r1 * 5, s2 = r2 * 5, s3 = r3 * 5, s4 = r4 * 5;
        long h0 = 0, h1 = 0, h2 = 0, h3 = 0, h4 = 0;
        byte[] blockBuf = new byte[17];
        for (int at = 0; at < m.length; at += 16) {
            int n = Math.min(16, m.length - at);
            java.util.Arrays.fill(blockBuf, (byte) 0);
            System.arraycopy(m, at, blockBuf, 0, n);
            long hibit;
            if (n == 16) {
                hibit = 1L << 24;
            } else {
                blockBuf[n] = 1;
                hibit = 0;
            }
            h0 += le32u(blockBuf, 0) & 0x3ffffff;
            h1 += (le32u(blockBuf, 3) >>> 2) & 0x3ffffff;
            h2 += (le32u(blockBuf, 6) >>> 4) & 0x3ffffff;
            h3 += (le32u(blockBuf, 9) >>> 6) & 0x3ffffff;
            h4 += (le32u(blockBuf, 12) >>> 8) | hibit;
            long d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1;
            long d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2;
            long d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3;
            long d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4;
            long d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0;
            long c = d0 >>> 26; h0 = d0 & 0x3ffffff;
            d1 += c; c = d1 >>> 26; h1 = d1 & 0x3ffffff;
            d2 += c; c = d2 >>> 26; h2 = d2 & 0x3ffffff;
            d3 += c; c = d3 >>> 26; h3 = d3 & 0x3ffffff;
            d4 += c; c = d4 >>> 26; h4 = d4 & 0x3ffffff;
            h0 += c * 5; c = h0 >>> 26; h0 &= 0x3ffffff;
            h1 += c;
        }
        long c = h1 >>> 26; h1 &= 0x3ffffff;
        h2 += c; c = h2 >>> 26; h2 &= 0x3ffffff;
        h3 += c; c = h3 >>> 26; h3 &= 0x3ffffff;
        h4 += c; c = h4 >>> 26; h4 &= 0x3ffffff;
        h0 += c * 5; c = h0 >>> 26; h0 &= 0x3ffffff;
        h1 += c;
        long g0 = h0 + 5; c = g0 >>> 26; g0 &= 0x3ffffff;
        long g1 = h1 + c; c = g1 >>> 26; g1 &= 0x3ffffff;
        long g2 = h2 + c; c = g2 >>> 26; g2 &= 0x3ffffff;
        long g3 = h3 + c; c = g3 >>> 26; g3 &= 0x3ffffff;
        long g4 = h4 + c - (1L << 26);
        if (g4 >= 0) {
            h0 = g0; h1 = g1; h2 = g2; h3 = g3; h4 = g4;
        }
        long w0 = (h0 | (h1 << 26)) & 0xffffffffL;
        long w1 = ((h1 >>> 6) | (h2 << 20)) & 0xffffffffL;
        long w2 = ((h2 >>> 12) | (h3 << 14)) & 0xffffffffL;
        long w3 = ((h3 >>> 18) | (h4 << 8)) & 0xffffffffL;
        long f = w0 + le32u(key, 16);
        w0 = f & 0xffffffffL;
        f = w1 + le32u(key, 20) + (f >>> 32);
        w1 = f & 0xffffffffL;
        f = w2 + le32u(key, 24) + (f >>> 32);
        w2 = f & 0xffffffffL;
        f = w3 + le32u(key, 28) + (f >>> 32);
        w3 = f & 0xffffffffL;
        byte[] tag = new byte[16];
        long[] w = {w0, w1, w2, w3};
        for (int i = 0; i < 4; i++) {
            tag[4 * i] = (byte) w[i];
            tag[4 * i + 1] = (byte) (w[i] >>> 8);
            tag[4 * i + 2] = (byte) (w[i] >>> 16);
            tag[4 * i + 3] = (byte) (w[i] >>> 24);
        }
        return tag;
    }

    private static long le32u(byte[] b, int at) { return le32(b, at) & 0xffffffffL; }

    // ----- the AEAD (RFC 8439 2.8) -----

    private static byte[] macData(byte[] ad, byte[] ct, int ctOff, int ctLen) {
        int adPad = (16 - ad.length % 16) % 16, ctPad = (16 - ctLen % 16) % 16;
        byte[] m = new byte[ad.length + adPad + ctLen + ctPad + 16];
        System.arraycopy(ad, 0, m, 0, ad.length);
        System.arraycopy(ct, ctOff, m, ad.length + adPad, ctLen);
        int at = ad.length + adPad + ctLen + ctPad;
        long al = ad.length, cl = ctLen;
        for (int i = 0; i < 8; i++) {
            m[at + i] = (byte) (al >>> (8 * i));
            m[at + 8 + i] = (byte) (cl >>> (8 * i));
        }
        return m;
    }

    public static byte[] seal(byte[] key, byte[] nonce, byte[] ad, byte[] plain) {
        byte[] polyKey = chacha20(key, 0, nonce, new byte[32], 0, 32);
        byte[] ct = chacha20(key, 1, nonce, plain, 0, plain.length);
        byte[] tag = poly1305(polyKey, macData(ad, ct, 0, ct.length));
        byte[] out = new byte[ct.length + 16];
        System.arraycopy(ct, 0, out, 0, ct.length);
        System.arraycopy(tag, 0, out, ct.length, 16);
        return out;
    }

    /** Decrypts and checks, or returns null when the tag doesn't match. */
    public static byte[] open(byte[] key, byte[] nonce, byte[] ad, byte[] sealed) {
        if (sealed.length < 16) return null;
        int n = sealed.length - 16;
        byte[] polyKey = chacha20(key, 0, nonce, new byte[32], 0, 32);
        byte[] tag = poly1305(polyKey, macData(ad, sealed, 0, n));
        int diff = 0;
        for (int i = 0; i < 16; i++) diff |= tag[i] ^ sealed[n + i];
        if (diff != 0) return null;
        return chacha20(key, 1, nonce, sealed, 0, n);
    }

    // ----- hashing -----

    public static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) d.update(p);
            return d.digest();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] hmac(byte[] key, byte[]... parts) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key.length == 0 ? new byte[1] : key, "HmacSHA256"));
            if (key.length == 0) {
                // SecretKeySpec refuses an empty key; HMAC pads an empty key and a zero byte alike.
                m = Mac.getInstance("HmacSHA256");
                m.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
            }
            for (byte[] p : parts) m.update(p);
            return m.doFinal();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ----- base64, which Android has only from API 26 in java.util -----

    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    public static String base64(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < b.length; i += 3) {
            int n = (b[i] & 0xff) << 16 | (i + 1 < b.length ? (b[i + 1] & 0xff) << 8 : 0) | (i + 2 < b.length ? b[i + 2] & 0xff : 0);
            s.append(B64[n >>> 18 & 63]).append(B64[n >>> 12 & 63]);
            s.append(i + 1 < b.length ? B64[n >>> 6 & 63] : '=');
            s.append(i + 2 < b.length ? B64[n & 63] : '=');
        }
        return s.toString();
    }

    /** Decodes standard base64, or returns null. */
    public static byte[] unbase64(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.length() % 4 != 0) return null;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int bits = 0, value = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '=') break;
            int v = c >= 'A' && c <= 'Z' ? c - 'A' : c >= 'a' && c <= 'z' ? c - 'a' + 26 : c >= '0' && c <= '9' ? c - '0' + 52
                    : c == '+' ? 62 : c == '/' ? 63 : -1;
            if (v < 0) return null;
            value = value << 6 | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((value >>> bits) & 0xff);
            }
        }
        return out.toByteArray();
    }
}
