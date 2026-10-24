package app.tilescreen.core;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * ESPHome's encrypted transport: Noise_NNpsk0_25519_ChaChaPoly_SHA256 with the device as responder, framed as
 * ESPHome frames it (indicator 0x01, a 16-bit big-endian length, the body). Follows go-esphome-device's
 * internal/wire/noise.go, which follows ESPHome's api_frame_helper.
 */
final class Noise {
    static final String PROTOCOL = "Noise_NNpsk0_25519_ChaChaPoly_SHA256";
    private static final byte[] PROLOGUE = "NoiseAPIInit\u0000\u0000".getBytes(Pb.UTF8);
    static final int MAX_PAYLOAD = 0xffff - 4 - 16;

    /** A failed handshake; its message goes to the client in the clear. */
    static final class Refused extends IOException {
        Refused(String m) { super(m); }
    }

    private static final class Cipher {
        final byte[] key;
        long n;

        Cipher(byte[] key) { this.key = key; }

        byte[] nonce() {
            byte[] b = new byte[12];
            for (int i = 0; i < 8; i++) b[4 + i] = (byte) (n >>> (8 * i));
            n++;
            return b;
        }
    }

    private final InputStream in;
    private final OutputStream out;
    private Cipher send, receive;

    Noise(InputStream in, OutputStream out) {
        this.in = in;
        this.out = out;
    }

    // ----- the symmetric state (Noise spec, section 5.2) -----

    private byte[] h, ck, k;
    private long n;

    private void mixHash(byte[] data) { h = Crypto.sha256(h, data); }

    private byte[][] hkdf(byte[] ikm, int outputs) {
        byte[] temp = Crypto.hmac(ck, ikm);
        byte[] o1 = Crypto.hmac(temp, new byte[]{1});
        byte[] o2 = Crypto.hmac(temp, o1, new byte[]{2});
        if (outputs == 2) return new byte[][]{o1, o2};
        return new byte[][]{o1, o2, Crypto.hmac(temp, o2, new byte[]{3})};
    }

    private void mixKey(byte[] ikm) {
        byte[][] o = hkdf(ikm, 2);
        ck = o[0];
        k = o[1];
        n = 0;
    }

    private void mixKeyAndHash(byte[] ikm) {
        byte[][] o = hkdf(ikm, 3);
        ck = o[0];
        mixHash(o[1]);
        k = o[2];
        n = 0;
    }

    private byte[] nonce(long value) {
        byte[] b = new byte[12];
        for (int i = 0; i < 8; i++) b[4 + i] = (byte) (value >>> (8 * i));
        return b;
    }

    private byte[] encryptAndHash(byte[] plain) {
        byte[] c = Crypto.seal(k, nonce(n++), h, plain);
        mixHash(c);
        return c;
    }

    private byte[] decryptAndHash(byte[] c) throws Refused {
        byte[] p = Crypto.open(k, nonce(n++), h, c);
        if (p == null) throw new Refused("Handshake MAC failure");
        mixHash(c);
        return p;
    }

    /**
     * Runs the responder's side of the handshake. `firstIndicator` is the 0x01 already read off the stream.
     */
    void handshake(byte[] psk, String name) throws IOException {
        // The client opens with an empty frame; the indicator was read by the caller.
        readBody(true);
        byte[] hello = new byte[name.getBytes(Pb.UTF8).length + 2];
        hello[0] = 0x01;
        System.arraycopy(name.getBytes(Pb.UTF8), 0, hello, 1, hello.length - 2);
        writeFrame(hello);

        byte[] body = readBody(false);
        if (body.length == 0) throw fail("Empty handshake message");
        if (body[0] == 0x01) throw new Refused(new String(body, 1, body.length - 1, Pb.UTF8));
        if (body[0] != 0x00) throw fail("Bad handshake error byte");

        byte[] name37 = PROTOCOL.getBytes(Pb.UTF8);
        h = name37.length <= 32 ? java.util.Arrays.copyOf(name37, 32) : Crypto.sha256(name37);
        ck = h.clone();
        mixHash(PROLOGUE);
        try {
            // Message one, -> psk, e
            mixKeyAndHash(psk);
            if (body.length < 1 + 32 + 16) throw new Refused("Handshake MAC failure");
            byte[] re = java.util.Arrays.copyOfRange(body, 1, 33);
            mixHash(re);
            mixKey(re);
            decryptAndHash(java.util.Arrays.copyOfRange(body, 33, body.length));
            // Message two, <- e, ee
            byte[] e = Crypto.random(32), ePub = Crypto.x25519Public(e);
            mixHash(ePub);
            mixKey(ePub);
            mixKey(Crypto.x25519(e, re));
            byte[] tag = encryptAndHash(new byte[0]);
            byte[] reply = new byte[1 + 32 + tag.length];
            reply[0] = 0x00;
            System.arraycopy(ePub, 0, reply, 1, 32);
            System.arraycopy(tag, 0, reply, 33, tag.length);
            byte[][] split = hkdf(new byte[0], 2);
            receive = new Cipher(split[0]); // initiator to responder
            send = new Cipher(split[1]);
            writeFrame(reply);
        } catch (Refused r) {
            throw fail(r.getMessage());
        }
    }

    private Refused fail(String message) throws IOException {
        byte[] m = message.getBytes(Pb.UTF8);
        byte[] body = new byte[m.length + 1];
        body[0] = 0x01;
        System.arraycopy(m, 0, body, 1, m.length);
        writeFrame(body);
        return new Refused(message);
    }

    /** Tells a plaintext client this device wants encryption, the way ESPHome does. */
    static void refusePlaintext(OutputStream out) throws IOException {
        byte[] m = "\u0001Bad indicator byte".getBytes(Pb.UTF8);
        byte[] f = new byte[3 + m.length];
        f[0] = 0x01;
        f[1] = (byte) (m.length >>> 8);
        f[2] = (byte) m.length;
        System.arraycopy(m, 0, f, 3, m.length);
        out.write(f);
        out.flush();
    }

    // ----- frames -----

    private byte[] readBody(boolean indicatorRead) throws IOException {
        if (!indicatorRead) {
            int marker = in.read();
            if (marker < 0) throw new EOFException();
            if (marker != 0x01) throw fail("Bad indicator byte");
        }
        int hi = in.read(), lo = in.read();
        if (hi < 0 || lo < 0) throw new EOFException();
        int len = hi << 8 | lo;
        byte[] b = new byte[len];
        int got = 0;
        while (got < len) {
            int r = in.read(b, got, len - got);
            if (r < 0) throw new EOFException();
            got += r;
        }
        return b;
    }

    private void writeFrame(byte[] body) throws IOException {
        byte[] f = new byte[3 + body.length];
        f[0] = 0x01;
        f[1] = (byte) (body.length >>> 8);
        f[2] = (byte) body.length;
        System.arraycopy(body, 0, f, 3, body.length);
        out.write(f);
        out.flush();
    }

    /** Reads one message: {type, payload...} as int type and the bytes. Returns null at the end of the stream. */
    Object[] read() throws IOException {
        int marker = in.read();
        if (marker < 0) return null;
        if (marker != 0x01) throw new IOException("bad indicator in an encrypted stream");
        byte[] sealed = readBody(true);
        byte[] plain = Crypto.open(receive.key, receive.nonce(), new byte[0], sealed);
        if (plain == null) throw new IOException("a frame failed its tag");
        if (plain.length < 4) throw new IOException("short frame");
        int type = (plain[0] & 0xff) << 8 | (plain[1] & 0xff);
        int len = (plain[2] & 0xff) << 8 | (plain[3] & 0xff);
        if (len != plain.length - 4) throw new IOException("frame length mismatch");
        return new Object[]{type, java.util.Arrays.copyOfRange(plain, 4, plain.length)};
    }

    /** Seals and writes one message. The caller serializes writes. */
    void write(int type, byte[] payload) throws IOException {
        if (payload.length > MAX_PAYLOAD) throw new IOException("message too large for one frame");
        byte[] plain = new byte[4 + payload.length];
        plain[0] = (byte) (type >>> 8);
        plain[1] = (byte) type;
        plain[2] = (byte) (payload.length >>> 8);
        plain[3] = (byte) payload.length;
        System.arraycopy(payload, 0, plain, 4, payload.length);
        writeFrame(Crypto.seal(send.key, send.nonce(), new byte[0], plain));
    }
}
